package com.lincoln.maceguard.warzone.command;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.message.WarzoneMessageService;
import com.lincoln.maceguard.warzone.restriction.RestrictionMode;
import com.lincoln.maceguard.warzone.restriction.RestrictionTarget;
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
import java.util.Map;

public final class WarzoneCommand implements TabExecutor {
    private static final List<String> SUBCOMMANDS = List.of("info", "modifiers", "items", "next",
            "skip", "force", "set", "extend", "reload", "validate", "debug");
    private static final RestrictionTarget MACE = target("MACE");
    private static final RestrictionTarget ENDER_PEARL = target("ENDER_PEARL");
    private static final RestrictionTarget WIND_CHARGE = target("WIND_CHARGE");
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
        module.send(sender, "<gold>Configured modifiers:");
        for (WarzoneConfig.Modifier modifier : runtime.config().modifiers().values().stream()
                .sorted(Comparator.comparing(WarzoneConfig.Modifier::id)).toList()) {
            String group = conflictGroup(runtime.config(), modifier.id());
            boolean selected = runtime.rotations().active().modifierIds().contains(modifier.id());
            module.send(sender, (selected ? "<green>* " : "<gray>- ")
                    + "<yellow>" + modifier.id()
                    + "<gray>: enabled=<white>" + modifier.enabled()
                    + "<gray>, weight=<white>" + modifier.weight()
                    + "<gray>, conflict=<white>" + group
                    + "<gray> — " + modifier.description());
        }
        module.send(sender, "<yellow>Count weights: <white>"
                + runtime.config().selection().countWeights());
        WarzoneConfig.SpecialRule elytra =
                runtime.config().specialRules().get("elytra-no-rockets");
        if (elytra != null) {
            module.send(sender, "<yellow>Elytra inclusion chance: <white>"
                    + elytra.weeklyInclusionChancePercent() + "%");
            module.send(sender, "<yellow>Elytra unrestricted-Mace chance: <white>"
                    + elytra.unrestrictedMaceChancePercent() + "%");
        }
    }

    private void restrictions(CommandSender sender) {
        WarzoneRuntime runtime = requireRuntime(sender);
        if (runtime != null)
            restrictionLines(sender, runtime.rotations().active(), runtime.gameplayScopeActive());
    }

    private void restrictionLines(CommandSender sender, WarzoneConfig.ActiveSet active,
                                  boolean scopeActive) {
        module.send(sender, "<yellow>Maces: <white>" + status(scopeActive, active, MACE));
        module.send(sender, "<yellow>Ender Pearls: <white>"
                + status(scopeActive, active, ENDER_PEARL));
        module.send(sender, "<yellow>Wind Charges: <white>"
                + status(scopeActive, active, WIND_CHARGE));
        module.send(sender, "<yellow>Spear Lunge: <white>"
                + status(scopeActive, active, RestrictionTarget.SPEAR_LUNGE));
        module.send(sender, "<yellow>Elytra: <white>" + elytraStatus(scopeActive, active));
        module.send(sender, "<yellow>Cobwebs: <white>"
                + (!scopeActive ? "Inactive" : active.cobwebsAllowed()
                ? "Allowed — clear after <cobweb_clear_time>" : "Disabled"));
        if (!scopeActive) return;
        module.send(sender, "<yellow>Disabled: <red>" + join(active, RestrictionMode.DISABLED, false));
        module.send(sender, "<yellow>Cooldowns: <gold>" + join(active, RestrictionMode.COOLDOWN, false));
        module.send(sender, "<yellow>Abilities: <gold>" + abilities(active));
    }

    private String status(boolean scopeActive, WarzoneConfig.ActiveSet active,
                          RestrictionTarget target) {
        if (!scopeActive) return "Inactive";
        WarzoneConfig.Restriction restriction = active.restrictions().get(target);
        if (restriction == null) return "Allowed";
        if (restriction.mode() == RestrictionMode.DISABLED) return "Disabled";
        return restriction.cooldown().getSeconds() + "s cooldown";
    }

    private String elytraStatus(boolean scopeActive, WarzoneConfig.ActiveSet active) {
        if (!scopeActive) return "Inactive";
        return active.elytraGlidingAllowed()
                ? "Gliding allowed; rockets disabled" : "Disabled";
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
        for (String id : ids) {
            WarzoneConfig.Modifier modifier = runtime.config().modifiers().get(id);
            if (modifier == null) {
                module.send(sender, "<red>Unknown modifier ID: <white>" + id);
                return;
            }
            if (!modifier.enabled()) {
                module.send(sender, "<red>Modifier <white>" + id
                        + "<red> is disabled in warzone.yml and cannot be selected.");
                return;
            }
        }
        if (!runtime.rotations().set(ids, true)) {
            module.send(sender, "<red>That modifier set conflicts, violates selection limits, "
                    + "breaks a conditional rule, or is already selected.");
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
        lines.add("<yellow>Count weights: <white>"
                + runtime.config().selection().countWeights());
        lines.add("<yellow>Conflict groups: <white>" + runtime.config().conflictGroups());
        lines.add("<yellow>Special rules: <white>" + runtime.config().specialRules());
        lines.add("<yellow>Effective Maces: <white>"
                + status(runtime.gameplayScopeActive(), runtime.rotations().active(), MACE));
        lines.add("<yellow>Effective Ender Pearls: <white>"
                + status(runtime.gameplayScopeActive(), runtime.rotations().active(), ENDER_PEARL));
        lines.add("<yellow>Effective Wind Charges: <white>"
                + status(runtime.gameplayScopeActive(), runtime.rotations().active(), WIND_CHARGE));
        lines.add("<yellow>Effective Spear Lunge: <white>"
                + status(runtime.gameplayScopeActive(), runtime.rotations().active(),
                RestrictionTarget.SPEAR_LUNGE));
        lines.add("<yellow>Effective Elytra: <white>"
                + elytraStatus(runtime.gameplayScopeActive(), runtime.rotations().active()));
        lines.add("<yellow>Active cooldown records: <white>" + runtime.cooldowns().size());
        lines.add("<yellow>MaceGuard temporary cobwebs: <white>"
                + module.temporaryCobwebCount());
        lines.add("<yellow>Scheduler active: <white>" + runtime.schedulerActive());
        lines.add("<yellow>PlaceholderAPI: <white>" + module.placeholderActive());
        lines.forEach(line -> module.send(sender, line));

        for (WarzoneConfig.Modifier modifier : runtime.config().modifiers().values().stream()
                .sorted(Comparator.comparing(WarzoneConfig.Modifier::id)).toList()) {
            module.send(sender, "<yellow>Modifier " + modifier.id() + ": <white>enabled="
                    + modifier.enabled() + ", weight=" + modifier.weight()
                    + ", conflict=" + conflictGroup(runtime.config(), modifier.id()));
        }

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

    private String conflictGroup(WarzoneConfig config, String modifierId) {
        String groups = config.conflictGroups().entrySet().stream()
                .filter(entry -> entry.getValue().contains(modifierId))
                .map(Map.Entry::getKey).sorted()
                .collect(java.util.stream.Collectors.joining(", "));
        return groups.isBlank() ? "none" : groups;
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
            List<String> enabled = runtime.config().modifiers().values().stream()
                    .filter(WarzoneConfig.Modifier::enabled)
                    .map(WarzoneConfig.Modifier::id).toList();
            return StringUtil.copyPartialMatches(current, enabled,
                    new ArrayList<>()).stream().sorted().toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("extend"))
            return List.of("30m", "1h", "1h 30m").stream()
                    .filter(value -> value.startsWith(args[1])).toList();
        return List.of();
    }

    private static RestrictionTarget target(String id) {
        return RestrictionTarget.parse(id).orElseThrow();
    }
}
