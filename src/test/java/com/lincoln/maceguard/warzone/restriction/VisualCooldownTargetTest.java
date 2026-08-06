package com.lincoln.maceguard.warzone.restriction;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VisualCooldownTargetTest {
    @Test void wholeSpearCooldownAppliesOnlyToExactSuccessfulMaterial() {
        Player player = player();
        VisualCooldownService service = new VisualCooldownService(mock(Server.class),
                () -> 1_000L, () -> 0L);
        service.apply(player, ready(RestrictionTarget.SPEAR), Material.IRON_SPEAR);
        verify(player).setCooldown(Material.IRON_SPEAR, 200);
        verify(player, never()).setCooldown(Material.WOODEN_SPEAR, 200);
    }

    @Test void spearDamageAndLungeDoNotShadeTheWholeItem() {
        Player player = player();
        VisualCooldownService service = new VisualCooldownService(mock(Server.class),
                () -> 1_000L, () -> 0L);
        service.apply(player, ready(RestrictionTarget.SPEAR_DAMAGE), Material.IRON_SPEAR);
        service.apply(player, ready(RestrictionTarget.SPEAR_LUNGE), Material.IRON_SPEAR);
        verify(player, never()).setCooldown(any(Material.class), anyInt());
    }

    @Test void exactSpearOwnershipTransfersAndRemovedMaterialClearsSafely() {
        AtomicInteger cooldown = new AtomicInteger();
        Player player = player(cooldown);
        VisualCooldownService service = new VisualCooldownService(mock(Server.class),
                () -> 1_000L, () -> 0L);
        service.reapplyMaterials(player, Map.of(Material.GOLDEN_SPEAR, Duration.ofSeconds(10)));
        assertEquals(200, cooldown.get());
        service.reapplyMaterials(player, Map.of());
        assertEquals(0, cooldown.get());
    }

    private RestrictionDecision ready(RestrictionTarget target) {
        return new RestrictionDecision(RestrictionDecision.Result.COOLDOWN_READY, target,
                new WarzoneConfig.Restriction(target, RestrictionMode.COOLDOWN,
                        Duration.ofSeconds(10)), Duration.ZERO);
    }

    private Player player() { return player(new AtomicInteger()); }

    private Player player(AtomicInteger cooldown) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getCooldown(any(Material.class))).thenAnswer(ignored -> cooldown.get());
        doAnswer(invocation -> {
            cooldown.set(invocation.getArgument(1));
            return null;
        }).when(player).setCooldown(any(Material.class), anyInt());
        return player;
    }
}
