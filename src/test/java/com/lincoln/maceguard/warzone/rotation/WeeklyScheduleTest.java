package com.lincoln.maceguard.warzone.rotation;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WeeklyScheduleTest {
    private final ZoneId zone = ZoneId.of("America/Indiana/Indianapolis");
    private final WeeklySchedule schedule = new WeeklySchedule(
            new WarzoneConfig.Schedule(DayOfWeek.SUNDAY, LocalTime.of(4, 0), zone));

    @Test void calculatesPreviousAndNextCalendarBoundaries() {
        Instant now = Instant.parse("2026-08-03T09:00:00Z");
        assertEquals(Instant.parse("2026-08-02T08:00:00Z"),
                schedule.previousBoundaryAtOrBefore(now));
        assertEquals(Instant.parse("2026-08-09T08:00:00Z"),
                schedule.nextBoundaryAfter(now));
    }

    @Test void springDstWeekIsOneHourShorter() {
        Instant before = Instant.parse("2026-03-01T09:00:00Z");
        Instant after = schedule.nextBoundaryAfter(before);
        Instant following = schedule.nextBoundaryAfter(after);
        assertEquals(Duration.ofHours(167), Duration.between(before, after));
        assertEquals(Duration.ofHours(168), Duration.between(after, following));
    }

    @Test void fallDstWeekIsOneHourLonger() {
        Instant before = Instant.parse("2026-10-25T08:00:00Z");
        Instant after = schedule.nextBoundaryAfter(before);
        Instant following = schedule.nextBoundaryAfter(after);
        assertEquals(Duration.ofHours(169), Duration.between(before, after));
        assertEquals(Duration.ofHours(168), Duration.between(after, following));
    }
}
