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
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WarzoneMessageServiceTest {
    private static final String MACE_TARGET = "MACE";
    private static final String PEARL_TARGET = "ENDER_PEARL";

    private final MutableClock clock = new MutableClock(1_000L);
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
        messages = new WarzoneMessageService(clock, mock(WarzoneRegionService.class),
                config, templates());

        RotationManager rotations = mock(RotationManager.class);
        when(rotations.active()).thenReturn(new WarzoneConfig.ActiveSet(List.of("no-mace"),
                "<white>No Mace", "test", Set.of(), Map.of()));
        when(rotations.remaining()).thenReturn(Duration.ofMinutes(5));
        RotationState state = mock(RotationState.class);
        when(state.transitionAtMillis()).thenReturn(301_000L);
        when(rotations.state()).thenReturn(state);
        RepeatingSchedule.Slot slot = mock(RepeatingSchedule.Slot.class);
        WarzoneControlConfig.Entry entry = WarzoneControlConfig.Entry.random();
        when(slot.entry()).thenReturn(entry);
        when(rotations.nextSlot()).thenReturn(slot);
        when(rotations.entryName(entry)).thenReturn("Random");
        messages.bind(rotations);
    }

    @Test void unrestrictedActionProducesNoMessage() {
        messages.denial(player, RestrictionDecision.unrestricted());
        verify(player, never()).sendMessage(any(Component.class));
    }

    @Test void disabledItemSendsOneClearMessage() {
        messages.denial(player, decision(target(MACE_TARGET), RestrictionMode.DISABLED,
                RestrictionDecision.Result.DISABLED, Duration.ZERO, Duration.ZERO), Material.MACE);
        assertEquals("Mace is disabled during No Mace.", onlyMessage());
    }

    @Test void activeCooldownUsesAuthoritativeRoundedUpRemainingTime() {
        messages.denial(player, decision(target(PEARL_TARGET), RestrictionMode.COOLDOWN,
                RestrictionDecision.Result.COOLDOWN_ACTIVE, Duration.ofSeconds(5),
                Duration.ofMillis(3_301)), Material.ENDER_PEARL);
        assertEquals("You must wait 3.4 seconds before throwing another Ender Pearl.",
                onlyMessage());
    }

    @Test void successfulCooldownStartUsesTargetSpecificWordingExactlyOnce() {
        RestrictionDecision decision = decision(target("WIND_CHARGE"), RestrictionMode.COOLDOWN,
                RestrictionDecision.Result.COOLDOWN_READY, Duration.ofSeconds(10), Duration.ZERO);
        messages.cooldownStarted(player, decision, Material.WIND_CHARGE);
        assertEquals("You can use another Wind Charge in 10 seconds.", onlyMessage());
    }

    @Test void maceSpearDamageAndLungeUseIndependentActionLanguage() {
        messages.cooldownStarted(player, ready(target(MACE_TARGET)), Material.MACE);
        messages.cooldownStarted(player, ready(RestrictionTarget.SPEAR_DAMAGE), null);
        messages.cooldownStarted(player, ready(RestrictionTarget.SPEAR_LUNGE), null);
        assertEquals(List.of(
                "You can use your Mace again in 10 seconds.",
                "You can deal Spear damage again in 10 seconds.",
                "You can Lunge again in 10 seconds."), allMessages());
    }

    @Test void wholeSpearAndGenericMaterialKeepDistinctFeedbackWording() {
        messages.cooldownStarted(player, ready(RestrictionTarget.SPEAR), Material.IRON_SPEAR);
        messages.cooldownStarted(player, ready(target("DIAMOND_SWORD")), Material.DIAMOND_SWORD);
        assertEquals(List.of(
                "You can use your Spear again in 10 seconds.",
                "You can use Diamond Sword again in 10 seconds."), allMessages());
    }

    @Test void nullFeedbackContextUsesSafeGenericPlaceholders() {
        Component rendered = messages.render(
                "<item>|<ability>|<action>|<ready_action>", null, null,
                Duration.ZERO, Duration.ZERO);
        assertEquals("Item|Ability|using this item again|You can use this item again",
                PlainTextComponentSerializer.plainText().serialize(rendered));
    }

    @Test void firstDenialAlwaysSendsAndRapidDuplicateIsBounded() {
        RestrictionDecision active = decision(target(MACE_TARGET), RestrictionMode.COOLDOWN,
                RestrictionDecision.Result.COOLDOWN_ACTIVE, Duration.ofSeconds(10),
                Duration.ofSeconds(8));
        messages.denial(player, active, Material.MACE);
        clock.advance(500L);
        messages.denial(player, active, Material.MACE);
        clock.advance(500L);
        messages.denial(player, active, Material.MACE);
        assertEquals(2, allMessages().size());
    }

    @Test void differentTargetsHaveIndependentThrottleKeys() {
        messages.denial(player, active(target(MACE_TARGET)), Material.MACE);
        messages.denial(player, active(target(PEARL_TARGET)), Material.ENDER_PEARL);
        assertEquals(2, allMessages().size());
    }

    @Test void disabledLungeUsesAbilityTemplateWithoutFakeCountdown() {
        messages.denial(player, decision(RestrictionTarget.SPEAR_LUNGE,
                RestrictionMode.DISABLED, RestrictionDecision.Result.DISABLED,
                Duration.ZERO, Duration.ZERO), null);
        assertEquals("Spear Lunge is disabled during No Mace.", onlyMessage());
    }

    @Test void playerDurationIsReadableAndNeverRoundsActiveTimeToZero() {
        assertEquals("10 seconds", WarzoneMessageService.playerDuration(Duration.ofSeconds(10)));
        assertEquals("1 second", WarzoneMessageService.playerDuration(Duration.ofMillis(999)));
        assertEquals("0.1 seconds", WarzoneMessageService.playerDuration(Duration.ofMillis(1)));
        assertEquals("3.4 seconds", WarzoneMessageService.playerDuration(Duration.ofMillis(3_301)));
    }

    private RestrictionDecision ready(RestrictionTarget target) {
        return decision(target, RestrictionMode.COOLDOWN,
                RestrictionDecision.Result.COOLDOWN_READY, Duration.ofSeconds(10), Duration.ZERO);
    }

    private RestrictionDecision active(RestrictionTarget target) {
        return decision(target, RestrictionMode.COOLDOWN,
                RestrictionDecision.Result.COOLDOWN_ACTIVE, Duration.ofSeconds(10),
                Duration.ofSeconds(8));
    }

    private RestrictionDecision decision(RestrictionTarget target, RestrictionMode mode,
                                         RestrictionDecision.Result result, Duration configured,
                                         Duration remaining) {
        return new RestrictionDecision(result, target,
                new WarzoneConfig.Restriction(target, mode, configured), remaining);
    }

    private RestrictionTarget target(String id) {
        return RestrictionTarget.parse(id).orElseThrow();
    }

    private String onlyMessage() {
        List<String> capturedMessages = allMessages();
        assertEquals(1, capturedMessages.size());
        return capturedMessages.getFirst();
    }

    private List<String> allMessages() {
        var captor = org.mockito.ArgumentCaptor.forClass(Component.class);
        verify(player, atLeastOnce()).sendMessage(captor.capture());
        return captor.getAllValues().stream()
                .map(PlainTextComponentSerializer.plainText()::serialize).toList();
    }

    private WarzoneMessages templates() {
        return new WarzoneMessages(
                "<red><item> is disabled during <white><meta><red>.",
                "<red>You must wait <white><cooldown_remaining><red> before <action>.",
                "<yellow><ready_action> in <white><cooldown><yellow>.",
                "<red><ability> is disabled during <white><meta><red>.",
                "<red>You must wait <white><cooldown_remaining><red> before <action>.",
                "<yellow><ready_action> in <white><cooldown><yellow>.",
                "cobweb", "elytra", "rocket", "place", "break", "bucket", "stasis",
                "warning");
    }

    private static final class MutableClock extends Clock {
        private long currentMillis;
        private MutableClock(long millis) { currentMillis = millis; }
        void advance(long amount) { currentMillis += amount; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(currentMillis); }
        @Override public long millis() { return currentMillis; }
    }
}
