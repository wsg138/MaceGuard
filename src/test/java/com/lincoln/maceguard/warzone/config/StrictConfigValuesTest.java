package com.lincoln.maceguard.warzone.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StrictConfigValuesTest {
    private static final String FIRST_KEY = "first";
    private static final String VALID_KEY = "valid";
    @Test void mappingPreservesOrderAndNullValuesInAnIndependentResult() {
        var input = LinkedHashMap.<String, Object>newLinkedHashMap(2);
        input.put(FIRST_KEY, null);
        input.put("second", 2);
        var errors = new ArrayList<String>();
        var result = StrictConfigValues.map(input, "config", errors);
        assertEquals(List.of(FIRST_KEY, "second"), List.copyOf(result.keySet()));
        assertTrue(result.containsKey(FIRST_KEY));
        assertNull(result.get(FIRST_KEY));
        input.put("third", 3);
        assertFalse(result.containsKey("third"));
        assertTrue(errors.isEmpty());
    }

    @Test void mappingRejectsNonStringKeysWithoutDiscardingValidEntries() {
        var input = LinkedHashMap.<Object, Object>newLinkedHashMap(2);
        input.put(4, "invalid");
        input.put(VALID_KEY, null);
        var errors = new ArrayList<String>();
        var result = StrictConfigValues.map(input, "config", errors);
        assertEquals(List.of(VALID_KEY), List.copyOf(result.keySet()));
        assertNull(result.get(VALID_KEY));
        assertEquals(List.of("config contains a non-string key."), errors);
    }
}
