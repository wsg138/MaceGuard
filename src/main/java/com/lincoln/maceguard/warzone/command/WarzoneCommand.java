package com.lincoln.maceguard.warzone.command;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.config.WarzoneControlConfig;
import com.lincoln.maceguard.warzone.gui.WarzoneGuiManager;
import com.lincoln.maceguard.warzone.message.WarzoneMessageService;
import com.lincoln.maceguard.warzone.restriction.RestrictionMode;
import com.lincoln.maceguard.warzone.restriction.RestrictionTarget;
import com.lincoln.maceguard.warzone.rotation.ActiveSelection;
import com.lincoln.maceguard.warzone.rotation.OverrideDurationMode;
import com.lincoln.maceguard.warzone.rotation.RotationManager;
import com.lincoln.maceguard.warzone.rotation.SelectionSourceType;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class WarzoneCommand implements TabExecutor {
    private static final List<String> ROOT = List.of("info", "modifiers", "modifier", "kit", "kits",
            "items", "next", "schedule", "menu", "help", "random", "override", "reload",
            "validate", "debug", "set", "force", "skip", "extend");
    private static final List<String> DURATIONS = List.of("1h", "next", "manual");
    private static final RestrictionTarget MACE = target("MACE");
    private static final RestrictionTarget ENDER_PEARL = target("ENDER_PEARL");
    private static final RestrictionTarget WIND_CHARGE = target("WIND_CHARGE");
    private final WarzoneModule module;

    public WarzoneCommand(WarzoneModule module) { this.module = module; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String root = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (root) {
                case "info" -> read(sender, "warzonerotator.command.info", () -> info(sender));
                case "modifiers" -> read(sender, "warzonerotator.command.modifiers", () -> activeModifiers(sender));
                case "modifier" -> modifier(sender, args);
                case "kit" -> kit(sender, args);
                case "kits" -> read(sender, "warzonerotator.command.kits", () -> kits(sender));
                case "items" -> read(sender, "warzonerotator.command.modifiers", () -> items(sender));
                case "next" -> read(sender, "warzonerotator.command.next", () -> next(sender));
                case "schedule" -> schedule(sender, args);
                case "menu" -> read(sender, "warzonerotator.command.menu", () -> menu(sender));
                case "help" -> help(sender);
                case "random", "force", "skip" -> manage(sender, "warzonerotator.manage.random",
                        () -> random(sender, args));
                case "override" -> override(sender, args);
                case "reload" -> compatibilityManage(sender, "warzonerotator.command.reload",
                        () -> module.reload(sender));
                case "validate" -> compatibilityManage(sender, "warzonerotator.command.validate",
                        () -> module.validate(sender));
                case "debug" -> compatibilityManage(sender, "warzonerotator.command.debug",
                        () -> debug(sender));
                case "set" -> manage(sender, "warzonerotator.manage.modifier",
                        () -> compatibilitySet(sender, args));
                case "extend" -> compatibilityManage(sender, "warzonerotator.command.extend",
                        () -> extend(sender, args));
                default -> error(sender, "Unknown subcommand. Use /warzone help.");
            }
        } catch (IllegalArgumentException | IllegalStateException ex) {
            error(sender, ex.getMessage());
        }
        return true;
    }

    private void menu(CommandSender sender) {
        WarzoneRuntime runtime = requireRuntime(sender);
        if (runtime == null) return;
        if (sender instanceof Player player) runtime.guis().openMain(player);
        else info(sender);
    }

    private void info(CommandSender sender) {
        WarzoneRuntime runtime = requireRuntime(sender);
        if (runtime == null) return;
        RotationManager rotations = runtime.rotations();
        ActiveSelection finalSelection = rotations.activeSelection();
        ActiveSelection automatic = rotations.automaticSelection();
        module.send(sender, "<gold>Warzone: <white>" + finalSelection.sourceType()
                + sourceId(finalSelection.sourceId()));
        module.send(sender, "<yellow>Active modifiers: <white>" + names(finalSelection.activeSet().modifierIds()));
        if (rotations.state().overrideActive()) {
            module.send(sender, "<yellow>Override: <white>" + rotations.state().overrideDurationMode()
                    + overrideEnd(runtime));
            module.send(sender, "<yellow>Suppressed automatic slot: <white>" + automatic.sourceType()
                    + sourceId(automatic.sourceId()) + " — " + names(automatic.activeSet().modifierIds()));
        } else module.send(sender, "<yellow>Override: <white>none");
        if (rotations.scheduleEnabled()) {
            module.send(sender, "<yellow>Next scheduled transition: <white>"
                    + runtime.messages().formatInstant(rotations.state().automaticSlotEndMillis())
                    + " — " + rotations.nextSlot().entry().type() + " / "
                    + rotations.entryName(rotations.nextSlot().entry()));
        } else {
            module.send(sender, "<yellow>Next scheduled transition: <white>none — schedule disabled");
        }
        module.send(sender, "<yellow>Gameplay scope active: <white>" + runtime.gameplayScopeActive());
    }

    private void activeModifiers(CommandSender sender) {
        WarzoneRuntime runtime = requireRuntime(sender);
        if (runtime == null) return;
        module.send(sender, "<gold>Active Warzone modifiers: <white>"
                + names(runtime.rotations().active().modifierIds()));
        module.send(sender, "<yellow>They end: <white>" + effectiveEnd(runtime));
    }

    private void modifier(CommandSender sender, String[] args) {
        String operation = args.length < 2 ? "list" : args[1].toLowerCase(Locale.ROOT);
        switch (operation) {
            case "list" -> read(sender, "warzonerotator.command.modifiers", () -> modifierList(sender));
            case "set", "add" -> manage(sender, "warzonerotator.manage.modifier",
                    () -> modifierChange(sender, args, true));
            case "remove" -> manage(sender, "warzonerotator.manage.modifier",
                    () -> modifierChange(sender, args, false));
            case "clear" -> manage(sender, "warzonerotator.manage.modifier",
                    () -> modifierClear(sender, args));
            default -> error(sender, "Usage: /warzone modifier <list|set|remove|clear> [modifier] [1h|next|manual]");
        }
    }

    private void modifierList(CommandSender sender) {
        WarzoneRuntime runtime = requireRuntime(sender);
        if (runtime == null) return;
        for (WarzoneConfig.Modifier modifier : runtime.config().modifiers().values().stream()
                .sorted(Comparator.comparing(WarzoneConfig.Modifier::id)).toList()) {
            module.send(sender, (runtime.rotations().active().modifierIds().contains(modifier.id())
                    ? "<green>* " : "<gray>- ") + "<yellow>" + modifier.id()
                    + "<gray>: enabled=<white>" + modifier.enabled()
                    + "<gray>, weight=<white>" + modifier.weight()
                    + "<gray>, conflict=<white>" + conflictGroup(runtime.config(), modifier.id())
                    + "<gray> — " + modifier.description());
        }
    }

    private void modifierChange(CommandSender sender, String[] args, boolean add) {
        WarzoneRuntime runtime = requireRuntime(sender);
        if (runtime == null) return;
        if (args.length < 3) {
            if (sender instanceof Player player) runtime.guis().openModifiers(player,
                    add ? WarzoneGuiManager.Operation.MODIFIER_ADD
                            : WarzoneGuiManager.Operation.MODIFIER_REMOVE);
            else usage(sender, "Console usage: /warzone modifier " + (add ? "set" : "remove")
                    + " <modifier> <1h|next|manual>");
            return;
        }
        String id = resolveModifier(runtime, args[2]);
        boolean custom = allowed(sender, "warzonerotator.manage.custom-combinations");
        requireKitDetachmentPermission(sender, runtime);
        WarzoneConfig.ActiveSet proposed = add
                ? runtime.rotations().previewAdd(id, custom)
                : runtime.rotations().previewRemove(id, custom);
        if (sender instanceof Player player) {
            runtime.guis().openPreview(player,
                    add ? WarzoneGuiManager.Operation.MODIFIER_ADD
                            : WarzoneGuiManager.Operation.MODIFIER_REMOVE,
                    SelectionSourceType.CUSTOM_OVERRIDE, null, proposed);
            return;
        }
        OverrideDurationMode mode = consoleDuration(sender, args, 3);
        if (mode == null) return;
        runtime.rotations().applyPrepared(SelectionSourceType.CUSTOM_OVERRIDE, null,
                proposed.modifierIds(), mode, true);
        success(sender, "Custom override applied: " + names(proposed.modifierIds()));
    }

    private void modifierClear(CommandSender sender, String[] args) {
        WarzoneRuntime runtime = requireRuntime(sender);
        if (runtime == null) return;
        requireKitDetachmentPermission(sender, runtime);
        WarzoneConfig.ActiveSet proposed = runtime.rotations().previewCustom(List.of());
        if (sender instanceof Player player) {
            runtime.guis().openPreview(player, WarzoneGuiManager.Operation.MODIFIER_CLEAR,
                    SelectionSourceType.CUSTOM_OVERRIDE, null, proposed);
            return;
        }
        OverrideDurationMode mode = consoleDuration(sender, args, 2);
        if (mode == null) return;
        runtime.rotations().clearModifiers(mode, true);
        success(sender, "Empty custom override applied.");
    }

    private void kit(CommandSender sender, String[] args) {
        WarzoneRuntime runtime = requireRuntime(sender);
        if (runtime == null) return;
        if (args.length == 1) {
            read(sender, "warzonerotator.command.kits", () -> kitStatus(sender, runtime));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "set" -> manage(sender, "warzonerotator.manage.kit", () -> kitSet(sender, args));
            case "remove" -> manage(sender, "warzonerotator.manage.kit", () -> kitRemove(sender));
            case "list" -> read(sender, "warzonerotator.command.kits", () -> kits(sender));
            default -> error(sender, "Usage: /warzone kit <set|remove|list> [kit] [1h|next|manual]");
        }
    }


    private void kitStatus(CommandSender sender, WarzoneRuntime runtime) {
        ActiveSelection active = runtime.rotations().activeSelection();
        module.send(sender, active.sourceType() == SelectionSourceType.KIT
                ? "<yellow>Active kit: <white>" + active.sourceId()
                : "<yellow>The active selection is " + active.sourceType() + ", not a kit.");
    }

    private void kits(CommandSender sender) {
        WarzoneRuntime runtime = requireRuntime(sender);
        if (runtime == null) return;
        if (sender instanceof Player player) {
            runtime.guis().openKits(player, false);
            return;
        }
        runtime.controlConfig().kits().values().stream().sorted(Comparator.comparing(WarzoneControlConfig.Kit::id))
                .forEach(kit -> module.send(sender, "<yellow>" + kit.id() + "<gray>: enabled=<white>"
                        + kit.enabled() + "<gray>, modifiers=<white>" + names(kit.modifierIds())));
    }

    private void kitSet(CommandSender sender, String[] args) {
        WarzoneRuntime runtime = requireRuntime(sender);
        if (runtime == null) return;
        if (args.length < 3) {
            if (sender instanceof Player player) runtime.guis().openKits(player, true);
            else usage(sender, "Console usage: /warzone kit set <kit> <1h|next|manual>");
            return;
        }
        String id = resolveKit(runtime, args[2]);
        WarzoneConfig.ActiveSet proposed = runtime.rotations().previewKit(id);
        if (sender instanceof Player player) {
            runtime.guis().openPreview(player, WarzoneGuiManager.Operation.KIT_SET,
                    SelectionSourceType.KIT, id, proposed);
            return;
        }
        OverrideDurationMode mode = consoleDuration(sender, args, 3);
        if (mode == null) return;
        runtime.rotations().setKit(id, mode, true);
        success(sender, "Kit override applied: " + id);
    }

    private void kitRemove(CommandSender sender) {
        WarzoneRuntime runtime = requireRuntime(sender);
        if (runtime == null) return;
        if (!runtime.rotations().state().overrideActive()
                || runtime.rotations().state().overrideSourceType() != SelectionSourceType.KIT) {
            error(sender, "No manual kit override is active.");
            return;
        }
        runtime.rotations().clearOverride(true);
        success(sender, "Manual kit override removed; current automatic slot applied.");
    }

    private void random(CommandSender sender, String[] args) {
        WarzoneRuntime runtime = requireRuntime(sender);
        if (runtime == null) return;
        WarzoneConfig.ActiveSet proposed = runtime.rotations().previewRandom();
        if (sender instanceof Player player) {
            runtime.guis().openPreview(player, WarzoneGuiManager.Operation.RANDOM,
                    SelectionSourceType.RANDOM, null, proposed);
            return;
        }
        OverrideDurationMode mode = consoleDuration(sender, args, 1);
        if (mode == null) return;
        runtime.rotations().applyPrepared(SelectionSourceType.RANDOM, null,
                proposed.modifierIds(), mode, true);
        success(sender, "Random override applied: " + names(proposed.modifierIds()));
    }

    private void override(CommandSender sender, String[] args) {
        String operation = args.length < 2 ? "status" : args[1].toLowerCase(Locale.ROOT);
        switch (operation) {
            case "clear" -> manage(sender, "warzonerotator.manage.override", () -> overrideClear(sender));
            case "status" -> read(sender, "warzonerotator.command.info", () -> overrideStatus(sender));
            default -> error(sender, "Usage: /warzone override <clear|status>");
        }
    }

    private void overrideClear(CommandSender sender) {
        WarzoneRuntime runtime = requireRuntime(sender);
        if (runtime == null) return;
        if (runtime.rotations().clearOverride(true))
            success(sender, "Manual override cleared; current automatic slot applied.");
        else error(sender, "No manual override is active.");
    }

    private void overrideStatus(CommandSender sender) {
        WarzoneRuntime runtime = requireRuntime(sender);
        if (runtime == null) return;
        var state = runtime.rotations().state();
        if (!state.overrideActive()) {
            module.send(sender, "<yellow>Manual override: <white>inactive");
            return;
        }
        module.send(sender, "<yellow>Manual override: <white>" + state.overrideSourceType()
                + sourceId(state.overrideSourceId()));
        module.send(sender, "<yellow>Mode: <white>" + state.overrideDurationMode());
        module.send(sender, "<yellow>Ends: <white>" + overrideEnd(runtime).replace("; ends ", ""));
    }

    private void schedule(CommandSender sender, String[] args) {
        WarzoneRuntime runtime = requireRuntime(sender);
        if (runtime == null) return;
        String operation = args.length < 2 ? "view" : args[1].toLowerCase(Locale.ROOT);
        switch (operation) {
            case "view" -> read(sender, "warzonerotator.command.schedule", () -> scheduleView(sender));
            case "preview" -> manage(sender, "warzonerotator.manage.schedule", () -> schedulePreview(sender));
            case "enable" -> manage(sender, "warzonerotator.manage.schedule", () -> {
                runtime.rotations().setScheduleEnabled(true); success(sender, "Automatic schedule enabled."); });
            case "disable" -> manage(sender, "warzonerotator.manage.schedule", () -> {
                runtime.rotations().setScheduleEnabled(false); success(sender, "Automatic schedule disabled and persisted."); });
            case "advance" -> manage(sender, "warzonerotator.manage.schedule", () -> {
                runtime.rotations().advanceSchedule(true); success(sender, "Advanced the current automatic selection once."); });
            default -> error(sender, "Usage: /warzone schedule [enable|disable|preview|advance]");
        }
    }

    private void scheduleView(CommandSender sender) {
        WarzoneRuntime runtime = requireRuntime(sender);
        if (runtime == null) return;
        if (sender instanceof Player player) runtime.guis().openSchedule(player);
        else schedulePreview(sender);
    }

    private void schedulePreview(CommandSender sender) {
        WarzoneRuntime runtime = requireRuntime(sender);
        if (runtime == null) return;
        module.send(sender, "<gold>Repeating cycle: <white>" + runtime.controlConfig().schedule().cadence().every()
                + " " + runtime.controlConfig().schedule().cadence().unit());
        List<WarzoneControlConfig.Entry> entries = runtime.controlConfig().schedule().cycle();
        for (int index = 0; index < entries.size(); index++) {
            WarzoneControlConfig.Entry entry = entries.get(index);
            module.send(sender, (index == runtime.rotations().state().currentCycleIndex()
                    ? "<green>* " : "<gray>- ") + (index + 1) + ". " + entry.type()
                    + " — " + runtime.rotations().entryName(entry));
        }
    }

    private void items(CommandSender sender) {
        WarzoneRuntime runtime = requireRuntime(sender);
        if (runtime == null) return;
        WarzoneConfig.ActiveSet active = runtime.rotations().active();
        boolean scope = runtime.gameplayScopeActive();
        module.send(sender, "<yellow>Maces: <white>" + status(scope, active, MACE));
        module.send(sender, "<yellow>Ender Pearls: <white>" + status(scope, active, ENDER_PEARL));
        module.send(sender, "<yellow>Wind Charges: <white>" + status(scope, active, WIND_CHARGE));
        module.send(sender, "<yellow>Spears: <white>" + status(scope, active, RestrictionTarget.SPEAR));
        module.send(sender, "<yellow>Spear damage: <white>" + status(scope, active, RestrictionTarget.SPEAR_DAMAGE));
        module.send(sender, "<yellow>Spear Lunge: <white>" + status(scope, active, RestrictionTarget.SPEAR_LUNGE));
        module.send(sender, "<yellow>Elytra: <white>" + (!scope ? "Inactive"
                : active.elytraGlidingAllowed() ? "Gliding allowed; rockets disabled" : "Disabled"));
        module.send(sender, "<yellow>Cobwebs: <white>" + (!scope ? "Inactive"
                : active.cobwebsAllowed() ? "Allowed" : "Disabled"));
    }

    private void next(CommandSender sender) {
        WarzoneRuntime runtime = requireRuntime(sender);
        if (runtime == null) return;
        if (!runtime.rotations().scheduleEnabled()) {
            module.send(sender, "<yellow>Next automatic transition: <white>none — schedule disabled");
            return;
        }
        module.send(sender, "<yellow>Next automatic transition: <white>"
                + runtime.messages().formatInstant(runtime.rotations().state().automaticSlotEndMillis()));
        module.send(sender, "<yellow>Next entry: <white>" + runtime.rotations().nextSlot().entry().type()
                + " — " + runtime.rotations().entryName(runtime.rotations().nextSlot().entry()));
    }

    private void compatibilitySet(CommandSender sender, String[] args) {
        WarzoneRuntime runtime = requireRuntime(sender);
        if (runtime == null) return;
        if (args.length < 2) {
            if (sender instanceof Player player)
                runtime.guis().openModifiers(player, WarzoneGuiManager.Operation.MODIFIER_ADD);
            else usage(sender, "Console usage: /warzone set <modifier[,modifier...]> <1h|next|manual>");
            return;
        }
        List<String> ids = splitIds(args[1]);
        for (int index = 0; index < ids.size(); index++) ids.set(index, resolveModifier(runtime, ids.get(index)));
        requireKitDetachmentPermission(sender, runtime);
        WarzoneConfig.ActiveSet proposed = runtime.rotations().previewCustom(ids,
                allowed(sender, "warzonerotator.manage.custom-combinations"));
        if (sender instanceof Player player) {
            runtime.guis().openPreview(player, WarzoneGuiManager.Operation.MODIFIER_ADD,
                    SelectionSourceType.CUSTOM_OVERRIDE, null, proposed);
            return;
        }
        OverrideDurationMode mode = consoleDuration(sender, args, 2);
        if (mode == null) return;
        runtime.rotations().setCustom(proposed.modifierIds(), mode, true);
        success(sender, "Custom override applied.");
    }

    private void extend(CommandSender sender, String[] args) {
        WarzoneRuntime runtime = requireRuntime(sender);
        if (runtime == null) return;
        if (args.length < 2) { usage(sender, "Usage: /warzone extend <duration>"); return; }
        Duration duration = DurationParser.parse(String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
        if (!runtime.rotations().extend(duration)) throw new IllegalArgumentException("Duration must be positive.");
        success(sender, "Override extended by " + DurationFormatter.words(duration) + ".");
    }

    private void debug(CommandSender sender) {
        WarzoneRuntime runtime = module.runtime();
        module.send(sender, "<gold>MaceGuard Warzone debug");
        if (runtime == null) {
            module.send(sender, "<red>Runtime inactive due to invalid configuration.");
            return;
        }
        var state = runtime.rotations().state();
        module.send(sender, "<yellow>Config schema: <white>" + runtime.controlConfig().version());
        module.send(sender, "<yellow>Schedule enabled: <white>" + runtime.rotations().scheduleEnabled());
        module.send(sender, "<yellow>Anchor: <white>" + runtime.controlConfig().schedule().anchorDate()
                + " " + runtime.controlConfig().schedule().time() + " "
                + runtime.controlConfig().schedule().timezone());
        module.send(sender, "<yellow>Cadence: <white>" + runtime.controlConfig().schedule().cadence());
        module.send(sender, "<yellow>Cycle size/position: <white>"
                + runtime.controlConfig().schedule().cycle().size() + " / " + state.currentCycleIndex());
        module.send(sender, "<yellow>Automatic slot: <white>" + state.automaticSlotIdentity());
        module.send(sender, "<yellow>Automatic start/end: <white>"
                + runtime.messages().formatInstant(state.automaticSlotStartMillis()) + " / "
                + runtime.messages().formatInstant(state.automaticSlotEndMillis()));
        module.send(sender, "<yellow>Automatic source/modifiers: <white>" + state.automaticSourceType()
                + sourceId(state.automaticSourceId()) + " / " + state.automaticModifierIds());
        module.send(sender, "<yellow>Manual override active: <white>" + state.overrideActive());
        module.send(sender, "<yellow>Override source/mode/expiration: <white>" + state.overrideSourceType()
                + sourceId(state.overrideSourceId()) + " / " + state.overrideDurationMode() + " / "
                + (state.overrideExpiresAtMillis() > 0
                ? runtime.messages().formatInstant(state.overrideExpiresAtMillis()) : "none"));
        module.send(sender, "<yellow>Final source/modifiers: <white>" + state.activeSourceType()
                + sourceId(state.activeSourceId()) + " / " + state.activeModifierIds());
        module.send(sender, "<yellow>Next scheduled slot: <white>"
                + (runtime.rotations().scheduleEnabled()
                ? runtime.rotations().nextSlot().identity() + " "
                + runtime.rotations().nextSlot().entry().type()
                : "none — schedule disabled"));
        module.send(sender, "<yellow>State persistence: <white>" + runtime.rotations().store().health());
        module.send(sender, "<yellow>Gameplay scope active: <white>" + runtime.gameplayScopeActive());
        module.send(sender, "<yellow>Whole-world fallback: <white>false");
        module.send(sender, "<yellow>GUI sessions: <white>" + runtime.guis().sessionCount());
        module.send(sender, "<yellow>Active cooldown records: <white>" + runtime.cooldowns().size());
    }

    private void help(CommandSender sender) {
        module.send(sender, "<gold>/warzone <white>info, modifiers, modifier list, kit, kits, items, next, schedule, menu");
        if (sender.hasPermission("warzonerotator.admin"))
            module.send(sender, "<gold>Admin: <white>modifier set/remove/clear, kit set/remove/list, random, "
                    + "override clear/status, schedule enable/disable/preview/advance, reload, validate, debug");
    }

    private String status(boolean scope, WarzoneConfig.ActiveSet active, RestrictionTarget target) {
        if (!scope) return "Inactive";
        WarzoneConfig.Restriction restriction = active.restrictions().get(target);
        if (restriction == null) return "Allowed";
        if (restriction.mode() == RestrictionMode.DISABLED) return "Disabled";
        return DurationFormatter.words(restriction.cooldown()) + " cooldown";
    }

    private OverrideDurationMode consoleDuration(CommandSender sender, String[] args, int index) {
        if (args.length <= index) {
            if (sender instanceof Player)
                throw new IllegalArgumentException("Select a duration in the confirmation GUI.");
            usage(sender, "A duration is required: 1h, next, or manual.");
            return null;
        }
        return OverrideDurationMode.parse(args[index]).orElseThrow(() ->
                new IllegalArgumentException("Unknown duration. Use 1h, next, or manual."));
    }


    private void requireKitDetachmentPermission(CommandSender sender, WarzoneRuntime runtime) {
        if (runtime.rotations().activeSelection().sourceType() == SelectionSourceType.KIT
                && !allowed(sender, "warzonerotator.manage.custom-combinations")) {
            throw new IllegalArgumentException("Changing a kit-derived selection requires "
                    + "warzonerotator.manage.custom-combinations.");
        }
    }

    private String resolveModifier(WarzoneRuntime runtime, String raw) {
        String id = raw.toLowerCase(Locale.ROOT);
        WarzoneConfig.Modifier modifier = runtime.config().modifiers().get(id);
        if (modifier == null) throw new IllegalArgumentException(unknown("modifier", id,
                runtime.config().modifiers().keySet()));
        if (!modifier.enabled()) throw new IllegalArgumentException("Modifier '" + id + "' is disabled.");
        return id;
    }

    private String resolveKit(WarzoneRuntime runtime, String raw) {
        String id = raw.toLowerCase(Locale.ROOT);
        WarzoneControlConfig.Kit kit = runtime.controlConfig().kits().get(id);
        if (kit == null) throw new IllegalArgumentException(unknown("kit", id,
                runtime.controlConfig().kits().keySet()));
        if (!kit.enabled()) throw new IllegalArgumentException("Kit '" + id + "' is disabled.");
        return id;
    }

    private String unknown(String kind, String id, Set<String> candidates) {
        List<String> nearest = candidates.stream().sorted(Comparator.comparingInt(value -> distance(id, value)))
                .limit(3).toList();
        return "Unknown " + kind + " '" + id + "'. Closest: " + String.join(", ", nearest);
    }

    private int distance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++)
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1));
            previous = current;
        }
        return previous[right.length()];
    }

    private WarzoneRuntime requireRuntime(CommandSender sender) {
        WarzoneRuntime runtime = module.runtime();
        if (runtime == null) error(sender, "The integrated Warzone module is inactive. Run /warzone validate.");
        return runtime;
    }

    private void read(CommandSender sender, String permission, Runnable action) {
        if (allowed(sender, permission)) action.run(); else error(sender, "You do not have permission.");
    }
    private void manage(CommandSender sender, String permission, Runnable action) {
        if (allowed(sender, permission)) action.run(); else error(sender, "You do not have permission.");
    }
    private void compatibilityManage(CommandSender sender, String oldPermission, Runnable action) {
        if (allowed(sender, oldPermission)) action.run(); else error(sender, "You do not have permission.");
    }
    private boolean allowed(CommandSender sender, String permission) {
        return sender.hasPermission("warzonerotator.admin") || sender.hasPermission(permission);
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command,
                                                 String alias, String[] args) {
        WarzoneRuntime runtime = module.runtime();
        if (args.length == 1) return partial(args[0], ROOT.stream().filter(value -> rootAllowed(sender, value)).toList());
        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "modifier" -> partial(args[1], modifierOperations(sender));
                case "kit" -> partial(args[1], kitOperations(sender));
                case "override" -> partial(args[1], overrideOperations(sender));
                case "schedule" -> partial(args[1], scheduleOperations(sender));
                case "random", "force", "skip" -> allowed(sender, "warzonerotator.manage.random")
                        ? partial(args[1], DURATIONS) : List.of();
                case "set" -> runtime == null || !allowed(sender, "warzonerotator.manage.modifier")
                        ? List.of() : partial(args[1], enabledModifiers(runtime));
                case "extend" -> allowed(sender, "warzonerotator.command.extend")
                        ? partial(args[1], List.of("30m", "1h", "2h")) : List.of();
                default -> List.of();
            };
        }
        if (runtime == null) return List.of();
        if (args.length == 3 && args[0].equalsIgnoreCase("kit") && args[1].equalsIgnoreCase("set")
                && allowed(sender, "warzonerotator.manage.kit"))
            return partial(args[2], enabledKits(runtime));
        if (args.length == 3 && args[0].equalsIgnoreCase("modifier")
                && allowed(sender, "warzonerotator.manage.modifier")
                && (args[1].equalsIgnoreCase("set") || args[1].equalsIgnoreCase("remove")))
            return partial(args[2], args[1].equalsIgnoreCase("remove")
                    ? runtime.rotations().active().modifierIds() : enabledModifiers(runtime));
        if (args.length == 4 && args[0].equalsIgnoreCase("kit") && args[1].equalsIgnoreCase("set")
                && allowed(sender, "warzonerotator.manage.kit"))
            return partial(args[3], DURATIONS);
        if (args.length == 4 && args[0].equalsIgnoreCase("modifier")
                && allowed(sender, "warzonerotator.manage.modifier")
                && (args[1].equalsIgnoreCase("set") || args[1].equalsIgnoreCase("remove")))
            return partial(args[3], DURATIONS);
        if (args.length == 3 && args[0].equalsIgnoreCase("modifier") && args[1].equalsIgnoreCase("clear")
                && allowed(sender, "warzonerotator.manage.modifier"))
            return partial(args[2], DURATIONS);
        if (args.length == 3 && args[0].equalsIgnoreCase("set")
                && allowed(sender, "warzonerotator.manage.modifier"))
            return partial(args[2], DURATIONS);
        return List.of();
    }

    private boolean rootAllowed(CommandSender sender, String root) {
        return switch (root) {
            case "info", "help" -> allowed(sender, "warzonerotator.command.info");
            case "modifiers", "items" -> allowed(sender, "warzonerotator.command.modifiers");
            case "modifier" -> allowed(sender, "warzonerotator.command.modifiers")
                    || allowed(sender, "warzonerotator.manage.modifier");
            case "kit", "kits" -> allowed(sender, "warzonerotator.command.kits")
                    || allowed(sender, "warzonerotator.manage.kit");
            case "next" -> allowed(sender, "warzonerotator.command.next");
            case "schedule" -> allowed(sender, "warzonerotator.command.schedule")
                    || allowed(sender, "warzonerotator.manage.schedule");
            case "menu" -> allowed(sender, "warzonerotator.command.menu");
            case "random", "force", "skip" -> allowed(sender, "warzonerotator.manage.random");
            case "override" -> allowed(sender, "warzonerotator.command.info")
                    || allowed(sender, "warzonerotator.manage.override");
            case "set" -> allowed(sender, "warzonerotator.manage.modifier");
            case "extend" -> allowed(sender, "warzonerotator.command.extend");
            case "reload" -> allowed(sender, "warzonerotator.command.reload");
            case "validate" -> allowed(sender, "warzonerotator.command.validate");
            case "debug" -> allowed(sender, "warzonerotator.command.debug");
            default -> false;
        };
    }


    private List<String> modifierOperations(CommandSender sender) {
        List<String> operations = new ArrayList<>();
        if (allowed(sender, "warzonerotator.command.modifiers")) operations.add("list");
        if (allowed(sender, "warzonerotator.manage.modifier"))
            operations.addAll(List.of("set", "remove", "clear"));
        return operations;
    }

    private List<String> kitOperations(CommandSender sender) {
        List<String> operations = new ArrayList<>();
        if (allowed(sender, "warzonerotator.command.kits")) operations.add("list");
        if (allowed(sender, "warzonerotator.manage.kit")) operations.addAll(List.of("set", "remove"));
        return operations;
    }

    private List<String> overrideOperations(CommandSender sender) {
        List<String> operations = new ArrayList<>();
        if (allowed(sender, "warzonerotator.command.info")) operations.add("status");
        if (allowed(sender, "warzonerotator.manage.override")) operations.add("clear");
        return operations;
    }

    private List<String> scheduleOperations(CommandSender sender) {
        List<String> operations = new ArrayList<>();
        if (allowed(sender, "warzonerotator.command.schedule")) operations.add("view");
        if (allowed(sender, "warzonerotator.manage.schedule"))
            operations.addAll(List.of("enable", "disable", "preview", "advance"));
        return operations;
    }

    private List<String> enabledModifiers(WarzoneRuntime runtime) {
        return runtime.config().modifiers().values().stream().filter(WarzoneConfig.Modifier::enabled)
                .map(WarzoneConfig.Modifier::id).sorted().toList();
    }
    private List<String> enabledKits(WarzoneRuntime runtime) {
        return runtime.controlConfig().kits().values().stream().filter(WarzoneControlConfig.Kit::enabled)
                .map(WarzoneControlConfig.Kit::id).sorted().toList();
    }
    private List<String> partial(String input, List<String> candidates) {
        return StringUtil.copyPartialMatches(input, candidates, new ArrayList<>()).stream().sorted().toList();
    }

    private String effectiveEnd(WarzoneRuntime runtime) {
        var state = runtime.rotations().state();
        if (state.overrideActive() && state.overrideDurationMode() == OverrideDurationMode.UNTIL_CLEARED)
            return "manually cleared";
        return runtime.messages().formatInstant(runtime.rotations().nextEffectiveTransitionMillis());
    }
    private String overrideEnd(WarzoneRuntime runtime) {
        var state = runtime.rotations().state();
        return state.overrideExpiresAtMillis() == 0 ? "; until manually cleared"
                : "; ends " + runtime.messages().formatInstant(state.overrideExpiresAtMillis());
    }
    private String conflictGroup(WarzoneConfig config, String modifierId) {
        String groups = config.conflictGroups().entrySet().stream()
                .filter(entry -> entry.getValue().contains(modifierId)).map(Map.Entry::getKey).sorted()
                .collect(java.util.stream.Collectors.joining(", "));
        return groups.isBlank() ? "none" : groups;
    }

    private static List<String> splitIds(String raw) {
        return new ArrayList<>(new LinkedHashSet<>(java.util.Arrays.stream(raw.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).toList()));
    }
    private static String names(List<String> ids) { return ids.isEmpty() ? "None" : String.join(", ", ids); }
    private static String sourceId(String id) { return id == null || id.isBlank() ? "" : " (" + id + ")"; }
    private void success(CommandSender sender, String message) { module.send(sender, "<green>" + message); }
    private void error(CommandSender sender, String message) { module.send(sender, "<red>" + message); }
    private void usage(CommandSender sender, String message) { module.send(sender, "<yellow>" + message); }
    private static RestrictionTarget target(String id) { return RestrictionTarget.parse(id).orElseThrow(); }
}
