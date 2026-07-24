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
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.block.BlockState;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class ResetCoordinator {
    private final JavaPlugin plugin;
    private final MaceGuardConfig config;
    private final MaceGuardFlags flags;
    private final WorldGuardRegionService regions;
    private final SnapshotRepository snapshots;
    private final SparseBaselineRepository sparseBaselines;
    private final ArmStateRepository arms;
    private final ResetJournalRepository journals;
    private final Executor io;
    private final SnapshotValidator validator = new SnapshotValidator();
    private final SparseBaselineValidator sparseValidator = new SparseBaselineValidator();
    private final ResetPlanner planner = new ResetPlanner();
    private final ConfirmationTokens tokens = new ConfirmationTokens();
    private final SnapshotCaptureService capture;
    private final ResetExecutor executor;
    private final AtomicBoolean destructiveOperation = new AtomicBoolean();
    private final AtomicBoolean resetLocked = new AtomicBoolean();
    private volatile String activeRegionKey;
    private volatile RegionDescriptor activeRegion;
    private final java.util.Map<String, SparseBaseline> sparseCache = new ConcurrentHashMap<>();
    private final java.util.Set<String> sparseLoads = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> sparsePendingCoordinates = ConcurrentHashMap.newKeySet();
    private volatile boolean stateReady;
    private volatile String startupProblem;
    private volatile String resetLockReason;

    public ResetCoordinator(JavaPlugin plugin, MaceGuardConfig config, MaceGuardFlags flags, WorldGuardRegionService regions,
                            SnapshotRepository snapshots, ArmStateRepository arms, ResetJournalRepository journals, Executor io) {
        this(plugin, config, flags, regions, snapshots, arms, journals, new SparseBaselineRepository(plugin.getDataFolder().toPath().resolve("sparse-baselines-v1")), io);
    }

    public ResetCoordinator(JavaPlugin plugin, MaceGuardConfig config, MaceGuardFlags flags, WorldGuardRegionService regions,
                            SnapshotRepository snapshots, ArmStateRepository arms, ResetJournalRepository journals,
                            SparseBaselineRepository sparseBaselines, Executor io) {
        this.plugin = plugin; this.config = config; this.flags = flags; this.regions = regions; this.snapshots = snapshots; this.sparseBaselines = sparseBaselines;
        this.arms = arms; this.journals = journals; this.io = io;
        this.capture = new SnapshotCaptureService(plugin, config.performance().captureBatchSize(), io);
        this.executor = new ResetExecutor(plugin, io, journals, config.performance().restoreBatchSize());
        io.execute(this::loadPersistentState);
    }

    public boolean hasActiveOperation() { return destructiveOperation.get(); }
    public String startupProblem() { return startupProblem; }

    public void recoveryStatus(Consumer<String> feedback) {
        io.execute(() -> {
            try {
                Optional<ResetJournal> journal = journals.load();
                main(() -> feedback.accept(journal.map(value -> "Restore journal: operation=" + value.operationId() + ", region=" + value.regionId()
                        + ", status=" + value.status() + ", progress=" + value.nextChange() + "/" + value.totalChanges())
                        .orElse("No restore journal exists.")));
            } catch (IOException ex) { main(() -> feedback.accept("Restore journal cannot be read: " + ex.getMessage())); }
        });
    }

    /** Returns true only when an explicitly armed sparse profile must delay this first change for a durable write. */
    public boolean prepareSparseOriginal(Location location, BlockState original, Consumer<String> feedback) {
        if (!RuntimeSafetyPolicy.allowsSparseOriginalInterception(config.enabled()) || !stateReady || location.getWorld() == null || flags.resetProfile() == null) return false;
        RegionDescriptor active = activeRegion;
        if (destructiveOperation.get() && active != null && active.worldUuid().equals(location.getWorld().getUID())
                && active.contains(location.getBlockX(), location.getBlockY(), location.getBlockZ())) {
            feedback.accept("Change delayed while the WorldGuard region capture/reset is active.");
            return true;
        }
        var applicable = com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                .getApplicableRegions(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(location)).getRegions();
        boolean delayed = false;
        for (ProtectedRegion candidate : applicable) {
            String profileName = candidate.getFlag(flags.resetProfile());
            ResetProfile profile = profileName == null ? null : config.resetProfiles().get(profileName);
            if (profile == null) continue;
            String candidateKey = sparseKey(location.getWorld().getUID().toString(), candidate.getId());
            if (destructiveOperation.get() && candidateKey.equals(activeRegionKey)) { feedback.accept("Change delayed while the region capture/reset is active."); return true; }
            Resolution resolution = resolve(location.getWorld(), candidate.getId());
            if (!resolution.valid()) continue;
            Optional<ArmState> armed = arms.get(location.getWorld().getUID().toString(), candidate.getId());
            if (armed.isEmpty() || !armMatches(armed.get(), resolution)) continue;
            if (profile.mode() != ResetProfile.Mode.SPARSE_ORIGINALS) continue;
            BlockVector3 point = BlockVector3.at(location.getBlockX(), location.getBlockY(), location.getBlockZ());
            boolean excluded = profile.excludedRegionIds().stream().map(id -> regions.region(location.getWorld(), id))
                    .anyMatch(region -> region.isPresent() && region.get().contains(point));
            if (excluded) continue;
            String baselineKey = sparseKey(location.getWorld().getUID().toString(), candidate.getId());
            SparseBaseline baseline = sparseCache.get(baselineKey);
            if (baseline == null) {
                if (sparseLoads.add(baselineKey)) loadSparse(resolution, loaded -> { sparseLoads.remove(baselineKey); feedback.accept("Sparse baseline loaded; retry the change."); }, error -> { sparseLoads.remove(baselineKey); feedback.accept("Sparse tracking unavailable: " + error); });
                delayed = true;
                continue;
            }
            String coordinate = SparseBaseline.coordinateKey(point.x(), point.y(), point.z());
            if (baseline.originals().containsKey(coordinate)) continue;
            String pendingKey = baselineKey + "|" + coordinate;
            if (!sparsePendingCoordinates.add(pendingKey)) { delayed = true; continue; }
            SnapshotBlock captured;
            try { captured = new BlockStateCodec().capture(original, point.x(), point.y(), point.z()); }
            catch (RuntimeException ex) { sparsePendingCoordinates.remove(pendingKey); feedback.accept("Change refused: " + ex.getMessage()); delayed = true; continue; }
            delayed = true;
            io.execute(() -> persistSparseOriginal(baselineKey, coordinate, captured, pendingKey, feedback));
        }
        return delayed;
    }

    private void persistSparseOriginal(String baselineKey, String coordinate, SnapshotBlock captured, String pendingKey, Consumer<String> feedback) {
        try {
            SparseBaseline current = sparseCache.get(baselineKey);
            if (current == null) throw new IOException("baseline is not loaded");
            java.util.Map<String, SnapshotBlock> originals = new java.util.LinkedHashMap<>(current.originals());
            originals.putIfAbsent(coordinate, captured);
            SparseBaseline updated = new SparseBaseline(current.formatVersion(), current.pluginVersion(), current.worldUuid(), current.regionId(),
                    current.geometry(), current.profile(), current.exclusionHash(), true, current.createdAt(), System.currentTimeMillis(),
                    sparseValidator.checksum(originals), java.util.Map.copyOf(originals));
            sparseBaselines.save(updated);
            sparseCache.put(baselineKey, updated);
            main(() -> feedback.accept("Original block state committed; retry the change."));
        } catch (IOException ex) { main(() -> feedback.accept("Change refused because its original state could not be committed: " + ex.getMessage())); }
        finally { sparsePendingCoordinates.remove(pendingKey); }
    }

    public void tickAutomaticResets() {
        if (!RuntimeSafetyPolicy.allowsAutomaticReset(config.enabled()) || !stateReady || startupProblem != null || resetLocked.get() || destructiveOperation.get()) return;
        long now = System.currentTimeMillis();
        for (ArmState state : arms.all().values()) {
            if (!state.isScheduleEnabled()) continue;
            if (destructiveOperation.get()) return;
            World world;
            try { world = org.bukkit.Bukkit.getWorld(java.util.UUID.fromString(state.worldUuid())); }
            catch (IllegalArgumentException ex) { continue; }
            if (world == null) continue;
            Resolution resolution = resolve(world, state.regionId());
            if (!resolution.valid() || !armMatches(state, resolution)) { disarm(world, state.regionId(), ignored -> { }); continue; }
            int interval = resolution.profile().intervalMinutes();
            if (interval > 0 && now - state.armedAt() >= interval * 60_000L) automaticReset(world, state.regionId());
        }
    }

    public void capture(World world, String regionId, Consumer<String> feedback) {
        if (!RuntimeSafetyPolicy.allowsCapture(config.enabled())) { feedback.accept("Capture refused: MaceGuard is disabled in config."); return; }
        Resolution resolution = resolve(world, regionId);
        if (!resolution.valid()) { feedback.accept(resolution.error()); return; }
        if (!beginOperation(world, regionId)) { feedback.accept("Another capture or restore is active."); return; }
        String worldUuid = world.getUID().toString();
        SparseBaseline sparseEmpty = resolution.profile().mode() == ResetProfile.Mode.SPARSE_ORIGINALS ? emptySparseBaseline(resolution) : null;
        io.execute(() -> {
            try { arms.disarm(worldUuid, resolution.region().id());
                if (resolution.profile().mode() == ResetProfile.Mode.SPARSE_ORIGINALS) {
                    Optional<SparseBaseline> existing = sparseBaselines.load(worldUuid, resolution.region().id());
                    if (existing.isPresent() && !existing.get().originals().isEmpty()) {
                        main(() -> { endOperation(); feedback.accept("Sparse capture refused: existing originals must be restored before a new baseline can replace them. Region is disarmed."); });
                        return;
                    }
                    sparseBaselines.save(sparseEmpty);
                    sparseCache.put(sparseKey(sparseEmpty.worldUuid(), sparseEmpty.regionId()), sparseEmpty);
                    main(() -> { endOperation(); feedback.accept("Sparse baseline created and validated for " + sparseEmpty.regionId() + ". Region remains disarmed."); });
                    return;
                }
                main(() -> capture.capture(world, resolution.region(), resolution.profile().name(), result -> {
                    if (!result.successful()) { endOperation(); feedback.accept("Capture failed: " + result.error()); return; }
                    io.execute(() -> { try { snapshots.save(result.snapshot()); main(() -> { endOperation(); feedback.accept("Snapshot captured and validated for " + resolution.region().id() + ". Region remains disarmed."); }); }
                    catch (IOException ex) { main(() -> { endOperation(); feedback.accept("Capture was not committed: " + ex.getMessage()); }); } });
                }));
            } catch (IOException ex) { main(() -> { endOperation(); feedback.accept("Capture refused: could not durably disarm region: " + ex.getMessage()); }); }
        });
    }

    public void validate(World world, String regionId, Consumer<String> feedback) {
        Resolution resolution = resolve(world, regionId);
        if (!resolution.valid()) { feedback.accept(resolution.error()); return; }
        if (resolution.profile().mode() == ResetProfile.Mode.SPARSE_ORIGINALS) {
            loadSparse(resolution, baseline -> feedback.accept("Sparse baseline " + regionId + ": VALID, originals=" + baseline.originals().size()), feedback);
            return;
        }
        loadSnapshot(resolution, snapshot -> {
            feedback.accept("Snapshot " + resolution.region().id() + ": VALID");
        }, feedback);
    }

    public void arm(World world, String regionId, Consumer<String> feedback) {
        if (!RuntimeSafetyPolicy.allowsArm(config.enabled())) { feedback.accept("Arm refused: MaceGuard is disabled in config."); return; }
        if (!stateReady) { feedback.accept("Persistent reset state is not ready; try again shortly."); return; }
        Resolution resolution = resolve(world, regionId);
        if (!resolution.valid()) { feedback.accept(resolution.error()); return; }
        if (resolution.profile().mode() == ResetProfile.Mode.SPARSE_ORIGINALS) {
            loadSparse(resolution, baseline -> {
                ArmState state = new ArmState(baseline.worldUuid(), baseline.regionId(), baseline.geometry().geometryHash(), resolution.profile().name(),
                        resolution.profile().mode().name(), resolution.exclusionHash(), baseline.formatVersion(), "SPARSE", System.currentTimeMillis(), true);
                io.execute(() -> { try { arms.arm(state); main(() -> feedback.accept("Armed " + baseline.regionId() + " for sparse original journaling and reset.")); }
                catch (IOException ex) { main(() -> feedback.accept("Arm failed: " + ex.getMessage())); } });
            }, feedback);
            return;
        }
        loadSnapshot(resolution, snapshot -> {
            ArmState state = new ArmState(snapshot.worldUuid(), snapshot.regionId(), snapshot.geometryHash(), resolution.profile().name(),
                    resolution.profile().mode().name(), resolution.exclusionHash(), snapshot.formatVersion(), snapshot.checksum(), System.currentTimeMillis(), true);
            io.execute(() -> { try { arms.arm(state); main(() -> feedback.accept("Armed " + snapshot.regionId() + " for this exact geometry and snapshot.")); }
            catch (IOException ex) { main(() -> feedback.accept("Arm failed: " + ex.getMessage())); } });
        }, feedback);
    }

    public void disarm(World world, String regionId, Consumer<String> feedback) {
        String worldUuid = world.getUID().toString();
        io.execute(() -> { try { arms.disarm(worldUuid, regionId); main(() -> feedback.accept("Disarmed " + regionId + ".")); }
        catch (IOException ex) { main(() -> feedback.accept("Disarm failed: " + ex.getMessage())); } });
    }

    public void setSchedule(World world, String regionId, boolean enabled, Consumer<String> feedback) {
        if (!RuntimeSafetyPolicy.allowsScheduleChange(config.enabled(), enabled)) { feedback.accept("Schedule enable refused: MaceGuard is disabled in config."); return; }
        String worldUuid = world.getUID().toString();
        Optional<ArmState> current = arms.get(worldUuid, regionId);
        if (current.isEmpty()) { feedback.accept("Schedule unchanged: " + regionId + " is not armed."); return; }
        ArmState state = current.get();
        Resolution resolution = resolve(world, regionId);
        if (!resolution.valid() || !armMatches(state, resolution)) {
            disarm(world, regionId, ignored -> { });
            feedback.accept("Schedule refused: armed state no longer matches the region/profile and was disarmed.");
            return;
        }
        long timerAnchor = enabled ? System.currentTimeMillis() : state.armedAt();
        ArmState updated = new ArmState(state.worldUuid(), state.regionId(), state.geometryHash(), state.profile(), state.mode(),
                state.exclusionsHash(), state.snapshotFormat(), state.snapshotChecksum(), timerAnchor, enabled);
        io.execute(() -> {
            try { arms.arm(updated); main(() -> feedback.accept("Automatic snapshot restores " + (enabled ? "enabled" : "paused") + " for " + regionId + ".")); }
            catch (IOException ex) { main(() -> feedback.accept("Schedule change failed: " + ex.getMessage())); }
        });
    }

    public void plan(World world, String regionId, Consumer<String> feedback) {
        buildPlan(world, regionId, result -> {
            if (result.plan() == null) { feedback.accept(result.error()); return; }
            String refusal = planner.refusal(result.plan(), result.profile().maxTotalChanges(), result.profile().maxAirChanges());
            String token = refusal == null ? tokens.issue(result.plan()) : null;
            ResetPlan plan = result.plan();
            feedback.accept("Preflight " + plan.regionId() + ": inspected=" + plan.coordinatesInspected() + ", changes=" + plan.totalChanges()
                    + ", non-air=" + plan.nonAirChanges() + ", air=" + plan.airChanges() + ", block-entities=" + plan.blockEntities()
                    + ", excluded=" + plan.excludedCoordinates() + ", batches=" + plan.estimatedBatches()
                    + (refusal == null ? ". Confirmation token: " + token : ". Reset refused: " + refusal));
        });
    }

    public void reset(World world, String regionId, String token, Consumer<String> feedback) {
        if (!RuntimeSafetyPolicy.allowsManualReset(config.enabled())) { feedback.accept("Reset refused: MaceGuard is disabled in config."); return; }
        if (!stateReady) { feedback.accept("Persistent reset state is not ready."); return; }
        if (resetLocked.get()) { feedback.accept("Reset refused: " + resetLockReason); return; }
        if (startupProblem != null) { feedback.accept("Reset refused: " + startupProblem); return; }
        if (!beginOperation(world, regionId)) { feedback.accept("Another capture or restore is active."); return; }
        buildPlan(world, regionId, result -> {
            if (result.plan() == null) { endOperation(); feedback.accept(result.error()); return; }
            String refusal = planner.refusal(result.plan(), result.profile().maxTotalChanges(), result.profile().maxAirChanges());
            if (refusal != null) { endOperation(); feedback.accept("Reset refused: " + refusal); return; }
            if (!tokens.consume(token, result.plan())) { endOperation(); feedback.accept("Reset refused: confirmation token is missing, stale, expired, or belongs to another plan."); return; }
            executor.execute(world, result.plan(), message -> {
                if (message.startsWith("Reset completed:")) finishSuccessfulReset(world, regionId, result.profile(), message, feedback);
                else { endOperation(); feedback.accept(message); }
            }, this::lockRestores);
        });
    }

    private void automaticReset(World world, String regionId) {
        if (!RuntimeSafetyPolicy.allowsAutomaticReset(config.enabled()) || resetLocked.get()) return;
        if (!beginOperation(world, regionId)) return;
        buildPlan(world, regionId, result -> {
            if (result.plan() == null) { endOperation(); plugin.getLogger().severe("Automatic reset disabled for " + regionId + ": " + result.error()); disarm(world, regionId, ignored -> { }); return; }
            String refusal = planner.refusal(result.plan(), result.profile().maxTotalChanges(), result.profile().maxAirChanges());
            if (refusal != null) { endOperation(); plugin.getLogger().severe("Automatic reset refused for " + regionId + ": " + refusal); disarm(world, regionId, ignored -> { }); return; }
            executor.execute(world, result.plan(), message -> {
                if (message.startsWith("Reset completed:")) finishSuccessfulReset(world, regionId, result.profile(), message, plugin.getLogger()::info);
                else { endOperation(); disarm(world, regionId, ignored -> { }); plugin.getLogger().info(message); }
            }, this::lockRestores);
        });
    }

    private void finishSuccessfulReset(World world, String regionId, ResetProfile profile, String message, Consumer<String> feedback) {
        if (profile.mode() != ResetProfile.Mode.SPARSE_ORIGINALS) { endOperation(); touchArm(world, regionId); feedback.accept(message); return; }
        String key = sparseKey(world.getUID().toString(), regionId);
        SparseBaseline current = sparseCache.get(key);
        if (current == null) { endOperation(); disarm(world, regionId, ignored -> { }); feedback.accept(message + " Sparse baseline cache was unavailable; region was disarmed."); return; }
        io.execute(() -> {
            try {
                java.util.Map<String, SnapshotBlock> empty = java.util.Map.of();
                SparseBaseline cleared = new SparseBaseline(current.formatVersion(), current.pluginVersion(), current.worldUuid(), current.regionId(),
                        current.geometry(), current.profile(), current.exclusionHash(), true, current.createdAt(), System.currentTimeMillis(),
                        sparseValidator.checksum(empty), empty);
                sparseBaselines.save(cleared);
                sparseCache.put(key, cleared);
                main(() -> { endOperation(); touchArm(world, regionId); feedback.accept(message + " Sparse originals were cleared only after durable completion."); });
            } catch (IOException ex) { main(() -> { endOperation(); disarm(world, regionId, ignored -> { }); feedback.accept(message + " Baseline cleanup failed and the region was disarmed: " + ex.getMessage()); }); }
        });
    }

    private void touchArm(World world, String regionId) {
        arms.get(world.getUID().toString(), regionId).ifPresent(state -> io.execute(() -> {
            try { arms.arm(new ArmState(state.worldUuid(), state.regionId(), state.geometryHash(), state.profile(), state.mode(), state.exclusionsHash(), state.snapshotFormat(), state.snapshotChecksum(), System.currentTimeMillis(), state.scheduleEnabled())); }
            catch (IOException ex) { plugin.getLogger().severe("Reset completed but schedule state could not be committed; disarm this region before further operation: " + ex.getMessage()); }
        }));
    }

    public void status(World world, String regionId, Consumer<String> feedback) {
        Resolution resolution = resolve(world, regionId);
        if (!resolution.valid()) { feedback.accept(resolution.error()); return; }
        Optional<ArmState> armed = arms.get(world.getUID().toString(), resolution.region().id());
        if (armed.isEmpty() || !armMatches(armed.get(), resolution)) {
            if (armed.isPresent()) disarm(world, regionId, ignored -> { });
            feedback.accept(statusLine(regionId, resolution, false));
            return;
        }
        if (resolution.profile().mode() == ResetProfile.Mode.SPARSE_ORIGINALS) {
            loadSparse(resolution, baseline -> feedback.accept(statusLine(regionId, resolution, true) + ", schedule="
                            + (armed.get().isScheduleEnabled() ? "enabled" : "paused") + ", originals=" + baseline.originals().size()),
                    error -> { disarm(world, regionId, ignored -> { }); feedback.accept(statusLine(regionId, resolution, false) + ", baseline=" + error); });
            return;
        }
        loadSnapshot(resolution, snapshot -> {
            boolean matches = armed.get().snapshotChecksum().equals(snapshot.checksum());
            if (!matches) disarm(world, regionId, ignored -> { });
            feedback.accept(statusLine(regionId, resolution, matches) + ", schedule=" + (armed.get().isScheduleEnabled() ? "enabled" : "paused"));
        }, error -> { disarm(world, regionId, ignored -> { }); feedback.accept(statusLine(regionId, resolution, false) + ", snapshot=" + error); });
    }

    private void buildPlan(World world, String regionId, Consumer<PlanResult> callback) {
        Resolution resolution = resolve(world, regionId);
        if (!resolution.valid()) { callback.accept(PlanResult.failure(resolution.error())); return; }
        Optional<ArmState> armed = arms.get(world.getUID().toString(), resolution.region().id());
        if (armed.isEmpty() || !armMatches(armed.get(), resolution)) { callback.accept(PlanResult.failure("Reset disabled: region is not armed for its current geometry/profile.")); return; }
        List<ProtectedRegion> exclusions = new ArrayList<>();
        for (String id : resolution.profile().excludedRegionIds()) {
            Optional<ProtectedRegion> excluded = regions.region(world, id);
            if (excluded.isEmpty()) { callback.accept(PlanResult.failure("Reset disabled: excluded WorldGuard region '" + id + "' could not be resolved.")); return; }
            exclusions.add(excluded.get());
        }
        if (resolution.profile().mode() == ResetProfile.Mode.SPARSE_ORIGINALS) {
            loadSparse(resolution, baseline -> {
                List<SnapshotBlock> originals = baseline.originals().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).map(java.util.Map.Entry::getValue).toList();
                Snapshot pseudo = new Snapshot(Snapshot.FORMAT_VERSION, baseline.pluginVersion(), baseline.regionId(), baseline.geometry().worldName(),
                        baseline.worldUuid(), baseline.geometry().type(), baseline.geometry(), baseline.geometry().geometryHash(), baseline.profile(),
                        baseline.createdAt(), baseline.updatedAt(), true, originals.size(), originals.size(), baseline.checksum(), originals);
                collectCurrent(world, pseudo, current -> {
                    ResetPlan plan = planner.plan(pseudo, current, block -> exclusions.stream().anyMatch(region -> region.contains(BlockVector3.at(block.x(), block.y(), block.z()))), config.performance().restoreBatchSize());
                    callback.accept(new PlanResult(plan, resolution.profile(), null));
                }, error -> callback.accept(PlanResult.failure(error)));
            }, error -> callback.accept(PlanResult.failure(error)));
            return;
        }
        loadSnapshot(resolution, snapshot -> {
            if (!armed.get().snapshotChecksum().equals(snapshot.checksum())) { callback.accept(PlanResult.failure("Reset disabled: snapshot checksum changed after arming.")); return; }
            collectCurrent(world, snapshot, current -> {
                ResetPlan plan = planner.plan(snapshot, current, block -> exclusions.stream().anyMatch(region -> region.contains(BlockVector3.at(block.x(), block.y(), block.z()))), config.performance().restoreBatchSize());
                callback.accept(new PlanResult(plan, resolution.profile(), null));
            }, error -> callback.accept(PlanResult.failure(error)));
        }, error -> callback.accept(PlanResult.failure(error)));
    }

    private void collectCurrent(World world, Snapshot snapshot, Consumer<List<SnapshotBlock>> done, Consumer<String> failed) {
        List<SnapshotBlock> current = new ArrayList<>(snapshot.blocks().size());
        BlockStateCodec codec = new BlockStateCodec();
        new BukkitRunnable() {
            int index;
            @Override public void run() {
                int end = Math.min(snapshot.blocks().size(), index + config.performance().planBatchSize());
                while (index < end) {
                    SnapshotBlock block = snapshot.blocks().get(index++);
                    if (!world.isChunkLoaded(block.x() >> 4, block.z() >> 4)) { cancel(); failed.accept("Preflight refused: required chunk is not loaded."); return; }
                    try { org.bukkit.Bukkit.createBlockData(block.blockData()); current.add(codec.capture(world.getBlockAt(block.x(), block.y(), block.z()))); }
                    catch (RuntimeException ex) { cancel(); failed.accept("Preflight refused: " + ex.getMessage()); return; }
                }
                if (index == snapshot.blocks().size()) { cancel(); done.accept(current); }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private Resolution resolve(World world, String regionId) {
        if (!config.validSchema()) return Resolution.failure("Reset disabled: configuration schema is invalid: " + String.join("; ", config.errors()));
        Optional<ProtectedRegion> raw = regions.region(world, regionId);
        if (raw.isEmpty()) return Resolution.failure("Reset disabled: WorldGuard region does not exist in world " + world.getName() + ".");
        Optional<RegionDescriptor> region = regions.cuboid(world, regionId);
        if (region.isEmpty()) return Resolution.failure("Reset disabled: only exact WorldGuard cuboid regions are supported.");
        if (flags.resetProfile() == null) return Resolution.failure("Reset disabled: reset-profile flag is unavailable.");
        String profileName = raw.get().getFlag(flags.resetProfile());
        if (profileName == null || profileName.isBlank()) return Resolution.failure("Reset disabled: region has no direct maceguard-reset-profile value.");
        ResetProfile profile = config.resetProfiles().get(profileName);
        if (profile == null) return Resolution.failure("Reset disabled: profile '" + profileName + "' is missing or invalid.");
        if (region.get().volume() > profile.maxCoordinates()) return Resolution.failure("Reset disabled: region volume " + region.get().volume() + " exceeds profile max-coordinates " + profile.maxCoordinates() + ".");
        StringBuilder exclusions = new StringBuilder();
        for (String excludedId : profile.excludedRegionIds()) {
            Optional<ProtectedRegion> excluded = regions.region(world, excludedId);
            if (excluded.isEmpty()) return Resolution.failure("Reset disabled: excluded WorldGuard region '" + excludedId + "' could not be resolved.");
            exclusions.append(exclusionGeometry(excluded.get())).append('\n');
        }
        return new Resolution(region.get(), profile, sha256(exclusions.toString()), null);
    }

    private boolean armMatches(ArmState state, Resolution current) {
        return state.geometryHash().equals(current.region().geometryHash()) && state.profile().equals(current.profile().name())
                && state.mode().equals(current.profile().mode().name()) && java.util.Objects.equals(state.exclusionsHash(), current.exclusionHash())
                && state.snapshotFormat() == (current.profile().mode() == ResetProfile.Mode.SPARSE_ORIGINALS ? SparseBaseline.FORMAT_VERSION : Snapshot.FORMAT_VERSION);
    }

    private String statusLine(String regionId, Resolution resolution, boolean armed) {
        String recovery = startupProblem != null ? startupProblem : resetLockReason;
        return "Reset " + regionId + ": profile=" + resolution.profile().name() + ", mode=" + resolution.profile().mode()
                + ", armed=" + armed + (recovery == null ? "" : ", recovery=" + recovery);
    }

    private String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private String exclusionGeometry(ProtectedRegion region) {
        StringBuilder value = new StringBuilder(region.getId()).append('|').append(region.getClass().getName()).append('|')
                .append(region.getMinimumPoint()).append('|').append(region.getMaximumPoint());
        if (region instanceof com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion polygon)
            polygon.getPoints().forEach(point -> value.append('|').append(point));
        return value.toString();
    }

    private void loadSnapshot(Resolution resolution, Consumer<Snapshot> success, Consumer<String> failure) {
        io.execute(() -> { try {
            Optional<Snapshot> snapshot = snapshots.load(resolution.region().worldUuid().toString(), resolution.region().id());
            if (snapshot.isEmpty()) { main(() -> failure.accept("Snapshot missing; reset disabled.")); return; }
            SnapshotValidator.Validation validation = validator.validate(snapshot.get(), resolution.region(), resolution.profile().name());
            main(() -> { if (validation.valid()) success.accept(snapshot.get()); else failure.accept("Snapshot invalid: " + validation.reason()); });
        } catch (IOException ex) { main(() -> failure.accept("Snapshot could not be loaded: " + ex.getMessage())); } });
    }

    private void loadSparse(Resolution resolution, Consumer<SparseBaseline> success, Consumer<String> failure) {
        String key = sparseKey(resolution.region().worldUuid().toString(), resolution.region().id());
        SparseBaseline cached = sparseCache.get(key);
        if (cached != null) {
            io.execute(() -> {
                SparseBaselineValidator.Validation validation = sparseValidator.validate(cached, resolution.region(), resolution.profile().name(), resolution.exclusionHash());
                main(() -> { if (validation.valid()) success.accept(cached); else failure.accept("Sparse baseline invalid: " + validation.reason()); });
            });
            return;
        }
        io.execute(() -> { try {
            Optional<SparseBaseline> loaded = sparseBaselines.load(resolution.region().worldUuid().toString(), resolution.region().id());
            if (loaded.isEmpty()) { main(() -> failure.accept("Sparse baseline missing; reset disabled.")); return; }
            SparseBaselineValidator.Validation validation = sparseValidator.validate(loaded.get(), resolution.region(), resolution.profile().name(), resolution.exclusionHash());
            if (validation.valid()) sparseCache.put(key, loaded.get());
            main(() -> { if (validation.valid()) success.accept(loaded.get()); else failure.accept("Sparse baseline invalid: " + validation.reason()); });
        } catch (IOException ex) { main(() -> failure.accept("Sparse baseline could not be loaded: " + ex.getMessage())); } });
    }

    private SparseBaseline emptySparseBaseline(Resolution resolution) {
        long now = System.currentTimeMillis();
        java.util.Map<String, SnapshotBlock> originals = java.util.Map.of();
        return new SparseBaseline(SparseBaseline.FORMAT_VERSION, plugin.getPluginMeta().getVersion(), resolution.region().worldUuid().toString(),
                resolution.region().id(), resolution.region(), resolution.profile().name(), resolution.exclusionHash(), true, now, now,
                sparseValidator.checksum(originals), originals);
    }

    private String sparseKey(String worldUuid, String regionId) { return worldUuid + ":" + regionId.toLowerCase(java.util.Locale.ROOT); }

    private void loadPersistentState() {
        try {
            arms.load();
            if (!config.enabled() && !arms.all().isEmpty()) {
                arms.disarmAll();
                plugin.getLogger().warning("MaceGuard is disabled; all reset arming states were cleared and require explicit re-arming after enable.");
            }
            Optional<ResetJournal> journal = journals.load();
            if (journal.filter(ResetJournal::requiresAdministratorReview).isPresent()) {
                lockRestores("unresolved restore " + journal.get().operationId() + " requires administrator review; region is not automatically resumed");
                startupProblem = resetLockReason;
            }
            if (snapshots.hasIncompleteFiles()) startupProblem = "an interrupted snapshot capture was detected; capture a fresh snapshot";
            if (sparseBaselines.hasIncompleteFiles()) startupProblem = "an interrupted sparse baseline write was detected; sparse reset state requires administrator review";
            stateReady = true;
        } catch (IOException ex) { startupProblem = "persistent reset state failed validation: " + ex.getMessage(); stateReady = true; }
    }

    private void main(Runnable action) { plugin.getServer().getScheduler().runTask(plugin, action); }
    private boolean beginOperation(World world, String regionId) {
        if (!config.enabled() || resetLocked.get()) return false;
        if (!destructiveOperation.compareAndSet(false, true)) return false;
        activeRegionKey = sparseKey(world.getUID().toString(), regionId);
        activeRegion = regions.cuboid(world, regionId).orElse(null);
        return true;
    }
    private void endOperation() { activeRegionKey = null; activeRegion = null; destructiveOperation.set(false); }
    private void lockRestores(String reason) {
        if (resetLocked.compareAndSet(false, true)) resetLockReason = reason;
    }
    private record Resolution(RegionDescriptor region, ResetProfile profile, String exclusionHash, String error) {
        static Resolution failure(String error) { return new Resolution(null, null, null, error); }
        boolean valid() { return error == null; }
    }
    private record PlanResult(ResetPlan plan, ResetProfile profile, String error) {
        static PlanResult failure(String error) { return new PlanResult(null, null, error); }
    }
}
