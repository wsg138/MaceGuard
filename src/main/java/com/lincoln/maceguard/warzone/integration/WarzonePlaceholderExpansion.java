package com.lincoln.maceguard.warzone.integration;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.message.WarzoneMessageService;
import com.lincoln.maceguard.warzone.restriction.RestrictionMode;
import com.lincoln.maceguard.warzone.runtime.WarzoneRuntime;
import com.lincoln.maceguard.warzone.util.DurationFormatter;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.util.Comparator;
import java.util.function.Supplier;

public final class WarzonePlaceholderExpansion extends PlaceholderExpansion implements WarzonePlaceholderHook {
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
        var next = live.rotations().next();
        var remaining = live.rotations().remaining();
        return switch (params.toLowerCase(java.util.Locale.ROOT)) {
            case "current_meta" -> live.messages().plain(active.displayName());
            case "current_meta_id" -> active.id();
            case "description" -> live.messages().plain(active.description());
            case "time_left" -> DurationFormatter.clock(remaining);
            case "time_left_words" -> DurationFormatter.words(remaining);
            case "time_left_seconds" -> Long.toString(Math.max(0, remaining.getSeconds()));
            case "changes_at" -> live.messages().formatInstant(live.rotations().state().endsAtMillis());
            case "next_meta" -> live.messages().plain(next.displayName());
            case "next_meta_id" -> next.id();
            case "disabled_items" -> joined(active, RestrictionMode.DISABLED, false);
            case "disabled_items_count" -> Long.toString(count(active, RestrictionMode.DISABLED, false));
            case "cooldown_items" -> joined(active, RestrictionMode.COOLDOWN, false);
            case "cooldown_items_count" -> Long.toString(count(active, RestrictionMode.COOLDOWN, false));
            case "restrictions" -> all(active);
            case "cobwebs_allowed" -> Boolean.toString(active.cobwebsAllowed());
            case "cobweb_clear_time" -> DurationFormatter.words(live.config().cobwebs().clearAfter());
            default -> null;
        };
    }

    private String joined(WarzoneConfig.Rotation rotation, RestrictionMode mode, boolean effects) {
        String value = rotation.restrictions().values().stream()
                .filter(restriction -> restriction.mode() == mode && restriction.target().effectOnly() == effects)
                .sorted(Comparator.comparing(restriction -> restriction.target().id()))
                .map(restriction -> WarzoneMessageService.friendly(restriction.target())
                        + (mode == RestrictionMode.COOLDOWN ? " — " + DurationFormatter.words(restriction.cooldown()) : ""))
                .collect(java.util.stream.Collectors.joining(", "));
        return value.isEmpty() ? "None" : value;
    }

    private long count(WarzoneConfig.Rotation rotation, RestrictionMode mode, boolean effects) {
        return rotation.restrictions().values().stream()
                .filter(restriction -> restriction.mode() == mode && restriction.target().effectOnly() == effects).count();
    }

    private String all(WarzoneConfig.Rotation rotation) {
        String value = rotation.restrictions().values().stream()
                .sorted(Comparator.comparing(restriction -> restriction.target().id()))
                .map(restriction -> WarzoneMessageService.friendly(restriction.target()) + " — "
                        + (restriction.mode() == RestrictionMode.DISABLED ? "disabled"
                        : DurationFormatter.words(restriction.cooldown()) + " cooldown"))
                .collect(java.util.stream.Collectors.joining(", "));
        return value.isEmpty() ? "None" : value;
    }
}
