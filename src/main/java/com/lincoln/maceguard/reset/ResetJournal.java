package com.lincoln.maceguard.reset;

public record ResetJournal(String operationId, String worldUuid, String regionId, String geometryHash,
                           String snapshotChecksum, String planHash, Status status, int nextChange, int totalChanges, long updatedAt) {
    public enum Status { PREPARED, RESTORING, COMPLETE, FAILED, ABANDONED }
    public boolean interrupted() { return status == Status.PREPARED || status == Status.RESTORING; }
}
