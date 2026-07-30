package com.lincoln.maceguard.warzone.rotation;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

public final class RotationManager {
    @FunctionalInterface public interface Transition {
        void accept(WarzoneConfig.Rotation previous, WarzoneConfig.Rotation current, boolean announce);
    }
    @FunctionalInterface public interface Warning {
        void accept(WarzoneConfig.Rotation rotation, Duration remaining);
    }

    private volatile WarzoneConfig config;
    private final WarzoneStateStore store;
    private final Clock clock;
    private final Transition transition;
    private final Warning warning;
    private volatile RotationState state;
    private final RotationState storedState;

    public RotationManager(WarzoneConfig config, WarzoneStateStore store, Clock clock,
                           Transition transition, Warning warning) {
        this.config = config;
        this.store = store;
        this.clock = clock;
        this.transition = transition;
        this.warning = warning;
        Optional<RotationState> stored = store.load();
        this.storedState = stored.orElse(null);
        this.state = restore(stored, config);
        store.update(state);
    }

    public void tick() {
        long now = clock.millis();
        if (now >= state.endsAtMillis()) {
            advanceElapsed(now, true);
            return;
        }
        long remainingMillis = state.endsAtMillis() - now;
        Duration threshold = config.warningTimes().stream()
                .filter(value -> value.compareTo(active().duration()) < 0 && value.toMillis() >= remainingMillis)
                .min(Duration::compareTo).orElse(null);
        if (threshold == null || state.emittedWarningsSeconds().contains(threshold.getSeconds())) return;
        Set<Long> emitted = new LinkedHashSet<>(state.emittedWarningsSeconds());
        emitted.add(threshold.getSeconds());
        state = new RotationState(state.activeRotationId(), state.startedAtMillis(), state.endsAtMillis(),
                state.nextRotationId(), emitted);
        store.update(state);
        warning.accept(active(), threshold);
    }

    public boolean skip() { return activate(next().id(), true); }

    public boolean activate(String id, boolean announce) {
        WarzoneConfig.Rotation target = config.rotationsById().get(id);
        if (target == null || target.id().equals(active().id())) return false;
        WarzoneConfig.Rotation previous = active();
        state = stateFor(target, clock.millis(), config);
        store.update(state);
        transition.accept(previous, target, announce);
        return true;
    }

    public boolean extend(Duration duration) {
        if (duration.isZero() || duration.isNegative()) return false;
        state = new RotationState(state.activeRotationId(), state.startedAtMillis(),
                Math.addExact(state.endsAtMillis(), duration.toMillis()), state.nextRotationId(), Set.of());
        store.update(state);
        return true;
    }

    public ApplyResult applyConfig(WarzoneConfig proposed) {
        WarzoneConfig.Rotation previous = active();
        config = proposed;
        boolean replaced = !proposed.rotationsById().containsKey(state.activeRotationId());
        if (replaced) {
            state = stateFor(proposed.rotations().getFirst(), clock.millis(), proposed);
        } else {
            state = new RotationState(state.activeRotationId(), state.startedAtMillis(), state.endsAtMillis(),
                    nextId(state.activeRotationId(), proposed), state.emittedWarningsSeconds());
        }
        store.update(state);
        return new ApplyResult(previous, active(), replaced);
    }

    public WarzoneConfig.Rotation active() { return config.rotationsById().get(state.activeRotationId()); }
    public WarzoneConfig.Rotation next() { return config.rotationsById().get(state.nextRotationId()); }
    public Duration remaining() {
        return Duration.ofMillis(Math.max(0, state.endsAtMillis() - clock.millis()));
    }
    public WarzoneConfig config() { return config; }
    public RotationState state() { return state; }
    public boolean advancedDuringRestore() {
        return storedState != null && (storedState.startedAtMillis() != state.startedAtMillis()
                || !storedState.activeRotationId().equals(state.activeRotationId()));
    }
    public String storedActiveRotationId() { return storedState == null ? null : storedState.activeRotationId(); }

    private RotationState restore(Optional<RotationState> stored, WarzoneConfig source) {
        if (stored.isEmpty() || !source.rotationsById().containsKey(stored.get().activeRotationId())
                || stored.get().endsAtMillis() <= stored.get().startedAtMillis())
            return stateFor(source.rotations().getFirst(), clock.millis(), source);
        RotationState restored = new RotationState(stored.get().activeRotationId(), stored.get().startedAtMillis(),
                stored.get().endsAtMillis(), nextId(stored.get().activeRotationId(), source),
                stored.get().emittedWarningsSeconds());
        return advanceRestored(restored, source, clock.millis());
    }

    private RotationState advanceRestored(RotationState restored, WarzoneConfig source, long now) {
        if (now >= restored.endsAtMillis()) {
            long cycleMillis = 0;
            for (WarzoneConfig.Rotation rotation : source.rotations())
                cycleMillis = Math.addExact(cycleMillis, rotation.duration().toMillis());
            long completeCycles = (now - restored.endsAtMillis()) / cycleMillis;
            if (completeCycles > 0) {
                long skipped = Math.multiplyExact(completeCycles, cycleMillis);
                restored = new RotationState(restored.activeRotationId(),
                        Math.addExact(restored.startedAtMillis(), skipped),
                        Math.addExact(restored.endsAtMillis(), skipped),
                        restored.nextRotationId(), restored.emittedWarningsSeconds());
            }
        }
        while (now >= restored.endsAtMillis()) {
            WarzoneConfig.Rotation target = source.rotationsById().get(restored.nextRotationId());
            long start = restored.endsAtMillis();
            restored = new RotationState(target.id(), start, Math.addExact(start, target.duration().toMillis()),
                    nextId(target.id(), source), Set.of());
        }
        return restored;
    }

    private void advanceElapsed(long now, boolean announce) {
        WarzoneConfig.Rotation previous = active();
        RotationState advanced = advanceRestored(state, config, now);
        boolean changed = !advanced.activeRotationId().equals(state.activeRotationId())
                || advanced.startedAtMillis() != state.startedAtMillis();
        state = advanced;
        store.update(state);
        if (changed && announce) transition.accept(previous, active(), true);
    }

    private RotationState stateFor(WarzoneConfig.Rotation rotation, long start, WarzoneConfig source) {
        return new RotationState(rotation.id(), start, Math.addExact(start, rotation.duration().toMillis()),
                nextId(rotation.id(), source), Set.of());
    }

    private String nextId(String id, WarzoneConfig source) {
        int index = 0;
        for (int candidate = 0; candidate < source.rotations().size(); candidate++) {
            if (source.rotations().get(candidate).id().equals(id)) {
                index = candidate;
                break;
            }
        }
        return source.rotations().get((index + 1) % source.rotations().size()).id();
    }

    public record ApplyResult(WarzoneConfig.Rotation previous, WarzoneConfig.Rotation current, boolean replaced) { }
}
