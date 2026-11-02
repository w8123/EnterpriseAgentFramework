package com.enterprise.ai.control.pageworkbench.api;

import com.enterprise.ai.control.compat.RegistryInstanceConsoleOperations;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsolePermissionPolicy;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.mcp.application.port.McpPlatformProjectPagesPort;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.ManualPageCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** These controllers rely on the console's project route rule, so this adapter applies the same rule before calling them. */
@Component
@RequiredArgsConstructor
class PageWorkbenchMcpPlatformAdapter implements McpPlatformProjectPagesPort {
    private final PageWorkbenchConsoleController workbench;
    private final RegistryInstanceConsoleOperations instances;
    private final PlatformDelegatedRequestScope scope;
    private final ObjectMapper json;

    @Override
    public ResponseEntity<?> read(PlatformAuthenticatedSession actor, String projectCode, Read read, Map<String, Object> query) {
        String base = "/api/registry/projects/" + projectCode;
        admit(actor, "GET", read == Read.INSTANCES ? base + "/instances" : base + "/page-workbench/" + read.name().toLowerCase().replace('_', '-'));
        return scope.run(actor, request -> switch (read) {
            case ACCESS_CENTER -> workbench.accessCenter(projectCode);
            case PAGES -> workbench.pages(projectCode, Boolean.TRUE.equals(query.get("includeArchived")));
            case PAGE -> workbench.page(projectCode, id(query, "pageId"));
            case PAGE_READINESS -> workbench.pageReadiness(projectCode, id(query, "pageId"));
            case PAGE_MAP -> workbench.pageMap(projectCode);
            case FINDINGS -> workbench.findings(projectCode, query.get("pageId") == null ? null : id(query, "pageId"), text(query, "status"));
            case PUBLISHED -> workbench.published(projectCode, text(query, "pageKey"));
            case INSTANCES -> instances.list(projectCode);
        });
    }

    @Override
    public ResponseEntity<?> write(PlatformAuthenticatedSession actor, String projectCode, Write write, Long targetId, Map<String, Object> body) {
        String base = "/api/registry/projects/" + projectCode;
        switch (write) {
            case CREATE_PAGE -> admit(actor, "POST", base + "/page-workbench/pages");
            case FINDING_STATUS -> admit(actor, "PATCH", base + "/page-workbench/findings/" + targetId + "/status");
            case INSTANCE_STATUS -> admit(actor, "POST", base + "/instances/status");
            case PURGE_OFFLINE_INSTANCES -> admit(actor, "POST", base + "/instances/purge-offline");
        }
        return scope.run(actor, request -> switch (write) {
            case CREATE_PAGE -> workbench.createPage(projectCode, json.convertValue(body, ManualPageCommand.class));
            case FINDING_STATUS -> workbench.updateFindingStatus(projectCode, targetId,
                    new PageWorkbenchConsoleController.FindingStatusRequest(text(body, "status"), actor.user().getUsername()));
            case INSTANCE_STATUS -> instances.change(request, projectCode, "status", body);
            case PURGE_OFFLINE_INSTANCES -> instances.change(request, projectCode, "purge-offline", body);
        });
    }

    private static void admit(PlatformAuthenticatedSession actor, String method, String path) {
        var rule = PlatformConsolePermissionPolicy.ruleFor(path).orElseThrow();
        if (actor == null || actor.user() == null || !PlatformConsolePermissionPolicy.admits(rule, method, path, actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "project permission is required");
        }
    }

    private static long id(Map<String, Object> query, String key) {
        if (!(query.get(key) instanceof Number value) || value.longValue() <= 0) throw new IllegalArgumentException(key + " is invalid");
        return value.longValue();
    }

    private static String text(Map<String, Object> query, String key) {
        return query != null && query.get(key) instanceof String value ? value : null;
    }
}
