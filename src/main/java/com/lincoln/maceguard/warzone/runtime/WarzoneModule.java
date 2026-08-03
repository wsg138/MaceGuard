package com.lincoln.maceguard.warzone.runtime;

import com.lincoln.maceguard.policy.BlockPolicyResolver;
import com.lincoln.maceguard.temporary.TemporaryBlockService;
import com.lincoln.maceguard.warzone.command.WarzoneCommand;
import com.lincoln.maceguard.warzone.config.ValidationResult;
import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.config.WarzoneConfigLoader;
import com.lincoln.maceguard.warzone.config.WarzoneMessages;
import com.lincoln.maceguard.warzone.config.WarzoneMessagesLoader;
import com.lincoln.maceguard.warzone.integration.PlaceholderHookFactory;
import com.lincoln.maceguard.warzone.integration.WarzonePlaceholderHook;
import com.lincoln.maceguard.warzone.restriction.RestrictionDecision;
import com.lincoln.maceguard.warzone.rotation.WarzoneStateStore;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

public final class WarzoneModule {
    private final JavaPlugin plugin;
    private final TemporaryBlockService temporaryBlocks;
    private final Path configFile;
    private final Path messagesFile;
    private final WarzoneStateStore stateStore;
    private final Clock clock;
    private final BlockPolicyResolver blockPolicies;
    private volatile WarzoneRuntime runtime;
    private WarzonePlaceholderHook placeholder;

    public WarzoneModule(JavaPlugin plugin, TemporaryBlockService temporaryBlocks, Executor io) {
        this(plugin, temporaryBlocks, io, Clock.systemUTC(), null);
    }

    public WarzoneModule(JavaPlugin plugin, TemporaryBlockService temporaryBlocks, Executor io,
                         BlockPolicyResolver blockPolicies) {
        this(plugin, temporaryBlocks, io, Clock.systemUTC(), blockPolicies);
    }

    WarzoneModule(JavaPlugin plugin, TemporaryBlockService temporaryBlocks, Executor io,
                  Clock clock) {
        this(plugin, temporaryBlocks, io, clock, null);
    }

    WarzoneModule(JavaPlugin plugin, TemporaryBlockService temporaryBlocks, Executor io,
                  Clock clock, BlockPolicyResolver blockPolicies) {
        this.plugin = plugin;
        this.temporaryBlocks = temporaryBlocks;
        this.clock = clock;
        this.blockPolicies = blockPolicies;
        this.configFile = plugin.getDataFolder().toPath().resolve("warzone.yml");
        this.messagesFile = plugin.getDataFolder().toPath().resolve("warzone-messages.yml");
        this.stateStore = new WarzoneStateStore(plugin.getDataFolder().toPath().resolve("state")
                .resolve("warzone-state.yml"), plugin.getLogger(), io);
    }

    public void start() {
        WarzoneCommand handler = new WarzoneCommand(this);
        var command = Objects.requireNonNull(plugin.getCommand("warzone"),
                "warzone command missing from plugin.yml");
        command.setExecutor(handler);
        command.setTabCompleter(handler);
        try {
            new WarzoneMigrationService(plugin).prepare();
            Prepared prepared = validateFiles(false);
            log(prepared);
            if (prepared.valid()) {
                runtime = new WarzoneRuntime(plugin, temporaryBlocks, prepared.config(),
                        prepared.messages(), stateStore, clock);
                runtime.start();
                plugin.getLogger().info("Integrated weekly warzone module started with "
                        + prepared.config().modifiers().size() + " modifiers; selected set is "
                        + runtime.rotations().active().modifierIds() + "; gameplay scope is "
                        + (runtime.gameplayScopeActive() ? "active" : "inactive") + ".");
            } else {
                plugin.getLogger().severe("Integrated warzone module is inactive; "
                        + "the rest of MaceGuard remains enabled.");
            }
        } catch (RuntimeException ex) {
            if (runtime != null) runtime.shutdown(false);
            runtime = null;
            plugin.getLogger().severe("Integrated warzone startup failed without disabling MaceGuard: "
                    + ex.getMessage());
        }
        try { registerPlaceholder(); }
        catch (RuntimeException | LinkageError ex) {
            placeholder = null;
            plugin.getLogger().warning("PlaceholderAPI warzone integration is unavailable: "
                    + ex.getMessage());
        }
    }

    public void shutdown(boolean pluginDisable) {
        if (runtime != null) runtime.shutdown(pluginDisable);
        runtime = null;
        if (placeholder != null) placeholder.close();
        placeholder = null;
    }

    public void reload(CommandSender sender) {
        Prepared prepared = validateFiles(true);
        log(prepared);
        if (!prepared.valid()) {
            send(sender, "<red>Reload rejected; the current weekly selection, transition, and services remain active. Check the console.");
            return;
        }
        WarzoneRuntime old = runtime;
        WarzoneConfig.ActiveSet oldSet = old == null ? null : old.rotations().active();
        ReloadGuard.Result<WarzoneRuntime> candidate = ReloadGuard.prepare(old, prepared.valid(),
                () -> new WarzoneRuntime(plugin, temporaryBlocks, prepared.config(),
                        prepared.messages(), stateStore, clock));
        if (!candidate.accepted()) {
            if (candidate.failure() != null)
                plugin.getLogger().severe("Warzone reload could not build the replacement runtime: "
                        + candidate.failure().getMessage());
            send(sender, "<red>Reload rejected; the current runtime remains active.");
            return;
        }
        WarzoneRuntime replacement = candidate.value();
        try { replacement.start(); }
        catch (RuntimeException ex) {
            replacement.shutdown(false);
            plugin.getLogger().severe("Warzone reload could not start the replacement runtime: "
                    + ex.getMessage());
            send(sender, "<red>Reload rejected; the current runtime remains active.");
            return;
        }
        if (old != null) old.shutdown(false);
        runtime = replacement;
        if (oldSet != null && oldSet.cobwebsAllowed()
                && !replacement.rotations().active().cobwebsAllowed()
                && prepared.config().cobwebs().clearOnMetaChange()) old.clearTrackedCobwebs();
        send(sender, "<green>Warzone configuration reloaded atomically; valid weekly state was preserved when possible.");
    }

    public void validate(CommandSender sender) {
        Prepared prepared = validateFiles(true);
        log(prepared);
        if (prepared.valid()) send(sender, "<green>Warzone configuration and effective scope are valid ("
                + prepared.config().modifiers().size() + " modifiers).");
        else send(sender, "<red>Warzone validation failed. Check the console for the exact unresolved world, region, or configuration path.");
    }

    public Prepared validateFiles(boolean validateResolvedRegion) {
        ValidationResult<WarzoneConfig> config = new WarzoneConfigLoader().load(configFile);
        ValidationResult<WarzoneMessages> messages = new WarzoneMessagesLoader().load(messagesFile);
        List<String> errors = new ArrayList<>();
        errors.addAll(config.errors());
        errors.addAll(messages.errors());
        List<String> warnings = new ArrayList<>(config.warnings());
        warnings.addAll(messages.warnings());
        if (validateResolvedRegion && config.valid()) {
            var region = new com.lincoln.maceguard.warzone.region.WarzoneRegionService(
                    config.value().region());
            if (!region.worldLoaded())
                errors.add("region.world '" + region.worldName() + "' is not loaded.");
            if (!region.regionResolved())
                errors.add("region.id '" + region.regionId() + "' is unresolved: "
                        + region.outerResolutionStatus() + ".");
            region.exclusionResolutionStatuses().forEach((id, status) -> {
                if (!"resolved".equals(status))
                    errors.add("required excluded region '" + id + "' is unresolved: "
                            + status + ".");
            });
        }
        return new Prepared(config.value(), messages.value(), List.copyOf(errors),
                List.copyOf(warnings));
    }

    public WarzoneRuntime.CobwebDecision cobwebDecision(Player player, Location location) {
        return runtime == null ? WarzoneRuntime.CobwebDecision.permit()
                : runtime.cobwebDecision(player, location);
    }

    public boolean appliesAt(Location location) {
        return runtime != null && runtime.appliesAt(location);
    }

    public void successfulCobweb(Player player, RestrictionDecision decision) {
        if (runtime != null && decision != null) runtime.successfulCobweb(player, decision);
    }

    public void sendCobwebDenial(Player player, WarzoneRuntime.CobwebDecision decision) {
        if (runtime != null) runtime.sendCobwebDenial(player, decision);
        else player.sendMessage("Cobweb placement is unavailable.");
    }

    public Duration cobwebLifetime(Duration fallback, Location location) {
        return appliesAt(location) ? runtime.config().cobwebs().clearAfter() : fallback;
    }

    public WarzoneRuntime runtime() { return runtime; }
    public boolean enabled() { return runtime != null && runtime.config().enabled(); }
    public boolean placeholderActive() { return placeholder != null && placeholder.active(); }
    public int temporaryCobwebCount() { return temporaryBlocks.count(); }
    public BlockPolicyResolver blockPolicies() { return blockPolicies; }

    public void send(CommandSender sender, String template) {
        if (runtime != null) runtime.messages().send(sender, template);
        else sender.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                .deserialize(template));
    }

    private void registerPlaceholder() {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) return;
        placeholder = PlaceholderHookFactory.register(plugin, () -> runtime);
        if (placeholder == null)
            plugin.getLogger().warning("PlaceholderAPI rejected the integrated warzone expansion registration.");
    }

    private void log(Prepared prepared) {
        prepared.warnings().forEach(value ->
                plugin.getLogger().warning("Warzone configuration: " + value));
        prepared.errors().forEach(value ->
                plugin.getLogger().severe("Warzone configuration: " + value));
    }

    public record Prepared(WarzoneConfig config, WarzoneMessages messages,
                           List<String> errors, List<String> warnings) {
        public boolean valid() { return config != null && messages != null && errors.isEmpty(); }
    }
}
