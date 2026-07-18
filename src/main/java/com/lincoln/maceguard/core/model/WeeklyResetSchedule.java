package com.lincoln.maceguard.core.model;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;

public record WeeklyResetSchedule(boolean enabled, DayOfWeek day, LocalTime time, ZoneId zoneId) {
    public static final WeeklyResetSchedule DISABLED = new WeeklyResetSchedule(false, DayOfWeek.SUNDAY, LocalTime.of(4, 0), ZoneId.of("America/Indiana/Indianapolis"));
}
