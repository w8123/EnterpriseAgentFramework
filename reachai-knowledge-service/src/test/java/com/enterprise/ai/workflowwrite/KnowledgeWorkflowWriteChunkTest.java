package com.enterprise.ai.workflowwrite;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeWorkflowWriteChunkTest {

    @Test
    void splitsByParagraphWithinChunkSize() {
        String longParagraph = "x".repeat(1700);
        List<String> chunks = KnowledgeWorkflowWriteInternalController.chunk("第一段\n\n第二段\n\n" + longParagraph);
        assertEquals("第一段\n\n第二段", chunks.get(0));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.length() <= KnowledgeWorkflowWriteInternalController.CHUNK_SIZE));
        assertEquals(1700, chunks.stream().skip(1).mapToInt(String::length).sum());
        assertTrue(KnowledgeWorkflowWriteInternalController.chunk("  ").isEmpty());
    }
}
