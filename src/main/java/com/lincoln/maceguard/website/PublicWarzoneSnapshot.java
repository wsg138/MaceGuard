package com.lincoln.maceguard.website;

import com.lincoln.maceguard.warzone.rotation.RotationManager;
import com.lincoln.maceguard.warzone.rotation.OverrideDurationMode;
import com.lincoln.maceguard.warzone.runtime.WarzoneRuntime;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class PublicWarzoneSnapshot {
    private static final String TYPE="type",NAME="name",DESCRIPTION="description",ITEMS="items";
    private PublicWarzoneSnapshot() { }
    public static Map<String, Object> capture(WarzoneRuntime runtime, long now) {
        RotationManager rotation = runtime.rotations();
        var selection = rotation.activeSelection();
        var active = selection.activeSet();
        Map<String, Object> kit = Map.of("id", "current-meta", TYPE, "kit",
                NAME, bounded(runtime.messages().plain(active.displayName()), 80),
                DESCRIPTION, bounded(runtime.messages().plain(active.description()), 2000),
                "manualOverride", selection.manualOverride());
        boolean indefinite = rotation.state().overrideActive()
                && rotation.state().overrideDurationMode() == OverrideDurationMode.UNTIL_CLEARED;
        if (!indefinite && (rotation.scheduleEnabled()
                || rotation.state().overrideActive() && rotation.state().overrideExpiresAtMillis() > 0)) {
            kit = withFields(kit, Map.of("endsAt", Instant.ofEpochMilli(rotation.nextEffectiveTransitionMillis()).toString()));
        }
        var next = Map.<String, Object>of("id", "next-meta", TYPE, "activity",
                NAME, bounded(runtime.messages().plain(nextName(rotation, indefinite)), 80), DESCRIPTION, "");
        return Map.of("kind", "warzone", "schemaVersion", 1,
                "generatedAt", Instant.ofEpochMilli(now).toString(),
                ITEMS, List.of(kit, next));
    }
    private static String nextName(RotationManager rotation, boolean indefinite) {
        if (indefinite) return "After override is cleared";
        if (rotation.state().overrideActive()
                && (!rotation.scheduleEnabled()
                    || rotation.nextEffectiveTransitionMillis() < rotation.state().automaticSlotEndMillis()))
            return rotation.automaticSelection().activeSet().displayName();
        if (!rotation.scheduleEnabled()) return "Automatic schedule paused";
        return rotation.entryName(rotation.nextSlot().entry());
    }
    private static String bounded(String value, int limit) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), limit));
    }
    public static long weekStart(long now) {
        return Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }
    public static Map<String, Object> withLeaders(Map<String, Object> rotation, Object raw) {
        var rows=copyRotationItems(rotation);
        var metadata=duelMetadata(rotation,raw);
        var leaders=leaderRows(raw,metadata);
        leaders.ifPresent(rows::addAll);
        rows.add(Map.of("id","duels-status",TYPE,"activity",NAME,"Weekly duel wins",DESCRIPTION,duelStatus(leaders)));
        var result=leaders.isPresent()?withFields(rotation,metadata):rotation;
        return withFields(result,Map.of(ITEMS,List.copyOf(rows),"source","server_export"));
    }
    private static Map<String,Object> withFields(Map<String,Object> original,Map<String,Object> fields) {
        return Stream.concat(original.entrySet().stream(),fields.entrySet().stream())
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,Map.Entry::getValue,(old,value)->value));
    }
    private static List<Map<String,Object>> copyRotationItems(Map<String,Object> rotation) {
        var rows=new ArrayList<Map<String,Object>>();
        for(Object item:(List<?>)rotation.get(ITEMS))rows.add(copyItem(item));
        return rows;
    }
    private static Map<String,Object> copyItem(Object item) {
        if(!(item instanceof Map<?,?> map))throw new IllegalArgumentException("Invalid rotation");
        return map.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                entry->entry.getKey().toString(),Map.Entry::getValue,(old,value)->value));
    }
    private static String duelStatus(java.util.Optional<List<Map<String,Object>>> leaders) {
        if(leaders.isEmpty())return "Weekly WarzoneDuels results are unavailable.";
        return leaders.get().isEmpty()?"No counted match wins this week.":"Counted WarzoneDuels match wins this week.";
    }
    private static java.util.Optional<List<Map<String,Object>>> leaderRows(Object raw,Map<String,Object> metadata) {
        if(raw instanceof Map<?,?> values) {
            if(metadata.isEmpty())return java.util.Optional.empty();
            return validateLeaders(values.get(ITEMS),500);
        }
        return validateLeaders(raw,3);
    }
    private static Map<String,Object> duelMetadata(Map<String,Object> rotation,Object raw) {
        if(!(raw instanceof Map<?,?> values))return Map.of();
        try {
            long capture=Instant.parse((String)rotation.get("generatedAt")).toEpochMilli();
            long week=Instant.parse((String)values.get("weekStartsAt")).toEpochMilli();
            long updated=Instant.parse((String)values.get("generatedAt")).toEpochMilli();
            if(week!=weekStart(capture)||updated<week||updated>capture||!(values.get("complete") instanceof Boolean complete))return Map.of();
            return Map.of("weekStartsAt",Instant.ofEpochMilli(week).toString(),
                    "duelUpdatedAt",Instant.ofEpochMilli(updated).toString(),"duelResultsComplete",complete);
        } catch(RuntimeException error) { return Map.of(); }
    }
    private static java.util.Optional<List<Map<String,Object>>> validateLeaders(Object raw,int limit) {
        if(!(raw instanceof List<?> values)||values.size()>limit)return java.util.Optional.empty();
        var leaders=new ArrayList<Map<String,Object>>();var ids=new java.util.HashSet<String>();
        for(Object value:values) {
            var leader=publicLeader(value,ids);
            if(leader.isEmpty())return java.util.Optional.empty();
            leaders.add(leader.get());
        }
        return java.util.Optional.of(List.copyOf(leaders));
    }
    private static java.util.Optional<Map<String,Object>> publicLeader(Object value,java.util.Set<String> ids) {
        if(!(value instanceof Map<?,?> row))return java.util.Optional.empty();
        var id=winnerId(row.get("id"),ids);var name=winnerName(row.get(NAME));var wins=publicWins(row.get("wins"));
        if(id.isEmpty()||name.isEmpty()||wins.isEmpty())return java.util.Optional.empty();
        return java.util.Optional.of(Map.of("id","duels-"+id.get(),TYPE,"leader",NAME,name.get(),"wins",wins.get(),DESCRIPTION,""));
    }
    private static java.util.Optional<String> winnerId(Object raw,java.util.Set<String> ids) {
        if(!(raw instanceof String id)||!id.matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}"))return java.util.Optional.empty();
        String normalized=id.toLowerCase(java.util.Locale.ROOT);
        return ids.add(normalized)?java.util.Optional.of(normalized):java.util.Optional.empty();
    }
    private static java.util.Optional<String> winnerName(Object raw) {
        return raw instanceof String name&&name.matches("[A-Za-z0-9_. -]{1,40}")?java.util.Optional.of(name):java.util.Optional.empty();
    }
    private static java.util.Optional<Long> publicWins(Object raw) {
        if(!(raw instanceof Number wins))return java.util.Optional.empty();
        double value=wins.doubleValue();
        if(!Double.isFinite(value)||value!=Math.rint(value)||value<0||value>1e9)return java.util.Optional.empty();
        return java.util.Optional.of(wins.longValue());
    }
}
