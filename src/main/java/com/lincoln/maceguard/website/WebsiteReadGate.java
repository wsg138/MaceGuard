package com.lincoln.maceguard.website;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One scheduled/running read, independent of its delivery future's timeout. */
final class WebsiteReadGate implements AutoCloseable {
    private boolean closed;
    private boolean active;
    private CompletableFuture<String> current;

    boolean busy() {
        synchronized (this) { return closed || active; }
    }

    Optional<CompletableFuture<String>> submit(Consumer<Runnable> scheduler, Supplier<String> read) {
        synchronized (this) {
            if (closed || active) return Optional.empty();
            active = true;
            var result = new CompletableFuture<String>();
            current = result;
            try {
                scheduler.accept(() -> execute(read, result));
            } catch (RuntimeException rejected) {
                active = false;
                result.completeExceptionally(rejected);
            }
            return Optional.of(result);
        }
    }

    private void execute(Supplier<String> read, CompletableFuture<String> result) {
        try {
            synchronized (this) {
                if (closed || result.isDone()) return;
            }
            result.complete(read.get());
        } catch (Exception error) {
            result.completeExceptionally(error);
        } finally {
            synchronized (this) { active = false; }
        }
    }

    @Override public void close() {
        synchronized (this) {
            closed = true;
            if (current != null) current.cancel(false);
        }
    }
}
