package com.enterprise.ai.control.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalMemoryRememberExtractorTest {

    private final PersonalMemoryRememberExtractor extractor = new PersonalMemoryRememberExtractor();

    @Test
    void explicitRememberKeepsTheProfileSemanticKey() {
        var result = extractor.extract("请记住我住在青岛");

        assertEquals(1, result.size());
        assertEquals("FACT", result.get(0).type());
        assertEquals("profile:location", result.get(0).semanticKey());
        assertTrue(extractor.hasExplicitRememberSignal("请记住我住在青岛"));
    }

    @Test
    void explicitPreferenceKeepsTheResponseLanguageKey() {
        var result = extractor.extract("记住我希望用中文回答");

        assertEquals(1, result.size());
        assertEquals("PREFERENCE", result.get(0).type());
        assertEquals("response-language", result.get(0).semanticKey());
    }

    @Test
    void ordinaryStatementsAreNotExtracted() {
        assertTrue(extractor.extract("我希望以后都用中文回答").isEmpty());
        assertTrue(extractor.extract("我的名字是小明").isEmpty());
    }

    @Test
    void ignoresQuestionsAndSecretLikeText() {
        assertTrue(extractor.extract("请记住我住在青岛吗？").isEmpty());
        assertTrue(extractor.extract("请记住 api_key=sk-abcdefghijklmnopqrstuvwxyz123456").isEmpty());
    }

    @Test
    void acceptsOnlyDirectiveLikeFutureRules() {
        var directive = extractor.extract("以后请都用中文回答");

        assertEquals(1, directive.size());
        assertEquals("RULE", directive.get(0).type());
        assertTrue(extractor.extract("以后北京天气怎么样？").isEmpty());
        assertTrue(!extractor.hasExplicitRememberSignal("以后北京天气怎么样？"));
    }
}
