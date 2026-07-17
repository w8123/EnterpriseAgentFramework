package com.enterprise.ai.model.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelApiUrlTest {

    @Test
    void joinsGeminiChatAndEmbeddings() {
        String root = "https://generativelanguage.googleapis.com/v1beta/openai";
        assertEquals(
                "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                ModelApiUrl.join(root, ModelApiUrl.DEFAULT_CHAT_PATH));
        assertEquals(
                "https://generativelanguage.googleapis.com/v1beta/openai/embeddings",
                ModelApiUrl.join(root, ModelApiUrl.DEFAULT_EMBEDDING_PATH));
    }

    @Test
    void joinsQianfanV2() {
        String root = "https://qianfan.baidubce.com/v2";
        assertEquals(
                "https://qianfan.baidubce.com/v2/chat/completions",
                ModelApiUrl.join(root, "/chat/completions"));
        assertEquals(
                "https://qianfan.baidubce.com/v2/embeddings",
                ModelApiUrl.join(root, "/embeddings"));
    }

    @Test
    void joinsVolcengineArk() {
        String root = "https://ark.cn-beijing.volces.com/api/v3";
        assertEquals(
                "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
                ModelApiUrl.join(root, "/chat/completions"));
        assertEquals(
                "https://ark.cn-beijing.volces.com/api/v3/embeddings",
                ModelApiUrl.join(root, "/embeddings"));
    }

    @Test
    void joinsAzureOpenAiV1() {
        String root = "https://my-resource.openai.azure.com/openai/v1";
        assertEquals(
                "https://my-resource.openai.azure.com/openai/v1/chat/completions",
                ModelApiUrl.join(root, "/chat/completions"));
    }

    @Test
    void joinsOpenAiOfficialV1() {
        String root = "https://api.openai.com/v1";
        assertEquals(
                "https://api.openai.com/v1/chat/completions",
                ModelApiUrl.join(root, "/chat/completions"));
        assertEquals(
                "https://api.openai.com/v1/chat/completions",
                OpenAiCompatibleRuntimeClient.joinApiUrl(root, ModelApiUrl.DEFAULT_CHAT_PATH));
    }

    @Test
    void stripsTrailingSlashOnRoot() {
        assertEquals(
                "https://api.openai.com/v1/embeddings",
                ModelApiUrl.join("https://api.openai.com/v1/", "/embeddings"));
    }

    @Test
    void rejectsAbsoluteRelativePath() {
        assertThrows(IllegalArgumentException.class,
                () -> ModelApiUrl.join("https://api.openai.com/v1", "https://evil.example/chat"));
    }
}
