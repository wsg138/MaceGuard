package com.lincoln.maceguard.warzone.gui;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.config.WarzoneControlConfig;
import com.lincoln.maceguard.warzone.rotation.ActiveSelection;
import com.lincoln.maceguard.warzone.rotation.OverrideDurationMode;
import com.lincoln.maceguard.warzone.rotation.RotationManager;
import com.lincoln.maceguard.warzone.rotation.SelectionSourceType;
import com.lincoln.maceguard.warzone.runtime.WarzoneRuntime;
import net.kyori.adventure.text.Component;
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
    private final JavaPlugin plugin;
    private final WarzoneRuntime runtime;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public WarzoneGuiManager(JavaPlugin plugin, WarzoneRuntime runtime) {
        this.plugin = plugin;
        this.runtime = runtime;
    }

    public void openMain(Player player) {
        Session session = start(player, Operation.MENU);
        Inventory inventory = inventory(session, Screen.MAIN, 27, "Warzone");
        ActiveSelection active = runtime.rotations().activeSelection();
        inventory.setItem(4, item(Material.NETHER_STAR, "Active: " + active.sourceType(),
                "Kit: " + value(active.sourceId()),
                "Modifiers: " + names(active.activeSet().modifierIds()),
                expiration(),
                runtime.rotations().scheduleEnabled()
                        ? "Next: " + runtime.rotations().nextSlot().entry().type() + " — "
                        + runtime.rotations().entryName(runtime.rotations().nextSlot().entry())
                        : "Next: none — schedule disabled",
                runtime.rotations().scheduleEnabled()
                        ? "Changes: " + runtime.messages().formatInstant(
                        runtime.rotations().state().automaticSlotEndMillis())
                        : "Changes: none"));
        inventory.setItem(10, item(Material.CHEST, "Kits", "Browse configured kits."));
        inventory.setItem(12, item(Material.BOOK, "Modifiers", "Browse active and configured modifiers."));
        inventory.setItem(14, item(Material.CLOCK, "Schedule", "View the repeating cycle."));
        inventory.setItem(16, item(Material.COBWEB, "Item Status", "Use /warzone items for full details."));
        if (player.hasPermission("warzonerotator.manage.kit"))
            inventory.setItem(21, item(Material.COMMAND_BLOCK, "Set Kit", "Choose a manual kit override."));
        if (player.hasPermission("warzonerotator.manage.modifier"))
            inventory.setItem(23, item(Material.REPEATER, "Add Modifier", "Create a custom override."));
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
                .sorted(Comparator.comparing(WarzoneControlConfig.Kit::id)).toList();
        Inventory inventory = inventory(session, Screen.KITS, 54, "Warzone Kits");
        int from = page * PAGE_SIZE;
        for (int index = from; index < Math.min(kits.size(), from + PAGE_SIZE); index++) {
            WarzoneControlConfig.Kit kit = kits.get(index);
            boolean active = runtime.rotations().activeSelection().sourceType() == SelectionSourceType.KIT
                    && kit.id().equals(runtime.rotations().activeSelection().sourceId());
            boolean next = runtime.rotations().scheduleEnabled()
                    && runtime.rotations().nextSlot().entry().type() == WarzoneControlConfig.EntryType.KIT
                    && kit.id().equals(runtime.rotations().nextSlot().entry().kitId());
            inventory.setItem(index - from, tagged(kit.enabled() ? kit.icon() : Material.GRAY_DYE,
                    kit.enabled() ? "kit" : "disabled", kit.id(),
                    kit.displayName(), kit.description(),
                    "Modifiers: " + names(kit.modifierIds()),
                    "Enabled: " + kit.enabled(), "Active: " + active, "Scheduled next: " + next));
        }
        navigation(inventory, page, kits.size());
        open(player, session, inventory);
    }

    public void openModifiers(Player player, Operation operation) {
        Session session = start(player, operation);
        openModifiers(player, session, 0);
    }

    private void openModifiers(Player player, Session session, int page) {
        session.page = page;
        List<WarzoneConfig.Modifier> modifiers = runtime.config().modifiers().values().stream()
                .sorted(Comparator.comparing(WarzoneConfig.Modifier::id)).toList();
        Inventory inventory = inventory(session, Screen.MODIFIERS, 54, "Warzone Modifiers");
        int from = page * PAGE_SIZE;
        Set<String> active = Set.copyOf(runtime.rotations().active().modifierIds());
        for (int index = from; index < Math.min(modifiers.size(), from + PAGE_SIZE); index++) {
            WarzoneConfig.Modifier modifier = modifiers.get(index);
            boolean selected = active.contains(modifier.id());
            boolean selectable = modifier.enabled()
                    && (session.operation != Operation.MODIFIER_ADD || !selected)
                    && (session.operation != Operation.MODIFIER_REMOVE || selected);
            Material icon = selectable ? Material.PAPER : Material.GRAY_DYE;
            inventory.setItem(index - from, tagged(icon, selectable ? "modifier" : "disabled",
                    modifier.id(), modifier.displayName(), modifier.description(),
                    "Weight: " + modifier.weight(),
                    "Conflict: " + conflictGroup(modifier.id()),
                    "Selected: " + selected,
                    "Effects: " + modifier.effects(),
                    "Restrictions: " + modifier.restrictions(),
                    "Selectable: " + selectable));
        }
        navigation(inventory, page, modifiers.size());
        open(player, session, inventory);
    }

    public void openSchedule(Player player) {
        Session session = start(player, Operation.SCHEDULE);
        openSchedule(player, session, 0);
    }

    private void openSchedule(Player player, Session session, int page) {
        session.page = page;
        Inventory inventory = inventory(session, Screen.SCHEDULE, 54, "Warzone Schedule");
        List<WarzoneControlConfig.Entry> cycle = runtime.controlConfig().schedule().cycle();
        int current = runtime.rotations().state().currentCycleIndex();
        int from = page * PAGE_SIZE;
        for (int index = from; index < Math.min(cycle.size(), from + PAGE_SIZE); index++) {
            WarzoneControlConfig.Entry entry = cycle.get(index);
            inventory.setItem(index - from, item(index == current ? Material.LIME_DYE : Material.CLOCK,
                    (index + 1) + ". " + entry.type(), runtime.rotations().entryName(entry),
                    index == current ? "Current automatic slot" : "Cycle entry"));
        }
        navigation(inventory, page, cycle.size());
        if (runtime.rotations().scheduleEnabled()) {
            inventory.setItem(49, item(Material.COMPASS, "Next Change",
                    runtime.messages().formatInstant(runtime.rotations().state().automaticSlotEndMillis()),
                    runtime.rotations().nextSlot().entry().type() + ": "
                            + runtime.rotations().entryName(runtime.rotations().nextSlot().entry())));
        } else {
            inventory.setItem(49, item(Material.GRAY_DYE, "Schedule Disabled",
                    "No automatic transition is pending."));
        }
        open(player, session, inventory);
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
        Inventory inventory = inventory(session, Screen.PREVIEW, 27, "Confirm Warzone Change");
        ActiveSelection current = runtime.rotations().activeSelection();
        inventory.setItem(10, item(Material.RED_STAINED_GLASS_PANE, "Current",
                "Source: " + current.sourceType(), "Kit: " + value(current.sourceId()),
                "Modifiers: " + names(current.activeSet().modifierIds())));
        List<String> lore = new ArrayList<>();
        lore.add("Source: " + proposedType);
        lore.add("Kit: " + value(proposedId));
        lore.add("Modifiers: " + names(proposed.modifierIds()));
        lore.add("Added: " + names(added(current.activeSet().modifierIds(), proposed.modifierIds())));
        lore.add("Removed: " + names(added(proposed.modifierIds(), current.activeSet().modifierIds())));
        if (current.sourceType() == SelectionSourceType.KIT
                && operation != Operation.KIT_SET) {
            lore.add("This detaches the active selection from " + current.sourceId());
            lore.add("and creates a Custom Override.");
        }
        inventory.setItem(12, item(Material.WRITABLE_BOOK, "Proposed", lore.toArray(String[]::new)));
        inventory.setItem(15, tagged(Material.LIME_CONCRETE, "action", "confirm",
                "Confirm", "Continue to duration selection."));
        inventory.setItem(17, tagged(Material.RED_CONCRETE, "action", "cancel",
                "Cancel", "Discard this operation."));
        open(player, session, inventory);
    }

    private void openDuration(Player player, Session session) {
        Inventory inventory = inventory(session, Screen.DURATION, 27, "Override Duration");
        inventory.setItem(11, tagged(Material.CLOCK, "duration", "1h", "One Hour",
                "Ends exactly one hour after confirmation."));
        if (runtime.rotations().scheduleEnabled()) {
            inventory.setItem(13, tagged(Material.COMPASS, "duration", "next",
                    "Until Next Scheduled Change",
                    runtime.messages().formatInstant(runtime.rotations().state().automaticSlotEndMillis()),
                    runtime.rotations().nextSlot().entry().type() + ": "
                            + runtime.rotations().entryName(runtime.rotations().nextSlot().entry())));
        } else {
            inventory.setItem(13, item(Material.GRAY_DYE, "Until Next Scheduled Change",
                    "Unavailable while the automatic schedule is disabled."));
        }
        inventory.setItem(15, tagged(Material.LEVER, "duration", "manual",
                "Until Manually Cleared", "Persists across restart and reload."));
        open(player, session, inventory);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ManagedHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Session session = valid(player, holder);
        if (session == null || event.getClickedInventory() != event.getView().getTopInventory()) return;
        String type = tag(event.getCurrentItem(), "warzone-type");
        String value = tag(event.getCurrentItem(), "warzone-value");
        if (type == null) return;
        try {
            switch (session.screen) {
                case MAIN -> mainClick(player, session, event.getSlot());
                case KITS -> kitClick(player, session, type, value);
                case MODIFIERS -> modifierClick(player, session, type, value);
                case PREVIEW -> previewClick(player, session, type, value);
                case DURATION -> durationClick(player, session, type, value);
                case SCHEDULE -> scheduleClick(player, session, type, value);
            }
        } catch (IllegalArgumentException | IllegalStateException ex) {
            player.sendMessage(Component.text("Warzone change rejected: " + ex.getMessage()));
            sessions.remove(player.getUniqueId());
            player.closeInventory();
        }
    }

    private void mainClick(Player player, Session session, int slot) {
        switch (slot) {
            case 10 -> { session.operation = Operation.KIT_LIST; openKits(player, session, 0); }
            case 12 -> { session.operation = Operation.MODIFIER_LIST; openModifiers(player, session, 0); }
            case 14 -> openSchedule(player);
            case 16 -> {
                sessions.remove(player.getUniqueId());
                player.closeInventory();
                player.performCommand("warzone items");
            }
            case 21 -> { if (player.hasPermission("warzonerotator.manage.kit")) {
                session.operation = Operation.KIT_SET; openKits(player, session, 0); } }
            case 23 -> { if (player.hasPermission("warzonerotator.manage.modifier")) {
                session.operation = Operation.MODIFIER_ADD; openModifiers(player, session, 0); } }
            default -> { }
        }
    }

    private void kitClick(Player player, Session session, String type, String value) {
        if (navigationClick(player, session, type, value, true)) return;
        if (!"kit".equals(type) || session.operation != Operation.KIT_SET) return;
        WarzoneConfig.ActiveSet proposed = runtime.rotations().previewKit(value);
        openPreview(player, session.operation, SelectionSourceType.KIT, value, proposed);
    }

    private void modifierClick(Player player, Session session, String type, String value) {
        if (navigationClick(player, session, type, value, false)) return;
        if (!"modifier".equals(type)) return;
        boolean custom = player.hasPermission("warzonerotator.admin")
                || player.hasPermission("warzonerotator.manage.custom-combinations");
        if (runtime.rotations().activeSelection().sourceType() == SelectionSourceType.KIT && !custom)
            throw new IllegalArgumentException("Kit detachment requires custom-combinations permission.");
        WarzoneConfig.ActiveSet proposed = switch (session.operation) {
            case MODIFIER_ADD -> runtime.rotations().previewAdd(value, custom);
            case MODIFIER_REMOVE -> runtime.rotations().previewRemove(value, custom);
            default -> null;
        };
        if (proposed != null)
            openPreview(player, session.operation, SelectionSourceType.CUSTOM_OVERRIDE, null, proposed);
    }

    private boolean navigationClick(Player player, Session session, String type,
                                    String value, boolean kits) {
        if (!"page".equals(type)) return false;
        int page = Integer.parseInt(value);
        if (kits) openKits(player, session, page); else openModifiers(player, session, page);
        return true;
    }

    private void scheduleClick(Player player, Session session, String type, String value) {
        if (!"page".equals(type)) return;
        openSchedule(player, session, Integer.parseInt(value));
    }

    private void previewClick(Player player, Session session, String type, String value) {
        if (!"action".equals(type)) return;
        if ("cancel".equals(value)) {
            sessions.remove(player.getUniqueId());
            player.closeInventory();
        } else if ("confirm".equals(value)) openDuration(player, session);
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
        player.sendMessage(Component.text("Warzone override applied: " + mode.name()));
    }


    private void requireCurrentPermission(Player player, Session session) {
        String permission = switch (session.operation) {
            case KIT_SET -> "warzonerotator.manage.kit";
            case MODIFIER_ADD, MODIFIER_REMOVE, MODIFIER_CLEAR -> "warzonerotator.manage.modifier";
            case RANDOM -> "warzonerotator.manage.random";
            default -> throw new IllegalStateException("No pending administrative operation.");
        };
        if (!player.hasPermission("warzonerotator.admin") && !player.hasPermission(permission))
            throw new IllegalStateException("Your permission to perform this operation was removed.");
        boolean detachesKit = session.originalSource == SelectionSourceType.KIT
                && session.operation != Operation.KIT_SET;
        int count = session.proposedModifiers.size();
        boolean bypassesCount = session.operation != Operation.MODIFIER_CLEAR
                && (count < runtime.config().selection().minimum()
                || count > runtime.config().selection().maximum());
        if ((detachesKit || bypassesCount)
                && !player.hasPermission("warzonerotator.admin")
                && !player.hasPermission("warzonerotator.manage.custom-combinations"))
            throw new IllegalStateException(
                    "This custom combination requires warzonerotator.manage.custom-combinations.");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ManagedHolder)
            event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof ManagedHolder holder)) return;
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && session.id.equals(holder.sessionId)
                && session.screen == holder.screen
                && session.currentViewId.equals(holder.viewId))
            sessions.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    public void cleanup() {
        sessions.entrySet().removeIf(entry -> expired(entry.getValue()));
    }

    public void clear() { sessions.clear(); }
    public int sessionCount() { return sessions.size(); }

    private Session valid(Player player, ManagedHolder holder) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || !session.id.equals(holder.sessionId)
                || session.screen != holder.screen
                || !session.currentViewId.equals(holder.viewId) || expired(session)) {
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
        Inventory inventory = Bukkit.createInventory(holder, size, Component.text(title));
        holder.inventory = inventory;
        return inventory;
    }

    private void open(Player player, Session session, Inventory inventory) {
        player.openInventory(inventory);
    }

    private void navigation(Inventory inventory, int page, int total) {
        if (page > 0) inventory.setItem(45, tagged(Material.ARROW, "page",
                Integer.toString(page - 1), "Previous Page"));
        if ((page + 1) * PAGE_SIZE < total) inventory.setItem(53, tagged(Material.ARROW,
                "page", Integer.toString(page + 1), "Next Page"));
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
        meta.displayName(Component.text(name));
        meta.lore(java.util.Arrays.stream(lore).filter(value -> value != null && !value.isBlank())
                .map(Component::text).toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }


    void requireUnchangedOriginal(Session session) {
        ActiveSelection current = runtime.rotations().activeSelection();
        if (current.sourceType() != session.originalSource
                || !Objects.equals(current.sourceId(), session.originalSourceId)
                || !current.activeSet().modifierIds().equals(session.originalModifiers)) {
            throw new IllegalStateException("The active Warzone selection changed while this menu was open. Start again.");
        }
    }

    private String conflictGroup(String id) {
        return runtime.config().conflictGroups().entrySet().stream()
                .filter(entry -> entry.getValue().contains(id)).map(Map.Entry::getKey)
                .sorted().reduce((a, b) -> a + ", " + b).orElse("none");
    }

    private String expiration() {
        var state = runtime.rotations().state();
        if (!state.overrideActive()) return "Automatic schedule";
        if (state.overrideExpiresAtMillis() == 0) return "Until manually cleared";
        return "Ends: " + runtime.messages().formatInstant(state.overrideExpiresAtMillis());
    }

    private static List<String> added(List<String> before, List<String> after) {
        LinkedHashSet<String> result = new LinkedHashSet<>(after);
        result.removeAll(before);
        return List.copyOf(result);
    }

    private static String names(List<String> ids) { return ids.isEmpty() ? "None" : String.join(", ", ids); }
    private static String value(String value) { return value == null || value.isBlank() ? "None" : value; }

    public enum Operation {
        MENU, KIT_LIST, KIT_SET, MODIFIER_LIST, MODIFIER_ADD, MODIFIER_REMOVE,
        MODIFIER_CLEAR, RANDOM, SCHEDULE
    }
    enum Screen { MAIN, KITS, MODIFIERS, PREVIEW, DURATION, SCHEDULE }

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
        @Override public Inventory getInventory() { return inventory; }
    }
}
