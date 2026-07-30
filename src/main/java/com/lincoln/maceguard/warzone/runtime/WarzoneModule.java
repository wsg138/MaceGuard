package com.lincoln.maceguard.warzone.runtime;

import com.lincoln.maceguard.temporary.TemporaryBlockService;
import com.lincoln.maceguard.warzone.command.WarzoneCommand;
import com.lincoln.maceguard.warzone.config.ValidationResult;
import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.config.WarzoneConfigLoader;
import com.lincoln.maceguard.warzone.config.WarzoneMessages;
import com.lincoln.maceguard.warzone.config.WarzoneMessagesLoader;
import com.lincoln.maceguard.warzone.integration.PlaceholderHookFactory;
import com.lincoln.maceguard.warzone.integration.WarzonePlaceholderHook;
import com.lincoln.maceguard.warzone.rotation.WarzoneStateStore;
import com.lincoln.maceguard.warzone.restriction.RestrictionDecision;
import org.bukkit.Bukkit;
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
    private volatile WarzoneRuntime runtime;
    private WarzonePlaceholderHook placeholder;

    public WarzoneModule(JavaPlugin plugin, TemporaryBlockService temporaryBlocks, Executor io) {
        this(plugin, temporaryBlocks, io, Clock.systemUTC());
    }

    WarzoneModule(JavaPlugin plugin, TemporaryBlockService temporaryBlocks, Executor io, Clock clock) {
        this.plugin = plugin;
        this.temporaryBlocks = temporaryBlocks;
        this.clock = clock;
        this.configFile = plugin.getDataFolder().toPath().resolve("warzone.yml");
        this.messagesFile = plugin.getDataFolder().toPath().resolve("warzone-messages.yml");
        this.stateStore = new WarzoneStateStore(plugin.getDataFolder().toPath().resolve("state")
                .resolve("warzone-state.yml"), plugin.getLogger(), io);
    }

    public void start() {
        WarzoneCommand handler = new WarzoneCommand(this);
        var command = Objects.requireNonNull(plugin.getCommand("warzone"), "warzone command missing from plugin.yml");
        command.setExecutor(handler);
        command.setTabCompleter(handler);
        try {
            new WarzoneMigrationService(plugin).prepare();
            Prepared prepared = validateFiles(false);
            log(prepared);
            if (prepared.valid()) {
                runtime = new WarzoneRuntime(plugin, temporaryBlocks, prepared.config(), prepared.messages(), stateStore, clock);
                runtime.start();
                if (!runtime.region().regionResolved())
                    plugin.getLogger().warning("WorldGuard region '" + runtime.region().regionId() + "' in world '"
                            + runtime.region().worldName() + "' is not currently available.");
                plugin.getLogger().info("Integrated warzone module started with " + prepared.config().rotations().size()
                        + " rotations; active rotation is '" + runtime.rotations().active().id() + "'.");
            } else {
                plugin.getLogger().severe("Integrated warzone module is inactive; the rest of MaceGuard remains enabled.");
            }
        } catch (RuntimeException ex) {
            if (runtime != null) runtime.shutdown(false);
            runtime = null;
            plugin.getLogger().severe("Integrated warzone startup failed without disabling MaceGuard: " + ex.getMessage());
        }
        try { registerPlaceholder(); }
        catch (RuntimeException | LinkageError ex) {
            placeholder = null;
            plugin.getLogger().warning("PlaceholderAPI warzone integration is unavailable: " + ex.getMessage());
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
            send(sender, "<red>Reload rejected; the current configuration, rotation, deadline, and services remain active. Check the console.");
            return;
        }
        WarzoneRuntime old = runtime;
        WarzoneConfig.Rotation oldRotation = old == null ? null : old.rotations().active();
        ReloadGuard.Result<WarzoneRuntime> candidate = ReloadGuard.prepare(old, prepared.valid(),
                () -> new WarzoneRuntime(plugin, temporaryBlocks, prepared.config(), prepared.messages(), stateStore, clock));
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
            plugin.getLogger().severe("Warzone reload could not start the replacement runtime: " + ex.getMessage());
            send(sender, "<red>Reload rejected; the current runtime remains active.");
            return;
        }
        if (old != null) old.shutdown(false);
        runtime = replacement;
        if (oldRotation != null && oldRotation.cobwebsAllowed() && !replacement.rotations().active().cobwebsAllowed()
                && prepared.config().cobwebs().clearOnMetaChange()) old.clearTrackedCobwebs();
        send(sender, "<green>Warzone configuration and messages reloaded; the valid rotation deadline was preserved when possible.");
    }

    public void validate(CommandSender sender) {
        Prepared prepared = validateFiles(true);
        log(prepared);
        if (prepared.valid()) send(sender, "<green>Warzone configuration is valid ("
                + prepared.config().rotations().size() + " rotations).");
        else send(sender, "<red>Warzone configuration is invalid. Check the console for path-specific errors.");
    }

    public Prepared validateFiles(boolean validateResolvedRegion) {
        ValidationResult<WarzoneConfig> config = new WarzoneConfigLoader().load(configFile);
        ValidationResult<WarzoneMessages> messages = new WarzoneMessagesLoader().load(messagesFile);
        List<String> errors = new ArrayList<>();
        errors.addAll(config.errors());
        errors.addAll(messages.errors());
        List<String> warnings = new ArrayList<>(config.warnings());
        warnings.addAll(messages.warnings());
        if (validateResolvedRegion && config.valid() && Bukkit.getWorld(config.value().region().world()) != null) {
            com.lincoln.maceguard.warzone.region.WarzoneRegionService region =
                    new com.lincoln.maceguard.warzone.region.WarzoneRegionService(config.value().region());
            if (!region.regionResolved()) errors.add("region.id '" + config.value().region().id()
                    + "' does not exist in loaded world '" + config.value().region().world() + "'.");
        }
        return new Prepared(config.value(), messages.value(), List.copyOf(errors), List.copyOf(warnings));
    }

    public WarzoneRuntime.CobwebDecision cobwebDecision(Player player, Location location) {
        return runtime == null ? WarzoneRuntime.CobwebDecision.permit() : runtime.cobwebDecision(player, location);
    }

    public boolean appliesAt(Location location) {
        return runtime != null && runtime.config().enabled() && runtime.region().contains(location);
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

    public void send(CommandSender sender, String template) {
        if (runtime != null) runtime.messages().send(sender, template);
        else sender.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(template));
    }

    private void registerPlaceholder() {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) return;
        placeholder = PlaceholderHookFactory.register(plugin, () -> runtime);
        if (placeholder == null) {
            placeholder = null;
            plugin.getLogger().warning("PlaceholderAPI rejected the integrated warzone expansion registration.");
        }
    }

    private void log(Prepared prepared) {
        prepared.warnings().forEach(value -> plugin.getLogger().warning("Warzone configuration: " + value));
        prepared.errors().forEach(value -> plugin.getLogger().severe("Warzone configuration: " + value));
    }

    public record Prepared(WarzoneConfig config, WarzoneMessages messages, List<String> errors, List<String> warnings) {
        public boolean valid() { return config != null && messages != null && errors.isEmpty(); }
    }
}
