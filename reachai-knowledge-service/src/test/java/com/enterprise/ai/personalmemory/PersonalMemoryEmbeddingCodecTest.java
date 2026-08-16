package com.enterprise.ai.personalmemory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalMemoryEmbeddingCodecTest {

    @Test
    void float32EncodingRoundTripsThroughCosine() {
        byte[] encoded = PersonalMemoryEmbeddingCodec.encode(List.of(1f, 2f, 3f));

        assertEquals(12, encoded.length);
        assertEquals(1d, PersonalMemoryEmbeddingCodec.cosine(
                List.of(1f, 2f, 3f), encoded, 3, PersonalMemoryEmbeddingCodec.FORMAT), 0.000001);
    }

    @Test
    void rejectsInvalidVectorsAndMetadata() {
        assertThrows(IllegalArgumentException.class,
                () -> PersonalMemoryEmbeddingCodec.encode(List.of(Float.NaN)));
        assertThrows(IllegalArgumentException.class,
                () -> PersonalMemoryEmbeddingCodec.encode(List.of(0f, 0f)));
        assertTrue(Double.isNaN(PersonalMemoryEmbeddingCodec.cosine(
                List.of(1f), new byte[4], 2, PersonalMemoryEmbeddingCodec.FORMAT)));
        assertTrue(Double.isNaN(PersonalMemoryEmbeddingCodec.cosine(
                List.of(1f), new byte[4], 1, "UNKNOWN")));
    }
}
