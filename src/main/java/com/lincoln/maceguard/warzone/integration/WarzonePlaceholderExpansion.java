package com.lincoln.maceguard.warzone.integration;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.message.WarzoneMessageService;
import com.lincoln.maceguard.warzone.restriction.RestrictionMode;
import com.lincoln.maceguard.warzone.restriction.RestrictionTarget;
import com.lincoln.maceguard.warzone.runtime.WarzoneRuntime;
import com.lincoln.maceguard.warzone.util.DurationFormatter;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Comparator;
import java.util.function.Supplier;

public final class WarzonePlaceholderExpansion extends PlaceholderExpansion
        implements WarzonePlaceholderHook {
    private static final RestrictionTarget MACE = target("MACE");
    private static final RestrictionTarget ENDER_PEARL = target("ENDER_PEARL");
    private static final RestrictionTarget WIND_CHARGE = target("WIND_CHARGE");

    private final Plugin plugin;
    private final Supplier<WarzoneRuntime> runtime;

    public WarzonePlaceholderExpansion(Plugin plugin, Supplier<WarzoneRuntime> runtime) {
        this.plugin = plugin;
        this.runtime = runtime;
    }

    @Override public String getIdentifier() { return "warzone"; }
    @Override public String getAuthor() { return "P2wn"; }
    @Override public String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }
    @Override public boolean active() { return isRegistered(); }
    @Override public void close() { if (isRegistered()) unregister(); }

    @Override public String onRequest(OfflinePlayer player, String params) {
        WarzoneRuntime live = runtime.get();
        if (live == null) return "";
        var active = live.rotations().active();
        var remaining = live.rotations().remaining();
        boolean scopeActive = live.gameplayScopeActive();
        return switch (params.toLowerCase(java.util.Locale.ROOT)) {
            case "current_meta", "current_modifiers" -> live.messages().plain(active.displayName());
            case "current_meta_id", "current_modifier_ids" -> active.id();
            case "description" -> live.messages().plain(active.description());
            case "time_left" -> DurationFormatter.clock(remaining);
            case "time_left_words" -> DurationFormatter.words(remaining);
            case "time_left_seconds" -> Long.toString(Math.max(0, remaining.getSeconds()));
            case "changes_at" -> live.messages().formatInstant(
                    live.rotations().state().transitionAtMillis());
            case "next_meta" -> "Random weekly selection";
            case "next_meta_id" -> "unselected";
            case "disabled_items" -> scopeActive
                    ? joined(active, RestrictionMode.DISABLED, false) : "None";
            case "disabled_items_count" -> scopeActive ? Long.toString(
                    count(active, RestrictionMode.DISABLED, false)) : "0";
            case "cooldown_items" -> scopeActive
                    ? joined(active, RestrictionMode.COOLDOWN, false) : "None";
            case "cooldown_items_count" -> scopeActive ? Long.toString(
                    count(active, RestrictionMode.COOLDOWN, false)) : "0";
            case "restrictions" -> scopeActive ? all(active) : "None";
            case "gameplay_scope_active" -> Boolean.toString(scopeActive);
            case "cobwebs_allowed" -> Boolean.toString(scopeActive && active.cobwebsAllowed());
            case "mace_status" -> status(scopeActive, active, MACE);
            case "ender_pearl_status" -> status(scopeActive, active, ENDER_PEARL);
            case "wind_charge_status" -> status(scopeActive, active, WIND_CHARGE);
            case "spear_lunge_status" -> status(scopeActive, active, RestrictionTarget.SPEAR_LUNGE);
            case "elytra_status" -> elytraStatus(scopeActive, active);
            case "mace_disabled" -> Boolean.toString(disabled(scopeActive, active, MACE));
            case "mace_cooldown_seconds" -> Long.toString(cooldownSeconds(scopeActive, active, MACE));
            case "ender_pearl_disabled" -> Boolean.toString(disabled(scopeActive, active, ENDER_PEARL));
            case "ender_pearl_cooldown_seconds" ->
                    Long.toString(cooldownSeconds(scopeActive, active, ENDER_PEARL));
            case "wind_charge_disabled" -> Boolean.toString(disabled(scopeActive, active, WIND_CHARGE));
            case "wind_charge_cooldown_seconds" ->
                    Long.toString(cooldownSeconds(scopeActive, active, WIND_CHARGE));
            case "spear_lunge_disabled" ->
                    Boolean.toString(disabled(scopeActive, active, RestrictionTarget.SPEAR_LUNGE));
            case "elytra_gliding_allowed" -> Boolean.toString(
                    scopeActive && active.elytraGlidingAllowed());
            case "firework_boost_blocked" -> Boolean.toString(
                    scopeActive && active.fireworkBoostBlocked());
            case "cobweb_clear_time" -> DurationFormatter.words(live.config().cobwebs().clearAfter());
            case "inside_effective_scope" -> Boolean.toString(player instanceof Player online
                    && live.appliesAt(online.getLocation()));
            default -> null;
        };
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

    private boolean disabled(boolean scopeActive, WarzoneConfig.ActiveSet active,
                             RestrictionTarget target) {
        if (!scopeActive) return false;
        WarzoneConfig.Restriction restriction = active.restrictions().get(target);
        return restriction != null && restriction.mode() == RestrictionMode.DISABLED;
    }

    private long cooldownSeconds(boolean scopeActive, WarzoneConfig.ActiveSet active,
                                 RestrictionTarget target) {
        if (!scopeActive) return 0;
        WarzoneConfig.Restriction restriction = active.restrictions().get(target);
        return restriction != null && restriction.mode() == RestrictionMode.COOLDOWN
                ? restriction.cooldown().getSeconds() : 0;
    }

    private String joined(WarzoneConfig.ActiveSet active, RestrictionMode mode, boolean effects) {
        String value = active.restrictions().values().stream()
                .filter(restriction -> restriction.mode() == mode
                        && restriction.target().effectOnly() == effects)
                .sorted(Comparator.comparing(restriction -> restriction.target().id()))
                .map(restriction -> WarzoneMessageService.friendly(restriction.target())
                        + (mode == RestrictionMode.COOLDOWN
                        ? " — " + DurationFormatter.words(restriction.cooldown()) : ""))
                .collect(java.util.stream.Collectors.joining(", "));
        return value.isEmpty() ? "None" : value;
    }

    private long count(WarzoneConfig.ActiveSet active, RestrictionMode mode, boolean effects) {
        return active.restrictions().values().stream()
                .filter(restriction -> restriction.mode() == mode
                        && restriction.target().effectOnly() == effects).count();
    }

    private String all(WarzoneConfig.ActiveSet active) {
        String value = active.restrictions().values().stream()
                .sorted(Comparator.comparing(restriction -> restriction.target().id()))
                .map(restriction -> WarzoneMessageService.friendly(restriction.target()) + " — "
                        + (restriction.mode() == RestrictionMode.DISABLED ? "disabled"
                        : DurationFormatter.words(restriction.cooldown()) + " cooldown"))
                .collect(java.util.stream.Collectors.joining(", "));
        return value.isEmpty() ? "None" : value;
    }

    private static RestrictionTarget target(String id) {
        return RestrictionTarget.parse(id).orElseThrow();
    }
}
