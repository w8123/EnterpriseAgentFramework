package com.enterprise.ai.agent.registry;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectService;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SdkAccessCheckService {

    private final ScanProjectService scanProjectService;
    private final RegistrySecurityService registrySecurityService;
    private final AiRegistryService aiRegistryService;
    private final ScanProjectToolService scanProjectToolService;

    public SdkAccessCheckResponse check(Long projectId, SdkAccessCheckRequest request) {
        ScanProjectEntity project = scanProjectService.getById(projectId);
        List<SdkAccessCheckItem> checks = new ArrayList<>();

        if (!isSdkBacked(project)) {
            checks.add(new SdkAccessCheckItem(
                    "project-kind",
                    "项目接入方式",
                    CheckStatus.FAIL,
                    "当前项目不是 SDK 接入项目，请使用扫描项目工作台。",
                    project.getProjectKind()));
            return new SdkAccessCheckResponse(project.getId(), project.getProjectCode(), CheckStatus.FAIL,
                    buildReadiness(checks), checks);
        }

        checks.add(new SdkAccessCheckItem(
                "project-kind",
                "项目接入方式",
                CheckStatus.PASS,
                "项目已按 SDK / 混合接入方式管理。",
                project.getProjectKind()));

        boolean hasCredential = StringUtils.hasText(project.getProjectCode())
                && registrySecurityService.findPrimaryActiveCredential(project.getProjectCode()).isPresent();
        checks.add(new SdkAccessCheckItem(
                "registry-credential",
                "服务端对接凭证",
                hasCredential ? CheckStatus.PASS : CheckStatus.FAIL,
                hasCredential ? "后端已配置项目对接凭证。" : "后端尚未配置项目对接凭证。",
                hasCredential ? "configured=true" : "configured=false"));

        List<ProjectInstanceEntity> instances = listInstances(project);
        long onlineCount = instances.stream().filter(item -> "ONLINE".equalsIgnoreCase(item.getStatus())).count();
        checks.add(new SdkAccessCheckItem(
                "online-instance",
                "SDK 实例心跳",
                onlineCount > 0 ? CheckStatus.PASS : CheckStatus.WARN,
                onlineCount > 0 ? "检测到在线 SDK 实例。" : "暂未检测到在线 SDK 实例。",
                onlineCount + " online / " + instances.size() + " total"));

        List<ScanProjectToolEntity> tools = scanProjectToolService.listByProject(project.getId());
        long callableCount = tools.stream()
                .filter(tool -> Boolean.TRUE.equals(tool.getEnabled()))
                .filter(tool -> Boolean.TRUE.equals(tool.getAgentVisible()))
                .count();
        checks.add(new SdkAccessCheckItem(
                "api-assets",
                "API 资产",
                callableCount > 0 ? CheckStatus.PASS : CheckStatus.WARN,
                callableCount > 0 ? "检测到可被 Agent 调用的 API 资产。" : "暂未检测到可调用 API 资产。",
                callableCount + " callable / " + tools.size() + " total"));

        String gatewayBaseUrl = trim(request == null ? null : request.gatewayBaseUrl());
        checks.add(new SdkAccessCheckItem(
                "gateway-route",
                "网关路由",
                StringUtils.hasText(gatewayBaseUrl) ? CheckStatus.PASS : CheckStatus.WARN,
                StringUtils.hasText(gatewayBaseUrl) ? "已提供网关入口地址，可继续做真实调用自检。" : "请确认网关路由已配置并透传调用头。",
                StringUtils.hasText(gatewayBaseUrl) ? gatewayBaseUrl : "manual-confirm-required"));

        String embedTokenPath = trim(request == null ? null : request.embedTokenPath());
        checks.add(new SdkAccessCheckItem(
                "embed-token",
                "前端 Embed Token",
                StringUtils.hasText(embedTokenPath) ? CheckStatus.PASS : CheckStatus.WARN,
                StringUtils.hasText(embedTokenPath) ? "已提供业务系统短期 token broker 路径。" : "请确认业务系统已提供 embed token broker。",
                StringUtils.hasText(embedTokenPath) ? embedTokenPath : "manual-confirm-required"));

        checks.add(runApiInvocationCheck(project.getId(), request));

        return new SdkAccessCheckResponse(project.getId(), project.getProjectCode(), overallStatus(checks),
                buildReadiness(checks), checks);
    }

    private SdkAccessCheckItem runApiInvocationCheck(Long projectId, SdkAccessCheckRequest request) {
        Long apiAssetId = request == null ? null : request.apiAssetId();
        if (apiAssetId == null) {
            return new SdkAccessCheckItem(
                    "api-invocation",
                    "最终接口自检",
                    CheckStatus.WARN,
                    "请选择一个 API 资产后发起真实调用自检。",
                    "apiAssetId=missing");
        }
        try {
            Object result = scanProjectToolService.execute(projectId, apiAssetId,
                    request.args() == null ? Map.of() : request.args());
            return new SdkAccessCheckItem(
                    "api-invocation",
                    "最终接口自检",
                    CheckStatus.PASS,
                    "平台已完成一次业务接口调用。",
                    String.valueOf(result));
        } catch (Exception ex) {
            return new SdkAccessCheckItem(
                    "api-invocation",
                    "最终接口自检",
                    CheckStatus.FAIL,
                    StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : "业务接口调用失败。",
                    ex.getClass().getSimpleName());
        }
    }

    private List<ProjectInstanceEntity> listInstances(ScanProjectEntity project) {
        if (!StringUtils.hasText(project.getProjectCode())) {
            return List.of();
        }
        try {
            return aiRegistryService.listInstances(project.getProjectCode());
        } catch (IllegalArgumentException ex) {
            return List.of();
        }
    }

    private static CheckStatus overallStatus(List<SdkAccessCheckItem> checks) {
        if (checks.stream().anyMatch(item -> item.status() == CheckStatus.FAIL)) {
            return CheckStatus.FAIL;
        }
        if (checks.stream().anyMatch(item -> item.status() == CheckStatus.WARN)) {
            return CheckStatus.WARN;
        }
        return CheckStatus.PASS;
    }

    private static List<SdkAccessReadiness> buildReadiness(List<SdkAccessCheckItem> checks) {
        CheckStatus codeReady = worstStatus(
                statusOf(checks, "project-kind"),
                statusOf(checks, "registry-credential"),
                statusOf(checks, "gateway-route"),
                statusOf(checks, "embed-token"));
        CheckStatus runtimeReady = worstStatus(
                statusOf(checks, "online-instance"),
                statusOf(checks, "api-assets"));
        CheckStatus e2eReady = statusOf(checks, "api-invocation");
        return List.of(
                new SdkAccessReadiness(
                        "CODE_READY",
                        "代码与配置就绪",
                        codeReady,
                        switch (codeReady) {
                            case PASS -> "manifest、凭证、网关入口和 token broker 路径已具备。";
                            case WARN -> "代码或配置基本存在，但仍有人工确认项。";
                            case FAIL -> "项目类型或基础凭证不满足 SDK 接入要求。";
                        }),
                new SdkAccessReadiness(
                        "RUNTIME_READY",
                        "运行实例与 API 资产就绪",
                        runtimeReady,
                        switch (runtimeReady) {
                            case PASS -> "检测到在线 SDK 实例和可调用 API 资产。";
                            case WARN -> "代码可能已接好，但业务服务尚未上线心跳或 API 尚未同步。";
                            case FAIL -> "运行态检查失败。";
                        }),
                new SdkAccessReadiness(
                        "E2E_READY",
                        "端到端调用就绪",
                        e2eReady,
                        switch (e2eReady) {
                            case PASS -> "已完成一次真实 API 调用自检。";
                            case WARN -> "尚未选择 API 资产或缺少真实调用参数。";
                            case FAIL -> "真实 API 调用失败。";
                        })
        );
    }

    private static CheckStatus statusOf(List<SdkAccessCheckItem> checks, String key) {
        return checks.stream()
                .filter(item -> key.equals(item.key()))
                .findFirst()
                .map(SdkAccessCheckItem::status)
                .orElse(CheckStatus.WARN);
    }

    private static CheckStatus worstStatus(CheckStatus... statuses) {
        for (CheckStatus status : statuses) {
            if (status == CheckStatus.FAIL) {
                return CheckStatus.FAIL;
            }
        }
        for (CheckStatus status : statuses) {
            if (status == CheckStatus.WARN) {
                return CheckStatus.WARN;
            }
        }
        return CheckStatus.PASS;
    }

    private static boolean isSdkBacked(ScanProjectEntity project) {
        String kind = project.getProjectKind();
        return "REGISTERED".equalsIgnoreCase(kind) || "HYBRID".equalsIgnoreCase(kind);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    public enum CheckStatus {
        PASS,
        WARN,
        FAIL
    }

    public record SdkAccessCheckRequest(
            Long apiAssetId,
            Map<String, Object> args,
            String gatewayBaseUrl,
            String embedTokenPath
    ) {
    }

    public record SdkAccessCheckResponse(
            Long projectId,
            String projectCode,
            CheckStatus overallStatus,
            List<SdkAccessReadiness> readiness,
            List<SdkAccessCheckItem> checks
    ) {
    }

    public record SdkAccessReadiness(
            String key,
            String label,
            CheckStatus status,
            String message
    ) {
    }

    public record SdkAccessCheckItem(
            String key,
            String label,
            CheckStatus status,
            String message,
            String evidence
    ) {
    }
}
