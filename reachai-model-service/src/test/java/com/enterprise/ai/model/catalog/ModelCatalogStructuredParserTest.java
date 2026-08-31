package com.enterprise.ai.model.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelCatalogStructuredParserTest {

    @Test
    void parsesCommonModelsApiShapesDeterministically() {
        ModelCatalogSourceEntity source = new ModelCatalogSourceEntity();
        source.setProvider("gemini");
        ModelCatalogAnalysis analysis = new ModelCatalogStructuredParser(new ObjectMapper()).parse(source, """
                {"models":[
                  {"name":"models/gemini-3.7-flash","displayName":"Gemini 3.7 Flash",
                   "supportedGenerationMethods":["generateContent"],"inputTokenLimit":1048576},
                  {"name":"models/gemini-embedding-001","supportedGenerationMethods":["embedContent"]}
                ]}
                """);

        assertEquals("DETERMINISTIC", analysis.analysisStatus());
        assertEquals(2, analysis.candidates().size());
        assertEquals("gemini-3.7-flash", analysis.candidates().get(0).modelName());
        assertEquals("LLM", analysis.candidates().get(0).modelType());
        assertEquals("EMBEDDING", analysis.candidates().get(1).modelType());
    }
}
