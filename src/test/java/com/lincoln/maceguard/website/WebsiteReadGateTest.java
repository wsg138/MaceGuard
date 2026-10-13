package com.lincoln.maceguard.website;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class WebsiteReadGateTest {
    @Test void timeoutDuringReadCannotStartAnotherReadOrDeliverLateResults() {
        var gate=new WebsiteReadGate();
        var scheduled=new ArrayList<Runnable>();
        var delivered=new AtomicBoolean();
        var future=gate.submit(scheduled::add,()->{
            CompletableFuture<String> current=holder;
            current.completeExceptionally(new TimeoutException());
            assertTrue(gate.busy());
            assertTrue(gate.submit(scheduled::add,()->"overlap").isEmpty());
            return "late";
        }).orElseThrow();
        holder=future;
        future.thenAccept(body->delivered.set(true));
        scheduled.getFirst().run();
        assertFalse(delivered.get());
        assertTrue(future.isCompletedExceptionally());
        assertFalse(gate.busy());
        assertTrue(gate.submit(scheduled::add,()->"retry").isPresent());
    }
    private CompletableFuture<String> holder;

    @Test void cancellationBeforeExecutionSkipsReadAndReleasesSlot() {
        var gate=new WebsiteReadGate();
        var scheduled=new ArrayList<Runnable>();
        var read=new AtomicBoolean();
        var future=gate.submit(scheduled::add,()->{read.set(true);return "body";}).orElseThrow();
        future.cancel(false);
        assertTrue(gate.busy());
        scheduled.getFirst().run();
        assertFalse(read.get());
        assertFalse(gate.busy());
    }
    @Test void closeCancelsQueuedReadAndRejectsNewWork() {
        var gate=new WebsiteReadGate();
        var scheduled=new ArrayList<Runnable>();
        var read=new AtomicBoolean();
        var future=gate.submit(scheduled::add,()->{read.set(true);return "body";}).orElseThrow();
        gate.close();
        scheduled.getFirst().run();
        assertTrue(future.isCancelled());
        assertFalse(read.get());
        assertTrue(gate.submit(scheduled::add,()->"new").isEmpty());
    }
    @Test void closeDuringReadPreventsLateDelivery() {
        var gate=new WebsiteReadGate();
        var scheduled=new ArrayList<Runnable>();
        var delivered=new AtomicBoolean();
        var future=gate.submit(scheduled::add,()->{gate.close();return "late";}).orElseThrow();
        future.thenAccept(body->delivered.set(true));
        scheduled.getFirst().run();
        assertTrue(future.isCancelled());
        assertFalse(delivered.get());
    }
    @Test void schedulingAndReadFailuresAllowRetry() {
        var gate=new WebsiteReadGate();
        var rejected=gate.submit(work->{throw new IllegalStateException("disabled");},()->"body").orElseThrow();
        assertTrue(rejected.isCompletedExceptionally());
        assertFalse(gate.busy());
        var failed=gate.submit(Runnable::run,()->{throw new IllegalArgumentException("read failed");}).orElseThrow();
        assertTrue(failed.isCompletedExceptionally());
        assertFalse(gate.busy());
        assertEquals("retry",gate.submit(Runnable::run,()->"retry").orElseThrow().join());
    }
}
