package com.lincoln.maceguard.warzone.config;

import org.bukkit.Material;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;

/** Complete schema-7 configuration: gameplay modifiers plus operator-facing kits and cycle. */
public record WarzoneControlConfig(
        int version,
        WarzoneConfig gameplay,
        Map<String, Kit> kits,
        Schedule schedule,
        Gui gui
) {
    public static final int VERSION = 7;

    public WarzoneControlConfig {
        kits = Map.copyOf(kits);
    }

    public record Kit(
            String id,
            boolean enabled,
            String displayName,
            String description,
            Material icon,
            List<String> modifierIds
    ) {
        public Kit {
            modifierIds = List.copyOf(modifierIds);
        }
    }

    public record Schedule(
            boolean enabled,
            ZoneId timezone,
            LocalDate anchorDate,
            LocalTime time,
            Cadence cadence,
            List<Entry> cycle
    ) {
        public Schedule {
            cycle = List.copyOf(cycle);
        }
    }

    public record Cadence(int every, CadenceUnit unit) { }

    public enum CadenceUnit { DAYS, WEEKS, MONTHS }

    public record Entry(EntryType type, String kitId, List<String> modifierIds) {
        public Entry {
            modifierIds = List.copyOf(modifierIds);
        }

        public static Entry random() { return new Entry(EntryType.RANDOM, null, List.of()); }
        public static Entry kit(String id) { return new Entry(EntryType.KIT, id, List.of()); }
        public static Entry modifiers(List<String> ids) {
            return new Entry(EntryType.MODIFIERS, null, ids);
        }
        public static Entry none() { return new Entry(EntryType.NONE, null, List.of()); }
    }

    public enum EntryType { RANDOM, KIT, MODIFIERS, NONE }

    public record Gui(boolean showDisabledKits, int sessionTimeoutSeconds) { }

    /** Compatibility wrapper for tests and callers that still construct schema-5 gameplay directly. */
    public static WarzoneControlConfig legacy(WarzoneConfig gameplay) {
        DayOfWeek day = gameplay.schedule().day();
        LocalDate referenceMonday = LocalDate.of(1970, 1, 5);
        LocalDate anchor = referenceMonday.with(TemporalAdjusters.nextOrSame(day));
        Schedule schedule = new Schedule(true, gameplay.schedule().timezone(), anchor,
                gameplay.schedule().time(), new Cadence(1, CadenceUnit.WEEKS),
                List.of(Entry.random()));
        return new WarzoneControlConfig(VERSION, gameplay, Map.of(), schedule,
                new Gui(false, 60));
    }
}
