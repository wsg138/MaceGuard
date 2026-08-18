package com.lincoln.maceguard.warzone.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarzoneGuiScheduleNavigationTest {
    @Test
    void mainNextScheduledRendersWarzoneBackAndFullScheduleActions() {
        WarzoneGuiManager.ScheduleDetailNavigation navigation =
                WarzoneGuiManager.scheduleDetailNavigation(
                        WarzoneGuiManager.ScheduleDetailOrigin.MAIN, 7, 200);

        assertEquals("back-main", navigation.backType());
        assertEquals("", navigation.backValue());
        assertEquals("Back to Warzone", navigation.backLabel());
        assertTrue(navigation.showFullSchedule());
        assertEquals(0, navigation.fullSchedulePage());
    }

    @Test
    void scheduleDetailReturnsToCapturedSchedulePage() {
        int sourcePage = 3;
        WarzoneGuiManager.ScheduleDetailNavigation navigation =
                WarzoneGuiManager.scheduleDetailNavigation(
                        WarzoneGuiManager.ScheduleDetailOrigin.SCHEDULE, sourcePage, 200);

        assertEquals("back-schedule", navigation.backType());
        assertEquals(Integer.toString(sourcePage), navigation.backValue());
        assertEquals("Back to Schedule", navigation.backLabel());
        assertFalse(navigation.showFullSchedule());
        assertEquals(sourcePage, navigation.fullSchedulePage());
    }

    @Test
    void scheduleReturnPageIsClampedToAvailablePages() {
        WarzoneGuiManager.ScheduleDetailNavigation negative =
                WarzoneGuiManager.scheduleDetailNavigation(
                        WarzoneGuiManager.ScheduleDetailOrigin.SCHEDULE, -4, 46);
        WarzoneGuiManager.ScheduleDetailNavigation oversized =
                WarzoneGuiManager.scheduleDetailNavigation(
                        WarzoneGuiManager.ScheduleDetailOrigin.SCHEDULE, 99, 46);

        assertEquals("0", negative.backValue());
        assertEquals(0, negative.fullSchedulePage());
        assertEquals("1", oversized.backValue());
        assertEquals(1, oversized.fullSchedulePage());
    }

    @Test
    void emptyScheduleReturnPageClampsToFirstPage() {
        WarzoneGuiManager.ScheduleDetailNavigation navigation =
                WarzoneGuiManager.scheduleDetailNavigation(
                        WarzoneGuiManager.ScheduleDetailOrigin.SCHEDULE, 4, 0);

        assertEquals("0", navigation.backValue());
        assertEquals(0, navigation.fullSchedulePage());
    }

    @Test
    void pageNormalizationHandlesEmptyAndBoundaryCases() {
        assertEquals(0, WarzoneGuiManager.normalizePage(-1, 0));
        assertEquals(0, WarzoneGuiManager.normalizePage(1, 45));
        assertEquals(1, WarzoneGuiManager.normalizePage(1, 46));
        assertEquals(1, WarzoneGuiManager.normalizePage(99, 90));
        assertEquals(2, WarzoneGuiManager.normalizePage(99, 91));
    }

    @Test
    void nullDetailOriginIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> WarzoneGuiManager.scheduleDetailNavigation(null, 0, 1));
    }
}
