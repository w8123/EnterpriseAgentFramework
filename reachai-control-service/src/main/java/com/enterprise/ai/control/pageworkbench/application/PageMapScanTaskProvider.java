package com.enterprise.ai.control.pageworkbench.application;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactApplyResult;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactEnvelope;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ReadinessItem;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskContract;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskDescriptor;
import com.enterprise.ai.control.aicoding.provider.AiCodingContractResourceLoader;
import com.enterprise.ai.control.aicoding.provider.AiCodingTaskKindProvider;
import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageMapPayload;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageReport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class PageMapScanTaskProvider implements AiCodingTaskKindProvider {

    public static final String TASK_KIND = "PAGE_MAP_SCAN";
    public static final String CONTRACT_KEY = "reachai.page-map-report";
    public static final String CONTRACT_VERSION = "v1";

    private final CapabilityProjectOnboardingClient capabilityClient;
    private final PageCatalogApplicationService pageCatalog;
    private final ObjectMapper objectMapper;
    private final TaskContract contract;

    public PageMapScanTaskProvider(
            CapabilityProjectOnboardingClient capabilityClient,
            PageCatalogApplicationService pageCatalog,
            ObjectMapper objectMapper,
            AiCodingContractResourceLoader resourceLoader) {
        this.capabilityClient = capabilityClient;
        this.pageCatalog = pageCatalog;
        this.objectMapper = objectMapper;
        this.contract = new TaskContract(
                "BUSINESS_PAGE_WORKBENCH",
                TASK_KIND,
                "READ_ONLY",
                "PROJECT",
                CONTRACT_KEY,
                CONTRACT_VERSION,
                resourceLoader.load("page-map-report-v1.schema.json"),
                resourceLoader.load("page-map-report-v1.example.json"));
    }

    @Override
    public String kind() {
        return TASK_KIND;
    }

    @Override
    public TaskContract contract() {
        return contract;
    }

    @Override
    public JsonNode buildContext(TaskDescriptor task) {
        Map<String, Object> source = capabilityClient.getOnboardingProjectById(
                task.projectId());
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schema", "reachai.ai-coding.page-map-context.v1");
        root.set("project", safeProject(source, task));

        ObjectNode scope = root.putObject("scope");
        scope.put("accessMode", "READ_ONLY");
        scope.putArray("includedRules")
                .add("Inspect routes, page components, directly associated APIs and permission declarations.")
                .add("Limit discovery to this project's business-page source tree.")
                .add("Use concise Simplified Chinese for human-readable module names, page names, descriptions, resource display names and action titles/descriptions. Keep technical identifiers, routes, component paths, API methods and enum values unchanged.")
                .add("project.baseUrl is the business backend or gateway API address. Never use it as a browser page origin.")
                .add("Set pages[].businessPageUrl only when the repository exposes an actual absolute HTTP(S) frontend URL or development-server origin. Otherwise return null; never guess it from project.baseUrl.");
        scope.putArray("excludedRules")
                .add("Do not modify business repository files.")
                .add("Do not inspect unrelated modules, dependencies, caches or generated output.")
                .add("Do not infer business value, priority or high-value actions.");
        scope.putArray("requiredChecks")
                .add("Every pageKey must be stable within the project.")
                .add("Every reported route, component and API location must come from inspected source.")
                .add("All human-readable titles and descriptions in the report must be Simplified Chinese.")
                .add("Return the report through the current task artifact endpoint.");

        root.set(
                "existingPages",
                objectMapper.valueToTree(pageCatalog.listPages(task.projectCode(), false)));
        List<Map<String, Object>> tools = capabilityClient.listProjectTools(
                task.projectId());
        root.set(
                "registeredCapabilities",
                objectMapper.valueToTree(tools == null ? List.of() : tools));

        ObjectNode instructions = root.putObject("instructions");
        instructions.put("objective", task.objective());
        instructions.put("scanModeGuidance",
                "Use FULL only for the first complete page-map snapshot; otherwise use INCREMENTAL.");
        instructions.put("discoveryBoundary",
                "This task establishes the page map only. Page interaction analysis is a separate task.");
        instructions.put("resultLimit",
                "Do not return analysis findings or automatically generated high-value actions.");
        instructions.put("humanLanguage",
                "Human-readable output uses Simplified Chinese; technical identities remain unchanged.");
        return root;
    }

    @Override
    public List<ReadinessItem> readiness(TaskDescriptor task) {
        return List.of();
    }

    @Override
    public ArtifactApplyResult applyArtifact(
            TaskDescriptor task,
            ArtifactEnvelope artifact) {
        PageMapPayload report;
        try {
            report = objectMapper.treeToValue(artifact.content(), PageMapPayload.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(
                    "Page-map artifact does not match " + CONTRACT_KEY + "/" + CONTRACT_VERSION,
                    ex);
        }
        validate(report);
        int applied = pageCatalog.applyPageMap(
                task.projectId(),
                task.projectCode(),
                report.scanMode(),
                report.scannedAt(),
                report.pages());

        ObjectNode result = objectMapper.createObjectNode();
        result.put("appliedPages", applied);
        result.put("scanMode", report.scanMode().toUpperCase());
        putText(result, "repositoryBranch", report.repositoryBranch());
        putText(result, "repositoryRevision", report.repositoryRevision());
        result.put("scannedAt", (report.scannedAt() == null
                ? LocalDateTime.now()
                : report.scannedAt()).toString());
        return ArtifactApplyResult.complete(
                "页面地图报告已校验并写入，共 " + applied + " 个页面",
                result);
    }

    private void validate(PageMapPayload report) {
        if (report == null) {
            throw new IllegalArgumentException("page-map report is required");
        }
        String scanMode = requireText(report.scanMode(), "scanMode").toUpperCase();
        if (!Set.of("FULL", "INCREMENTAL").contains(scanMode)) {
            throw new IllegalArgumentException(
                    "scanMode must be FULL or INCREMENTAL");
        }
        List<PageReport> pages = report.pages() == null ? List.of() : report.pages();
        if (pages.size() > 5000) {
            throw new IllegalArgumentException(
                    "page-map report exceeds the 5000-page task limit");
        }
        Set<String> pageKeys = new HashSet<>();
        for (PageReport page : pages) {
            if (page == null) {
                throw new IllegalArgumentException("pages cannot contain null");
            }
            String pageKey = requireText(page.pageKey(), "pages[].pageKey");
            requireText(page.name(), "pages[].name");
            if (!StringUtils.hasText(page.routePattern())
                    && !StringUtils.hasText(page.componentPath())) {
                throw new IllegalArgumentException(
                        "page " + pageKey + " must include routePattern or componentPath");
            }
            if (!pageKeys.add(pageKey)) {
                throw new IllegalArgumentException(
                        "duplicate pageKey in page-map report: " + pageKey);
            }
        }
    }

    private ObjectNode safeProject(
            Map<String, Object> source,
            TaskDescriptor task) {
        Map<String, Object> project = source == null ? Map.of() : source;
        ObjectNode safe = objectMapper.createObjectNode();
        safe.put("id", task.projectId());
        safe.put("projectCode", task.projectCode());
        copyText(project, safe, "name");
        copyText(project, safe, "projectKind");
        copyText(project, safe, "environment");
        copyText(project, safe, "baseUrl");
        copyText(project, safe, "contextPath");
        copyText(project, safe, "repositoryUrl");
        copyText(project, safe, "repositoryRoot");
        return safe;
    }

    private static void copyText(
            Map<String, Object> source,
            ObjectNode target,
            String field) {
        Object value = source.get(field);
        if (value != null && StringUtils.hasText(String.valueOf(value))) {
            target.put(field, String.valueOf(value));
        }
    }

    private static String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static void putText(
            ObjectNode target,
            String field,
            String value) {
        if (StringUtils.hasText(value)) {
            target.put(field, value.trim());
        }
    }
}
