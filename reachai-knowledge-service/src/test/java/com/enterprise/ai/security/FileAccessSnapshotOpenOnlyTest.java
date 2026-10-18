package com.enterprise.ai.security;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FileAccessSnapshotOpenOnlyTest {

    @Test
    void identityLessSnapshotReadsOnlyOpenBases() {
        var snapshot = new FileAccessSnapshot(null, Map.of(), Set.of(7L));
        assertNull(snapshot.userId());
        assertTrue(snapshot.hasAccess());
        assertTrue(snapshot.isOpen(7L));
        assertFalse(snapshot.isOpen(8L));
        assertTrue(snapshot.fileIds().isEmpty());
    }

    @Test
    void fileGrantsAlwaysNeedAUser() {
        var grant = new FileAccessSnapshot.Grant(1L, null, 2L, null, 8L, "file-a", "kb_8");
        assertThrows(IllegalArgumentException.class, () -> new FileAccessSnapshot(" ", Map.of(1L, grant), Set.of()));
    }
}
