package com.lincoln.maceguard.warzone.rotation;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

public final class WeeklySchedule {
    private final DayOfWeek day;
    private final java.time.LocalTime time;
    private final ZoneId zone;

    public WeeklySchedule(WarzoneConfig.Schedule schedule) {
        this.day = schedule.day();
        this.time = schedule.time();
        this.zone = schedule.timezone();
    }

    public Instant previousBoundaryAtOrBefore(Instant instant) {
        ZonedDateTime local = instant.atZone(zone);
        LocalDate date = local.toLocalDate().with(TemporalAdjusters.previousOrSame(day));
        ZonedDateTime candidate = date.atTime(time).atZone(zone);
        if (candidate.toInstant().isAfter(instant)) {
            candidate = date.minusWeeks(1).atTime(time).atZone(zone);
        }
        return candidate.toInstant();
    }

    public Instant nextBoundaryAfter(Instant instant) {
        Instant previous = previousBoundaryAtOrBefore(instant);
        ZonedDateTime next = previous.atZone(zone).plusWeeks(1)
                .with(TemporalAdjusters.nextOrSame(day))
                .withHour(time.getHour()).withMinute(time.getMinute())
                .withSecond(time.getSecond()).withNano(time.getNano());
        if (!next.toInstant().isAfter(instant)) {
            next = next.plusWeeks(1);
        }
        return next.toInstant();
    }

    public ZoneId zone() { return zone; }
}
