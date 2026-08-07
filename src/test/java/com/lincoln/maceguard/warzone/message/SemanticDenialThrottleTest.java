package com.lincoln.maceguard.warzone.message;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.config.WarzoneControlConfig;
import com.lincoln.maceguard.warzone.config.WarzoneMessages;
import com.lincoln.maceguard.warzone.region.WarzoneRegionService;
import com.lincoln.maceguard.warzone.restriction.RestrictionDecision;
import com.lincoln.maceguard.warzone.restriction.RestrictionMode;
import com.lincoln.maceguard.warzone.restriction.RestrictionTarget;
import com.lincoln.maceguard.warzone.rotation.RepeatingSchedule;
import com.lincoln.maceguard.warzone.rotation.RotationManager;
import com.lincoln.maceguard.warzone.rotation.RotationState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class SemanticDenialThrottleTest {
    private final Player player = mock(Player.class);
    private WarzoneMessageService messages;

    @BeforeEach void setUp() {
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        WarzoneConfig config = mock(WarzoneConfig.class);
        when(config.messages()).thenReturn(new WarzoneConfig.Messages(Duration.ofSeconds(1),
                WarzoneConfig.Audience.GLOBAL, WarzoneConfig.Audience.GLOBAL));
        when(config.cobwebs()).thenReturn(new WarzoneConfig.Cobwebs(
                Duration.ofSeconds(60), true, true));
        when(config.schedule()).thenReturn(new WarzoneConfig.Schedule(DayOfWeek.SUNDAY,
                LocalTime.of(4, 0), ZoneId.of("America/Indiana/Indianapolis")));
        messages = new WarzoneMessageService(Clock.systemUTC(),
                mock(WarzoneRegionService.class), config, templates());
        RotationManager rotations = mock(RotationManager.class);
        when(rotations.active()).thenReturn(new WarzoneConfig.ActiveSet(List.of("test"),
                "Test", "test", Set.of(), Map.of()));
        when(rotations.remaining()).thenReturn(Duration.ofMinutes(5));
        RotationState state = mock(RotationState.class);
        when(state.transitionAtMillis()).thenReturn(System.currentTimeMillis() + 300_000L);
        when(rotations.state()).thenReturn(state);
        RepeatingSchedule.Slot slot = mock(RepeatingSchedule.Slot.class);
        WarzoneControlConfig.Entry entry = WarzoneControlConfig.Entry.random();
        when(slot.entry()).thenReturn(entry);
        when(rotations.nextSlot()).thenReturn(slot);
        when(rotations.entryName(entry)).thenReturn("Random");
        messages.bind(rotations);
    }

    @Test void placeAndBreakOfSameMaterialHaveIndependentChannels() {
        messages.blockPlaceDenied(player, Material.STONE);
        messages.blockBreakDenied(player, Material.STONE);
        assertEquals(List.of("place", "break"), allMessages());
    }

    @Test void bucketEmptyAndFillHaveIndependentChannels() {
        messages.bucketEmptyDenied(player, Material.WATER);
        messages.bucketFillDenied(player, Material.WATER);
        assertEquals(List.of("bucket", "bucket"), allMessages());
    }

    @Test void policyAndWarzoneCobwebDenialsHaveIndependentChannels() {
        messages.blockPlaceDenied(player, Material.COBWEB);
        messages.cobwebUnavailable(player);
        assertEquals(List.of("place", "cobweb"), allMessages());
    }

    @Test void ordinaryRocketRestrictionAndCombatBoostHaveIndependentChannels() {
        RestrictionTarget target = RestrictionTarget.parse("FIREWORK_ROCKET").orElseThrow();
        RestrictionDecision active = new RestrictionDecision(
                RestrictionDecision.Result.COOLDOWN_ACTIVE, target,
                new WarzoneConfig.Restriction(target, RestrictionMode.COOLDOWN,
                        Duration.ofSeconds(10)), Duration.ofSeconds(8));
        messages.denial(player, active, Material.FIREWORK_ROCKET);
        messages.rocketUnavailable(player);
        assertEquals(List.of("cooldown", "rocket"), allMessages());
    }

    private List<String> allMessages() {
        var captor = org.mockito.ArgumentCaptor.forClass(Component.class);
        verify(player, atLeastOnce()).sendMessage(captor.capture());
        return captor.getAllValues().stream()
                .map(PlainTextComponentSerializer.plainText()::serialize).toList();
    }

    private WarzoneMessages templates() {
        return new WarzoneMessages("disabled", "cooldown", "started",
                "ability-disabled", "ability-cooldown", "ability-started",
                "cobweb", "elytra", "rocket", "place", "break", "bucket",
                "stasis", "warning");
    }
}
