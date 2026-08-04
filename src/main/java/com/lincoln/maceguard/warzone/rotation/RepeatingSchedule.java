package com.lincoln.maceguard.warzone.rotation;

import com.lincoln.maceguard.warzone.config.WarzoneControlConfig;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/** Timezone-aware anchored schedule with deterministic month-end clamping. */
public final class RepeatingSchedule {
    private final WarzoneControlConfig.Schedule config;

    public RepeatingSchedule(WarzoneControlConfig.Schedule config) {
        this.config = config;
        if (config.cadence().every() < 1)
            throw new IllegalArgumentException("Schedule cadence must be positive.");
        if (config.cycle().isEmpty())
            throw new IllegalArgumentException("Schedule cycle must not be empty.");
    }

    public Slot slotAt(Instant instant) {
        long index = estimatedIndex(instant);
        while (boundary(index).isAfter(instant)) index--;
        while (!boundary(index + 1).isAfter(instant)) index++;
        return slot(index);
    }

    public Slot slot(long index) {
        int cycleIndex = Math.floorMod(index, config.cycle().size());
        Instant start = boundary(index);
        Instant end = boundary(index + 1);
        return new Slot(index, cycleIndex, start, end,
                config.cycle().get(cycleIndex), start.toEpochMilli() + ":" + cycleIndex);
    }

    public Slot nextAfter(Instant instant) {
        Slot current = slotAt(instant);
        return slot(current.index() + 1);
    }

    public Instant boundary(long index) {
        long amount = Math.multiplyExact(index, config.cadence().every());
        LocalDate date = switch (config.cadence().unit()) {
            case DAYS -> config.anchorDate().plusDays(amount);
            case WEEKS -> config.anchorDate().plusWeeks(amount);
            case MONTHS -> clampedMonth(amount);
        };
        // atZone resolves a DST gap forward and chooses the earlier offset for an overlap.
        return date.atTime(config.time()).atZone(config.timezone()).toInstant();
    }

    private LocalDate clampedMonth(long months) {
        YearMonth target = YearMonth.from(config.anchorDate()).plusMonths(months);
        int day = Math.min(config.anchorDate().getDayOfMonth(), target.lengthOfMonth());
        return target.atDay(day);
    }

    private long estimatedIndex(Instant instant) {
        ZonedDateTime local = instant.atZone(config.timezone());
        long distance = switch (config.cadence().unit()) {
            case DAYS -> ChronoUnit.DAYS.between(config.anchorDate(), local.toLocalDate());
            case WEEKS -> ChronoUnit.WEEKS.between(config.anchorDate(), local.toLocalDate());
            case MONTHS -> ChronoUnit.MONTHS.between(
                    YearMonth.from(config.anchorDate()), YearMonth.from(local));
        };
        return Math.floorDiv(distance, config.cadence().every());
    }

    public ZoneId zone() { return config.timezone(); }
    public WarzoneControlConfig.Schedule config() { return config; }

    public record Slot(
            long index,
            int cycleIndex,
            Instant start,
            Instant end,
            WarzoneControlConfig.Entry entry,
            String identity
    ) { }
}
