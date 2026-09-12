package com.enterprise.ai.pipeline.document.job;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DocumentIndexVectorManifestTest {
    @Test
    void persistedPrefixAndCountReconstructEveryIdInBoundedBatches() {
        var original = DocumentIndexVectorManifest.forExecution("文件", "lease", 3);
        var restored = new DocumentIndexVectorManifest(original.prefix(), original.count());
        assertEquals(List.of(original.vectorId(0), original.vectorId(1)), restored.batch(0, 2));
        assertEquals(List.of(original.vectorId(2)), restored.batch(2, 2));
        assertTrue(restored.batch(3, 2).isEmpty());
        assertNotEquals(original.prefix(), DocumentIndexVectorManifest.forExecution("文件", "other", 3).prefix());
    }

    @Test
    void rejectsAmbiguousIdentityAndInvalidBoundsWithoutOverflow() {
        assertThrows(IllegalArgumentException.class, () -> DocumentIndexVectorManifest.forExecution("a\0b", "c", 1));
        assertThrows(IllegalArgumentException.class, () -> new DocumentIndexVectorManifest("bad", 3));
        var manifest = new DocumentIndexVectorManifest("a".repeat(64), Integer.MAX_VALUE);
        assertEquals(1, manifest.batch(Integer.MAX_VALUE - 1, 1000).size());
        assertThrows(IllegalArgumentException.class, () -> manifest.batch(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> manifest.batch(0, 1001));
        assertThrows(IllegalArgumentException.class, () -> manifest.vectorId(Integer.MAX_VALUE));
    }
}
