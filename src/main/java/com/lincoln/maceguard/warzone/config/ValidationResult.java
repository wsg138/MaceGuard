package com.lincoln.maceguard.warzone.config;

import java.util.List;

public record ValidationResult<T>(T value, List<String> errors, List<String> warnings) {
    public boolean valid() { return value != null && errors.isEmpty(); }
    public static <T> ValidationResult<T> invalid(List<String> errors) {
        return new ValidationResult<>(null, List.copyOf(errors), List.of());
    }
}
