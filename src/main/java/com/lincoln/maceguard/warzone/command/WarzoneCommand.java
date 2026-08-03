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
    private static final List<String> SUBCOMMANDS = List.of("info", "modifiers", "items", "next",
            "skip", "force", "set", "extend", "reload", "validate", "debug");
    private final WarzoneModule module;

    public WarzoneCommand(WarzoneModule module) { this.module = module; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> permitted(sender, "info", () -> info(sender));
            case "modifiers" -> permitted(sender, "modifiers", () -> modifiers(sender));
            case "items" -> permitted(sender, "items", () -> restrictions(sender));
            case "next" -> permitted(sender, "next", () -> next(sender));
            case "skip" -> permitted(sender, "skip", () -> reroll(sender, false));
            case "force" -> permitted(sender, "force", () -> reroll(sender, true));
            case "set" -> permitted(sender, "set", () -> set(sender, args));
            case "extend" -> permitted(sender, "extend", () -> extend(sender,
                    args.length > 1 ? String.join(" ",
                            java.util.Arrays.copyOfRange(args, 1, args.length)) : ""));
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
        module.send(sender, "<gold>Weekly Warzone Modifiers: <meta>");
        module.send(sender, runtime.rotations().active().description());
        module.send(sender, "<yellow>Selected IDs: <white><modifiers>");
        if (!runtime.gameplayScopeActive())
            module.send(sender, "<red>The modifier set is selected, but gameplay scope is inactive. "
                    + "No restrictions or positive effects are being applied.");
        module.send(sender, "<yellow>Next random transition: <white><changes_at>");
        module.send(sender, "<yellow>Time remaining: <white><time_left>");
        restrictionLines(sender, runtime.rotations().active(), runtime.gameplayScopeActive());
    }

    private void modifiers(CommandSender sender) {
        WarzoneRuntime runtime = requireRuntime(sender);
        if (runtime == null) return;
        module.send(sender, "<gold>Selected modifiers:");
        for (String id : runtime.rotations().active().modifierIds()) {
            WarzoneConfig.Modifier modifier = runtime.config().modifiers().get(id);
            module.send(sender, "<yellow>" + id + "<gray>: " + modifier.description());
        }
    }

    private void restrictions(CommandSender sender) {
        WarzoneRuntime runtime = requireRuntime(sender);
        if (runtime != null)
            restrictionLines(sender, runtime.rotations().active(), runtime.gameplayScopeActive());
    }

    private void restrictionLines(CommandSender sender, WarzoneConfig.ActiveSet active,
                                  boolean scopeActive) {
        if (!scopeActive) {
            module.send(sender, "<yellow>Effective gameplay restrictions: <red>inactive");
            return;
        }
        module.send(sender, "<yellow>Disabled: <red>" + join(active, RestrictionMode.DISABLED, false));
        module.send(sender, "<yellow>Cooldowns: <gold>" + join(active, RestrictionMode.COOLDOWN, false));
        module.send(sender, "<yellow>Abilities: <gold>" + abilities(active));
        module.send(sender, active.cobwebsAllowed()
                ? "<yellow>Cobwebs: <green>Available <gray>— clear after <cobweb_clear_time>"
                : "<yellow>Cobwebs: <red>Unavailable outside an always-on block-policy region");
        module.send(sender, active.elytraGlidingAllowed()
                ? "<yellow>Elytra: <green>Gliding allowed; rocket boosting blocked"
                : "<yellow>Elytra: <red>Gliding cannot be started");
    }

    private String join(WarzoneConfig.ActiveSet active, RestrictionMode mode, boolean effectOnly) {
        String value = active.restrictions().values().stream()
                .filter(restriction -> restriction.mode() == mode
                        && restriction.target().effectOnly() == effectOnly)
                .sorted(Comparator.comparing(restriction -> restriction.target().id()))
                .map(restriction -> WarzoneMessageService.friendly(restriction.target())
                        + (mode == RestrictionMode.COOLDOWN
                        ? " — " + DurationFormatter.words(restriction.cooldown()) : ""))
                .collect(java.util.stream.Collectors.joining(", "));
        return value.isEmpty() ? "None" : value;
    }

    private String abilities(WarzoneConfig.ActiveSet active) {
        String value = active.restrictions().values().stream()
                .filter(value1 -> value1.target().effectOnly())
                .sorted(Comparator.comparing(value1 -> value1.target().id()))
                .map(value1 -> WarzoneMessageService.friendly(value1.target()) + " — "
                        + (value1.mode() == RestrictionMode.DISABLED ? "disabled"
                        : DurationFormatter.words(value1.cooldown()) + " cooldown"))
                .collect(java.util.stream.Collectors.joining(", "));
        return value.isEmpty() ? "None" : value;
    }

    private void next(CommandSender sender) {
        WarzoneRuntime runtime = requireRuntime(sender);
        if (runtime == null) return;
        module.send(sender, "<yellow>Next weekly transition: <white><changes_at><gray>. "
                + "The random modifier combination has not been selected yet.");
    }

    private void reroll(CommandSender sender, boolean force) {
        WarzoneRuntime runtime = requireRuntime(sender);
        if (runtime == null) return;
        boolean changed = force ? runtime.rotations().force() : runtime.rotations().skip();
        module.send(sender, changed
                ? "<green>Selected a new modifier set without moving the weekly boundary: <meta>"
                : "<yellow>The only valid combination remained selected; the weekly boundary was unchanged.");
    }

    private void set(CommandSender sender, String[] args) {
        WarzoneRuntime runtime = requireRuntime(sender);
        if (runtime == null) return;
        if (args.length < 2) {
            module.send(sender, "<red>Usage: /warzone set <modifier-id> [modifier-id...]");
            return;
        }
        List<String> ids = java.util.Arrays.stream(args).skip(1)
                .flatMap(value -> java.util.Arrays.stream(value.split(",")))
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty()).distinct().toList();
        if (!runtime.rotations().set(ids, true)) {
            module.send(sender, "<red>That modifier set is unknown, conflicts, violates selection limits, or is already selected.");
            return;
        }
        module.send(sender, "<green>Selected <meta><green>; the next weekly boundary was preserved.");
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
        if (!runtime.rotations().extend(duration)) {
            module.send(sender, "<red>Enter a positive duration.");
            return;
        }
        module.send(sender, "<green>Extended this set by <white>"
                + DurationFormatter.words(duration)
                + "<green>. The calendar schedule remains anchored for later weeks.");
    }

    private void debug(CommandSender sender) {
        WarzoneRuntime runtime = module.runtime();
        module.send(sender, "<gold>MaceGuard weekly warzone debug");
        module.send(sender, "<yellow>Configured enabled: <white>" + module.enabled());
        if (runtime == null) {
            module.send(sender, "<yellow>Runtime: <red>inactive due to invalid or missing configuration");
            module.send(sender, "<yellow>MaceGuard temporary cobwebs: <white>"
                    + module.temporaryCobwebCount());
            module.send(sender, "<yellow>PlaceholderAPI: <white>" + module.placeholderActive());
            return;
        }
        var state = runtime.rotations().state();
        Object playerInside = sender instanceof Player player
                ? runtime.appliesAt(player.getLocation()) : "not a player";
        List<String> lines = new ArrayList<>();
        lines.add("<yellow>Outer world/region: <white>" + runtime.region().worldName()
                + " / " + runtime.region().regionId());
        lines.add("<yellow>Outer resolution: <white>" + runtime.region().outerResolutionStatus());
        runtime.region().exclusionResolutionStatuses().forEach((id, status) ->
                lines.add("<yellow>Exclusion " + id + ": <white>" + status));
        lines.add("<yellow>Effective scope resolution: <white>" + runtime.region().resolutionStatus());
        lines.add("<yellow>Gameplay scope active: <white>" + runtime.gameplayScopeActive());
        lines.add("<yellow>Whole-world fallback: <white>false");
        lines.add("<yellow>You are inside effective scope: <white>" + playerInside);
        lines.add("<yellow>Selected modifiers: <white>" + state.activeModifierIds());
        lines.add("<yellow>Activated: <white>"
                + runtime.messages().formatInstant(state.activatedAtMillis()));
        lines.add("<yellow>Calendar boundary: <white>"
                + runtime.messages().formatInstant(state.weeklyBoundaryMillis()));
        lines.add("<yellow>Effective transition: <white>"
                + runtime.messages().formatInstant(state.transitionAtMillis()));
        lines.add("<yellow>Selection: <white>" + runtime.config().selection().mode()
                + " min=" + runtime.config().selection().minimum()
                + " max=" + runtime.config().selection().maximum()
                + " prevent-repeat=" + runtime.config().selection().preventIdenticalRepeat());
        lines.add("<yellow>Conflict groups: <white>" + runtime.config().conflictGroups());
        lines.add("<yellow>Active cooldown records: <white>" + runtime.cooldowns().size());
        lines.add("<yellow>MaceGuard temporary cobwebs: <white>"
                + module.temporaryCobwebCount());
        lines.add("<yellow>Scheduler active: <white>" + runtime.schedulerActive());
        lines.add("<yellow>PlaceholderAPI: <white>" + module.placeholderActive());
        lines.forEach(line -> module.send(sender, line));

        if (sender instanceof Player player && module.blockPolicies() != null) {
            var policy = module.blockPolicies().resolve(player.getLocation());
            module.send(sender, "<yellow>Effective block policy: <white>"
                    + (policy.referenced() ? policy.name() : "none"));
            module.send(sender, "<yellow>Policy source: <white>region="
                    + (policy.scopeId().isBlank() ? "none" : policy.scopeId())
                    + ", kind=" + policy.sourceKind()
                    + ", global=" + policy.globalSource());
            module.send(sender, "<yellow>Named policy exists: <white>"
                    + policy.namedPolicyExists());
            module.send(sender, "<yellow>Schema permits enforcement: <white>"
                    + policy.schemaAllowsEnforcement());
            module.send(sender, "<yellow>Final policy result: <white>"
                    + policy.finalResult());
        }
    }

    private WarzoneRuntime requireRuntime(CommandSender sender) {
        WarzoneRuntime runtime = module.runtime();
        if (runtime == null)
            module.send(sender, "<red>The integrated warzone module is inactive. Use /warzone validate.");
        return runtime;
    }

    private void permitted(CommandSender sender, String node, Runnable action) {
        if (sender.hasPermission("warzonerotator.command." + node)) action.run();
        else module.send(sender, "<red>You do not have permission.");
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command,
                                                 String alias, String[] args) {
        if (args.length == 1) {
            List<String> allowed = SUBCOMMANDS.stream()
                    .filter(value -> sender.hasPermission("warzonerotator.command." + value)).toList();
            return StringUtil.copyPartialMatches(args[0], allowed, new ArrayList<>())
                    .stream().sorted().toList();
        }
        WarzoneRuntime runtime = module.runtime();
        if (args.length >= 2 && args[0].equalsIgnoreCase("set") && runtime != null
                && sender.hasPermission("warzonerotator.command.set")) {
            String current = args[args.length - 1];
            return StringUtil.copyPartialMatches(current, runtime.config().modifiers().keySet(),
                    new ArrayList<>()).stream().sorted().toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("extend"))
            return List.of("30m", "1h", "1h 30m").stream()
                    .filter(value -> value.startsWith(args[1])).toList();
        return List.of();
    }
}
