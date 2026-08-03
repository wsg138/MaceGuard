package com.lincoln.maceguard.warzone.message;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.config.WarzoneMessages;
import com.lincoln.maceguard.warzone.region.WarzoneRegionService;
import com.lincoln.maceguard.warzone.restriction.DenialThrottle;
import com.lincoln.maceguard.warzone.restriction.RestrictionDecision;
import com.lincoln.maceguard.warzone.restriction.RestrictionTarget;
import com.lincoln.maceguard.warzone.rotation.RotationManager;
import com.lincoln.maceguard.warzone.util.DurationFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

public final class WarzoneMessageService {
    private final MiniMessage mini = MiniMessage.miniMessage();
    private final PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
    private final DenialThrottle denialThrottle = new DenialThrottle();
    private final Clock clock;
    private final WarzoneRegionService region;
    private WarzoneConfig config;
    private WarzoneMessages templates;
    private RotationManager rotations;

    public WarzoneMessageService(Clock clock, WarzoneRegionService region, WarzoneConfig config,
                                 WarzoneMessages templates) {
        this.clock = clock;
        this.region = region;
        this.config = config;
        this.templates = templates;
    }

    public void bind(RotationManager rotations) { this.rotations = rotations; }

    public void apply(WarzoneConfig config, WarzoneMessages templates) {
        this.config = config;
        this.templates = templates;
        denialThrottle.clear();
    }

    public void denial(Player player, RestrictionDecision decision) {
        RestrictionTarget target = decision.target();
        if (target == null) return;
        if (!denialThrottle.acquire(player.getUniqueId(), target, clock.millis(),
                config.messages().blockedMessageCooldown())) return;
        String template;
        if (target.effectOnly()) {
            template = decision.result() == RestrictionDecision.Result.DISABLED
                    ? templates.abilityDisabled() : templates.abilityCooldown();
        } else {
            template = decision.result() == RestrictionDecision.Result.DISABLED
                    ? templates.itemDisabled() : templates.itemCooldown();
        }
        player.sendMessage(render(template, target, decision.remaining()));
    }

    public void cobwebUnavailable(Player player) {
        RestrictionTarget target = RestrictionTarget.parse("COBWEB").orElseThrow();
        if (!denialThrottle.acquire(player.getUniqueId(), target, clock.millis(),
                config.messages().blockedMessageCooldown())) return;
        player.sendMessage(render(templates.cobwebUnavailable(), target, Duration.ZERO));
    }

    public void elytraUnavailable(Player player) {
        player.sendMessage(mini.deserialize("<red>Elytra gliding is not active in the warzone this week."));
    }

    public void rocketUnavailable(Player player) {
        player.sendMessage(mini.deserialize("<red>Firework boosting is disabled in the warzone this week."));
    }

    public void broadcast(String template, WarzoneConfig.Audience audience) {
        Component component = render(template, null, Duration.ZERO);
        Bukkit.getOnlinePlayers().stream()
                .filter(player -> audience == WarzoneConfig.Audience.GLOBAL
                        || region.contains(player.getLocation()))
                .forEach(player -> player.sendMessage(component));
        Bukkit.getConsoleSender().sendMessage(component);
    }

    public void send(CommandSender sender, String template) {
        sender.sendMessage(render(template, null, Duration.ZERO));
    }

    public Component render(String template, RestrictionTarget target, Duration cooldownRemaining) {
        WarzoneConfig.ActiveSet active = rotations.active();
        return mini.deserialize(template,
                Placeholder.component("meta", mini.deserialize(active.displayName())),
                Placeholder.unparsed("meta_id", active.id()),
                Placeholder.unparsed("modifiers", String.join(", ", active.modifierIds())),
                Placeholder.unparsed("item", friendly(target)),
                Placeholder.unparsed("ability",
                        target == RestrictionTarget.SPEAR_LUNGE ? "Spear Lunge" : friendly(target)),
                Placeholder.unparsed("time_left", DurationFormatter.words(rotations.remaining())),
                Placeholder.unparsed("changes_at", formatInstant(rotations.state().transitionAtMillis())),
                Placeholder.unparsed("next_meta", "Random weekly selection"),
                Placeholder.unparsed("next_meta_id", "unselected"),
                Placeholder.unparsed("cooldown_remaining", precise(cooldownRemaining)),
                Placeholder.unparsed("cooldown",
                        cooldownRemaining.isZero() ? "0s" : DurationFormatter.words(cooldownRemaining)),
                Placeholder.unparsed("cobweb_clear_time",
                        DurationFormatter.words(config.cobwebs().clearAfter())));
    }

    public String plain(String miniMessage) { return plain.serialize(mini.deserialize(miniMessage)); }

    public String formatInstant(long epochMillis) {
        return DateTimeFormatter.ofPattern("EEE, MMM d h:mm a z")
                .withZone(config.schedule().timezone())
                .format(Instant.ofEpochMilli(epochMillis));
    }

    public String rotationWarning() { return templates.rotationWarning(); }

    public void cleanup() {
        denialThrottle.discardOlderThan(clock.millis() - Math.max(1_000L,
                config.messages().blockedMessageCooldown().toMillis()));
    }

    public static String friendly(RestrictionTarget target) {
        if (target == null) return "item";
        if (target == RestrictionTarget.SPEAR_LUNGE) return "Spear Lunge";
        if (target == RestrictionTarget.SPEAR) return "Spear";
        return friendly(target.material());
    }

    public static String friendly(Material material) {
        if (material == null) return "Item";
        String[] parts = material.name().toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return result.toString();
    }

    private String precise(Duration duration) {
        if (duration.isZero() || duration.isNegative()) return "0s";
        long millis = duration.toMillis();
        if (millis >= 10_000 || millis % 1_000 == 0)
            return DurationFormatter.words(Duration.ofSeconds((millis + 999) / 1_000));
        return "%.1fs".formatted(java.util.Locale.ROOT, Math.ceil(millis / 100.0D) / 10.0D);
    }
}
