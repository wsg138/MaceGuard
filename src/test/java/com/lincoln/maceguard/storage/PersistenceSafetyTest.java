package com.lincoln.maceguard.storage;

import com.lincoln.maceguard.reset.ResetJournal;
import com.lincoln.maceguard.reset.SparseBaseline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PersistenceSafetyTest {
    @TempDir Path directory;

    @Test void interruptedRestoreRemainsExplicitlyIncompleteAfterRestart() throws Exception {
        ResetJournalRepository repository = new ResetJournalRepository(directory.resolve("journal.json"));
        ResetJournal journal = new ResetJournal("op", "world", "pit", "geometry", "snapshot", "plan", ResetJournal.Status.RESTORING, 5, 10, 1);
        repository.save(journal);
        ResetJournal loaded = new ResetJournalRepository(directory.resolve("journal.json")).load().orElseThrow();
        assertTrue(loaded.interrupted()); assertEquals(5, loaded.nextChange()); assertNotEquals(ResetJournal.Status.COMPLETE, loaded.status());
    }

    @Test void durableCompletionIsDistinguishableFromInterruptedState() throws Exception {
        ResetJournalRepository repository = new ResetJournalRepository(directory.resolve("journal.json"));
        repository.save(new ResetJournal("op", "world", "pit", "geometry", "snapshot", "plan", ResetJournal.Status.COMPLETE, 10, 10, 1));
        assertFalse(repository.load().orElseThrow().interrupted());
    }

    @Test void sparseOriginalSurvivesRepositoryRestart() throws Exception {
        var region = new com.lincoln.maceguard.worldguard.RegionDescriptor("warzone", "world", java.util.UUID.randomUUID(), "CUBOID", 0,0,0,0,0,0,"hash",1);
        var original = new com.lincoln.maceguard.reset.SnapshotBlock(0,0,0,"minecraft:stone",null);
        var entries = java.util.Map.of("0:0:0", original);
        var validator = new com.lincoln.maceguard.reset.SparseBaselineValidator();
        SparseBaseline baseline = new SparseBaseline(1,"test",region.worldUuid().toString(),"warzone",region,"profile","ex",true,1,2,validator.checksum(entries),entries);
        SparseBaselineRepository repository = new SparseBaselineRepository(directory.resolve("sparse"));
        repository.save(baseline);
        assertEquals(original, new SparseBaselineRepository(directory.resolve("sparse")).load(baseline.worldUuid(), "warzone").orElseThrow().originals().get("0:0:0"));
    }
}
