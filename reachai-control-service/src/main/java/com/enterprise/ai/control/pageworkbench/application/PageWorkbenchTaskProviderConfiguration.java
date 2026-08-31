package com.enterprise.ai.control.pageworkbench.application;

import com.enterprise.ai.control.aicoding.provider.AiCodingContractResourceLoader;
import com.enterprise.ai.control.aicoding.provider.AiCodingTaskKindProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PageWorkbenchTaskProviderConfiguration {

    @Bean
    public AiCodingTaskKindProvider pageReadonlyAnalysisTaskProvider(
            PageCatalogApplicationService pageCatalog,
            PageAnalysisApplicationService pageAnalysis,
            PageWorkbenchProjectPort capabilityClient,
            PageWorkbenchModelPort modelCatalogClient,
            PageWorkbenchRuntimePort runtimeClient,
            PageWorkbenchBrowserReadinessApplicationService browserReadiness,
            PageWorkbenchAgentModelReadinessApplicationService modelReadiness,
            PageWorkbenchWorkflowTraceReadinessApplicationService
                    workflowTraceReadiness,
            PageWorkbenchReleaseReadinessApplicationService releaseReadiness,
            ObjectMapper objectMapper,
            AiCodingContractResourceLoader resources) {
        return provider(
                PageWorkbenchTaskProvider.PAGE_ANALYSIS,
                "READ_ONLY",
                "reachai.page-analysis-report",
                "page-analysis-report-v1",
                pageCatalog,
                pageAnalysis,
                capabilityClient,
                modelCatalogClient,
                runtimeClient,
                browserReadiness,
                modelReadiness,
                workflowTraceReadiness,
                releaseReadiness,
                objectMapper,
                resources);
    }

    @Bean
    public AiCodingTaskKindProvider workflowEngineeringTaskProvider(
            PageCatalogApplicationService pageCatalog,
            PageAnalysisApplicationService pageAnalysis,
            PageWorkbenchProjectPort capabilityClient,
            PageWorkbenchModelPort modelCatalogClient,
            PageWorkbenchRuntimePort runtimeClient,
            PageWorkbenchBrowserReadinessApplicationService browserReadiness,
            PageWorkbenchAgentModelReadinessApplicationService modelReadiness,
            PageWorkbenchWorkflowTraceReadinessApplicationService
                    workflowTraceReadiness,
            PageWorkbenchReleaseReadinessApplicationService releaseReadiness,
            ObjectMapper objectMapper,
            AiCodingContractResourceLoader resources) {
        return provider(
                PageWorkbenchTaskProvider.WORKFLOW_ENGINEERING,
                "READ_ONLY",
                "reachai.workflow-engineering-report",
                "workflow-engineering-report-v1",
                pageCatalog,
                pageAnalysis,
                capabilityClient,
                modelCatalogClient,
                runtimeClient,
                browserReadiness,
                modelReadiness,
                workflowTraceReadiness,
                releaseReadiness,
                objectMapper,
                resources);
    }

    @Bean
    public AiCodingTaskKindProvider codeImplementationTaskProvider(
            PageCatalogApplicationService pageCatalog,
            PageAnalysisApplicationService pageAnalysis,
            PageWorkbenchProjectPort capabilityClient,
            PageWorkbenchModelPort modelCatalogClient,
            PageWorkbenchRuntimePort runtimeClient,
            PageWorkbenchBrowserReadinessApplicationService browserReadiness,
            PageWorkbenchAgentModelReadinessApplicationService modelReadiness,
            PageWorkbenchWorkflowTraceReadinessApplicationService
                    workflowTraceReadiness,
            PageWorkbenchReleaseReadinessApplicationService releaseReadiness,
            ObjectMapper objectMapper,
            AiCodingContractResourceLoader resources) {
        return provider(
                PageWorkbenchTaskProvider.CODE_IMPLEMENTATION,
                "READ_WRITE",
                "reachai.code-implementation-report",
                "code-implementation-report-v1",
                pageCatalog,
                pageAnalysis,
                capabilityClient,
                modelCatalogClient,
                runtimeClient,
                browserReadiness,
                modelReadiness,
                workflowTraceReadiness,
                releaseReadiness,
                objectMapper,
                resources);
    }

    @Bean
    public AiCodingTaskKindProvider browserAcceptanceTaskProvider(
            PageCatalogApplicationService pageCatalog,
            PageAnalysisApplicationService pageAnalysis,
            PageWorkbenchProjectPort capabilityClient,
            PageWorkbenchModelPort modelCatalogClient,
            PageWorkbenchRuntimePort runtimeClient,
            PageWorkbenchBrowserReadinessApplicationService browserReadiness,
            PageWorkbenchAgentModelReadinessApplicationService modelReadiness,
            PageWorkbenchWorkflowTraceReadinessApplicationService
                    workflowTraceReadiness,
            PageWorkbenchReleaseReadinessApplicationService releaseReadiness,
            ObjectMapper objectMapper,
            AiCodingContractResourceLoader resources) {
        return provider(
                PageWorkbenchTaskProvider.BROWSER_ACCEPTANCE,
                "READ_WRITE",
                "reachai.browser-acceptance-report",
                "browser-acceptance-report-v1",
                pageCatalog,
                pageAnalysis,
                capabilityClient,
                modelCatalogClient,
                runtimeClient,
                browserReadiness,
                modelReadiness,
                workflowTraceReadiness,
                releaseReadiness,
                objectMapper,
                resources);
    }

    @Bean
    public AiCodingTaskKindProvider preReleaseCheckTaskProvider(
            PageCatalogApplicationService pageCatalog,
            PageAnalysisApplicationService pageAnalysis,
            PageWorkbenchProjectPort capabilityClient,
            PageWorkbenchModelPort modelCatalogClient,
            PageWorkbenchRuntimePort runtimeClient,
            PageWorkbenchBrowserReadinessApplicationService browserReadiness,
            PageWorkbenchAgentModelReadinessApplicationService modelReadiness,
            PageWorkbenchWorkflowTraceReadinessApplicationService
                    workflowTraceReadiness,
            PageWorkbenchReleaseReadinessApplicationService releaseReadiness,
            ObjectMapper objectMapper,
            AiCodingContractResourceLoader resources) {
        return provider(
                PageWorkbenchTaskProvider.PRE_RELEASE_CHECK,
                "READ_ONLY",
                "reachai.pre-release-report",
                "pre-release-report-v1",
                pageCatalog,
                pageAnalysis,
                capabilityClient,
                modelCatalogClient,
                runtimeClient,
                browserReadiness,
                modelReadiness,
                workflowTraceReadiness,
                releaseReadiness,
                objectMapper,
                resources);
    }

    private AiCodingTaskKindProvider provider(
            String taskKind,
            String accessMode,
            String contractKey,
            String resourcePrefix,
            PageCatalogApplicationService pageCatalog,
            PageAnalysisApplicationService pageAnalysis,
            PageWorkbenchProjectPort capabilityClient,
            PageWorkbenchModelPort modelCatalogClient,
            PageWorkbenchRuntimePort runtimeClient,
            PageWorkbenchBrowserReadinessApplicationService browserReadiness,
            PageWorkbenchAgentModelReadinessApplicationService modelReadiness,
            PageWorkbenchWorkflowTraceReadinessApplicationService
                    workflowTraceReadiness,
            PageWorkbenchReleaseReadinessApplicationService releaseReadiness,
            ObjectMapper objectMapper,
            AiCodingContractResourceLoader resources) {
        return new PageWorkbenchTaskProvider(
                taskKind,
                accessMode,
                contractKey,
                resources.load(resourcePrefix + ".schema.json"),
                resources.load(resourcePrefix + ".example.json"),
                pageCatalog,
                pageAnalysis,
                capabilityClient,
                modelCatalogClient,
                runtimeClient,
                browserReadiness,
                modelReadiness,
                workflowTraceReadiness,
                releaseReadiness,
                objectMapper);
    }
}
