package com.lincoln.maceguard.warzone.restriction;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import org.bukkit.Material;

import java.time.Duration;
import java.util.Comparator;
import java.util.UUID;
import java.util.function.Supplier;

public final class RestrictionService {
    private final Supplier<WarzoneConfig.ActiveSet> activeSet;
    private final CooldownService cooldowns;

    public RestrictionService(Supplier<WarzoneConfig.ActiveSet> activeSet, CooldownService cooldowns) {
        this.activeSet = activeSet;
        this.cooldowns = cooldowns;
    }

    public RestrictionDecision material(UUID playerId, Material material, boolean bypass,
                                        boolean actorInside, boolean targetInside) {
        if (bypass || (!actorInside && !targetInside)) return RestrictionDecision.unrestricted();
        WarzoneConfig.Restriction restriction = activeSet.get().restrictions().values().stream()
                .filter(value -> !value.target().effectOnly() && value.target().matches(material))
                .sorted(Comparator.comparingInt(value ->
                        value.target().kind() == RestrictionTarget.Kind.MATERIAL ? 0 : 1))
                .findFirst().orElse(null);
        return decide(playerId, restriction);
    }

    public RestrictionDecision lunge(UUID playerId, boolean bypass, boolean actorInside, boolean targetInside) {
        if (bypass || (!actorInside && !targetInside)) return RestrictionDecision.unrestricted();
        return decide(playerId, activeSet.get().restrictions().get(RestrictionTarget.SPEAR_LUNGE));
    }

    public void success(UUID playerId, RestrictionDecision decision) {
        if (decision.startsCooldownAfterSuccess())
            cooldowns.start(playerId, decision.target(), decision.restriction().cooldown());
    }

    private RestrictionDecision decide(UUID playerId, WarzoneConfig.Restriction restriction) {
        if (restriction == null) return RestrictionDecision.unrestricted();
        if (restriction.mode() == RestrictionMode.DISABLED)
            return new RestrictionDecision(RestrictionDecision.Result.DISABLED,
                    restriction.target(), restriction, Duration.ZERO);
        Duration remaining = cooldowns.remaining(playerId, restriction.target());
        return new RestrictionDecision(remaining.isZero() ? RestrictionDecision.Result.COOLDOWN_READY
                : RestrictionDecision.Result.COOLDOWN_ACTIVE, restriction.target(), restriction, remaining);
    }
}
