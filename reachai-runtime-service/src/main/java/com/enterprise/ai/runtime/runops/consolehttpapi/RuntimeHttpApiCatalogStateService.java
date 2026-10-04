package com.enterprise.ai.runtime.runops.consolehttpapi;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.common.capability.HttpApiConsoleContracts;
import com.enterprise.ai.runtime.runops.consolecapability.ConsoleCapabilityInvocationEntity;
import com.enterprise.ai.runtime.runops.consolecapability.ConsoleCapabilityInvocationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** One bounded Runtime read for a Capability-owned catalog page; never a callable-readiness grant. */
@Service
@RequiredArgsConstructor
public class RuntimeHttpApiCatalogStateService {
    private final RuntimeHttpApiConnectionMapper connections;
    private final ConsoleCapabilityInvocationMapper attempts;

    public List<HttpApiConsoleContracts.CatalogState> read(HttpApiConsoleContracts.CatalogStatesRequest request,
                                                           String actorId) {
        if (request == null || request.contractVersion() != HttpApiConsoleContracts.VERSION
                || request.projectId() == null || request.projectId() <= 0
                || !StringUtils.hasText(request.projectCode()) || !StringUtils.hasText(actorId)
                || request.qualifiedNames() == null || request.qualifiedNames().isEmpty()
                || request.qualifiedNames().size() > 100) {
            throw new IllegalArgumentException("invalid HTTP API catalog state request");
        }
        List<String> names = request.qualifiedNames();
        if (new HashSet<>(names).size() != names.size() || names.stream().anyMatch(
                name -> !StringUtils.hasText(name) || name.length() > 200)) {
            throw new IllegalArgumentException("invalid HTTP API catalog refs");
        }
        Map<String, RuntimeHttpApiConnectionEntity> saved = new HashMap<>();
        for (RuntimeHttpApiConnectionEntity row : connections.selectList(
                Wrappers.<RuntimeHttpApiConnectionEntity>lambdaQuery()
                        .eq(RuntimeHttpApiConnectionEntity::getProjectId, request.projectId())
                        .eq(RuntimeHttpApiConnectionEntity::getProjectCode, request.projectCode())
                        .in(RuntimeHttpApiConnectionEntity::getQualifiedName, names))) {
            saved.put(row.getQualifiedName(), row);
        }
        Map<String, ConsoleCapabilityInvocationEntity> latest = new HashMap<>();
        for (ConsoleCapabilityInvocationEntity row : attempts.selectLatestHttpApisForActor(
                request.projectId(), request.projectCode(), actorId, names)) {
            latest.put(row.getQualifiedName(), row);
        }
        return names.stream().map(name -> {
            ConsoleCapabilityInvocationEntity attempt = latest.get(name);
            LocalDateTime at = attempt == null ? null : attempt.getEndedAt() == null
                    ? attempt.getStartedAt() : attempt.getEndedAt();
            return new HttpApiConsoleContracts.CatalogState(name,
                    saved.containsKey(name) ? "SAVED" : "UNCONFIGURED",
                    attempt == null ? null : attempt.getStatus(),
                    attempt == null ? null : attempt.getHttpStatus(),
                    at == null ? null : at.toString());
        }).toList();
    }
}
