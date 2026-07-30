package com.lincoln.maceguard.warzone.command;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.message.WarzoneMessageService;
import com.lincoln.maceguard.warzone.restriction.RestrictionMode;
import com.lincoln.maceguard.warzone.runtime.WarzoneModule;
import com.lincoln.maceguard.warzone.runtime.WarzoneRuntime;
import com.lincoln.maceguard.warzone.util.DurationFormatter;
import com.lincoln.maceguard.warzone.util.DurationParser;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class WarzoneCommand implements TabExecutor {
    private static final List<String> SUBCOMMANDS = List.of("info", "items", "next", "skip", "force",
            "set", "extend", "reload", "validate", "debug");
    private final WarzoneModule module;

    public WarzoneCommand(WarzoneModule module) { this.module = module; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> permitted(sender, "info", () -> info(sender));
            case "items" -> permitted(sender, "items", () -> items(sender));
            case "next" -> permitted(sender, "next", () -> next(sender));
            case "skip", "force" -> permitted(sender, sub, () -> skip(sender));
            case "set" -> permitted(sender, "set", () -> set(sender, args.length > 1 ? args[1] : null));
            case "extend" -> permitted(sender, "extend", () -> extend(sender,
                    args.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : ""));
            case "reload" -> permitted(sender, "reload", () -> module.reload(sender));
            case "validate" -> permitted(sender, "validate", () -> module.validate(sender));
            case "debug" -> permitted(sender, "debug", () -> debug(sender));
            default -> module.send(sender, "<red>Unknown subcommand. Use <white>/warzone info<red>.");
        }
        return true;
    }

    private void info(CommandSender sender) {
        WarzoneRuntime runtime = requireRuntime(sender);
        if (runtime == null) return;
        WarzoneConfig.Rotation rotation = runtime.rotations().active();
        module.send(sender, "<gold>Warzone Meta: <meta>");
        module.send(sender, rotation.description());
        module.send(sender, "<yellow>Changes in: <white><time_left>");
        module.send(sender, "<yellow>Changes at: <white><changes_at>");
        module.send(sender, "<yellow>Next meta: <next_meta>");
        restrictionLines(sender, rotation);
    }

    private void items(CommandSender sender) {
        WarzoneRuntime runtime = requireRuntime(sender);
        if (runtime == null) return;
        restrictionLines(sender, runtime.rotations().active());
    }

    private void restrictionLines(CommandSender sender, WarzoneConfig.Rotation rotation) {
        module.send(sender, "<yellow>Disabled: <red>" + join(rotation, RestrictionMode.DISABLED, false));
        module.send(sender, "<yellow>Cooldowns: <gold>" + join(rotation, RestrictionMode.COOLDOWN, false));
        module.send(sender, "<yellow>Abilities: <gold>" + abilities(rotation));
        module.send(sender, rotation.cobwebsAllowed()
                ? "<yellow>Cobwebs: <green>Available <gray>— clear after <cobweb_clear_time>"
                : "<yellow>Cobwebs: <red>Unavailable");
    }

    private String join(WarzoneConfig.Rotation rotation, RestrictionMode mode, boolean effectOnly) {
        String value = rotation.restrictions().values().stream()
                .filter(restriction -> restriction.mode() == mode && restriction.target().effectOnly() == effectOnly)
                .sorted(Comparator.comparing(restriction -> restriction.target().id()))
                .map(restriction -> WarzoneMessageService.friendly(restriction.target())
                        + (mode == RestrictionMode.COOLDOWN ? " — " + DurationFormatter.words(restriction.cooldown()) : ""))
                .collect(java.util.stream.Collectors.joining(", "));
        return value.isEmpty() ? "None" : value;
    }

    private String abilities(WarzoneConfig.Rotation rotation) {
        String value = rotation.restrictions().values().stream().filter(value1 -> value1.target().effectOnly())
                .sorted(Comparator.comparing(value1 -> value1.target().id()))
                .map(value1 -> WarzoneMessageService.friendly(value1.target()) + " — "
                        + (value1.mode() == RestrictionMode.DISABLED ? "disabled"
                        : DurationFormatter.words(value1.cooldown()) + " cooldown"))
                .collect(java.util.stream.Collectors.joining(", "));
        return value.isEmpty() ? "None" : value;
    }

    private void next(CommandSender sender) {
        if (requireRuntime(sender) != null)
            module.send(sender, "<yellow>Next meta: <next_meta><gray>, at <white><changes_at><gray>.");
    }

    private void skip(CommandSender sender) {
        WarzoneRuntime runtime = requireRuntime(sender);
        if (runtime == null) return;
        if (!runtime.rotations().skip()) module.send(sender, "<red>The rotation did not change.");
        else module.send(sender, "<green>Advanced to <meta><green>.");
    }

    private void set(CommandSender sender, String id) {
        WarzoneRuntime runtime = requireRuntime(sender);
        if (runtime == null) return;
        if (id == null) {
            module.send(sender, "<red>Usage: /warzone set <rotation-id>");
            return;
        }
        if (!runtime.rotations().config().rotationsById().containsKey(id)) {
            module.send(sender, "<red>Unknown rotation ID '<white>" + id + "<red>'.");
            return;
        }
        if (!runtime.rotations().activate(id, true))
            module.send(sender, "<red>Rotation '<white>" + id + "<red>' is already active.");
        else module.send(sender, "<green>Activated <meta><green>.");
    }

    private void extend(CommandSender sender, String raw) {
        WarzoneRuntime runtime = requireRuntime(sender);
        if (runtime == null) return;
        Duration duration;
        try { duration = DurationParser.parse(raw); }
        catch (RuntimeException ex) {
            module.send(sender, "<red>Enter a positive duration, for example <white>30m<red> or <white>1h 30m<red>.");
            return;
        }
        if (duration.isZero() || duration.isNegative()) {
            module.send(sender, "<red>Enter a positive duration.");
            return;
        }
        if (runtime.rotations().extend(duration))
            module.send(sender, "<green>Extended the current meta by <white>" + DurationFormatter.words(duration) + "<green>.");
    }

    private void debug(CommandSender sender) {
        WarzoneRuntime runtime = module.runtime();
        module.send(sender, "<gold>MaceGuard integrated warzone debug");
        module.send(sender, "<yellow>Module enabled: <white>" + module.enabled());
        if (runtime == null) {
            module.send(sender, "<yellow>Runtime: <red>inactive due to invalid or missing configuration");
            module.send(sender, "<yellow>MaceGuard temporary cobwebs: <white>" + module.temporaryCobwebCount());
            module.send(sender, "<yellow>PlaceholderAPI: <white>" + module.placeholderActive());
            return;
        }
        var state = runtime.rotations().state();
        Object playerInside = sender instanceof Player player ? runtime.region().contains(player.getLocation()) : "not a player";
        List<String> lines = List.of(
                "<yellow>World/region: <white>" + runtime.region().worldName() + " / " + runtime.region().regionId(),
                "<yellow>World loaded: <white>" + runtime.region().worldLoaded(),
                "<yellow>Region resolved: <white>" + runtime.region().regionResolved(),
                "<yellow>Resolution status: <white>" + runtime.region().resolutionStatus(),
                "<yellow>Active/next: <white>" + state.activeRotationId() + " / " + state.nextRotationId(),
                "<yellow>Deadline: <white>" + state.endsAtMillis() + " (" + runtime.messages().formatInstant(state.endsAtMillis()) + ")",
                "<yellow>Active cooldown records: <white>" + runtime.cooldowns().size(),
                "<yellow>MaceGuard temporary cobwebs: <white>" + module.temporaryCobwebCount(),
                "<yellow>Scheduler active: <white>" + runtime.schedulerActive(),
                "<yellow>PlaceholderAPI: <white>" + module.placeholderActive(),
                "<yellow>You are inside: <white>" + playerInside);
        lines.forEach(line -> module.send(sender, line));
    }

    private WarzoneRuntime requireRuntime(CommandSender sender) {
        WarzoneRuntime runtime = module.runtime();
        if (runtime == null) module.send(sender, "<red>The integrated warzone module is inactive. Use /warzone validate.");
        return runtime;
    }

    private void permitted(CommandSender sender, String node, Runnable action) {
        if (sender.hasPermission("warzonerotator.command." + node)) action.run();
        else module.send(sender, "<red>You do not have permission.");
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> allowed = SUBCOMMANDS.stream()
                    .filter(value -> sender.hasPermission("warzonerotator.command." + value)).toList();
            return StringUtil.copyPartialMatches(args[0], allowed, new ArrayList<>()).stream().sorted().toList();
        }
        WarzoneRuntime runtime = module.runtime();
        if (args.length == 2 && args[0].equalsIgnoreCase("set") && runtime != null
                && sender.hasPermission("warzonerotator.command.set"))
            return StringUtil.copyPartialMatches(args[1], runtime.rotations().config().rotationsById().keySet(),
                    new ArrayList<>()).stream().sorted().toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("extend"))
            return List.of("30m", "1h", "1h 30m").stream().filter(value -> value.startsWith(args[1])).toList();
        return List.of();
    }
}
