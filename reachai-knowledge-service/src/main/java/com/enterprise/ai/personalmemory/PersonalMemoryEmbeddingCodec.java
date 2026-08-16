package com.enterprise.ai.personalmemory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Deterministic, versioned float32 encoding for the rebuildable MySQL projection. */
final class PersonalMemoryEmbeddingCodec {

    static final String FORMAT = "FLOAT32_BE_V1";
    private static final int MAX_DIMENSION = 8192;

    private PersonalMemoryEmbeddingCodec() {
    }

    static byte[] encode(List<Float> values) {
        validate(values);
        ByteBuffer buffer = ByteBuffer.allocate(Math.multiplyExact(values.size(), Float.BYTES))
                .order(ByteOrder.BIG_ENDIAN);
        for (Float value : values) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    static int validate(List<Float> values) {
        if (values == null || values.isEmpty() || values.size() > MAX_DIMENSION) {
            throw new IllegalArgumentException("embedding vector dimension is invalid");
        }
        double norm = 0;
        for (Float value : values) {
            if (value == null || !Float.isFinite(value)) {
                throw new IllegalArgumentException("embedding vector contains a non-finite value");
            }
            norm += (double) value * value;
        }
        if (norm == 0) {
            throw new IllegalArgumentException("embedding vector must have a non-zero norm");
        }
        return values.size();
    }

    static double cosine(List<Float> query, byte[] encoded, Integer dimension, String format) {
        if (query == null || encoded == null || dimension == null || dimension <= 0
                || dimension > MAX_DIMENSION || query.size() != dimension
                || encoded.length != dimension * Float.BYTES || !FORMAT.equals(format)) {
            return Double.NaN;
        }
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        double dot = 0;
        double queryNorm = 0;
        double storedNorm = 0;
        for (int index = 0; index < dimension; index++) {
            Float queryValue = query.get(index);
            float storedValue = buffer.getFloat();
            if (queryValue == null || !Float.isFinite(queryValue) || !Float.isFinite(storedValue)) {
                return Double.NaN;
            }
            dot += queryValue * storedValue;
            queryNorm += queryValue * queryValue;
            storedNorm += storedValue * storedValue;
        }
        if (queryNorm == 0 || storedNorm == 0) {
            return Double.NaN;
        }
        return Math.max(-1, Math.min(1, dot / Math.sqrt(queryNorm * storedNorm)));
    }
}
