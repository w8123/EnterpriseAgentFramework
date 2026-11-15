package com.enterprise.ai.runtime.automation;

import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient.AutomationRunAsRequest;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient.AutomationRunAsResolution;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * An Automation runs as the employee who saved its pinned version, in that person's account of the
 * target business system. Control re-checks the runtime user mapping and the business account before
 * every run. When it cannot confirm one person, the run keeps the non-human service account, which
 * can only do less: methods that need an employee identity are then rejected by Capability.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "reachai.runtime.automation.enabled", havingValue = "true")
class RuntimeAutomationRunAs {

    static final String SERVICE_ACCOUNT = "SERVICE_ACCOUNT";
    static final String EMPLOYEE = "EMPLOYEE";

    private final RuntimeControlCatalogClient controlClient;

    Decision decide(RuntimeAutomationEntity automation, RuntimeAutomationVersionEntity version) {
        WorkflowExecutionIdentity serviceAccount = WorkflowExecutionIdentity.fromAutomation(
                automation.getTenantId(), automation.getProjectId(), automation.getProjectCode(),
                version.getPrincipalId());
        if (!StringUtils.hasText(version.getCreatedBy()) || !StringUtils.hasText(automation.getProjectCode())) {
            return new Decision(serviceAccount, SERVICE_ACCOUNT, "AUTOMATION_RUN_AS_INVALID", null);
        }
        AutomationRunAsResolution resolution;
        try {
            resolution = controlClient.resolveAutomationRunAs(new AutomationRunAsRequest(
                    automation.getTenantId(), version.getCreatedBy(), automation.getProjectId(),
                    automation.getProjectCode()));
        } catch (RuntimeException unavailable) {
            log.warn("[Automation] run-as lookup failed for {}, keeping the service account: {}",
                    automation.getAutomationKey(), unavailable.getClass().getSimpleName());
            return new Decision(serviceAccount, SERVICE_ACCOUNT, "AUTOMATION_RUN_AS_UNAVAILABLE", null);
        }
        if (resolution == null || !resolution.resolved() || !StringUtils.hasText(resolution.externalUserId())) {
            return new Decision(serviceAccount, SERVICE_ACCOUNT,
                    resolution == null ? "AUTOMATION_RUN_AS_UNAVAILABLE" : resolution.code(), null);
        }
        WorkflowExecutionIdentity employee = WorkflowExecutionIdentity.fromAutomationEmployee(
                StringUtils.hasText(resolution.tenantId()) ? resolution.tenantId() : automation.getTenantId(),
                automation.getProjectId(), automation.getProjectCode(), resolution.externalUserId(),
                resolution.roles());
        return new Decision(employee, EMPLOYEE, resolution.code(), resolution.externalUserId());
    }

    /** Plain-language reason a service-account run could not act as an employee. */
    static String serviceAccountHint(String reason) {
        String why = switch (reason == null ? "" : reason) {
            case "AUTOMATION_RUN_AS_NO_MAPPING" ->
                    "最后保存这个自动化的人，在这个业务系统里还没有「运行时用户映射」";
            case "AUTOMATION_RUN_AS_AMBIGUOUS" ->
                    "最后保存这个自动化的人，在这个业务系统里映射了不止一个账号，无法确定用哪一个";
            case "AUTOMATION_RUN_AS_ACCOUNT_INACTIVE" ->
                    "最后保存这个自动化的人映射的业务账号已停用或未同步";
            case "AUTOMATION_RUN_AS_UNAVAILABLE" -> "运行时暂时查不到运行身份";
            default -> "这个自动化没有可用的员工身份";
        };
        return "这一步需要员工身份，但本次自动化只能用服务账号运行：" + why
                + "。请平台管理员在「身份与权限 → 运行时用户映射」里为他绑定业务系统账号，或换一位有账号的人重新保存自动化。";
    }

    record Decision(WorkflowExecutionIdentity identity, String mode, String reason, String externalUserId) {
    }
}
