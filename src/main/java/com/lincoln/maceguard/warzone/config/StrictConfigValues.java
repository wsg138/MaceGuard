package com.lincoln.maceguard.warzone.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict scalar/mapping readers shared by Warzone configuration parsing. */
final class StrictConfigValues {
    private StrictConfigValues() { }

    static Map<String, Object> map(Object value, String path, List<String> errors) {
        if (value instanceof Map<?, ?> raw) {
            // This local parse result preserves YAML order and null values; it is never shared.
            Map<String, Object> result = LinkedHashMap.newLinkedHashMap(raw.size());
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    errors.add(path + " contains a non-string key.");
                    continue;
                }
                result.put(key, entry.getValue());
            }
            return result;
        }
        errors.add(path + " must be a mapping.");
        return Map.of();
    }

    static void keys(Map<String, Object> values, String path, Set<String> allowed, List<String> errors) {
        values.keySet().stream().filter(key -> !allowed.contains(key))
                .forEach(key -> errors.add((path.equals("<root>") ? "" : path + ".") + key + " is not supported."));
    }

    static String nonBlank(Object value, String path, List<String> errors) {
        if (!(value instanceof String text) || text.isBlank()) {
            errors.add(path + " must be a non-blank string.");
            return "";
        }
        return text;
    }

    static String optionalString(Object value, String path, List<String> errors) {
        if (value == null) return null;
        if (!(value instanceof String text)) {
            errors.add(path + " must be a string.");
            return null;
        }
        return text;
    }

    static boolean bool(Object value, String path, List<String> errors, boolean fallback) {
        if (value instanceof Boolean result) return result;
        errors.add(path + " must be true or false.");
        return fallback;
    }

    static int integer(Object value, String path, List<String> errors, int fallback) {
        if (value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue()))
            return number.intValue();
        errors.add(path + " must be an integer.");
        return fallback;
    }
}
