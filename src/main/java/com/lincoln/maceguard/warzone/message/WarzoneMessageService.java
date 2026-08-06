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

import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Central player-facing feedback for all Warzone restrictions and cooldowns. */
public final class WarzoneMessageService {
    private static final int EQUAL = 0;
    private static final long ZERO = 0L;
    private static final long ONE = 1L;
    private static final long TENTHS_PER_SECOND = 10L;
    private static final long DECIMAL_SECONDS_LIMIT_TENTHS = 100L;
    private static final int NANOS_PER_TENTH = 100_000_000;
    private static final Duration MINIMUM_THROTTLE_RETENTION = Duration.ofSeconds(1);

    private final MiniMessage mini = MiniMessage.miniMessage();
    private final PlainTextComponentSerializer plain =
            PlainTextComponentSerializer.plainText();
    private final DenialThrottle denialThrottle = new DenialThrottle();
    private final Clock clock;
    private final WarzoneRegionService region;
    private WarzoneConfig config;
    private WarzoneMessages templates;
    private RotationManager rotations;

    public WarzoneMessageService(Clock clock, WarzoneRegionService region,
                                 WarzoneConfig config, WarzoneMessages templates) {
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
        denial(player, decision, null);
    }

    public void denial(Player player, RestrictionDecision decision, Material actualMaterial) {
        RestrictionTarget target = decision.target();
        if (target == null || !decision.denied() || !acquire(player, target)) return;
        boolean ability = target.effectOnly();
        String template = decision.result() == RestrictionDecision.Result.DISABLED
                ? ability ? templates.abilityDisabled() : templates.itemDisabled()
                : ability ? templates.abilityCooldown() : templates.itemCooldown();
        player.sendMessage(render(template, target, actualMaterial,
                totalCooldown(decision), decision.remaining()));
    }

    /** Sent exactly once by a finalized successful-action path after the authoritative cooldown starts. */
    public void cooldownStarted(Player player, RestrictionDecision decision, Material actualMaterial) {
        RestrictionTarget target = decision.target();
        if (target == null || !decision.startsCooldownAfterSuccess()
                || decision.restriction() == null) return;
        String template = target.effectOnly()
                ? templates.abilityCooldownStarted() : templates.itemCooldownStarted();
        Duration total = decision.restriction().cooldown();
        player.sendMessage(render(template, target, actualMaterial, total, total));
    }

    public void cobwebUnavailable(Player player) {
        RestrictionTarget target = RestrictionTarget.parse("COBWEB").orElseThrow();
        if (!acquire(player, target)) return;
        player.sendMessage(render(templates.cobwebUnavailable(), target, Material.COBWEB,
                Duration.ZERO, Duration.ZERO));
    }

    public void elytraUnavailable(Player player) {
        RestrictionTarget target = RestrictionTarget.parse("ELYTRA").orElseThrow();
        if (!acquire(player, target)) return;
        player.sendMessage(render(templates.elytraUnavailable(), target, Material.ELYTRA,
                Duration.ZERO, Duration.ZERO));
    }

    public void rocketUnavailable(Player player) {
        RestrictionTarget target = RestrictionTarget.parse("FIREWORK_ROCKET").orElseThrow();
        if (!acquire(player, target)) return;
        player.sendMessage(render(templates.fireworkUnavailable(), target, Material.FIREWORK_ROCKET,
                Duration.ZERO, Duration.ZERO));
    }

    public void blockPlaceDenied(Player player, Material material) {
        policyDenied(player, material, templates.blockPlaceDenied());
    }

    public void blockBreakDenied(Player player, Material material) {
        policyDenied(player, material, templates.blockBreakDenied());
    }

    public void bucketUseDenied(Player player, Material material) {
        policyDenied(player, material, templates.bucketUseDenied());
    }

    private void policyDenied(Player player, Material material, String template) {
        if (material == null) {
            player.sendMessage(render(template, null, null, Duration.ZERO, Duration.ZERO));
            return;
        }
        RestrictionTarget target = RestrictionTarget.parse(material.name()).orElseThrow();
        if (!acquire(player, target)) return;
        player.sendMessage(render(template, target, material, Duration.ZERO, Duration.ZERO));
    }

    public void stasisBlocked(Player player) {
        RestrictionTarget target = RestrictionTarget.parse("ENDER_PEARL").orElseThrow();
        if (!acquire(player, target)) return;
        player.sendMessage(render(templates.stasisBlocked(), target, Material.ENDER_PEARL,
                Duration.ZERO, Duration.ZERO));
    }

    private boolean acquire(Player player, RestrictionTarget target) {
        return denialThrottle.acquire(player.getUniqueId(), target, clock.millis(),
                config.messages().blockedMessageCooldown());
    }

    public void broadcast(String template, WarzoneConfig.Audience audience) {
        Component component = render(template, null, null, Duration.ZERO, Duration.ZERO);
        Bukkit.getOnlinePlayers().stream()
                .filter(player -> audience == WarzoneConfig.Audience.GLOBAL
                        || region.contains(player.getLocation()))
                .forEach(player -> player.sendMessage(component));
        Bukkit.getConsoleSender().sendMessage(component);
    }

    public void send(CommandSender sender, String template) {
        sender.sendMessage(render(template, null, null, Duration.ZERO, Duration.ZERO));
    }

    public Component render(String template, RestrictionTarget target,
                            Duration cooldownRemaining) {
        return render(template, target, null, cooldownRemaining, cooldownRemaining);
    }

    public Component render(String template, RestrictionTarget target, Material actualMaterial,
                            Duration cooldown, Duration cooldownRemaining) {
        WarzoneConfig.ActiveSet active = rotations.active();
        FeedbackText feedback = feedback(target, actualMaterial);
        return mini.deserialize(template,
                Placeholder.component("meta", mini.deserialize(active.displayName())),
                Placeholder.unparsed("meta_id", active.id()),
                Placeholder.unparsed("modifiers", String.join(", ", active.modifierIds())),
                Placeholder.unparsed("item", feedback.item()),
                Placeholder.unparsed("ability", feedback.ability()),
                Placeholder.unparsed("action", feedback.action()),
                Placeholder.unparsed("ready_action", feedback.readyAction()),
                Placeholder.unparsed("time_left", DurationFormatter.words(rotations.remaining())),
                Placeholder.unparsed("changes_at",
                        formatInstant(rotations.state().transitionAtMillis())),
                Placeholder.unparsed("next_meta", rotations.entryName(rotations.nextSlot().entry())),
                Placeholder.unparsed("next_meta_id", rotations.nextSlot().entry().type().name()),
                Placeholder.unparsed("cooldown_remaining", playerDuration(cooldownRemaining)),
                Placeholder.unparsed("cooldown", playerDuration(cooldown)),
                Placeholder.unparsed("cobweb_clear_time",
                        DurationFormatter.words(config.cobwebs().clearAfter())));
    }

    public String plain(String miniMessage) {
        return plain.serialize(mini.deserialize(miniMessage));
    }

    public String formatInstant(long epochMillis) {
        return DateTimeFormatter.ofPattern("EEE, MMM d h:mm a z")
                .withZone(config.schedule().timezone())
                .format(Instant.ofEpochMilli(epochMillis));
    }

    public String rotationWarning() { return templates.rotationWarning(); }

    public void cleanup() {
        Duration configured = config.messages().blockedMessageCooldown();
        Duration retention = configured == null
                || configured.compareTo(MINIMUM_THROTTLE_RETENTION) < EQUAL
                ? MINIMUM_THROTTLE_RETENTION : configured;
        denialThrottle.discardOutsideWindow(clock.millis(), retention);
    }

    public static String friendly(RestrictionTarget target) {
        if (target == null) return "Item";
        if (target == RestrictionTarget.SPEAR_LUNGE) return "Spear Lunge";
        if (target == RestrictionTarget.SPEAR_DAMAGE) return "Spear Damage";
        if (target == RestrictionTarget.SPEAR) return "Spear";
        return friendly(target.material());
    }

    public static String friendly(Material material) {
        if (material == null) return "Item";
        String[] parts = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return result.toString();
    }

    /** Readable, deterministic and rounded upward so an active denial never displays zero. */
    public static String playerDuration(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) return "0 seconds";
        long secondsPart = duration.getSeconds();
        int nanosPart = duration.getNano();
        if (secondsPart < TENTHS_PER_SECOND) {
            long tenths = secondsPart * TENTHS_PER_SECOND
                    + divideCeil(nanosPart, NANOS_PER_TENTH);
            tenths = Math.max(ONE, tenths);
            if (tenths < DECIMAL_SECONDS_LIMIT_TENTHS
                    && tenths % TENTHS_PER_SECOND != ZERO)
                return "%d.%d seconds".formatted(tenths / TENTHS_PER_SECOND,
                        tenths % TENTHS_PER_SECOND);
            if (tenths < DECIMAL_SECONDS_LIMIT_TENTHS)
                return tenths == TENTHS_PER_SECOND ? "1 second"
                        : tenths / TENTHS_PER_SECOND + " seconds";
        }
        BigInteger roundedSeconds = BigInteger.valueOf(secondsPart);
        if (nanosPart != ZERO) roundedSeconds = roundedSeconds.add(BigInteger.ONE);
        return roundedSeconds.equals(BigInteger.ONE) ? "1 second"
                : roundedSeconds + " seconds";
    }

    private Duration totalCooldown(RestrictionDecision decision) {
        return decision.restriction() == null || decision.restriction().cooldown() == null
                ? Duration.ZERO : decision.restriction().cooldown();
    }

    private FeedbackText feedback(RestrictionTarget target, Material actualMaterial) {
        if (target == null) return genericFeedback();
        return switch (target.kind()) {
            case SPEAR_DAMAGE -> new FeedbackText("Spear", "Spear Damage",
                    "dealing Spear damage again", "You can deal Spear damage again");
            case SPEAR_LUNGE -> new FeedbackText("Spear", "Spear Lunge", "Lunging again",
                    "You can Lunge again");
            case SPEAR_GROUP -> new FeedbackText("Spear", "Spear", "using your Spear again",
                    "You can use your Spear again");
            case MATERIAL -> materialFeedback(
                    actualMaterial != null ? actualMaterial : target.material());
        };
    }

    private FeedbackText materialFeedback(Material material) {
        String item = friendly(material);
        return switch (material) {
            case ENDER_PEARL -> new FeedbackText(item, item,
                    "throwing another Ender Pearl", "You can throw another Ender Pearl");
            case WIND_CHARGE -> new FeedbackText(item, item,
                    "using another Wind Charge", "You can use another Wind Charge");
            case MACE -> new FeedbackText(item, item, "using your Mace again",
                    "You can use your Mace again");
            default -> new FeedbackText(item, item, "using " + item + " again",
                    "You can use " + item + " again");
        };
    }

    private FeedbackText genericFeedback() {
        return new FeedbackText("Item", "Ability", "using this item again",
                "You can use this item again");
    }

    private static long divideCeil(long value, long divisor) {
        if (value <= ZERO) return ZERO;
        return ONE + (value - ONE) / divisor;
    }

    private record FeedbackText(String item, String ability, String action, String readyAction) { }
}
