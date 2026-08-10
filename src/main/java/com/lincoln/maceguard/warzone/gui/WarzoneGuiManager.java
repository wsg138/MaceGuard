package com.lincoln.maceguard.warzone.gui;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.config.WarzoneControlConfig;
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
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Bukkit-only inventory UI with holder identity and short-lived per-player sessions. */
public final class WarzoneGuiManager implements Listener {
    private static final int PAGE_SIZE = 45;
    private static final String NAME_STYLE = "<!italic><gold>";
    private static final String LORE_STYLE = "<!italic><gray>";
    private static final String GOOD = "<green>";
    private static final String BAD = "<red>";
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
        Inventory inventory = inventory(session, Screen.MAIN, 27, "<gold><bold>Warzone Control");
        ActiveSelection active = runtime.rotations().activeSelection();

        inventory.setItem(4, item(Material.NETHER_STAR, "<aqua><bold>Current Warzone",
                "<gray>Source: <white>" + friendly(active.sourceType()),
                "<gray>Kit: <white>" + value(active.sourceId()),
                "<gray>Modifiers: <white>" + names(active.activeSet().modifierIds()),
                "<gray>Duration: <white>" + expiration(),
                runtime.rotations().scheduleEnabled()
                        ? "<gray>Next: <yellow>" + friendly(runtime.rotations().nextSlot().entry().type())
                        + " <dark_gray>• <white>" + runtime.rotations().entryName(runtime.rotations().nextSlot().entry())
                        : "<gray>Next: <red>Schedule disabled",
                runtime.rotations().scheduleEnabled()
                        ? "<gray>Changes: <white>" + runtime.messages().formatInstant(
                        runtime.rotations().state().automaticSlotEndMillis())
                        : "<gray>Changes: <dark_gray>None"));

        inventory.setItem(10, item(Material.CHEST, "<green>Kits",
                "<gray>Browse every configured kit and its modifiers.",
                "<yellow>Click to open"));
        inventory.setItem(12, item(Material.BOOK, "<aqua>Modifiers",
                "<gray>Browse every modifier, restriction, and status.",
                "<yellow>Click to open"));
        inventory.setItem(14, item(Material.CLOCK, "<gold>Schedule",
                "<gray>View schedule timing and every cycle entry.",
                "<yellow>Click to open"));
        inventory.setItem(16, item(Material.COBWEB, "<light_purple>Item Status",
                "<gray>Show current item and ability rules.",
                "<yellow>Click to view in chat"));

        if (player.hasPermission("warzonerotator.manage.kit")) {
            inventory.setItem(21, item(Material.COMMAND_BLOCK, "<green>Set Kit Override",
                    "<gray>Choose a kit to apply manually.",
                    "<yellow>Click to configure"));
        }
        if (player.hasPermission("warzonerotator.manage.modifier")) {
            inventory.setItem(23, item(Material.REPEATER, "<yellow>Add Modifier",
                    "<gray>Create a custom modifier override.",
                    "<yellow>Click to configure"));
        }
        open(player, session, inventory);
    }

    public void openKits(Player player, boolean administrative) {
        Session session = start(player, administrative ? Operation.KIT_SET : Operation.KIT_LIST);
        openKits(player, session, 0);
    }

    private void openKits(Player player, Session session, int page) {
        session.page = page;
        List<WarzoneControlConfig.Kit> kits = runtime.controlConfig().kits().values().stream()
                .filter(kit -> kit.enabled() || runtime.controlConfig().gui().showDisabledKits())
                .sorted(Comparator.comparing(WarzoneControlConfig.Kit::id))
                .toList();

        Inventory inventory = inventory(session, Screen.KITS, 54,
                session.operation == Operation.KIT_SET
                        ? "<gold><bold>Select Kit Override"
                        : "<gold><bold>Warzone Kits");
        int from = page * PAGE_SIZE;
        for (int index = from; index < Math.min(kits.size(), from + PAGE_SIZE); index++) {
            WarzoneControlConfig.Kit kit = kits.get(index);
            boolean active = runtime.rotations().activeSelection().sourceType() == SelectionSourceType.KIT
                    && kit.id().equals(runtime.rotations().activeSelection().sourceId());
            boolean next = runtime.rotations().scheduleEnabled()
                    && runtime.rotations().nextSlot().entry().type() == WarzoneControlConfig.EntryType.KIT
                    && kit.id().equals(runtime.rotations().nextSlot().entry().kitId());
            boolean actionable = session.operation == Operation.KIT_SET && kit.enabled();

            inventory.setItem(index - from, tagged(
                    kit.enabled() ? kit.icon() : Material.GRAY_DYE,
                    actionable ? "kit" : "kit-detail",
                    kit.id(),
                    kit.displayName(),
                    kit.description(),
                    "<gray>Modifiers: <white>" + names(kit.modifierIds()),
                    "<gray>Enabled: " + status(kit.enabled()),
                    "<gray>Active now: " + status(active),
                    "<gray>Scheduled next: " + status(next),
                    actionable
                            ? "<yellow>Click to preview this override"
                            : "<aqua>Click for full details"));
        }

        navigation(inventory, page, kits.size());
        inventory.setItem(49, tagged(Material.ARROW, "back-main", "",
                "<yellow>Back to Warzone Control"));
        open(player, session, inventory);
    }

    private void openKitDetail(Player player, Session session, String kitId) {
        WarzoneControlConfig.Kit kit = runtime.controlConfig().kits().get(kitId);
        if (kit == null) throw new IllegalArgumentException("Kit '" + kitId + "' no longer exists.");

        boolean active = runtime.rotations().activeSelection().sourceType() == SelectionSourceType.KIT
                && kit.id().equals(runtime.rotations().activeSelection().sourceId());
        boolean next = runtime.rotations().scheduleEnabled()
                && runtime.rotations().nextSlot().entry().type() == WarzoneControlConfig.EntryType.KIT
                && kit.id().equals(runtime.rotations().nextSlot().entry().kitId());

        List<String> lore = new ArrayList<>();
        lore.add(kit.description());
        lore.add("<gray>ID: <white>" + kit.id());
        lore.add("<gray>Enabled: " + status(kit.enabled()));
        lore.add("<gray>Active now: " + status(active));
        lore.add("<gray>Scheduled next: " + status(next));
        lore.add("<gray>Modifier count: <white>" + kit.modifierIds().size());
        lore.add("<gold>Modifiers");
        if (kit.modifierIds().isEmpty()) {
            lore.add("<dark_gray>• <white>None");
        } else {
            for (String modifierId : kit.modifierIds()) {
                lore.add("<dark_gray>• " + modifierDisplay(modifierId));
            }
        }

        Inventory inventory = inventory(session, Screen.KITS, 27, "<gold><bold>Kit Details");
        inventory.setItem(13, item(kit.enabled() ? kit.icon() : Material.GRAY_DYE,
                kit.displayName(), lore.toArray(String[]::new)));
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
                .sorted(Comparator.comparing(WarzoneConfig.Modifier::id))
                .toList();

        Inventory inventory = inventory(session, Screen.MODIFIERS, 54,
                switch (session.operation) {
                    case MODIFIER_ADD -> "<gold><bold>Add Warzone Modifier";
                    case MODIFIER_REMOVE -> "<gold><bold>Remove Warzone Modifier";
                    default -> "<gold><bold>Warzone Modifiers";
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

            inventory.setItem(index - from, tagged(icon,
                    actionable ? "modifier" : "modifier-detail",
                    modifier.id(),
                    modifier.displayName(),
                    modifier.description(),
                    "<gray>ID: <white>" + modifier.id(),
                    "<gray>Active now: " + status(selected),
                    "<gray>Enabled: " + status(modifier.enabled()),
                    "<gray>Weight: <white>" + modifier.weight(),
                    "<gray>Conflict group: <white>" + conflictGroup(modifier.id()),
                    actionable
                            ? "<yellow>Click to preview this change"
                            : "<aqua>Click for full details"));
        }

        navigation(inventory, page, modifiers.size());
        inventory.setItem(49, tagged(Material.ARROW, "back-main", "",
                "<yellow>Back to Warzone Control"));
        open(player, session, inventory);
    }

    private void openModifierDetail(Player player, Session session, String modifierId) {
        WarzoneConfig.Modifier modifier = runtime.config().modifiers().get(modifierId);
        if (modifier == null) {
            throw new IllegalArgumentException("Modifier '" + modifierId + "' no longer exists.");
        }

        boolean active = runtime.rotations().active().modifierIds().contains(modifier.id());
        List<String> lore = new ArrayList<>();
        lore.add(modifier.description());
        lore.add("<gray>ID: <white>" + modifier.id());
        lore.add("<gray>Enabled: " + status(modifier.enabled()));
        lore.add("<gray>Active now: " + status(active));
        lore.add("<gray>Weight: <white>" + modifier.weight());
        lore.add("<gray>Combat carryover: " + status(modifier.combatCarryover()));
        lore.add("<gray>Conflict group: <white>" + conflictGroup(modifier.id()));
        lore.add("<gray>Effects: <white>" + effectNames(modifier));
        lore.add("<gold>Restrictions");
        addRestrictionLore(lore, modifier);
        if (modifier.startMessage() != null && !modifier.startMessage().isBlank()) {
            lore.add("<gold>Start message");
            lore.add("<gray>" + modifier.startMessage());
        }
        if (modifier.endMessage() != null && !modifier.endMessage().isBlank()) {
            lore.add("<gold>End message");
            lore.add("<gray>" + modifier.endMessage());
        }

        Inventory inventory = inventory(session, Screen.MODIFIERS, 27, "<gold><bold>Modifier Details");
        inventory.setItem(13, item(
                modifier.enabled() ? (active ? Material.LIME_DYE : Material.PAPER) : Material.GRAY_DYE,
                modifier.displayName(), lore.toArray(String[]::new)));
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
        int from = page * PAGE_SIZE;

        for (int index = from; index < Math.min(cycle.size(), from + PAGE_SIZE); index++) {
            WarzoneControlConfig.Entry entry = cycle.get(index);
            List<String> lore = scheduleEntryLore(index, entry, current, next, false);
            inventory.setItem(index - from, tagged(
                    index == current ? Material.LIME_DYE : index == next ? Material.COMPASS : Material.CLOCK,
                    "schedule-entry",
                    Integer.toString(index),
                    (index == current ? "<green>" : index == next ? "<aqua>" : "<gold>")
                            + (index + 1) + ". " + friendly(entry.type()),
                    lore.toArray(String[]::new)));
        }

        navigation(inventory, page, cycle.size());
        inventory.setItem(48, tagged(Material.ARROW, "back-main", "",
                "<yellow>Back to Warzone Control"));
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

        Inventory inventory = inventory(session, Screen.SCHEDULE, 27, "<gold><bold>Schedule Entry Details");
        inventory.setItem(11, scheduleTimingItem());
        inventory.setItem(13, item(
                index == current ? Material.LIME_DYE : index == next ? Material.COMPASS : Material.CLOCK,
                (index == current ? "<green>" : index == next ? "<aqua>" : "<gold>")
                        + (index + 1) + ". " + friendly(entry.type()),
                lore.toArray(String[]::new)));
        inventory.setItem(15, currentSelectionItem());
        inventory.setItem(22, tagged(Material.ARROW, "back-schedule", Integer.toString(session.page),
                "<yellow>Back to Schedule"));
        open(player, session, inventory);
    }

    private ItemStack scheduleOverview() {
        WarzoneControlConfig.Schedule schedule = runtime.controlConfig().schedule();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Status: " + status(runtime.rotations().scheduleEnabled()));
        lore.add("<gray>Timezone: <white>" + schedule.timezone());
        lore.add("<gray>Anchor: <white>" + schedule.anchorDate() + " " + schedule.time());
        lore.add("<gray>Cadence: <white>Every " + schedule.cadence().every()
                + " " + friendly(schedule.cadence().unit()));
        lore.add("<gray>Cycle entries: <white>" + schedule.cycle().size());
        lore.add("<gray>Current position: <white>"
                + (schedule.cycle().isEmpty()
                ? "None"
                : (runtime.rotations().state().currentCycleIndex() + 1) + " / " + schedule.cycle().size()));
        if (runtime.rotations().scheduleEnabled()) {
            lore.add("<gray>Next change: <white>" + runtime.messages().formatInstant(
                    runtime.rotations().state().automaticSlotEndMillis()));
            lore.add("<gray>Next entry: <yellow>" + friendly(runtime.rotations().nextSlot().entry().type())
                    + " <dark_gray>• <white>" + runtime.rotations().entryName(runtime.rotations().nextSlot().entry()));
        } else {
            lore.add("<red>Automatic transitions are paused.");
        }
        return item(runtime.rotations().scheduleEnabled() ? Material.COMPASS : Material.GRAY_DYE,
                runtime.rotations().scheduleEnabled()
                        ? "<aqua>Schedule Overview"
                        : "<red>Schedule Disabled",
                lore.toArray(String[]::new));
    }

    private ItemStack scheduleTimingItem() {
        WarzoneControlConfig.Schedule schedule = runtime.controlConfig().schedule();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Status: " + status(runtime.rotations().scheduleEnabled()));
        lore.add("<gray>Timezone: <white>" + schedule.timezone());
        lore.add("<gray>Anchor: <white>" + schedule.anchorDate() + " " + schedule.time());
        lore.add("<gray>Cadence: <white>Every " + schedule.cadence().every()
                + " " + friendly(schedule.cadence().unit()));
        lore.add("<gray>Current slot start: <white>" + runtime.messages().formatInstant(
                runtime.rotations().state().automaticSlotStartMillis()));
        lore.add("<gray>Current slot end: <white>" + runtime.messages().formatInstant(
                runtime.rotations().state().automaticSlotEndMillis()));
        return item(Material.REPEATER, "<aqua>Schedule Timing", lore.toArray(String[]::new));
    }

    private ItemStack currentSelectionItem() {
        ActiveSelection active = runtime.rotations().activeSelection();
        return item(Material.NETHER_STAR, "<aqua>Current Effective Selection",
                "<gray>Source: <white>" + friendly(active.sourceType()),
                "<gray>Kit: <white>" + value(active.sourceId()),
                "<gray>Modifiers: <white>" + names(active.activeSet().modifierIds()),
                "<gray>Duration: <white>" + expiration());
    }

    private List<String> scheduleEntryLore(int index, WarzoneControlConfig.Entry entry,
                                           int current, int next, boolean detailed) {
        List<String> lore = new ArrayList<>();
        List<WarzoneControlConfig.Entry> cycle = runtime.controlConfig().schedule().cycle();
        lore.add("<gray>Position: <white>" + (index + 1) + " / " + cycle.size());
        lore.add("<gray>Type: <white>" + friendly(entry.type()));

        switch (entry.type()) {
            case KIT -> {
                WarzoneControlConfig.Kit kit = runtime.controlConfig().kits().get(entry.kitId());
                lore.add("<gray>Kit: " + (kit == null ? "<red>" + entry.kitId() : kit.displayName()));
                lore.add("<gray>Kit ID: <white>" + value(entry.kitId()));
                lore.add("<gray>Modifiers: <white>" + (kit == null
                        ? "Unavailable"
                        : names(kit.modifierIds())));
                if (detailed && kit != null) {
                    for (String modifierId : kit.modifierIds()) {
                        lore.add("<dark_gray>• " + modifierDisplay(modifierId));
                    }
                }
            }
            case MODIFIERS -> {
                lore.add("<gray>Exact modifiers: <white>" + names(entry.modifierIds()));
                if (detailed) {
                    for (String modifierId : entry.modifierIds()) {
                        lore.add("<dark_gray>• " + modifierDisplay(modifierId));
                    }
                }
            }
            case RANDOM -> {
                WarzoneConfig.Selection selection = runtime.config().selection();
                lore.add("<gray>Selection: <white>Weighted random modifiers");
                lore.add("<gray>Modifier count: <white>" + selection.minimum()
                        + "–" + selection.maximum());
                lore.add("<gray>Prevent repeat: " + status(selection.preventIdenticalRepeat()));
                if (detailed) {
                    lore.add("<gray>Count weights: <white>" + selection.countWeights());
                    lore.add("<gray>Eligible modifiers use their configured weights");
                    lore.add("<gray>and conflict groups before a result is accepted.");
                }
            }
            case NONE -> {
                lore.add("<gray>Selection: <white>No modifiers");
                if (detailed) {
                    lore.add("<gray>This slot intentionally clears automatic modifiers.");
                }
            }
        }

        lore.add("<gray>Current slot: " + status(index == current));
        lore.add("<gray>Next slot: " + status(index == next));
        if (!detailed) lore.add("<aqua>Click for full details");
        return lore;
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
        inventory.setItem(10, item(Material.RED_STAINED_GLASS_PANE, "<red>Current Selection",
                "<gray>Source: <white>" + friendly(current.sourceType()),
                "<gray>Kit: <white>" + value(current.sourceId()),
                "<gray>Modifiers: <white>" + names(current.activeSet().modifierIds())));

        List<String> lore = new ArrayList<>();
        lore.add("<gray>Source: <white>" + friendly(proposedType));
        lore.add("<gray>Kit: <white>" + value(proposedId));
        lore.add("<gray>Modifiers: <white>" + names(proposed.modifierIds()));
        lore.add("<green>Added: <white>" + names(added(
                current.activeSet().modifierIds(), proposed.modifierIds())));
        lore.add("<red>Removed: <white>" + names(added(
                proposed.modifierIds(), current.activeSet().modifierIds())));
        if (current.sourceType() == SelectionSourceType.KIT && operation != Operation.KIT_SET) {
            lore.add("<yellow>This detaches the active selection from " + current.sourceId());
            lore.add("<yellow>and creates a Custom Override.");
        }

        inventory.setItem(12, item(Material.WRITABLE_BOOK, "<yellow>Proposed Selection",
                lore.toArray(String[]::new)));
        inventory.setItem(15, tagged(Material.LIME_CONCRETE, "action", "confirm",
                "<green><bold>Confirm", "<gray>Continue to duration selection."));
        inventory.setItem(17, tagged(Material.RED_CONCRETE, "action", "cancel",
                "<red><bold>Cancel", "<gray>Discard this operation."));
        open(player, session, inventory);
    }

    private void openDuration(Player player, Session session) {
        Inventory inventory = inventory(session, Screen.DURATION, 27, "<gold><bold>Override Duration");
        inventory.setItem(4, item(Material.WRITABLE_BOOK, "<aqua>Pending Selection",
                "<gray>Source: <white>" + friendly(session.proposedType),
                "<gray>Kit: <white>" + value(session.proposedId),
                "<gray>Modifiers: <white>" + names(session.proposedModifiers)));
        inventory.setItem(11, tagged(Material.CLOCK, "duration", "1h", "<yellow>One Hour",
                "<gray>Ends exactly one hour after confirmation."));
        if (runtime.rotations().scheduleEnabled()) {
            inventory.setItem(13, tagged(Material.COMPASS, "duration", "next",
                    "<aqua>Until Next Scheduled Change",
                    "<gray>Ends: <white>" + runtime.messages().formatInstant(
                            runtime.rotations().state().automaticSlotEndMillis()),
                    "<gray>Then: <yellow>" + friendly(runtime.rotations().nextSlot().entry().type())
                            + " <dark_gray>• <white>"
                            + runtime.rotations().entryName(runtime.rotations().nextSlot().entry())));
        } else {
            inventory.setItem(13, item(Material.GRAY_DYE, "<red>Until Next Scheduled Change",
                    "<gray>Unavailable while the automatic schedule is disabled."));
        }
        inventory.setItem(15, tagged(Material.LEVER, "duration", "manual",
                "<light_purple>Until Manually Cleared",
                "<gray>Persists across restart and reload."));
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
            if (session.screen == Screen.MAIN) {
                mainClick(player, session, event.getSlot());
                return;
            }

            String type = tag(event.getCurrentItem(), "warzone-type");
            String value = tag(event.getCurrentItem(), "warzone-value");
            if (type == null) return;

            switch (session.screen) {
                case MAIN -> throw new IllegalStateException("Unexpected main-menu dispatch.");
                case KITS -> kitClick(player, session, type, value);
                case MODIFIERS -> modifierClick(player, session, type, value);
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

    private void mainClick(Player player, Session session, int slot) {
        switch (slot) {
            case 10 -> {
                session.operation = Operation.KIT_LIST;
                openKits(player, session, 0);
            }
            case 12 -> {
                session.operation = Operation.MODIFIER_LIST;
                openModifiers(player, session, 0);
            }
            case 14 -> {
                session.operation = Operation.SCHEDULE;
                openSchedule(player, session, 0);
            }
            case 16 -> {
                sessions.remove(player.getUniqueId());
                player.closeInventory();
                player.performCommand("warzone items");
            }
            case 21 -> {
                if (player.hasPermission("warzonerotator.manage.kit")) {
                    session.operation = Operation.KIT_SET;
                    openKits(player, session, 0);
                }
            }
            case 23 -> {
                if (player.hasPermission("warzonerotator.manage.modifier")) {
                    session.operation = Operation.MODIFIER_ADD;
                    openModifiers(player, session, 0);
                }
            }
            default -> { }
        }
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
                org.bukkit.persistence.PersistentDataType.STRING, type);
        meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "warzone-value"),
                org.bukkit.persistence.PersistentDataType.STRING, value == null ? "" : value);
        item.setItemMeta(meta);
        return item;
    }

    private String tag(ItemStack item, String key) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(
                new org.bukkit.NamespacedKey(plugin, key),
                org.bukkit.persistence.PersistentDataType.STRING);
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

    private String modifierDisplay(String id) {
        WarzoneConfig.Modifier modifier = runtime.config().modifiers().get(id);
        return modifier == null
                ? "<red>" + id + " <dark_gray>(missing)"
                : modifier.displayName() + " <dark_gray>(<gray>" + id + "<dark_gray>)";
    }

    private String effectNames(WarzoneConfig.Modifier modifier) {
        if (modifier.effects().isEmpty()) return "None";
        return modifier.effects().stream()
                .map(WarzoneGuiManager::friendly)
                .sorted()
                .reduce((left, right) -> left + ", " + right)
                .orElse("None");
    }

    private void addRestrictionLore(List<String> lore, WarzoneConfig.Modifier modifier) {
        if (modifier.restrictions().isEmpty()) {
            lore.add("<dark_gray>• <white>None");
            return;
        }

        modifier.restrictions().entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().id()))
                .forEach(entry -> {
                    WarzoneConfig.Restriction restriction = entry.getValue();
                    String detail = friendly(restriction.mode());
                    if (restriction.cooldown() != null
                            && !restriction.cooldown().isZero()
                            && !restriction.cooldown().isNegative()) {
                        detail += " <dark_gray>• <white>" + readableDuration(restriction.cooldown());
                    }
                    lore.add("<dark_gray>• <white>"
                            + com.lincoln.maceguard.warzone.message.WarzoneMessageService.friendly(entry.getKey())
                            + ": <yellow>" + detail);
                });
    }

    private String conflictGroup(String id) {
        return runtime.config().conflictGroups().entrySet().stream()
                .filter(entry -> entry.getValue().contains(id))
                .map(Map.Entry::getKey)
                .sorted()
                .reduce((left, right) -> left + ", " + right)
                .orElse("none");
    }

    private String expiration() {
        var state = runtime.rotations().state();
        if (!state.overrideActive()) return "Automatic schedule";
        if (state.overrideExpiresAtMillis() == 0) return "Until manually cleared";
        return "Ends " + runtime.messages().formatInstant(state.overrideExpiresAtMillis());
    }

    private static String readableDuration(Duration duration) {
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

    private static String status(boolean value) {
        return value ? GOOD + "Yes" : BAD + "No";
    }

    private static String names(List<String> ids) {
        return ids.isEmpty() ? "None" : String.join(", ", ids);
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "None" : value;
    }

    private static String friendly(Enum<?> value) {
        if (value == null) return "None";
        String[] words = value.name().toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    public enum Operation {
        MENU, KIT_LIST, KIT_SET, MODIFIER_LIST, MODIFIER_ADD, MODIFIER_REMOVE,
        MODIFIER_CLEAR, RANDOM, SCHEDULE
    }

    enum Screen {
        MAIN, KITS, MODIFIERS, PREVIEW, DURATION, SCHEDULE
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
