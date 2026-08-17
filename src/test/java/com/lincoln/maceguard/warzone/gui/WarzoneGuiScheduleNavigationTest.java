package com.lincoln.maceguard.warzone.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarzoneGuiScheduleNavigationTest {
    @Test
    void mainNextScheduledBackReturnsToMain() {
        WarzoneGuiManager.ScheduleDetailNavigation navigation =
                WarzoneGuiManager.scheduleDetailNavigation(
                        WarzoneGuiManager.ScheduleDetailOrigin.MAIN, 0);
        WarzoneGuiManager.ScheduleClickAction action =
                WarzoneGuiManager.scheduleClickAction(
                        navigation.backType(), navigation.backValue());

        assertEquals("Back to Warzone", navigation.backLabel());
        assertEquals(WarzoneGuiManager.ScheduleDestination.MAIN, action.destination());
    }

    @Test
    void mainNextScheduledViewFullScheduleOpensSchedule() {
        WarzoneGuiManager.ScheduleDetailNavigation navigation =
                WarzoneGuiManager.scheduleDetailNavigation(
                        WarzoneGuiManager.ScheduleDetailOrigin.MAIN, 0);
        WarzoneGuiManager.ScheduleClickAction action =
                WarzoneGuiManager.scheduleClickAction(
                        "view-schedule", Integer.toString(navigation.fullSchedulePage()));

        assertTrue(navigation.showFullSchedule());
        assertEquals(WarzoneGuiManager.ScheduleDestination.SCHEDULE, action.destination());
        assertEquals(0, action.value());
    }

    @Test
    void scheduleEntryDetailBackReturnsToSameSchedulePage() {
        int sourcePage = 3;
        WarzoneGuiManager.ScheduleDetailNavigation navigation =
                WarzoneGuiManager.scheduleDetailNavigation(
                        WarzoneGuiManager.ScheduleDetailOrigin.SCHEDULE, sourcePage);
        WarzoneGuiManager.ScheduleClickAction action =
                WarzoneGuiManager.scheduleClickAction(
                        navigation.backType(), navigation.backValue());

        assertEquals("Back to Schedule", navigation.backLabel());
        assertFalse(navigation.showFullSchedule());
        assertEquals(WarzoneGuiManager.ScheduleDestination.SCHEDULE, action.destination());
        assertEquals(sourcePage, action.value());
    }
}
