package com.lincoln.maceguard.warzone.gui;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.config.WarzoneControlConfig;
import com.lincoln.maceguard.warzone.message.WarzoneMessageService;
import com.lincoln.maceguard.warzone.restriction.RestrictionMode;
import com.lincoln.maceguard.warzone.restriction.RestrictionTarget;
import com.lincoln.maceguard.warzone.rotation.ActiveSelection;
import com.lincoln.maceguard.warzone.rotation.OverrideDurationMode;
import com.lincoln.maceguard.warzone.rotation.SelectionSourceType;
import com.lincoln.maceguard.warzone.runtime.WarzoneRuntime;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Player-facing Warzone inventory UI.
 *
 * <p>The UI intentionally favors human-readable gameplay information over configuration IDs.
 * Administrative actions retain the same short-lived session, permission, and stale-state guards
 * as the original GUI.</p>
 */
public final class WarzoneGuiManager implements Listener {
    private static final int PAGE_SIZE = 45;
    private static final String NAME_STYLE = "<!italic>";
    private static final String LORE_STYLE = "<!italic><gray>";
    private static final String GOOD = "<green>";
    private static final String BAD = "<red>";
    private static final String SECTION = "<dark_gray>────────────";
    private static final int CURRENT_MODIFIER_PREVIEW_LIMIT = 9;

    private final JavaPlugin plugin;
    private final WarzoneRuntime runtime;
    private final MiniMessage mini = MiniMessage.miniMessage();
    private final Map<UUID, Session> sessions = new HashMap<>();

    public WarzoneGuiManager(JavaPlugin plugin, WarzoneRuntime runtime) {
        this.plugin = plugin;
        this.runtime = runtime;
    }

    public void openMain(Player player) {
        Session session = start(player, Operation.MENU);
        Inventory inventory = inventory(session, Screen.MAIN, 45, "<gold><bold>Warzone");

        inventory.setItem(4, warzoneStatusItem());
        inventory.setItem(11, tagged(Material.NETHER_STAR, "main", "current",
                "<aqua><bold>Current Warzone",
                currentSummaryLore()));
        inventory.setItem(13, tagged(Material.COMPASS, "main", "next",
                "<yellow><bold>Next Scheduled",
                nextSummaryLore()));
        inventory.setItem(15, tagged(Material.CLOCK, "main", "schedule",
                "<gold>Rotation Schedule",
                "<gray>See the full cycle and when each",
                "<gray>selection is scheduled to appear.",
                "<yellow>Click to view schedule"));

        inventory.setItem(29, tagged(Material.CHEST, "main", "kits",
                "<green>Warzone Kits",
                "<gray>Browse the preset rule combinations",
                "<gray>used by the rotation.",
                "<yellow>Click to browse kits"));
        inventory.setItem(31, tagged(Material.BOOK, "main", "modifiers",
                "<light_purple>All Modifiers",
                "<gray>See every modifier and exactly",
                "<gray>what each one changes.",
                "<yellow>Click to browse modifiers"));
        inventory.setItem(33, tagged(Material.SHIELD, "main", "rules",
                "<aqua>Current Rules",
                "<gray>Quick view of item and ability",
                "<gray>rules active right now.",
                "<yellow>Click to view current rules"));

        if (player.hasPermission("warzonerotator.manage.kit")) {
            inventory.setItem(38, tagged(Material.COMMAND_BLOCK, "main", "kit-set",
                    "<green>Set Kit Override",
                    "<gray>Temporarily replace the rotation",
                    "<gray>with a configured kit.",
                    "<yellow>Click to choose a kit"));
        }
        if (player.hasPermission("warzonerotator.manage.modifier")) {
            inventory.setItem(40, tagged(Material.LIME_DYE, "main", "modifier-add",
                    "<green>Add Modifier",
                    "<gray>Add a modifier to the current",
                    "<gray>selection as an override.",
                    "<yellow>Click to choose a modifier"));
            inventory.setItem(42, tagged(Material.RED_DYE, "main", "modifier-remove",
                    "<red>Remove Modifier",
                    "<gray>Remove an active modifier using",
                    "<gray>a temporary override.",
                    "<yellow>Click to choose a modifier"));
        }

        open(player, session, inventory);
    }

    private ItemStack warzoneStatusItem() {
        boolean scope = runtime.gameplayScopeActive();
        List<String> lore = new ArrayList<>();
        if (scope) {
            lore.add("<green>Warzone rules are active.");
        } else {
            lore.add("<red>Warzone gameplay rules are inactive.");
        }
        lore.add("<gray>Current: <white>" + selectionName(runtime.rotations().activeSelection()));
        if (runtime.rotations().state().overrideActive()) {
            lore.add("<yellow>Manual override active");
        } else if (runtime.rotations().scheduleEnabled()) {
            lore.add("<green>Automatic rotation active");
        } else {
            lore.add("<red>Automatic rotation paused");
        }
        return item(scope ? Material.RECOVERY_COMPASS : Material.BARRIER,
                scope ? "<green><bold>Warzone Active" : "<red><bold>Warzone Inactive",
                lore.toArray(String[]::new));
    }

    private String[] currentSummaryLore() {
        ActiveSelection active = runtime.rotations().activeSelection();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Selection: <white>" + selectionName(active));
        appendModifierLines(lore, active.activeSet().modifierIds(), CURRENT_MODIFIER_PREVIEW_LIMIT);
        lore.add(SECTION);
        addEffectiveTiming(lore);
        lore.add("<aqua>Click for current details");
        return lore.toArray(String[]::new);
    }

    private String[] nextSummaryLore() {
        List<String> lore = new ArrayList<>();
        if (!runtime.rotations().scheduleEnabled()) {
            lore.add("<red>The automatic schedule is paused.");
            lore.add("<gray>No scheduled transition will run");
            lore.add("<gray>until it is enabled again.");
            lore.add("<gold>Click to view the configured cycle");
            return lore.toArray(String[]::new);
        }

        WarzoneControlConfig.Entry entry = runtime.rotations().nextSlot().entry();
        lore.add("<gray>Selection: <white>" + entryDisplayName(entry));
        appendEntryModifierPreview(lore, entry, 4);
        lore.add(SECTION);
        lore.add("<gray>Scheduled: <white>" + runtime.messages().formatInstant(
                runtime.rotations().state().automaticSlotEndMillis()));
        if (runtime.rotations().state().overrideActive()
                && runtime.rotations().state().overrideDurationMode() == OverrideDurationMode.UNTIL_CLEARED) {
            lore.add("<yellow>Manual override will remain active.");
        }
        lore.add("<aqua>Click for schedule details");
        return lore.toArray(String[]::new);
    }

    private void openCurrent(Player player, Session session) {
        session.operation = Operation.MENU;
        Inventory inventory = inventory(session, Screen.CURRENT, 54, "<aqua><bold>Current Warzone");
        ActiveSelection active = runtime.rotations().activeSelection();

        List<String> selectionLore = new ArrayList<>();
        selectionLore.add("<gray>Selection: <white>" + selectionName(active));
        if (active.sourceType() == SelectionSourceType.KIT) {
            WarzoneControlConfig.Kit kit = runtime.controlConfig().kits().get(active.sourceId());
            if (kit != null && kit.description() != null && !kit.description().isBlank()) {
                selectionLore.add("<gray>" + kit.description());
            }
        }
        selectionLore.add(SECTION);
        selectionLore.add("<gray>Modifiers: <white>" + active.activeSet().modifierIds().size());
        inventory.setItem(10, item(Material.NETHER_STAR, "<aqua>Active Selection",
                selectionLore.toArray(String[]::new)));

        List<String> timingLore = new ArrayList<>();
        addEffectiveTiming(timingLore);
        if (runtime.rotations().scheduleEnabled()) {
            timingLore.add("<gray>Scheduled boundary: <white>" + runtime.messages().formatInstant(
                    runtime.rotations().state().automaticSlotEndMillis()));
        }
        inventory.setItem(12, item(Material.CLOCK, "<gold>Timing", timingLore.toArray(String[]::new)));

        inventory.setItem(14, overrideStatusItem());
        inventory.setItem(16, nextSelectionItem());

        List<String> ids = active.activeSet().modifierIds();
        int shown = Math.min(ids.size(), CURRENT_MODIFIER_PREVIEW_LIMIT);
        for (int index = 0; index < shown; index++) {
            WarzoneConfig.Modifier modifier = runtime.config().modifiers().get(ids.get(index));
            inventory.setItem(27 + index, modifierSummaryItem(modifier, ids.get(index), true, false));
        }
        if (ids.isEmpty()) {
            inventory.setItem(31, item(Material.GRAY_DYE, "<gray>No Active Modifiers",
                    "<gray>This selection applies no optional modifiers."));
        } else if (ids.size() > shown) {
            inventory.setItem(36, item(Material.PAPER, "<yellow>More Active Modifiers",
                    "<gray>" + (ids.size() - shown) + " more modifiers are active.",
                    "<gray>Use All Modifiers to view everything."));
        }

        inventory.setItem(45, tagged(Material.ARROW, "back-main", "",
                "<yellow>Back to Warzone"));
        inventory.setItem(49, tagged(Material.BOOK, "current-browse-modifiers", "",
                "<light_purple>Browse All Modifiers",
                "<gray>See active and inactive modifiers."));
        open(player, session, inventory);
    }

    private ItemStack overrideStatusItem() {
        var state = runtime.rotations().state();
        if (!state.overrideActive()) {
            return item(Material.GRAY_DYE, "<gray>No Manual Override",
                    "<gray>The automatic rotation controls",
                    "<gray>the current Warzone selection.");
        }

        List<String> lore = new ArrayList<>();
        lore.add("<yellow>A manual override is replacing");
        lore.add("<yellow>the automatic selection.");
        lore.add("<gray>Type: <white>" + sourceTypeName(state.overrideSourceType()));
        if (state.overrideSourceType() == SelectionSourceType.KIT) {
            lore.add("<gray>Kit: <white>" + kitName(state.overrideSourceId()));
        }
        lore.add("<gray>Duration: <white>" + expiration());
        ActiveSelection automatic = runtime.rotations().automaticSelection();
        lore.add(SECTION);
        lore.add("<gray>Automatic underneath:");
        lore.add("<white>" + selectionName(automatic));
        return item(Material.LEVER, "<yellow>Manual Override", lore.toArray(String[]::new));
    }

    private ItemStack nextSelectionItem() {
        if (!runtime.rotations().scheduleEnabled()) {
            return item(Material.GRAY_DYE, "<red>Schedule Paused",
                    "<gray>No automatic transition is currently scheduled.");
        }
        WarzoneControlConfig.Entry entry = runtime.rotations().nextSlot().entry();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Selection: <white>" + entryDisplayName(entry));
        appendEntryModifierPreview(lore, entry, 4);
        lore.add(SECTION);
        lore.add("<gray>Starts: <white>" + runtime.messages().formatInstant(
                runtime.rotations().state().automaticSlotEndMillis()));
        return item(Material.COMPASS, "<yellow>Next Scheduled", lore.toArray(String[]::new));
    }

    public void openKits(Player player, boolean administrative) {
        Session session = start(player, administrative ? Operation.KIT_SET : Operation.KIT_LIST);
        openKits(player, session, 0);
    }

    private void openKits(Player player, Session session, int page) {
        session.page = page;
        List<WarzoneControlConfig.Kit> kits = runtime.controlConfig().kits().values().stream()
                .filter(kit -> kit.enabled() || runtime.controlConfig().gui().showDisabledKits())
                .sorted(Comparator.comparing(kit -> plainName(kit.displayName())))
                .toList();

        Inventory inventory = inventory(session, Screen.KITS, 54,
                session.operation == Operation.KIT_SET
                        ? "<green><bold>Choose Kit Override"
                        : "<green><bold>Warzone Kits");
        int from = page * PAGE_SIZE;
        for (int index = from; index < Math.min(kits.size(), from + PAGE_SIZE); index++) {
            WarzoneControlConfig.Kit kit = kits.get(index);
            boolean active = runtime.rotations().activeSelection().sourceType() == SelectionSourceType.KIT
                    && kit.id().equals(runtime.rotations().activeSelection().sourceId());
            boolean next = isNextKit(kit.id());
            boolean actionable = session.operation == Operation.KIT_SET && kit.enabled();

            List<String> lore = new ArrayList<>();
            lore.add(kit.description());
            if (active) lore.add("<green><bold>ACTIVE NOW");
            else if (next) lore.add("<aqua><bold>UP NEXT");
            else if (!kit.enabled()) lore.add("<red>Disabled");
            lore.add(SECTION);
            lore.add("<gold>Includes");
            appendModifierLines(lore, kit.modifierIds(), 5);
            lore.add(actionable
                    ? "<yellow>Click to preview this override"
                    : "<aqua>Click for kit details");

            inventory.setItem(index - from, tagged(
                    kit.enabled() ? kit.icon() : Material.GRAY_DYE,
                    actionable ? "kit" : "kit-detail",
                    kit.id(),
                    kit.displayName(),
                    lore.toArray(String[]::new)));
        }

        navigation(inventory, page, kits.size());
        inventory.setItem(49, tagged(Material.ARROW, "back-main", "",
                "<yellow>Back to Warzone"));
        open(player, session, inventory);
    }

    private void openKitDetail(Player player, Session session, String kitId) {
        WarzoneControlConfig.Kit kit = runtime.controlConfig().kits().get(kitId);
        if (kit == null) throw new IllegalArgumentException("That kit no longer exists.");

        boolean active = runtime.rotations().activeSelection().sourceType() == SelectionSourceType.KIT
                && kit.id().equals(runtime.rotations().activeSelection().sourceId());
        boolean next = isNextKit(kit.id());

        Inventory inventory = inventory(session, Screen.KITS, 27, "<green><bold>Kit Details");

        inventory.setItem(11, item(kit.enabled() ? kit.icon() : Material.GRAY_DYE,
                kit.displayName(),
                kit.description(),
                kit.enabled() ? "<green>Available in rotation" : "<red>Disabled in configuration"));

        List<String> modifierLore = new ArrayList<>();
        if (kit.modifierIds().isEmpty()) {
            modifierLore.add("<gray>This kit has no modifiers.");
        } else {
            appendModifierLines(modifierLore, kit.modifierIds(), Integer.MAX_VALUE);
        }
        inventory.setItem(13, item(Material.BOOK, "<gold>Included Modifiers",
                modifierLore.toArray(String[]::new)));

        List<String> statusLore = new ArrayList<>();
        statusLore.add(active ? "<green>Active now" : "<gray>Not active now");
        statusLore.add(next ? "<aqua>Scheduled next" : "<gray>Not the next scheduled kit");
        inventory.setItem(15, item(active ? Material.LIME_DYE : Material.COMPASS,
                "<aqua>Rotation Status", statusLore.toArray(String[]::new)));

        inventory.setItem(22, tagged(Material.ARROW, "back-kits", Integer.toString(session.page),
                "<yellow>Back to Kits"));
        open(player, session, inventory);
    }

    public void openModifiers(Player player, Operation operation) {
        Session session = start(player, operation);
        openModifiers(player, session, 0);
    }

    private void openModifiers(Player player, Session session, int page) {
        session.page = page;
        List<WarzoneConfig.Modifier> modifiers = runtime.config().modifiers().values().stream()
                .sorted(Comparator.comparing(modifier -> plainName(modifier.displayName())))
                .toList();

        Inventory inventory = inventory(session, Screen.MODIFIERS, 54,
                switch (session.operation) {
                    case MODIFIER_ADD -> "<green><bold>Add Warzone Modifier";
                    case MODIFIER_REMOVE -> "<red><bold>Remove Warzone Modifier";
                    default -> "<light_purple><bold>Warzone Modifiers";
                });

        int from = page * PAGE_SIZE;
        Set<String> active = Set.copyOf(runtime.rotations().active().modifierIds());
        for (int index = from; index < Math.min(modifiers.size(), from + PAGE_SIZE); index++) {
            WarzoneConfig.Modifier modifier = modifiers.get(index);
            boolean selected = active.contains(modifier.id());
            boolean actionable = modifier.enabled()
                    && (session.operation == Operation.MODIFIER_ADD && !selected
                    || session.operation == Operation.MODIFIER_REMOVE && selected);
            Material icon = modifier.enabled()
                    ? (selected ? Material.LIME_DYE : Material.PAPER)
                    : Material.GRAY_DYE;

            List<String> lore = modifierSummaryLore(modifier, selected);
            lore.add(actionable
                    ? "<yellow>Click to preview this change"
                    : "<aqua>Click for modifier details");

            inventory.setItem(index - from, tagged(icon,
                    actionable ? "modifier" : "modifier-detail",
                    modifier.id(),
                    modifier.displayName(),
                    lore.toArray(String[]::new)));
        }

        navigation(inventory, page, modifiers.size());
        inventory.setItem(49, tagged(Material.ARROW, "back-main", "",
                "<yellow>Back to Warzone"));
        open(player, session, inventory);
    }

    private List<String> modifierSummaryLore(WarzoneConfig.Modifier modifier, boolean active) {
        List<String> lore = new ArrayList<>();
        lore.add(modifier.description());
        lore.add(active ? "<green><bold>ACTIVE NOW" : "<dark_gray>Not active");
        if (!modifier.enabled()) lore.add("<red>Disabled in configuration");
        lore.add(SECTION);
        lore.add("<gold>Gameplay");
        appendGameplayLines(lore, modifier, 3);
        return lore;
    }

    private ItemStack modifierSummaryItem(WarzoneConfig.Modifier modifier, String fallbackId,
                                          boolean active, boolean clickable) {
        String name = modifier == null ? humanizeIdentifier(fallbackId) : modifier.displayName();
        List<String> lore = new ArrayList<>();
        if (modifier == null) {
            lore.add("<red>This modifier is missing from configuration.");
        } else {
            lore.add(modifier.description());
            lore.add(SECTION);
            appendGameplayLines(lore, modifier, 3);
        }
        if (active) lore.add("<green>Active now");
        if (clickable) lore.add("<aqua>Click for details");
        Material material = modifier == null ? Material.BARRIER : active ? Material.LIME_DYE : Material.PAPER;
        return item(material, name, lore.toArray(String[]::new));
    }

    private void openModifierDetail(Player player, Session session, String modifierId) {
        WarzoneConfig.Modifier modifier = runtime.config().modifiers().get(modifierId);
        if (modifier == null) {
            throw new IllegalArgumentException("That modifier no longer exists.");
        }

        boolean active = runtime.rotations().active().modifierIds().contains(modifier.id());
        Inventory inventory = inventory(session, Screen.MODIFIERS, 27, "<light_purple><bold>Modifier Details");

        List<String> overview = new ArrayList<>();
        overview.add(modifier.description());
        overview.add(active ? "<green>Active now" : "<gray>Not active now");
        overview.add(modifier.enabled()
                ? "<green>Available to the rotation"
                : "<red>Disabled in configuration");
        inventory.setItem(11, item(active ? Material.LIME_DYE : Material.PAPER,
                modifier.displayName(), overview.toArray(String[]::new)));

        List<String> rules = new ArrayList<>();
        appendGameplayLines(rules, modifier, Integer.MAX_VALUE);
        inventory.setItem(13, item(Material.SHIELD, "<gold>Gameplay Rules",
                rules.toArray(String[]::new)));

        List<String> behavior = new ArrayList<>();
        behavior.add("<gray>Combat carryover: "
                + (modifier.combatCarryover() ? "<green>Yes" : "<gray>No"));
        if (modifier.combatCarryover()) {
            behavior.add("<dark_gray>Can remain effective during an");
            behavior.add("<dark_gray>existing fight after leaving the zone.");
        }
        List<String> conflicts = conflictingModifierNames(modifier.id());
        if (!conflicts.isEmpty()) {
            behavior.add(SECTION);
            behavior.add("<gold>Cannot combine with");
            conflicts.forEach(name -> behavior.add("<dark_gray>• <white>" + name));
        } else {
            behavior.add("<gray>No configured conflicts.");
        }
        inventory.setItem(15, item(Material.REPEATER, "<aqua>Rotation Behavior",
                behavior.toArray(String[]::new)));

        inventory.setItem(22, tagged(Material.ARROW, "back-modifiers", Integer.toString(session.page),
                "<yellow>Back to Modifiers"));
        open(player, session, inventory);
    }

    public void openSchedule(Player player) {
        Session session = start(player, Operation.SCHEDULE);
        openSchedule(player, session, 0);
    }

    private void openSchedule(Player player, Session session, int page) {
        session.operation = Operation.SCHEDULE;
        session.page = page;
        Inventory inventory = inventory(session, Screen.SCHEDULE, 54, "<gold><bold>Warzone Schedule");
        List<WarzoneControlConfig.Entry> cycle = runtime.controlConfig().schedule().cycle();
        int current = runtime.rotations().state().currentCycleIndex();
        int next = runtime.rotations().scheduleEnabled() ? runtime.rotations().nextSlot().cycleIndex() : -1;
        List<Integer> order = scheduleOrder(cycle.size(), current);
        int from = page * PAGE_SIZE;

        for (int displayIndex = from; displayIndex < Math.min(order.size(), from + PAGE_SIZE); displayIndex++) {
            int index = order.get(displayIndex);
            WarzoneControlConfig.Entry entry = cycle.get(index);
            boolean isCurrent = index == current;
            boolean isNext = index == next;
            List<String> lore = scheduleEntryLore(index, entry, current, next, false);
            String prefix = isCurrent ? "<green><bold>NOW <dark_gray>• "
                    : isNext ? "<aqua><bold>NEXT <dark_gray>• " : "<gold>";
            inventory.setItem(displayIndex - from, tagged(
                    isCurrent ? Material.LIME_DYE : isNext ? Material.COMPASS : Material.CLOCK,
                    "schedule-entry",
                    Integer.toString(index),
                    prefix + entryDisplayName(entry),
                    lore.toArray(String[]::new)));
        }

        navigation(inventory, page, order.size());
        inventory.setItem(48, tagged(Material.ARROW, "back-main", "",
                "<yellow>Back to Warzone"));
        inventory.setItem(49, scheduleOverview());
        open(player, session, inventory);
    }

    private void openScheduleDetail(Player player, Session session, int index) {
        List<WarzoneControlConfig.Entry> cycle = runtime.controlConfig().schedule().cycle();
        if (index < 0 || index >= cycle.size()) {
            throw new IllegalArgumentException("That schedule entry no longer exists.");
        }

        int current = runtime.rotations().state().currentCycleIndex();
        int next = runtime.rotations().scheduleEnabled() ? runtime.rotations().nextSlot().cycleIndex() : -1;
        WarzoneControlConfig.Entry entry = cycle.get(index);
        List<String> lore = scheduleEntryLore(index, entry, current, next, true);

        Inventory inventory = inventory(session, Screen.SCHEDULE, 27,
                "<gold><bold>Scheduled Selection");
        inventory.setItem(11, scheduleTimingItem());
        inventory.setItem(13, item(
                index == current ? Material.LIME_DYE : index == next ? Material.COMPASS : Material.CLOCK,
                entryDisplayName(entry), lore.toArray(String[]::new)));
        inventory.setItem(15, currentSelectionItem());
        inventory.setItem(22, tagged(Material.ARROW, "back-schedule", Integer.toString(session.page),
                "<yellow>Back to Schedule"));
        open(player, session, inventory);
    }

    private ItemStack scheduleOverview() {
        WarzoneControlConfig.Schedule schedule = runtime.controlConfig().schedule();
        List<String> lore = new ArrayList<>();
        lore.add(runtime.rotations().scheduleEnabled()
                ? "<green>Automatic rotation enabled"
                : "<red>Automatic rotation paused");
        lore.add("<gray>Changes: <white>" + cadenceName(schedule));
        lore.add("<gray>At: <white>" + schedule.time() + " <dark_gray>(" + zoneName(schedule) + ")");
        lore.add("<gray>Selections in cycle: <white>" + schedule.cycle().size());
        if (runtime.rotations().scheduleEnabled()) {
            lore.add(SECTION);
            lore.add("<gray>Next change:");
            lore.add("<white>" + runtime.messages().formatInstant(
                    runtime.rotations().state().automaticSlotEndMillis()));
        }
        return item(runtime.rotations().scheduleEnabled() ? Material.COMPASS : Material.GRAY_DYE,
                runtime.rotations().scheduleEnabled()
                        ? "<aqua>Schedule Overview"
                        : "<red>Schedule Paused",
                lore.toArray(String[]::new));
    }

    private ItemStack scheduleTimingItem() {
        WarzoneControlConfig.Schedule schedule = runtime.controlConfig().schedule();
        List<String> lore = new ArrayList<>();
        lore.add(runtime.rotations().scheduleEnabled()
                ? "<green>Automatic rotation enabled"
                : "<red>Automatic rotation paused");
        lore.add("<gray>Cadence: <white>" + cadenceName(schedule));
        lore.add("<gray>Time: <white>" + schedule.time() + " <dark_gray>(" + zoneName(schedule) + ")");
        if (runtime.rotations().scheduleEnabled()) {
            lore.add("<gray>Current slot ends:");
            lore.add("<white>" + runtime.messages().formatInstant(
                    runtime.rotations().state().automaticSlotEndMillis()));
        }
        return item(Material.CLOCK, "<aqua>Schedule Timing", lore.toArray(String[]::new));
    }

    private ItemStack currentSelectionItem() {
        ActiveSelection active = runtime.rotations().activeSelection();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Selection: <white>" + selectionName(active));
        appendModifierLines(lore, active.activeSet().modifierIds(), 5);
        return item(Material.NETHER_STAR, "<aqua>Current Warzone", lore.toArray(String[]::new));
    }

    private List<String> scheduleEntryLore(int index, WarzoneControlConfig.Entry entry,
                                           int current, int next, boolean detailed) {
        List<String> lore = new ArrayList<>();
        if (index == current) {
            lore.add("<green><bold>Current automatic slot");
            if (runtime.rotations().state().overrideActive()) {
                lore.add("<yellow>A manual override is currently");
                lore.add("<yellow>replacing this selection.");
            }
        } else if (index == next) {
            lore.add("<aqua><bold>Next scheduled slot");
        } else {
            lore.add("<gray>Later in the rotation");
        }

        switch (entry.type()) {
            case KIT -> {
                WarzoneControlConfig.Kit kit = runtime.controlConfig().kits().get(entry.kitId());
                if (kit == null) {
                    lore.add("<red>Configured kit is unavailable.");
                } else {
                    lore.add("<gray>" + kit.description());
                    lore.add(SECTION);
                    lore.add("<gold>Modifiers");
                    appendModifierLines(lore, kit.modifierIds(), detailed ? Integer.MAX_VALUE : 4);
                }
            }
            case MODIFIERS -> {
                lore.add("<gray>A fixed set of modifiers.");
                lore.add(SECTION);
                lore.add("<gold>Modifiers");
                appendModifierLines(lore, entry.modifierIds(), detailed ? Integer.MAX_VALUE : 4);
            }
            case RANDOM -> {
                WarzoneConfig.Selection selection = runtime.config().selection();
                lore.add("<gray>Chooses a fresh weighted combination.");
                lore.add(SECTION);
                lore.add("<gray>Modifier count: <white>" + selection.minimum()
                        + (selection.minimum() == selection.maximum()
                        ? "" : "–" + selection.maximum()));
                lore.add("<gray>Avoid identical repeat: "
                        + (selection.preventIdenticalRepeat() ? "<green>Yes" : "<gray>No"));
                if (detailed) {
                    lore.add("<gray>Only enabled, compatible modifiers");
                    lore.add("<gray>can be selected.");
                }
            }
            case NONE -> lore.add("<gray>No optional modifiers are applied.");
        }

        if (!detailed) lore.add("<aqua>Click for full details");
        return lore;
    }

    private List<Integer> scheduleOrder(int size, int current) {
        if (size <= 0) return List.of();
        int start = current >= 0 && current < size ? current : 0;
        List<Integer> order = new ArrayList<>(size);
        for (int offset = 0; offset < size; offset++) {
            order.add((start + offset) % size);
        }
        return order;
    }

    private void openRules(Player player, Session session) {
        session.operation = Operation.MENU;
        Inventory inventory = inventory(session, Screen.RULES, 54, "<aqua><bold>Current Warzone Rules");
        WarzoneConfig.ActiveSet active = runtime.rotations().active();
        boolean scope = runtime.gameplayScopeActive();

        inventory.setItem(4, item(scope ? Material.SHIELD : Material.BARRIER,
                scope ? "<green><bold>Rules Active" : "<red><bold>Rules Inactive",
                scope
                        ? "<gray>These are the effective rules inside the Warzone."
                        : "<gray>The Warzone scope is not currently active.",
                "<gray>Selection: <white>" + selectionName(runtime.rotations().activeSelection())));

        List<RestrictionTarget> targets = runtime.config().targetPolicies().keySet().stream()
                .sorted(Comparator.comparing(WarzoneMessageService::friendly))
                .toList();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25,
                28, 29, 30, 31, 32, 33, 34};
        for (int index = 0; index < Math.min(targets.size(), slots.length); index++) {
            RestrictionTarget target = targets.get(index);
            inventory.setItem(slots[index], ruleItem(target, active, scope));
        }
        if (targets.size() > slots.length) {
            inventory.setItem(43, item(Material.PAPER, "<yellow>Additional Rules Configured",
                    "<gray>Not shown on this page: <white>" + (targets.size() - slots.length),
                    "<yellow>Some configured rules do not fit here."));
        }


        inventory.setItem(38, item(Material.COBWEB, "<white>Cobwebs",
                !scope ? "<dark_gray>Inactive"
                        : active.cobwebsAllowed() ? "<green>Allowed" : "<red>Disabled",
                "<gray>Temporary Warzone cobweb placement"));
        List<String> elytraLore = new ArrayList<>();
        elytraLore.add(!scope ? "<dark_gray>Inactive"
                : active.elytraGlidingAllowed() ? "<green>Gliding allowed" : "<red>Disabled");
        if (scope && active.elytraGlidingAllowed()) {
            elytraLore.add("<red>Firework boosting disabled");
        }
        inventory.setItem(40, item(Material.ELYTRA, "<aqua>Elytra",
                elytraLore.toArray(String[]::new)));
        inventory.setItem(42, nextSelectionItem());

        inventory.setItem(49, tagged(Material.ARROW, "back-main", "",
                "<yellow>Back to Warzone"));
        open(player, session, inventory);
    }

    private ItemStack ruleItem(RestrictionTarget target, WarzoneConfig.ActiveSet active, boolean scope) {
        String name = WarzoneMessageService.friendly(target);
        Material material = ruleIcon(target);
        List<String> lore = new ArrayList<>();
        lore.add(currentRule(target, active, scope));
        WarzoneConfig.TargetPolicy policy = runtime.config().targetPolicies().get(target);
        if (policy != null) {
            lore.add(SECTION);
            if (policy.canDisable()) lore.add("<gray>Can rotate to: <red>Disabled");
            if (policy.canCooldown()) lore.add("<gray>Can rotate to: <yellow>Cooldown");
        }
        return item(material, "<white>" + name, lore.toArray(String[]::new));
    }

    private String currentRule(RestrictionTarget target, WarzoneConfig.ActiveSet active, boolean scope) {
        if (!scope) return "<dark_gray>Inactive";

        WarzoneConfig.Restriction restriction = active.restrictions().get(target);
        if ((target == RestrictionTarget.SPEAR_DAMAGE || target == RestrictionTarget.SPEAR_LUNGE)
                && active.restrictions().get(RestrictionTarget.SPEAR) != null
                && active.restrictions().get(RestrictionTarget.SPEAR).mode() == RestrictionMode.DISABLED) {
            return "<red>Disabled <dark_gray>• Spears disabled";
        }
        if (restriction == null) return "<green>Allowed";
        if (restriction.mode() == RestrictionMode.DISABLED) return "<red>Disabled";
        return "<yellow>" + readableDuration(restriction.cooldown()) + " cooldown";
    }

    private Material ruleIcon(RestrictionTarget target) {
        if (target.material() != null) return target.material();
        if (target == RestrictionTarget.SPEAR) return Material.WOODEN_SPEAR;
        if (target == RestrictionTarget.SPEAR_DAMAGE) return Material.IRON_SWORD;
        if (target == RestrictionTarget.SPEAR_LUNGE) return Material.FEATHER;
        return Material.PAPER;
    }

    public void openPreview(Player player, Operation operation, SelectionSourceType proposedType,
                            String proposedId, WarzoneConfig.ActiveSet proposed) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || expired(session)) session = start(player, operation);
        session.operation = operation;
        session.proposedType = proposedType;
        session.proposedId = proposedId;
        session.proposedModifiers = proposed.modifierIds();
        session.proposedSet = proposed;

        Inventory inventory = inventory(session, Screen.PREVIEW, 27, "<gold><bold>Confirm Warzone Change");
        ActiveSelection current = runtime.rotations().activeSelection();

        List<String> currentLore = new ArrayList<>();
        currentLore.add("<gray>Selection: <white>" + selectionName(current));
        appendModifierLines(currentLore, current.activeSet().modifierIds(), Integer.MAX_VALUE);
        inventory.setItem(10, item(Material.RED_STAINED_GLASS_PANE, "<red>Current",
                currentLore.toArray(String[]::new)));

        List<String> proposedLore = new ArrayList<>();
        proposedLore.add("<gray>Selection: <white>" + proposedSelectionName(proposedType, proposedId));
        proposedLore.add(SECTION);
        proposedLore.add("<gold>Modifiers");
        appendModifierLines(proposedLore, proposed.modifierIds(), Integer.MAX_VALUE);

        List<String> added = added(current.activeSet().modifierIds(), proposed.modifierIds());
        List<String> removed = added(proposed.modifierIds(), current.activeSet().modifierIds());
        if (!added.isEmpty() || !removed.isEmpty()) {
            proposedLore.add(SECTION);
            if (!added.isEmpty()) {
                proposedLore.add("<green>Added");
                added.forEach(id -> proposedLore.add("<green>+ <white>" + modifierName(id)));
            }
            if (!removed.isEmpty()) {
                proposedLore.add("<red>Removed");
                removed.forEach(id -> proposedLore.add("<red>− <white>" + modifierName(id)));
            }
        }

        if (current.sourceType() == SelectionSourceType.KIT && operation != Operation.KIT_SET) {
            proposedLore.add(SECTION);
            proposedLore.add("<yellow>This creates a custom override");
            proposedLore.add("<yellow>instead of changing the active kit.");
        }

        inventory.setItem(13, item(Material.WRITABLE_BOOK, "<yellow>Proposed",
                proposedLore.toArray(String[]::new)));
        inventory.setItem(16, tagged(Material.LIME_CONCRETE, "action", "confirm",
                "<green><bold>Continue",
                "<gray>Choose how long this override lasts."));
        inventory.setItem(18, tagged(Material.RED_CONCRETE, "action", "cancel",
                "<red><bold>Cancel",
                "<gray>Discard this change."));
        open(player, session, inventory);
    }

    private void openDuration(Player player, Session session) {
        Inventory inventory = inventory(session, Screen.DURATION, 27, "<gold><bold>Override Duration");

        List<String> pending = new ArrayList<>();
        pending.add("<gray>Selection: <white>" + proposedSelectionName(
                session.proposedType, session.proposedId));
        appendModifierLines(pending, session.proposedModifiers, 5);
        inventory.setItem(4, item(Material.WRITABLE_BOOK, "<aqua>Pending Selection",
                pending.toArray(String[]::new)));

        inventory.setItem(11, tagged(Material.CLOCK, "duration", "1h", "<yellow>One Hour",
                "<gray>Ends one hour after confirmation."));
        if (runtime.rotations().scheduleEnabled()) {
            inventory.setItem(13, tagged(Material.COMPASS, "duration", "next",
                    "<aqua>Until Next Scheduled Change",
                    "<gray>Ends: <white>" + runtime.messages().formatInstant(
                            runtime.rotations().state().automaticSlotEndMillis()),
                    "<gray>Then: <white>" + entryDisplayName(runtime.rotations().nextSlot().entry())));
        } else {
            inventory.setItem(13, item(Material.GRAY_DYE, "<red>Until Next Scheduled Change",
                    "<gray>Unavailable while the automatic",
                    "<gray>schedule is paused."));
        }
        inventory.setItem(15, tagged(Material.LEVER, "duration", "manual",
                "<light_purple>Until Manually Cleared",
                "<gray>Stays active across reloads and restarts",
                "<gray>until a staff member clears it."));
        open(player, session, inventory);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ManagedHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Session session = valid(player, holder);
        if (session == null || event.getClickedInventory() != event.getView().getTopInventory()) return;

        try {
            String type = tag(event.getCurrentItem(), "warzone-type");
            String value = tag(event.getCurrentItem(), "warzone-value");

            if (session.screen == Screen.MAIN) {
                mainClick(player, session, type, value);
                return;
            }
            if (type == null) return;

            switch (session.screen) {
                case MAIN -> throw new IllegalStateException("Unexpected main-menu dispatch.");
                case CURRENT -> currentClick(player, session, type);
                case KITS -> kitClick(player, session, type, value);
                case MODIFIERS -> modifierClick(player, session, type, value);
                case RULES -> rulesClick(player, type);
                case PREVIEW -> previewClick(player, session, type, value);
                case DURATION -> durationClick(player, session, type, value);
                case SCHEDULE -> scheduleClick(player, session, type, value);
            }
        } catch (IllegalArgumentException | IllegalStateException ex) {
            player.sendMessage(gui("<red><bold>Warzone change rejected</bold> <dark_gray>• <gray>"
                    + ex.getMessage()));
            sessions.remove(player.getUniqueId());
            player.closeInventory();
        }
    }

    private void mainClick(Player player, Session session, String type, String value) {
        if (!"main".equals(type)) return;
        switch (value) {
            case "current" -> openCurrent(player, session);
            case "next" -> {
                session.operation = Operation.SCHEDULE;
                if (runtime.rotations().scheduleEnabled()) {
                    openScheduleDetail(player, session, runtime.rotations().nextSlot().cycleIndex());
                } else {
                    openSchedule(player, session, 0);
                }
            }
            case "schedule" -> {
                session.operation = Operation.SCHEDULE;
                openSchedule(player, session, 0);
            }
            case "kits" -> {
                session.operation = Operation.KIT_LIST;
                openKits(player, session, 0);
            }
            case "modifiers" -> {
                session.operation = Operation.MODIFIER_LIST;
                openModifiers(player, session, 0);
            }
            case "rules" -> openRules(player, session);
            case "kit-set" -> {
                if (player.hasPermission("warzonerotator.manage.kit")) {
                    session.operation = Operation.KIT_SET;
                    openKits(player, session, 0);
                }
            }
            case "modifier-add" -> {
                if (player.hasPermission("warzonerotator.manage.modifier")) {
                    session.operation = Operation.MODIFIER_ADD;
                    openModifiers(player, session, 0);
                }
            }
            case "modifier-remove" -> {
                if (player.hasPermission("warzonerotator.manage.modifier")) {
                    session.operation = Operation.MODIFIER_REMOVE;
                    openModifiers(player, session, 0);
                }
            }
            default -> { }
        }
    }

    private void currentClick(Player player, Session session, String type) {
        if ("back-main".equals(type)) {
            openMain(player);
        } else if ("current-browse-modifiers".equals(type)) {
            session.operation = Operation.MODIFIER_LIST;
            openModifiers(player, session, 0);
        }
    }

    private void rulesClick(Player player, String type) {
        if ("back-main".equals(type)) openMain(player);
    }

    private void kitClick(Player player, Session session, String type, String value) {
        if ("back-main".equals(type)) {
            openMain(player);
            return;
        }
        if ("back-kits".equals(type)) {
            openKits(player, session, Integer.parseInt(value));
            return;
        }
        if (navigationClick(player, session, type, value, true)) return;
        if ("kit-detail".equals(type)) {
            openKitDetail(player, session, value);
            return;
        }
        if (!"kit".equals(type) || session.operation != Operation.KIT_SET) return;

        WarzoneConfig.ActiveSet proposed = runtime.rotations().previewKit(value);
        openPreview(player, session.operation, SelectionSourceType.KIT, value, proposed);
    }

    private void modifierClick(Player player, Session session, String type, String value) {
        if ("back-main".equals(type)) {
            openMain(player);
            return;
        }
        if ("back-modifiers".equals(type)) {
            openModifiers(player, session, Integer.parseInt(value));
            return;
        }
        if (navigationClick(player, session, type, value, false)) return;
        if ("modifier-detail".equals(type)) {
            openModifierDetail(player, session, value);
            return;
        }
        if (!"modifier".equals(type)) return;

        boolean custom = player.hasPermission("warzonerotator.admin")
                || player.hasPermission("warzonerotator.manage.custom-combinations");
        if (runtime.rotations().activeSelection().sourceType() == SelectionSourceType.KIT && !custom) {
            throw new IllegalArgumentException("Kit detachment requires custom-combinations permission.");
        }

        WarzoneConfig.ActiveSet proposed = switch (session.operation) {
            case MODIFIER_ADD -> runtime.rotations().previewAdd(value, custom);
            case MODIFIER_REMOVE -> runtime.rotations().previewRemove(value, custom);
            default -> null;
        };
        if (proposed != null) {
            openPreview(player, session.operation, SelectionSourceType.CUSTOM_OVERRIDE, null, proposed);
        }
    }

    private boolean navigationClick(Player player, Session session, String type,
                                    String value, boolean kits) {
        if (!"page".equals(type)) return false;
        int page = Integer.parseInt(value);
        if (kits) openKits(player, session, page);
        else openModifiers(player, session, page);
        return true;
    }

    private void scheduleClick(Player player, Session session, String type, String value) {
        if ("back-main".equals(type)) {
            openMain(player);
            return;
        }
        if ("back-schedule".equals(type)) {
            openSchedule(player, session, Integer.parseInt(value));
            return;
        }
        if ("page".equals(type)) {
            openSchedule(player, session, Integer.parseInt(value));
            return;
        }
        if ("schedule-entry".equals(type)) {
            openScheduleDetail(player, session, Integer.parseInt(value));
        }
    }

    private void previewClick(Player player, Session session, String type, String value) {
        if (!"action".equals(type)) return;
        if ("cancel".equals(value)) {
            sessions.remove(player.getUniqueId());
            player.closeInventory();
        } else if ("confirm".equals(value)) {
            openDuration(player, session);
        }
    }

    private void durationClick(Player player, Session session, String type, String value) {
        if (!"duration".equals(type)) return;
        requireUnchangedOriginal(session);
        requireCurrentPermission(player, session);
        OverrideDurationMode mode = OverrideDurationMode.parse(value).orElseThrow();

        switch (session.operation) {
            case KIT_SET -> runtime.rotations().setKit(session.proposedId, mode, true);
            case MODIFIER_ADD, MODIFIER_REMOVE, MODIFIER_CLEAR, RANDOM ->
                    runtime.rotations().applyPrepared(session.proposedType, session.proposedId,
                            session.proposedModifiers, mode, true);
            default -> throw new IllegalStateException("No pending administrative operation.");
        }

        sessions.remove(player.getUniqueId());
        player.closeInventory();
        player.sendMessage(gui("<green><bold>Warzone override applied</bold> <dark_gray>• <gray>Duration: <white>"
                + friendly(mode)));
    }

    private void requireCurrentPermission(Player player, Session session) {
        String permission = switch (session.operation) {
            case KIT_SET -> "warzonerotator.manage.kit";
            case MODIFIER_ADD, MODIFIER_REMOVE, MODIFIER_CLEAR -> "warzonerotator.manage.modifier";
            case RANDOM -> "warzonerotator.manage.random";
            default -> throw new IllegalStateException("No pending administrative operation.");
        };
        if (!player.hasPermission("warzonerotator.admin") && !player.hasPermission(permission)) {
            throw new IllegalStateException("Your permission to perform this operation was removed.");
        }

        boolean detachesKit = session.originalSource == SelectionSourceType.KIT
                && session.operation != Operation.KIT_SET;
        int count = session.proposedModifiers.size();
        boolean bypassesCount = session.operation != Operation.MODIFIER_CLEAR
                && (count < runtime.config().selection().minimum()
                || count > runtime.config().selection().maximum());
        if ((detachesKit || bypassesCount)
                && !player.hasPermission("warzonerotator.admin")
                && !player.hasPermission("warzonerotator.manage.custom-combinations")) {
            throw new IllegalStateException(
                    "This custom combination requires warzonerotator.manage.custom-combinations.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ManagedHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof ManagedHolder holder)) return;
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null
                && session.id.equals(holder.sessionId)
                && session.screen == holder.screen
                && session.currentViewId.equals(holder.viewId)) {
            sessions.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    public void cleanup() {
        sessions.entrySet().removeIf(entry -> expired(entry.getValue()));
    }

    public void clear() {
        sessions.clear();
    }

    public int sessionCount() {
        return sessions.size();
    }

    private Session valid(Player player, ManagedHolder holder) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null
                || !session.id.equals(holder.sessionId)
                || session.screen != holder.screen
                || !session.currentViewId.equals(holder.viewId)
                || expired(session)) {
            sessions.remove(player.getUniqueId());
            player.closeInventory();
            return null;
        }
        return session;
    }

    Session start(Player player, Operation operation) {
        ActiveSelection current = runtime.rotations().activeSelection();
        Session session = new Session(UUID.randomUUID(), operation, current.sourceType(),
                current.sourceId(), current.activeSet().modifierIds(), System.currentTimeMillis());
        sessions.put(player.getUniqueId(), session);
        return session;
    }

    private boolean expired(Session session) {
        return System.currentTimeMillis() - session.openedAt
                > Duration.ofSeconds(runtime.controlConfig().gui().sessionTimeoutSeconds()).toMillis();
    }

    private Inventory inventory(Session session, Screen screen, int size, String title) {
        session.screen = screen;
        session.currentViewId = UUID.randomUUID();
        ManagedHolder holder = new ManagedHolder(session.id, screen, session.currentViewId);
        Inventory inventory = Bukkit.createInventory(holder, size, gui("<!italic>" + title));
        holder.inventory = inventory;
        return inventory;
    }

    private void open(Player player, Session session, Inventory inventory) {
        player.openInventory(inventory);
    }

    private void navigation(Inventory inventory, int page, int total) {
        if (page > 0) {
            inventory.setItem(45, tagged(Material.ARROW, "page",
                    Integer.toString(page - 1), "<yellow>Previous Page"));
        }
        if ((page + 1) * PAGE_SIZE < total) {
            inventory.setItem(53, tagged(Material.ARROW, "page",
                    Integer.toString(page + 1), "<yellow>Next Page"));
        }
    }

    private ItemStack tagged(Material material, String type, String value,
                             String name, String... lore) {
        ItemStack item = item(material, name, lore);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "warzone-type"),
                PersistentDataType.STRING, type);
        meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "warzone-value"),
                PersistentDataType.STRING, value == null ? "" : value);
        item.setItemMeta(meta);
        return item;
    }

    private String tag(ItemStack item, String key) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(
                new org.bukkit.NamespacedKey(plugin, key),
                PersistentDataType.STRING);
    }

    private ItemStack item(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(gui(NAME_STYLE + name));
        meta.lore(java.util.Arrays.stream(lore)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(text -> !text.isEmpty())
                .map(text -> gui(LORE_STYLE + text))
                .toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private Component gui(String markup) {
        return mini.deserialize(markup);
    }

    void requireUnchangedOriginal(Session session) {
        ActiveSelection current = runtime.rotations().activeSelection();
        if (current.sourceType() != session.originalSource
                || !Objects.equals(current.sourceId(), session.originalSourceId)
                || !current.activeSet().modifierIds().equals(session.originalModifiers)) {
            throw new IllegalStateException(
                    "The active Warzone selection changed while this menu was open. Start again.");
        }
    }

    private void appendModifierLines(List<String> lore, List<String> ids, int limit) {
        if (ids == null || ids.isEmpty()) {
            lore.add("<dark_gray>• <gray>None");
            return;
        }
        int shown = Math.min(ids.size(), limit);
        for (int index = 0; index < shown; index++) {
            lore.add("<dark_gray>• " + modifierName(ids.get(index)));
        }
        if (ids.size() > shown) {
            lore.add("<dark_gray>• <gray>+" + (ids.size() - shown) + " more");
        }
    }

    private void appendEntryModifierPreview(List<String> lore, WarzoneControlConfig.Entry entry, int limit) {
        switch (entry.type()) {
            case KIT -> {
                WarzoneControlConfig.Kit kit = runtime.controlConfig().kits().get(entry.kitId());
                if (kit != null) appendModifierLines(lore, kit.modifierIds(), limit);
            }
            case MODIFIERS -> appendModifierLines(lore, entry.modifierIds(), limit);
            case RANDOM -> {
                WarzoneConfig.Selection selection = runtime.config().selection();
                lore.add("<dark_gray>• <gray>Randomly chooses <white>" + selection.minimum()
                        + (selection.minimum() == selection.maximum()
                        ? "" : "–" + selection.maximum()) + " <gray>modifiers");
            }
            case NONE -> lore.add("<dark_gray>• <gray>No modifiers");
        }
    }

    private void appendGameplayLines(List<String> lore, WarzoneConfig.Modifier modifier, int limit) {
        List<String> lines = new ArrayList<>();
        modifier.restrictions().entrySet().stream()
                .sorted(Comparator.comparing(entry -> WarzoneMessageService.friendly(entry.getKey())))
                .forEach(entry -> {
                    WarzoneConfig.Restriction restriction = entry.getValue();
                    String rule = restriction.mode() == RestrictionMode.DISABLED
                            ? "<red>Disabled"
                            : "<yellow>" + readableDuration(restriction.cooldown()) + " cooldown";
                    lines.add("<dark_gray>• <white>" + WarzoneMessageService.friendly(entry.getKey())
                            + ": " + rule);
                });
        modifier.effects().stream()
                .sorted(Comparator.comparing(Enum::name))
                .forEach(effect -> lines.add("<dark_gray>• <white>" + effectDisplay(effect)));

        if (lines.isEmpty()) {
            lore.add("<dark_gray>• <gray>No direct item restrictions");
            return;
        }
        int shown = Math.min(lines.size(), limit);
        lore.addAll(lines.subList(0, shown));
        if (lines.size() > shown) {
            lore.add("<dark_gray>• <gray>+" + (lines.size() - shown) + " more rule changes");
        }
    }

    private String effectDisplay(WarzoneConfig.Effect effect) {
        return switch (effect) {
            case COBWEBS -> "<green>Cobweb placement enabled";
            case ELYTRA_NO_ROCKETS -> "<green>Elytra gliding <dark_gray>• <red>No rocket boosting";
        };
    }

    private List<String> conflictingModifierNames(String id) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        runtime.config().conflictGroups().values().stream()
                .filter(group -> group.contains(id))
                .forEach(group -> group.stream()
                        .filter(other -> !other.equals(id))
                        .map(this::modifierName)
                        .map(this::plainName)
                        .sorted()
                        .forEach(names::add));
        return List.copyOf(names);
    }

    private String modifierName(String id) {
        if (id == null || id.isBlank()) return "<gray>None";
        WarzoneConfig.Modifier modifier = runtime.config().modifiers().get(id);
        return modifier == null ? "<white>" + humanizeIdentifier(id) : modifier.displayName();
    }

    private String kitName(String id) {
        if (id == null || id.isBlank()) return "None";
        WarzoneControlConfig.Kit kit = runtime.controlConfig().kits().get(id);
        return kit == null ? humanizeIdentifier(id) : kit.displayName();
    }

    private String selectionName(ActiveSelection selection) {
        if (selection == null) return "None";
        return switch (selection.sourceType()) {
            case KIT -> kitName(selection.sourceId());
            case RANDOM -> "<light_purple>Random Selection";
            case SCHEDULED_MODIFIERS -> "<gold>Scheduled Modifier Mix";
            case CUSTOM_OVERRIDE -> "<yellow>Custom Override";
            case NONE -> "<gray>No Modifiers";
        };
    }

    private String proposedSelectionName(SelectionSourceType type, String id) {
        if (type == null) return "None";
        return switch (type) {
            case KIT -> kitName(id);
            case RANDOM -> "<light_purple>Random Selection";
            case SCHEDULED_MODIFIERS -> "<gold>Scheduled Modifier Mix";
            case CUSTOM_OVERRIDE -> "<yellow>Custom Override";
            case NONE -> "<gray>No Modifiers";
        };
    }

    private String sourceTypeName(SelectionSourceType type) {
        if (type == null) return "None";
        return switch (type) {
            case KIT -> "Kit";
            case RANDOM -> "Random Selection";
            case SCHEDULED_MODIFIERS -> "Scheduled Modifier Mix";
            case CUSTOM_OVERRIDE -> "Custom Override";
            case NONE -> "No Modifiers";
        };
    }

    private String entryDisplayName(WarzoneControlConfig.Entry entry) {
        if (entry == null) return "None";
        return switch (entry.type()) {
            case KIT -> kitName(entry.kitId());
            case RANDOM -> "<light_purple>Random Modifiers";
            case MODIFIERS -> "<gold>Fixed Modifier Mix";
            case NONE -> "<gray>No Modifiers";
        };
    }

    private boolean isNextKit(String kitId) {
        return runtime.rotations().scheduleEnabled()
                && runtime.rotations().nextSlot().entry().type() == WarzoneControlConfig.EntryType.KIT
                && kitId.equals(runtime.rotations().nextSlot().entry().kitId());
    }

    private void addEffectiveTiming(List<String> lore) {
        var state = runtime.rotations().state();
        if (state.overrideActive()) {
            lore.add("<gray>Override: <yellow>Active");
            lore.add("<gray>Ends: <white>" + expiration());
            return;
        }
        if (runtime.rotations().scheduleEnabled()) {
            lore.add("<gray>Changes: <white>" + runtime.messages().formatInstant(
                    state.automaticSlotEndMillis()));
        } else {
            lore.add("<gray>Changes: <dark_gray>No automatic change scheduled");
        }
    }

    private String expiration() {
        var state = runtime.rotations().state();
        if (!state.overrideActive()) return "Automatic schedule";
        if (state.overrideExpiresAtMillis() == 0) return "When manually cleared";
        return runtime.messages().formatInstant(state.overrideExpiresAtMillis());
    }

    private String cadenceName(WarzoneControlConfig.Schedule schedule) {
        int every = schedule.cadence().every();
        String unit = switch (schedule.cadence().unit()) {
            case DAYS -> every == 1 ? "day" : "days";
            case WEEKS -> every == 1 ? "week" : "weeks";
            case MONTHS -> every == 1 ? "month" : "months";
        };
        return every == 1 ? "Every " + unit : "Every " + every + " " + unit;
    }

    private String zoneName(WarzoneControlConfig.Schedule schedule) {
        String id = schedule.timezone().getId();
        if ("America/Indiana/Indianapolis".equals(id)) return "Eastern Time";
        return id.replace('_', ' ');
    }

    private String plainName(String miniMessage) {
        if (miniMessage == null) return "";
        return runtime.messages().plain(miniMessage);
    }

    static String humanizeIdentifier(String value) {
        if (value == null || value.isBlank()) return "None";
        String normalized = value.trim().replace('-', ' ').replace('_', ' ');
        StringBuilder result = new StringBuilder();
        for (String word : normalized.split("\\s+")) {
            if (word.isBlank()) continue;
            if (!result.isEmpty()) result.append(' ');
            String lower = word.toLowerCase(Locale.ROOT);
            result.append(Character.toUpperCase(lower.charAt(0))).append(lower.substring(1));
        }
        return result.isEmpty() ? "None" : result.toString();
    }

    private static String readableDuration(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) return "No cooldown";
        long seconds = duration.getSeconds();
        if (seconds % 3600 == 0) {
            long hours = seconds / 3600;
            return hours + (hours == 1 ? " hour" : " hours");
        }
        if (seconds % 60 == 0) {
            long minutes = seconds / 60;
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        return seconds + (seconds == 1 ? " second" : " seconds");
    }

    private static List<String> added(List<String> before, List<String> after) {
        LinkedHashSet<String> result = new LinkedHashSet<>(after);
        result.removeAll(before);
        return List.copyOf(result);
    }

    private static String friendly(Enum<?> value) {
        if (value == null) return "None";
        return humanizeIdentifier(value.name());
    }

    public enum Operation {
        MENU, KIT_LIST, KIT_SET, MODIFIER_LIST, MODIFIER_ADD, MODIFIER_REMOVE,
        MODIFIER_CLEAR, RANDOM, SCHEDULE
    }

    enum Screen {
        MAIN, CURRENT, KITS, MODIFIERS, RULES, PREVIEW, DURATION, SCHEDULE
    }

    static final class Session {
        final UUID id;
        private Operation operation;
        private final SelectionSourceType originalSource;
        private final String originalSourceId;
        private final List<String> originalModifiers;
        private final long openedAt;
        Screen screen;
        private int page;
        private SelectionSourceType proposedType;
        private String proposedId;
        private List<String> proposedModifiers = List.of();
        private WarzoneConfig.ActiveSet proposedSet;
        UUID currentViewId = UUID.randomUUID();

        private Session(UUID id, Operation operation, SelectionSourceType originalSource,
                        String originalSourceId, List<String> originalModifiers, long openedAt) {
            this.id = id;
            this.operation = operation;
            this.originalSource = originalSource;
            this.originalSourceId = originalSourceId;
            this.originalModifiers = List.copyOf(originalModifiers);
            this.openedAt = openedAt;
        }
    }

    static final class ManagedHolder implements InventoryHolder {
        private final UUID sessionId;
        private final Screen screen;
        private final UUID viewId;
        private Inventory inventory;

        ManagedHolder(UUID sessionId, Screen screen) {
            this(sessionId, screen, UUID.randomUUID());
        }

        ManagedHolder(UUID sessionId, Screen screen, UUID viewId) {
            this.sessionId = sessionId;
            this.screen = screen;
            this.viewId = viewId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
