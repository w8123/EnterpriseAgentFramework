package com.enterprise.ai.agent.platform.control.identity;

import com.enterprise.ai.agent.platform.control.identity.PageActionCatalogContracts.PageActionDefinitionRequest;
import com.enterprise.ai.agent.platform.control.identity.PageActionCatalogContracts.PageCatalogRegisterRequest;
import com.enterprise.ai.agent.registry.RegistryCredentialEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PageActionCatalogService {

    private final PageRegistryMapper pageMapper;
    private final PageActionRegistryMapper actionMapper;
    private final ObjectMapper objectMapper;

    public PageCatalogRegisterResult registerFromProjectCredential(RegistryCredentialEntity credential,
                                                                   PageCatalogRegisterRequest request) {
        if (credential == null || !StringUtils.hasText(credential.getProjectCode())) {
            throw new IllegalArgumentException("project credential is required");
        }
        if (request == null) {
            throw new IllegalArgumentException("page register request is required");
        }
        String pageKey = requiredText(request.pageKey(), "page key is required");
        String projectCode = credential.getProjectCode().trim();
        String appId = StringUtils.hasText(credential.getAppKey()) ? credential.getAppKey().trim() : projectCode;
        LocalDateTime now = LocalDateTime.now();

        PageRegistryEntity page = new PageRegistryEntity();
        page.setProjectCode(projectCode);
        page.setAppId(appId);
        page.setPageKey(pageKey);
        page.setName(StringUtils.hasText(request.name()) ? request.name().trim() : pageKey);
        page.setRoutePattern(trimToNull(request.routePattern()));
        page.setOrigin(normalizedOrigin(request.origin()));
        page.setCurrentPageInstanceId(trimToNull(request.pageInstanceId()));
        page.setStatus("ACTIVE");
        page.setLastSeenAt(now);
        page.setMetadataJson(writeJson(request.metadata() == null ? Map.of() : request.metadata()));
        page.setCreatedAt(now);
        page.setUpdatedAt(now);
        pageMapper.upsert(page);

        List<PageActionDefinitionRequest> actions = request.actions() == null ? List.of() : request.actions();
        List<String> activeActionKeys = actions.stream()
                .map(PageActionDefinitionRequest::actionKey)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        for (PageActionDefinitionRequest action : actions) {
            upsertAction(projectCode, appId, pageKey, action, now);
        }
        if (Boolean.TRUE.equals(request.replaceActions())) {
            actionMapper.markMissingRemoved(projectCode, appId, pageKey, activeActionKeys);
        }
        return new PageCatalogRegisterResult(projectCode, appId, pageKey, actions.size());
    }

    private void upsertAction(String projectCode,
                              String appId,
                              String pageKey,
                              PageActionDefinitionRequest request,
                              LocalDateTime now) {
        if (request == null) {
            return;
        }
        String actionKey = requiredText(request.actionKey(), "page action key is required");
        PageActionRegistryEntity entity = new PageActionRegistryEntity();
        entity.setProjectCode(projectCode);
        entity.setAppId(appId);
        entity.setPageKey(pageKey);
        entity.setActionKey(actionKey);
        entity.setTitle(StringUtils.hasText(request.title()) ? request.title().trim() : actionKey);
        entity.setDescription(trimToNull(request.description()));
        entity.setConfirmRequired(Boolean.TRUE.equals(request.confirmRequired()));
        entity.setInputSchemaJson(writeJson(request.inputSchema() == null ? Map.of() : request.inputSchema()));
        entity.setOutputSchemaJson(writeJson(request.outputSchema() == null ? Map.of() : request.outputSchema()));
        entity.setSampleArgsJson(writeJson(request.sampleArgs() == null ? Map.of() : request.sampleArgs()));
        entity.setAllowedAgentIdsJson(writeJson(request.allowedAgentIds() == null ? List.of() : request.allowedAgentIds()));
        entity.setMetadataJson(writeJson(request.metadata() == null ? Map.of() : request.metadata()));
        entity.setStatus("ACTIVE");
        entity.setLastSeenAt(now);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        actionMapper.upsert(entity);
    }

    private String requiredText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String normalizedOrigin(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("page action catalog json serialize failed", ex);
        }
    }
}
