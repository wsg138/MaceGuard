package com.lincoln.maceguard.storage;

import com.lincoln.maceguard.reset.ResetJournal;
import com.lincoln.maceguard.reset.SparseBaseline;
import com.lincoln.maceguard.reset.ArmState;
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

    @Test void failedJournalBlocksAnotherResetInTheSameRuntime() throws Exception {
        ResetJournalRepository repository = new ResetJournalRepository(directory.resolve("journal.json"));
        ResetJournal failed = journal(ResetJournal.Status.FAILED, 3);
        repository.save(failed);
        assertFalse(repository.savePreparedIfNoUnresolved(journal(ResetJournal.Status.PREPARED, 0)));
        assertEquals(failed, repository.load().orElseThrow());
    }

    @Test void failedJournalBlocksAnotherResetAfterRepositoryReload() throws Exception {
        Path file = directory.resolve("journal.json");
        new ResetJournalRepository(file).save(journal(ResetJournal.Status.FAILED, 3));
        ResetJournalRepository restarted = new ResetJournalRepository(file);
        assertFalse(restarted.savePreparedIfNoUnresolved(journal(ResetJournal.Status.PREPARED, 0)));
        assertEquals(ResetJournal.Status.FAILED, restarted.load().orElseThrow().status());
    }

    @Test void completeJournalDoesNotBlockTheNextReset() throws Exception {
        ResetJournalRepository repository = new ResetJournalRepository(directory.resolve("journal.json"));
        repository.save(journal(ResetJournal.Status.COMPLETE, 10));
        assertTrue(repository.savePreparedIfNoUnresolved(journal(ResetJournal.Status.PREPARED, 0)));
        assertEquals(ResetJournal.Status.PREPARED, repository.load().orElseThrow().status());
    }

    @Test void failedRestoreAfterPartialProgressCannotReplaceItsJournal() throws Exception {
        ResetJournalRepository repository = new ResetJournalRepository(directory.resolve("journal.json"));
        ResetJournal failedAfterPartialProgress = journal(ResetJournal.Status.FAILED, 5);
        repository.save(failedAfterPartialProgress);
        assertFalse(repository.savePreparedIfNoUnresolved(journal(ResetJournal.Status.PREPARED, 0)));
        ResetJournal retained = repository.load().orElseThrow();
        assertEquals(ResetJournal.Status.FAILED, retained.status());
        assertEquals(5, retained.nextChange());
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

    @Test void pausedAutomaticScheduleSurvivesRepositoryRestart() throws Exception {
        Path file = directory.resolve("armed.json");
        ArmState state = new ArmState("world", "war-pit", "geometry", "war-pit", "FULL_SNAPSHOT",
                "exclusions", 1, "checksum", 123L, false);
        ArmStateRepository repository = new ArmStateRepository(file);
        repository.arm(state);
        ArmStateRepository restarted = new ArmStateRepository(file);
        restarted.load();
        ArmState loaded = restarted.get("world", "war-pit").orElseThrow();
        assertFalse(loaded.isScheduleEnabled());
    }

    @Test void legacyArmingStateKeepsItsExistingAutomaticScheduleBehavior() throws Exception {
        Path file = directory.resolve("legacy-armed.json");
        java.nio.file.Files.writeString(file, "{\"world:war-pit\":{\"worldUuid\":\"world\",\"regionId\":\"war-pit\","
                + "\"geometryHash\":\"geometry\",\"profile\":\"war-pit\",\"mode\":\"FULL_SNAPSHOT\","
                + "\"exclusionsHash\":\"exclusions\",\"snapshotFormat\":1,\"snapshotChecksum\":\"checksum\",\"armedAt\":123}}", java.nio.charset.StandardCharsets.UTF_8);
        ArmStateRepository repository = new ArmStateRepository(file);
        repository.load();
        assertTrue(repository.get("world", "war-pit").orElseThrow().isScheduleEnabled());
    }

    private ResetJournal journal(ResetJournal.Status status, int nextChange) {
        return new ResetJournal("op", "world", "pit", "geometry", "snapshot", "plan", status, nextChange, 10, 1);
    }
}
