package com.lincoln.maceguard.reset;

import com.lincoln.maceguard.config.MaceGuardConfig;
import com.lincoln.maceguard.config.ResetProfile;
import com.lincoln.maceguard.runtime.RuntimeSafetyPolicy;
import com.lincoln.maceguard.storage.ArmStateRepository;
import com.lincoln.maceguard.storage.ResetJournalRepository;
import com.lincoln.maceguard.storage.SnapshotRepository;
import com.lincoln.maceguard.storage.SparseBaselineRepository;
import com.lincoln.maceguard.worldguard.MaceGuardFlags;
import com.lincoln.maceguard.worldguard.RegionDescriptor;
import com.lincoln.maceguard.worldguard.WorldGuardRegionService;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class ResetCoordinator {
    private final JavaPlugin plugin;
    private final MaceGuardConfig config;
    private final MaceGuardFlags flags;
    private final WorldGuardRegionService regions;
    private final SnapshotRepository snapshots;
    private final ArmStateRepository arms;
    private final ResetJournalRepository journals;
    private final Executor io;
    private final SnapshotValidator validator = new SnapshotValidator();
    private final ResetPlanner planner = new ResetPlanner();
    private final ConfirmationTokens tokens = new ConfirmationTokens();
    private final SnapshotCaptureService capture;
    private final ResetExecutor executor;
    private final AtomicBoolean destructiveOperation = new AtomicBoolean();
    private final AtomicBoolean resetLocked = new AtomicBoolean();
    private volatile String activeRegionKey;
    private volatile RegionDescriptor activeRegion;
    private volatile boolean stateReady;
    private volatile String startupProblem;
    private volatile String resetLockReason;
    private java.util.function.BiConsumer<World, String> successfulResetHook =
            (world, region) -> { };

    public ResetCoordinator(JavaPlugin plugin, MaceGuardConfig config, MaceGuardFlags flags,
                            WorldGuardRegionService regions, SnapshotRepository snapshots,
                            ArmStateRepository arms, ResetJournalRepository journals, Executor io) {
        this(plugin, config, flags, regions, snapshots, arms, journals, null, io);
    }

    /** The sparse repository parameter is retained only for binary/source compatibility. */
    public ResetCoordinator(JavaPlugin plugin, MaceGuardConfig config, MaceGuardFlags flags,
                            WorldGuardRegionService regions, SnapshotRepository snapshots,
                            ArmStateRepository arms, ResetJournalRepository journals,
                            SparseBaselineRepository ignoredSparseRepository, Executor io) {
        this.plugin = plugin;
        this.config = config;
        this.flags = flags;
        this.regions = regions;
        this.snapshots = snapshots;
        this.arms = arms;
        this.journals = journals;
        this.io = io;
        this.capture = new SnapshotCaptureService(plugin,
                config.performance().captureBatchSize(), io);
        this.executor = new ResetExecutor(plugin, io, journals,
                config.performance().restoreBatchSize());
        io.execute(this::loadPersistentState);
    }

    public boolean hasActiveOperation() { return destructiveOperation.get(); }
    public String startupProblem() { return startupProblem; }

    public void onSuccessfulReset(java.util.function.BiConsumer<World, String> hook) {
        successfulResetHook = hook == null ? (world, region) -> { } : hook;
    }

    public void recoveryStatus(Consumer<String> feedback) {
        io.execute(() -> {
            try {
                Optional<ResetJournal> journal = journals.load();
                main(() -> feedback.accept(journal.map(value ->
                        "Restore journal: operation=" + value.operationId()
                                + ", region=" + value.regionId()
                                + ", status=" + value.status()
                                + ", progress=" + value.nextChange()
                                + "/" + value.totalChanges())
                        .orElse("No restore journal exists.")));
            } catch (IOException ex) {
                main(() -> feedback.accept("Restore journal cannot be read: " + ex.getMessage()));
            }
        });
    }

    /** Obsolete sparse-original interception is fail-closed and never delays gameplay. */
    public boolean prepareSparseOriginal(Location location, BlockState original,
                                         Consumer<String> feedback) {
        return false;
    }

    public void tickAutomaticResets() {
        if (!RuntimeSafetyPolicy.allowsAutomaticReset(config.enabled())
                || !stateReady || startupProblem != null || resetLocked.get()
                || destructiveOperation.get()) return;
        long now = System.currentTimeMillis();
        for (ArmState state : arms.all().values()) {
            if (!state.isScheduleEnabled()) continue;
            World world;
            try { world = org.bukkit.Bukkit.getWorld(java.util.UUID.fromString(state.worldUuid())); }
            catch (IllegalArgumentException ex) { continue; }
            if (world == null) continue;
            Resolution resolution = resolve(world, state.regionId());
            if (!resolution.valid() || !armMatches(state, resolution)) {
                disarm(world, state.regionId(), ignored -> { });
                continue;
            }
            int interval = resolution.profile().intervalMinutes();
            if (interval > 0 && now - state.armedAt() >= interval * 60_000L)
                automaticReset(world, state.regionId());
        }
    }

    public void capture(World world, String regionId, Consumer<String> feedback) {
        if (!RuntimeSafetyPolicy.allowsCapture(config.enabled())) {
            feedback.accept("Capture refused: MaceGuard is disabled in config.");
            return;
        }
        Resolution resolution = resolve(world, regionId);
        if (!resolution.valid()) {
            feedback.accept(resolution.error());
            return;
        }
        if (!beginOperation(world, regionId)) {
            feedback.accept("Another capture or restore is active.");
            return;
        }
        String worldUuid = world.getUID().toString();
        io.execute(() -> {
            try {
                arms.disarm(worldUuid, resolution.region().id());
                main(() -> capture.capture(world, resolution.region(), resolution.profile(),
                        (x, y, z) -> resolution.exclusions().stream()
                                .anyMatch(region -> region.contains(BlockVector3.at(x, y, z))),
                        result -> {
                            if (!result.successful()) {
                                endOperation();
                                feedback.accept("Capture failed: " + result.error());
                                return;
                            }
                            io.execute(() -> {
                                try {
                                    snapshots.save(result.snapshot());
                                    main(() -> {
                                        endOperation();
                                        feedback.accept("Snapshot captured and validated for "
                                                + resolution.region().id()
                                                + ". Region remains disarmed.");
                                    });
                                } catch (IOException ex) {
                                    main(() -> {
                                        endOperation();
                                        feedback.accept("Capture was not committed: "
                                                + ex.getMessage());
                                    });
                                }
                            });
                        }));
            } catch (IOException ex) {
                main(() -> {
                    endOperation();
                    feedback.accept("Capture refused: could not durably disarm region: "
                            + ex.getMessage());
                });
            }
        });
    }

    public void validate(World world, String regionId, Consumer<String> feedback) {
        Resolution resolution = resolve(world, regionId);
        if (!resolution.valid()) {
            feedback.accept(resolution.error());
            return;
        }
        loadSnapshot(resolution, snapshot -> feedback.accept("Snapshot "
                + resolution.region().id() + ": VALID, mode=" + resolution.profile().mode()
                + ", persisted=" + snapshot.blocks().size()
                + ", scanned=" + snapshot.scannedCoordinateCount()), feedback);
    }

    public void arm(World world, String regionId, Consumer<String> feedback) {
        if (!RuntimeSafetyPolicy.allowsArm(config.enabled())) {
            feedback.accept("Arm refused: MaceGuard is disabled in config.");
            return;
        }
        if (!stateReady) {
            feedback.accept("Persistent reset state is not ready; try again shortly.");
            return;
        }
        Resolution resolution = resolve(world, regionId);
        if (!resolution.valid()) {
            feedback.accept(resolution.error());
            return;
        }
        loadSnapshot(resolution, snapshot -> {
            ArmState state = new ArmState(snapshot.worldUuid(), snapshot.regionId(),
                    snapshot.geometryHash(), resolution.profile().name(),
                    resolution.profile().mode().name(), resolution.exclusionHash(),
                    snapshot.formatVersion(), snapshot.checksum(),
                    System.currentTimeMillis(), false);
            io.execute(() -> {
                try {
                    arms.arm(state);
                    main(() -> feedback.accept("Armed " + snapshot.regionId()
                            + " for this exact geometry, exclusions, profile, and snapshot. "
                            + "Automatic schedule remains paused."));
                } catch (IOException ex) {
                    main(() -> feedback.accept("Arm failed: " + ex.getMessage()));
                }
            });
        }, feedback);
    }

    public void disarm(World world, String regionId, Consumer<String> feedback) {
        String worldUuid = world.getUID().toString();
        io.execute(() -> {
            try {
                arms.disarm(worldUuid, regionId);
                main(() -> feedback.accept("Disarmed " + regionId + "."));
            } catch (IOException ex) {
                main(() -> feedback.accept("Disarm failed: " + ex.getMessage()));
            }
        });
    }

    public void setSchedule(World world, String regionId, boolean enabled,
                            Consumer<String> feedback) {
        if (!RuntimeSafetyPolicy.allowsScheduleChange(config.enabled(), enabled)) {
            feedback.accept("Schedule enable refused: MaceGuard is disabled in config.");
            return;
        }
        String worldUuid = world.getUID().toString();
        Optional<ArmState> current = arms.get(worldUuid, regionId);
        if (current.isEmpty()) {
            feedback.accept("Schedule unchanged: " + regionId + " is not armed.");
            return;
        }
        Resolution resolution = resolve(world, regionId);
        if (!resolution.valid() || !armMatches(current.get(), resolution)) {
            disarm(world, regionId, ignored -> { });
            feedback.accept("Schedule refused: armed state no longer matches and was disarmed.");
            return;
        }
        ArmState state = current.get();
        long timerAnchor = enabled ? System.currentTimeMillis() : state.armedAt();
        ArmState updated = new ArmState(state.worldUuid(), state.regionId(),
                state.geometryHash(), state.profile(), state.mode(),
                state.exclusionsHash(), state.snapshotFormat(),
                state.snapshotChecksum(), timerAnchor, enabled);
        io.execute(() -> {
            try {
                arms.arm(updated);
                main(() -> feedback.accept("Automatic snapshot restores "
                        + (enabled ? "enabled" : "paused") + " for " + regionId + "."));
            } catch (IOException ex) {
                main(() -> feedback.accept("Schedule change failed: " + ex.getMessage()));
            }
        });
    }

    public void plan(World world, String regionId, Consumer<String> feedback) {
        buildPlan(world, regionId, result -> {
            if (result.plan() == null) {
                feedback.accept(result.error());
                return;
            }
            String refusal = planner.refusal(result.plan(),
                    result.profile().maxTotalChanges(), result.profile().maxAirChanges());
            String token = refusal == null ? tokens.issue(result.plan()) : null;
            ResetPlan plan = result.plan();
            feedback.accept("Preflight " + plan.regionId()
                    + ": inspected=" + plan.coordinatesInspected()
                    + ", changes=" + plan.totalChanges()
                    + ", non-air=" + plan.nonAirChanges()
                    + ", air=" + plan.airChanges()
                    + ", block-entities=" + plan.blockEntities()
                    + ", skipped-solid-conflicts=" + plan.unsupportedStates()
                    + ", excluded=" + plan.excludedCoordinates()
                    + ", batches=" + plan.estimatedBatches()
                    + (refusal == null ? ". Confirmation token: " + token
                    : ". Reset refused: " + refusal));
        });
    }

    public void reset(World world, String regionId, String token,
                      Consumer<String> feedback) {
        if (!RuntimeSafetyPolicy.allowsManualReset(config.enabled())) {
            feedback.accept("Reset refused: MaceGuard is disabled in config.");
            return;
        }
        if (!stateReady) {
            feedback.accept("Persistent reset state is not ready.");
            return;
        }
        if (resetLocked.get()) {
            feedback.accept("Reset refused: " + resetLockReason);
            return;
        }
        if (startupProblem != null) {
            feedback.accept("Reset refused: " + startupProblem);
            return;
        }
        if (!beginOperation(world, regionId)) {
            feedback.accept("Another capture or restore is active.");
            return;
        }
        buildPlan(world, regionId, result -> {
            if (result.plan() == null) {
                endOperation();
                feedback.accept(result.error());
                return;
            }
            String refusal = planner.refusal(result.plan(),
                    result.profile().maxTotalChanges(), result.profile().maxAirChanges());
            if (refusal != null) {
                endOperation();
                feedback.accept("Reset refused: " + refusal);
                return;
            }
            if (!tokens.consume(token, result.plan())) {
                endOperation();
                feedback.accept("Reset refused: confirmation token is missing, stale, expired, "
                        + "or belongs to another plan.");
                return;
            }
            executor.execute(world, result.plan(), message -> {
                if (message.startsWith("Reset completed:"))
                    finishSuccessfulReset(world, regionId, result.profile(), message, feedback);
                else {
                    endOperation();
                    feedback.accept(message);
                }
            }, this::lockRestores);
        });
    }

    private void automaticReset(World world, String regionId) {
        if (!RuntimeSafetyPolicy.allowsAutomaticReset(config.enabled())
                || resetLocked.get()) return;
        if (!beginOperation(world, regionId)) return;
        buildPlan(world, regionId, result -> {
            if (result.plan() == null) {
                endOperation();
                plugin.getLogger().severe("Automatic reset disabled for "
                        + regionId + ": " + result.error());
                disarm(world, regionId, ignored -> { });
                return;
            }
            String refusal = planner.refusal(result.plan(),
                    result.profile().maxTotalChanges(), result.profile().maxAirChanges());
            if (refusal != null) {
                endOperation();
                plugin.getLogger().severe("Automatic reset refused for "
                        + regionId + ": " + refusal);
                disarm(world, regionId, ignored -> { });
                return;
            }
            executor.execute(world, result.plan(), message -> {
                if (message.startsWith("Reset completed:"))
                    finishSuccessfulReset(world, regionId, result.profile(),
                            message, plugin.getLogger()::info);
                else {
                    endOperation();
                    disarm(world, regionId, ignored -> { });
                    plugin.getLogger().info(message);
                }
            }, this::lockRestores);
        });
    }

    private void finishSuccessfulReset(World world, String regionId,
                                       ResetProfile profile, String message,
                                       Consumer<String> feedback) {
        if (profile.mode() == ResetProfile.Mode.FULL_SNAPSHOT)
            notifySuccessfulReset(world, regionId);
        endOperation();
        touchArm(world, regionId);
        feedback.accept(message);
    }

    private void touchArm(World world, String regionId) {
        arms.get(world.getUID().toString(), regionId).ifPresent(state -> io.execute(() -> {
            try {
                arms.arm(new ArmState(state.worldUuid(), state.regionId(),
                        state.geometryHash(), state.profile(), state.mode(),
                        state.exclusionsHash(), state.snapshotFormat(),
                        state.snapshotChecksum(), System.currentTimeMillis(),
                        state.scheduleEnabled()));
            } catch (IOException ex) {
                plugin.getLogger().severe("Reset completed but schedule state could not "
                        + "be committed; disarm before further operation: " + ex.getMessage());
            }
        }));
    }

    public void status(World world, String regionId, Consumer<String> feedback) {
        Resolution resolution = resolve(world, regionId);
        if (!resolution.valid()) {
            feedback.accept(resolution.error());
            return;
        }
        Optional<ArmState> armed = arms.get(world.getUID().toString(),
                resolution.region().id());
        if (armed.isEmpty() || !armMatches(armed.get(), resolution)) {
            if (armed.isPresent()) disarm(world, regionId, ignored -> { });
            feedback.accept(statusLine(regionId, resolution, false));
            return;
        }
        loadSnapshot(resolution, snapshot -> {
            boolean matches = armed.get().snapshotChecksum().equals(snapshot.checksum());
            if (!matches) disarm(world, regionId, ignored -> { });
            feedback.accept(statusLine(regionId, resolution, matches)
                    + ", schedule=" + (armed.get().isScheduleEnabled()
                    ? "enabled" : "paused")
                    + ", persisted=" + snapshot.blocks().size());
        }, error -> {
            disarm(world, regionId, ignored -> { });
            feedback.accept(statusLine(regionId, resolution, false)
                    + ", snapshot=" + error);
        });
    }

    private void buildPlan(World world, String regionId, Consumer<PlanResult> callback) {
        Resolution resolution = resolve(world, regionId);
        if (!resolution.valid()) {
            callback.accept(PlanResult.failure(resolution.error()));
            return;
        }
        Optional<ArmState> armed = arms.get(world.getUID().toString(),
                resolution.region().id());
        if (armed.isEmpty() || !armMatches(armed.get(), resolution)) {
            callback.accept(PlanResult.failure(
                    "Reset disabled: region is not armed for its current geometry/profile."));
            return;
        }
        loadSnapshot(resolution, snapshot -> {
            if (!armed.get().snapshotChecksum().equals(snapshot.checksum())) {
                callback.accept(PlanResult.failure(
                        "Reset disabled: snapshot checksum changed after arming."));
                return;
            }
            collectCurrent(world, snapshot, current -> {
                ResetPlan plan = planner.plan(snapshot, current,
                        block -> resolution.exclusions().stream().anyMatch(region ->
                                region.contains(BlockVector3.at(block.x(), block.y(), block.z()))),
                        config.performance().restoreBatchSize(), resolution.profile());
                callback.accept(new PlanResult(plan, resolution.profile(), null));
            }, error -> callback.accept(PlanResult.failure(error)));
        }, error -> callback.accept(PlanResult.failure(error)));
    }

    private void collectCurrent(World world, Snapshot snapshot,
                                Consumer<List<SnapshotBlock>> done,
                                Consumer<String> failed) {
        List<SnapshotBlock> current = new ArrayList<>(snapshot.blocks().size());
        BlockStateCodec codec = new BlockStateCodec();
        new BukkitRunnable() {
            int index;

            @Override public void run() {
                int end = Math.min(snapshot.blocks().size(),
                        index + config.performance().planBatchSize());
                while (index < end) {
                    SnapshotBlock block = snapshot.blocks().get(index++);
                    if (!world.isChunkLoaded(block.x() >> 4, block.z() >> 4)) {
                        cancel();
                        failed.accept("Preflight refused: required chunk is not loaded.");
                        return;
                    }
                    try {
                        org.bukkit.Bukkit.createBlockData(block.blockData());
                        current.add(codec.capture(
                                world.getBlockAt(block.x(), block.y(), block.z())));
                    } catch (RuntimeException ex) {
                        cancel();
                        failed.accept("Preflight refused: " + ex.getMessage());
                        return;
                    }
                }
                if (index == snapshot.blocks().size()) {
                    cancel();
                    done.accept(current);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private Resolution resolve(World world, String regionId) {
        if (!config.validSchema())
            return Resolution.failure("Reset disabled: configuration schema is invalid: "
                    + String.join("; ", config.errors()));
        Optional<ProtectedRegion> raw = regions.region(world, regionId);
        if (raw.isEmpty())
            return Resolution.failure("Reset disabled: WorldGuard region does not exist in world "
                    + world.getName() + ".");
        Optional<RegionDescriptor> descriptor = regions.cuboid(world, regionId);
        if (descriptor.isEmpty())
            return Resolution.failure("Reset disabled: only exact WorldGuard cuboid regions are supported.");
        if (flags.resetProfile() == null)
            return Resolution.failure("Reset disabled: reset-profile flag is unavailable.");
        String profileName = raw.get().getFlag(flags.resetProfile());
        if (profileName == null || profileName.isBlank())
            return Resolution.failure("Reset disabled: region has no direct maceguard-reset-profile value.");
        ResetProfile profile = config.resetProfiles().get(profileName);
        if (profile == null)
            return Resolution.failure("Reset disabled: profile '" + profileName
                    + "' is missing or invalid.");
        if (descriptor.get().volume() > profile.maxCoordinates())
            return Resolution.failure("Reset disabled: region volume "
                    + descriptor.get().volume() + " exceeds profile scan maximum "
                    + profile.maxCoordinates() + ".");

        List<ProtectedRegion> exclusions = new ArrayList<>();
        StringBuilder exclusionGeometry = new StringBuilder();
        for (String excludedId : profile.excludedRegionIds()) {
            Optional<ProtectedRegion> excluded = regions.region(world, excludedId);
            if (excluded.isEmpty())
                return Resolution.failure("Reset disabled: excluded WorldGuard region '"
                        + excludedId + "' could not be resolved.");
            exclusions.add(excluded.get());
            exclusionGeometry.append(exclusionGeometry(excluded.get())).append('\n');
        }
        return new Resolution(descriptor.get(), profile, List.copyOf(exclusions),
                sha256(exclusionGeometry.toString()), null);
    }

    private boolean armMatches(ArmState state, Resolution current) {
        return state.geometryHash().equals(current.region().geometryHash())
                && state.profile().equals(current.profile().name())
                && state.mode().equals(current.profile().mode().name())
                && java.util.Objects.equals(state.exclusionsHash(),
                        current.exclusionHash())
                && state.snapshotFormat() == Snapshot.FORMAT_VERSION;
    }

    private String statusLine(String regionId, Resolution resolution, boolean armed) {
        String recovery = startupProblem != null ? startupProblem : resetLockReason;
        return "Reset " + regionId + ": profile=" + resolution.profile().name()
                + ", mode=" + resolution.profile().mode()
                + ", armed=" + armed
                + (recovery == null ? "" : ", recovery=" + recovery);
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String exclusionGeometry(ProtectedRegion region) {
        StringBuilder value = new StringBuilder(region.getId()).append('|')
                .append(region.getClass().getName()).append('|')
                .append(region.getMinimumPoint()).append('|')
                .append(region.getMaximumPoint());
        if (region instanceof com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion polygon)
            polygon.getPoints().forEach(point -> value.append('|').append(point));
        return value.toString();
    }

    private void loadSnapshot(Resolution resolution, Consumer<Snapshot> success,
                              Consumer<String> failure) {
        io.execute(() -> {
            try {
                Optional<Snapshot> snapshot = snapshots.load(
                        resolution.region().worldUuid().toString(),
                        resolution.region().id());
                if (snapshot.isEmpty()) {
                    main(() -> failure.accept("Snapshot missing; reset disabled."));
                    return;
                }
                SnapshotValidator.Validation validation = validator.validate(
                        snapshot.get(), resolution.region(), resolution.profile());
                main(() -> {
                    if (validation.valid()) success.accept(snapshot.get());
                    else failure.accept("Snapshot invalid: " + validation.reason());
                });
            } catch (IOException ex) {
                main(() -> failure.accept("Snapshot could not be loaded: " + ex.getMessage()));
            }
        });
    }

    private void loadPersistentState() {
        try {
            arms.load();
            if (!config.enabled() && !arms.all().isEmpty()) {
                arms.disarmAll();
                plugin.getLogger().warning("MaceGuard is disabled; all reset arming states "
                        + "were cleared and require explicit re-arming after enable.");
            }
            Optional<ResetJournal> journal = journals.load();
            if (journal.filter(ResetJournal::requiresAdministratorReview).isPresent()) {
                lockRestores("unresolved restore " + journal.get().operationId()
                        + " requires administrator review; region is not automatically resumed");
                startupProblem = resetLockReason;
            }
            if (snapshots.hasIncompleteFiles())
                startupProblem = "an interrupted snapshot capture was detected; "
                        + "capture a fresh snapshot";
            stateReady = true;
        } catch (IOException ex) {
            startupProblem = "persistent reset state failed validation: " + ex.getMessage();
            stateReady = true;
        }
    }

    private void main(Runnable action) {
        plugin.getServer().getScheduler().runTask(plugin, action);
    }

    private void notifySuccessfulReset(World world, String regionId) {
        try { successfulResetHook.accept(world, regionId); }
        catch (RuntimeException ex) {
            plugin.getLogger().severe("Reset completed, but post-reset temporary-block "
                    + "reconciliation failed: " + ex.getMessage());
        }
    }

    private boolean beginOperation(World world, String regionId) {
        if (!config.enabled() || resetLocked.get()) return false;
        if (!destructiveOperation.compareAndSet(false, true)) return false;
        activeRegionKey = world.getUID() + ":" + regionId.toLowerCase(java.util.Locale.ROOT);
        activeRegion = regions.cuboid(world, regionId).orElse(null);
        return true;
    }

    private void endOperation() {
        activeRegionKey = null;
        activeRegion = null;
        destructiveOperation.set(false);
    }

    private void lockRestores(String reason) {
        if (resetLocked.compareAndSet(false, true)) resetLockReason = reason;
    }

    private record Resolution(RegionDescriptor region, ResetProfile profile,
                              List<ProtectedRegion> exclusions,
                              String exclusionHash, String error) {
        static Resolution failure(String error) {
            return new Resolution(null, null, List.of(), null, error);
        }
        boolean valid() { return error == null; }
    }

    private record PlanResult(ResetPlan plan, ResetProfile profile, String error) {
        static PlanResult failure(String error) {
            return new PlanResult(null, null, error);
        }
    }
}
