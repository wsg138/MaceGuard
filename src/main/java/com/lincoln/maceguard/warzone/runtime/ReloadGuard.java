package com.lincoln.maceguard.warzone.runtime;

import java.util.function.Supplier;

public final class ReloadGuard {
    private ReloadGuard() { }

    public static <T> Result<T> prepare(T current, boolean valid, Supplier<T> replacement) {
        if (!valid) return new Result<>(current, false, null);
        try { return new Result<>(replacement.get(), true, null); }
        catch (RuntimeException ex) { return new Result<>(current, false, ex); }
    }

    public record Result<T>(T value, boolean accepted, RuntimeException failure) { }
}
