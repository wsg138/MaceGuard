package com.lincoln.maceguard.warzone.integration;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.message.WarzoneMessageService;
import com.lincoln.maceguard.warzone.restriction.RestrictionMode;
import com.lincoln.maceguard.warzone.runtime.WarzoneRuntime;
import com.lincoln.maceguard.warzone.util.DurationFormatter;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

public final class WarzonePlaceholderExpansion extends PlaceholderExpansion
        implements WarzonePlaceholderHook {
    private static final Set<String> SUPPORTED_PARAMETERS = Set.of(
            "current_meta", "current_modifiers", "current_meta_id", "current_modifier_ids",
            "description", "time_left", "time_left_words", "time_left_seconds", "changes_at",
            "next_meta", "next_meta_id", "source_type", "active_kit", "override_active",
            "override_mode", "override_ends_at", "override_time_left", "schedule_slot",
            "schedule_cycle_position", "next_source_type", "next_name", "next_changes_at",
            "disabled_items", "disabled_items_count", "cooldown_items", "cooldown_items_count",
            "restrictions", "gameplay_scope_active", "cobwebs_allowed", "cobweb_clear_time",
            "inside_effective_scope", "mace_status", "mace_disabled", "mace_cooldown_seconds",
            "ender_pearl_status", "ender_pearl_disabled", "ender_pearl_cooldown_seconds",
            "wind_charge_status", "wind_charge_disabled", "wind_charge_cooldown_seconds",
            "spear_status", "spear_disabled", "spear_damage_status",
            "spear_damage_cooldown_seconds", "spear_lunge_status", "spear_lunge_disabled",
            "spear_lunge_cooldown_seconds", "elytra_status", "elytra_gliding_allowed",
            "firework_boost_blocked", "modifier_1", "modifier_1_id", "modifier_1_description",
            "modifier_2", "modifier_2_id", "modifier_2_description", "modifier_3",
            "modifier_3_id", "modifier_3_description");

    private final Plugin plugin;
    private final Supplier<WarzoneRuntime> runtime;

    public WarzonePlaceholderExpansion(Plugin plugin, Supplier<WarzoneRuntime> runtime) {
        this.plugin = plugin;
        this.runtime = runtime;
    }

    static Set<String> supportedParameters() { return SUPPORTED_PARAMETERS; }

    @Override public String getIdentifier() { return "warzone"; }
    @Override public String getAuthor() { return "P2wn"; }
    @Override public String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }
    @Override public boolean active() { return isRegistered(); }
    @Override public void close() { if (isRegistered()) unregister(); }

    @Override public String onRequest(OfflinePlayer player, String params) {
        String parameter = params.toLowerCase(Locale.ROOT);
        if (!SUPPORTED_PARAMETERS.contains(parameter)) return null;
        WarzoneRuntime live = runtime.get();
        if (live == null) return "";
        var active = live.rotations().active();
        var remaining = live.rotations().remaining();
        boolean scopeActive = live.gameplayScopeActive();
        String modifierValue = WarzoneStatusValues.resolveModifier(
                parameter, live.config().modifiers(), active);
        if (modifierValue != null) {
            return parameter.endsWith("_id")
                    ? modifierValue : live.messages().plain(modifierValue);
        }
        String statusValue = WarzoneStatusValues.resolve(parameter, scopeActive, active);
        if (statusValue != null) return statusValue;
        return switch (parameter) {
            case "current_meta", "current_modifiers" -> live.messages().plain(active.displayName());
            case "current_meta_id", "current_modifier_ids" -> active.id();
            case "description" -> live.messages().plain(active.description());
            case "time_left" -> DurationFormatter.clock(remaining);
            case "time_left_words" -> DurationFormatter.words(remaining);
            case "time_left_seconds" -> Long.toString(Math.max(0, remaining.getSeconds()));
            case "changes_at" -> (live.rotations().state().overrideActive()
                    && live.rotations().state().overrideExpiresAtMillis() == 0)
                    || (!live.rotations().state().overrideActive()
                    && !live.rotations().scheduleEnabled()) ? ""
                    : live.messages().formatInstant(live.rotations().nextEffectiveTransitionMillis());
            case "next_meta" -> live.rotations().scheduleEnabled()
                    ? live.rotations().entryName(live.rotations().nextSlot().entry()) : "";
            case "next_meta_id" -> live.rotations().scheduleEnabled() ? nextId(live) : "";
            case "source_type" -> live.rotations().activeSelection().sourceType().name();
            case "active_kit" -> live.rotations().activeSelection().sourceType()
                    == com.lincoln.maceguard.warzone.rotation.SelectionSourceType.KIT
                    ? value(live.rotations().activeSelection().sourceId()) : "";
            case "override_active" -> Boolean.toString(live.rotations().state().overrideActive());
            case "override_mode" -> live.rotations().state().overrideDurationMode() == null ? ""
                    : live.rotations().state().overrideDurationMode().name();
            case "override_ends_at" -> live.rotations().state().overrideExpiresAtMillis() <= 0 ? ""
                    : live.messages().formatInstant(live.rotations().state().overrideExpiresAtMillis());
            case "override_time_left" -> overrideTimeLeft(live);
            case "schedule_slot" -> live.rotations().state().automaticSlotIdentity();
            case "schedule_cycle_position" -> Integer.toString(
                    live.rotations().state().currentCycleIndex() + 1);
            case "next_source_type" -> live.rotations().scheduleEnabled() ? nextSource(live) : "";
            case "next_name" -> live.rotations().scheduleEnabled()
                    ? live.rotations().entryName(live.rotations().nextSlot().entry()) : "";
            case "next_changes_at" -> live.rotations().scheduleEnabled()
                    ? live.messages().formatInstant(live.rotations().state().automaticSlotEndMillis()) : "";
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
            case "cobweb_clear_time" -> DurationFormatter.words(live.config().cobwebs().clearAfter());
            case "inside_effective_scope" -> Boolean.toString(player instanceof Player online
                    && live.appliesAt(online.getLocation()));
            default -> null;
        };
    }

    private String overrideTimeLeft(WarzoneRuntime live) {
        long expires = live.rotations().state().overrideExpiresAtMillis();
        if (expires <= 0) return "";
        return DurationFormatter.clock(java.time.Duration.ofMillis(
                Math.max(0, expires - live.rotations().nowMillis())));
    }

    private String nextSource(WarzoneRuntime live) {
        return switch (live.rotations().nextSlot().entry().type()) {
            case RANDOM -> "RANDOM";
            case KIT -> "KIT";
            case MODIFIERS -> "SCHEDULED_MODIFIERS";
            case NONE -> "NONE";
        };
    }

    private String nextId(WarzoneRuntime live) {
        var entry = live.rotations().nextSlot().entry();
        return entry.type() == com.lincoln.maceguard.warzone.config.WarzoneControlConfig.EntryType.KIT
                ? value(entry.kitId()) : entry.type() == com.lincoln.maceguard.warzone.config.WarzoneControlConfig.EntryType.MODIFIERS
                ? String.join("+", entry.modifierIds()) : entry.type().name().toLowerCase(Locale.ROOT);
    }

    private String value(String value) { return value == null ? "" : value; }

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
}
