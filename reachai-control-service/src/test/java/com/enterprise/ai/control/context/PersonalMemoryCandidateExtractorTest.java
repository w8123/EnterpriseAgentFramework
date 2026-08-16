package com.enterprise.ai.control.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalMemoryCandidateExtractorTest {

    private final PersonalMemoryCandidateExtractor extractor = new PersonalMemoryCandidateExtractor();

    @Test
    void extractsStablePreferenceSemanticKey() {
        var result = extractor.extract("我希望以后都用中文回答");

        assertEquals(1, result.size());
        assertEquals("PREFERENCE", result.get(0).type());
        assertEquals("response-language", result.get(0).semanticKey());
    }

    @Test
    void explicitRememberIsMarkedForDirectConfirmation() {
        var result = extractor.extract("请记住我住在青岛");

        assertEquals(1, result.size());
        assertTrue(result.get(0).explicit());
        assertEquals("FACT", result.get(0).type());
        assertEquals("profile:location", result.get(0).semanticKey());
        assertTrue(extractor.hasExplicitRememberSignal("请记住我住在青岛"));
    }

    @Test
    void ignoresQuestionsAndSecretLikeText() {
        assertTrue(extractor.extract("我住在青岛吗？").isEmpty());
        assertTrue(extractor.extract("请记住 api_key=sk-abcdefghijklmnopqrstuvwxyz123456").isEmpty());
    }

    @Test
    void classifiesANameAsAProfileFact() {
        var result = extractor.extract("我的名字是小明");

        assertEquals(1, result.size());
        assertEquals("FACT", result.get(0).type());
        assertEquals("profile:name", result.get(0).semanticKey());
        assertEquals("姓名", result.get(0).title());
    }

    @Test
    void acceptsOnlyDirectiveLikeFutureRules() {
        var directive = extractor.extract("以后请都用中文回答");

        assertEquals(1, directive.size());
        assertEquals("RULE", directive.get(0).type());
        assertTrue(directive.get(0).explicit());
        assertTrue(extractor.extract("以后北京天气怎么样？").isEmpty());
        assertTrue(!extractor.hasExplicitRememberSignal("以后北京天气怎么样？"));
    }
}
