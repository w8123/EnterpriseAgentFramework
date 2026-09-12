package com.enterprise.ai.control.aicoding.application;

import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskArtifactMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEventMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskQuestionMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskTargetMapper;
import com.enterprise.ai.control.aicoding.provider.AiCodingContractResourceLoader;
import com.enterprise.ai.control.aicoding.provider.AiCodingTaskProviderRegistry;
import com.enterprise.ai.control.aicoding.security.AiCodingSensitiveJsonSanitizer;
import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 为现有门面测试组装真实的内部职责组件，共用测试提供的持久化和外部依赖。 */
final class AiCodingTaskTestServices {
    private AiCodingTaskTestServices() { }

    static AiCodingTaskApplicationService create(
            AiCodingTaskMapper tasks,
            AiCodingTaskTargetMapper targets,
            AiCodingTaskEventMapper events,
            AiCodingTaskQuestionMapper questions,
            AiCodingTaskArtifactMapper artifacts,
            AiCodingTaskProviderRegistry providers,
            AiCodingArtifactProviderExecutor providerExecutor,
            AiCodingHandoffApplicationService handoffs,
            CapabilityProjectOnboardingClient capabilityClient,
            AiCodingSensitiveJsonSanitizer sanitizer,
            ObjectMapper objectMapper,
            AiCodingContractResourceLoader contracts) {
        var json = new AiCodingTaskJsonSupport(objectMapper);
        var stateChanges = new AiCodingTaskStateChanges(tasks, events, questions, json);
        var readModels = new AiCodingTaskDescriptorReader(targets, json);
        var delivery = new AiCodingTaskDeliveryService(artifacts, providers, providerExecutor, handoffs,
                sanitizer, objectMapper, json, stateChanges, readModels);
        var creation = new AiCodingTaskCreationService(tasks, targets, providers, capabilityClient, sanitizer, objectMapper, json, stateChanges);
        var queries = new AiCodingTaskQueryService(tasks, events, questions, artifacts,
                providers, handoffs, sanitizer, objectMapper, contracts,
                json, stateChanges, readModels, delivery);
        return new AiCodingTaskApplicationService(queries, delivery, creation,
                new AiCodingTaskEventService(stateChanges, questions, handoffs, sanitizer, json),
                new AiCodingTaskQuestionService(stateChanges, questions, sanitizer, objectMapper, json));
    }
}
