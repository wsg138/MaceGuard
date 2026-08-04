package com.lincoln.maceguard.warzone.rotation;

import com.lincoln.maceguard.warzone.config.WarzoneControlConfig;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RepeatingScheduleTest {
    private static final ZoneId INDIANAPOLIS = ZoneId.of("America/Indiana/Indianapolis");

    @Test void repeatsFourEntryWeeklyCycle() {
        RepeatingSchedule schedule = schedule(LocalDate.of(2026, 8, 9), LocalTime.of(4, 0),
                1, WarzoneControlConfig.CadenceUnit.WEEKS, fourEntries());
        RepeatingSchedule.Slot first = schedule.slotAt(zdt(2026, 8, 9, 4, 0).toInstant());
        assertEquals(0, first.cycleIndex());
        assertEquals(WarzoneControlConfig.EntryType.KIT, first.entry().type());
        assertEquals(1, schedule.slot(first.index() + 1).cycleIndex());
        assertEquals(0, schedule.slot(first.index() + 4).cycleIndex());
    }

    @Test void dailyCycleAdvancesWithoutFixedMillisecondArithmetic() {
        RepeatingSchedule schedule = schedule(LocalDate.of(2026, 3, 7), LocalTime.of(4, 0),
                1, WarzoneControlConfig.CadenceUnit.DAYS, fourEntries());
        RepeatingSchedule.Slot before = schedule.slotAt(zdt(2026, 3, 7, 23, 0).toInstant());
        RepeatingSchedule.Slot after = schedule.slotAt(zdt(2026, 3, 8, 5, 0).toInstant());
        assertEquals(before.index() + 1, after.index());
    }

    @Test void monthlyAnchorClampsToLastValidDay() {
        RepeatingSchedule schedule = schedule(LocalDate.of(2024, 1, 31), LocalTime.of(4, 0),
                1, WarzoneControlConfig.CadenceUnit.MONTHS, fourEntries());
        assertEquals(LocalDate.of(2024, 2, 29),
                schedule.boundary(1).atZone(INDIANAPOLIS).toLocalDate());
        assertEquals(LocalDate.of(2024, 3, 31),
                schedule.boundary(2).atZone(INDIANAPOLIS).toLocalDate());
        assertEquals(LocalDate.of(2024, 4, 30),
                schedule.boundary(3).atZone(INDIANAPOLIS).toLocalDate());
    }

    @Test void nonLeapFebruaryClampsDeterministically() {
        RepeatingSchedule schedule = schedule(LocalDate.of(2025, 1, 31), LocalTime.of(4, 0),
                1, WarzoneControlConfig.CadenceUnit.MONTHS, fourEntries());
        assertEquals(LocalDate.of(2025, 2, 28),
                schedule.boundary(1).atZone(INDIANAPOLIS).toLocalDate());
    }

    @Test void dstGapResolvesForwardAndBoundariesRemainOrdered() {
        RepeatingSchedule schedule = schedule(LocalDate.of(2026, 3, 7), LocalTime.of(2, 30),
                1, WarzoneControlConfig.CadenceUnit.DAYS, fourEntries());
        ZonedDateTime gap = schedule.boundary(1).atZone(INDIANAPOLIS);
        assertEquals(LocalDate.of(2026, 3, 8), gap.toLocalDate());
        assertEquals(LocalTime.of(3, 30), gap.toLocalTime());
        assertTrue(schedule.boundary(2).isAfter(schedule.boundary(1)));
    }

    @Test void offlineAdvancementComputesCurrentSlotDirectly() {
        RepeatingSchedule schedule = schedule(LocalDate.of(2026, 8, 9), LocalTime.of(4, 0),
                1, WarzoneControlConfig.CadenceUnit.WEEKS, fourEntries());
        RepeatingSchedule.Slot slot = schedule.slotAt(zdt(2026, 10, 20, 12, 0).toInstant());
        assertEquals(Math.floorMod(slot.index(), 4), slot.cycleIndex());
        assertTrue(slot.start().isBefore(zdt(2026, 10, 20, 12, 0).toInstant()));
        assertTrue(slot.end().isAfter(zdt(2026, 10, 20, 12, 0).toInstant()));
    }

    private RepeatingSchedule schedule(LocalDate anchor, LocalTime time, int every,
                                       WarzoneControlConfig.CadenceUnit unit,
                                       List<WarzoneControlConfig.Entry> cycle) {
        return new RepeatingSchedule(new WarzoneControlConfig.Schedule(true, INDIANAPOLIS,
                anchor, time, new WarzoneControlConfig.Cadence(every, unit), cycle));
    }

    private List<WarzoneControlConfig.Entry> fourEntries() {
        return List.of(WarzoneControlConfig.Entry.kit("smp"),
                WarzoneControlConfig.Entry.random(),
                WarzoneControlConfig.Entry.kit("mace"),
                WarzoneControlConfig.Entry.none());
    }

    private ZonedDateTime zdt(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, INDIANAPOLIS);
    }
}
