package com.lincoln.maceguard.warzone.integration;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.config.WarzoneControlConfig;
import com.lincoln.maceguard.warzone.message.WarzoneMessageService;
import com.lincoln.maceguard.warzone.rotation.ActiveSelection;
import com.lincoln.maceguard.warzone.rotation.OverrideDurationMode;
import com.lincoln.maceguard.warzone.rotation.RepeatingSchedule;
import com.lincoln.maceguard.warzone.rotation.RotationManager;
import com.lincoln.maceguard.warzone.rotation.RotationState;
import com.lincoln.maceguard.warzone.rotation.SelectionSourceType;
import com.lincoln.maceguard.warzone.runtime.WarzoneRuntime;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WarzoneControlPlaceholderTest {
    @Test void overrideAndSchedulePlaceholdersExposeStablePlainValues() {
        Fixture fixture = fixture(true);
        assertEquals("KIT", fixture.expansion.onRequest(null, "source_type"));
        assertEquals("smp", fixture.expansion.onRequest(null, "active_kit"));
        assertEquals("true", fixture.expansion.onRequest(null, "override_active"));
        assertEquals("UNTIL_NEXT_SCHEDULED_CHANGE",
                fixture.expansion.onRequest(null, "override_mode"));
        assertEquals("instant:2000", fixture.expansion.onRequest(null, "override_ends_at"));
        assertEquals("00:00", fixture.expansion.onRequest(null, "override_time_left"));
        assertEquals("slot-0", fixture.expansion.onRequest(null, "schedule_slot"));
        assertEquals("1", fixture.expansion.onRequest(null, "schedule_cycle_position"));
        assertEquals("NONE", fixture.expansion.onRequest(null, "next_source_type"));
        assertEquals("No modifiers", fixture.expansion.onRequest(null, "next_name"));
        assertEquals("instant:3000", fixture.expansion.onRequest(null, "next_changes_at"));
    }

    @Test void disabledScheduleReturnsEmptyFutureValues() {
        Fixture fixture = fixture(false);
        assertEquals("", fixture.expansion.onRequest(null, "next_source_type"));
        assertEquals("", fixture.expansion.onRequest(null, "next_name"));
        assertEquals("", fixture.expansion.onRequest(null, "next_changes_at"));
    }

    private Fixture fixture(boolean scheduleEnabled) {
        Plugin plugin = mock(Plugin.class);
        WarzoneRuntime runtime = mock(WarzoneRuntime.class);
        RotationManager rotations = mock(RotationManager.class);
        WarzoneMessageService messages = mock(WarzoneMessageService.class);
        WarzoneConfig config = mock(WarzoneConfig.class);
        WarzoneConfig.ActiveSet set = new WarzoneConfig.ActiveSet(List.of("cobwebs"),
                "Cobwebs", "Cobwebs", Set.of(), Map.of());
        ActiveSelection active = new ActiveSelection(SelectionSourceType.KIT, "smp", set, true);
        RotationState state = new RotationState(RotationState.VERSION, "slot-0", 0, 0, 0,
                null, 1_000, 3_000, 1_000, SelectionSourceType.RANDOM, null,
                List.of("cobwebs"), SelectionSourceType.KIT, "smp",
                List.of("cobwebs"), OverrideDurationMode.UNTIL_NEXT_SCHEDULED_CHANGE,
                1_500, 2_000, Set.of(), 2);
        RepeatingSchedule.Slot next = new RepeatingSchedule.Slot(1, 1,
                Instant.ofEpochMilli(3_000), Instant.ofEpochMilli(4_000),
                WarzoneControlConfig.Entry.none(), "slot-1");

        when(runtime.rotations()).thenReturn(rotations);
        when(runtime.config()).thenReturn(config);
        when(config.modifiers()).thenReturn(Map.of());
        when(rotations.active()).thenReturn(set);
        when(rotations.activeSelection()).thenReturn(active);
        when(rotations.state()).thenReturn(state);
        when(rotations.remaining()).thenReturn(Duration.ZERO);
        when(rotations.nowMillis()).thenReturn(2_000L);
        when(rotations.scheduleEnabled()).thenReturn(scheduleEnabled);
        when(rotations.nextSlot()).thenReturn(next);
        when(rotations.entryName(next.entry())).thenReturn("No modifiers");
        when(runtime.messages()).thenReturn(messages);
        when(messages.formatInstant(2_000L)).thenReturn("instant:2000");
        when(messages.formatInstant(3_000L)).thenReturn("instant:3000");
        when(runtime.gameplayScopeActive()).thenReturn(true);
        return new Fixture(new WarzonePlaceholderExpansion(plugin, () -> runtime));
    }

    private record Fixture(WarzonePlaceholderExpansion expansion) { }
}
