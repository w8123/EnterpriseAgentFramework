package com.enterprise.ai.agent.platform.control.context;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.platform.control.auth.PlatformAuthContext;
import com.enterprise.ai.agent.platform.control.auth.PlatformAuthorizationService;
import com.enterprise.ai.agent.platform.control.auth.PlatformPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ContextAccessPolicyService {

    private static final Set<ContextBindType> RUNTIME_PRIVATE_BIND_TYPES = EnumSet.of(
            ContextBindType.USER,
            ContextBindType.SESSION,
            ContextBindType.AGENT,
            ContextBindType.PAGE,
            ContextBindType.WORKFLOW);

    private final PlatformAuthorizationService platformAuthorizationService;
    private final ContextBindingMapper bindingMapper;
    private final ContextItemMapper itemMapper;
    private final ContextNamespaceMapper namespaceMapper;

    public MemoryLane requireMemoryLane(String memoryLane) {
        if (!StringUtils.hasText(memoryLane)) {
            throw new IllegalArgumentException("memoryLane is required (PROJECT_DEV or RUNTIME_USER)");
        }
        try {
            return MemoryLane.valueOf(memoryLane.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid memoryLane: " + memoryLane);
        }
    }

    public void validateRequiredScope(ContextQueryRequest scope) {
        if (scope == null) {
            throw new IllegalArgumentException("scope is required");
        }
        validateQueryScope(scope);
    }

    public void validateQueryScope(ContextQueryRequest query) {
        requireMemoryLane(query.getMemoryLane());
        if (!StringUtils.hasText(query.getTenantId())) {
            throw new IllegalArgumentException("tenantId is required");
        }
        assertProjectAccess(query.getProjectCode(), query.getProjectId());
    }

    public void validateWriteScope(String tenantId, String projectCode, Long projectId, String memoryLane) {
        requireMemoryLane(memoryLane);
        if (!StringUtils.hasText(tenantId)) {
            throw new IllegalArgumentException("tenantId is required");
        }
        assertProjectAccess(projectCode, projectId);
    }

    public void assertProjectAccessOnly(String projectCode, Long projectId) {
        assertProjectAccess(projectCode, projectId);
    }

    public void requireItemReadAccess(Long itemId, ContextQueryRequest scope) {
        validateRequiredScope(scope);
        ItemContext context = loadItemContext(itemId);
        requireReadableStatus(context.item());
        if (!isItemReadable(context.item(), context.namespace(), scope)) {
            throw new IllegalArgumentException("Context item access denied");
        }
    }

    public void requireItemWriteAccess(Long itemId, ContextQueryRequest scope) {
        validateRequiredScope(scope);
        ItemContext context = loadItemContext(itemId);
        if (!matchesItemAccessBoundary(context.item(), context.namespace(), scope)) {
            throw new IllegalArgumentException("Context item access denied");
        }
    }

    public void validateNamespaceBoundaryForCreate(ContextItemCreateRequest request, ContextNamespaceEntity namespace) {
        if (request == null || namespace == null) {
            throw new IllegalArgumentException("create request and namespace are required");
        }
        if (!StringUtils.hasText(request.getTenantId())) {
            throw new IllegalArgumentException("tenantId is required");
        }
        requireMemoryLane(request.getMemoryLane());
        if (!request.getTenantId().equals(namespace.getTenantId())) {
            throw new IllegalArgumentException("namespace tenant mismatch");
        }
        ContextProjectIdentity.requireMatch(
                request.getProjectCode(), request.getProjectId(),
                namespace.getProjectCode(), namespace.getProjectId(),
                "namespace project mismatch");
    }

    public void validateCreateItemPolicy(ContextItemCreateRequest request,
                                         ContextNamespaceEntity namespace,
                                         String visibility) {
        validateNamespaceBoundaryForCreate(request, namespace);
        MemoryLane lane = requireMemoryLane(request.getMemoryLane());
        ContextVisibility parsedVisibility = parseVisibility(visibility);
        if (lane != MemoryLane.RUNTIME_USER || parsedVisibility != ContextVisibility.PRIVATE) {
            return;
        }
        ContextQueryRequest scope = toCreateScope(request);
        if (!hasRuntimeIdentityTarget(scope)) {
            throw new IllegalArgumentException(
                    "RUNTIME_USER PRIVATE requires userId, sessionId, agentId, pageInstanceId, or workflowId");
        }
        if (request.getBindings() != null && !request.getBindings().isEmpty()) {
            for (ContextBindingRequest binding : request.getBindings()) {
                validateBindingRequestShape(binding);
                ContextBindType bindType = ContextBindType.valueOf(binding.getBindType().trim().toUpperCase());
                if (!RUNTIME_PRIVATE_BIND_TYPES.contains(bindType)) {
                    throw new IllegalArgumentException(
                            "RUNTIME_USER PRIVATE initial binding must be USER/SESSION/AGENT/PAGE/WORKFLOW");
                }
                if (!initialBindingMatchesScope(bindType, binding.getBindId(), scope)) {
                    throw new IllegalArgumentException("initial binding does not match create scope");
                }
            }
        }
    }

    public void validateInitialBinding(ContextItemEntity item,
                                       ContextNamespaceEntity namespace,
                                       ContextBindingRequest binding,
                                       ContextQueryRequest scope) {
        validateRequiredScope(scope);
        if (!item.getMemoryLane().equalsIgnoreCase(scope.getMemoryLane())) {
            throw new IllegalArgumentException("Context item access denied");
        }
        if (!scope.getTenantId().equals(namespace.getTenantId())) {
            throw new IllegalArgumentException("Context item access denied");
        }
        validateBindingRequestShape(binding);
        ContextBindType bindType = ContextBindType.valueOf(binding.getBindType().trim().toUpperCase());
        MemoryLane lane = MemoryLane.valueOf(item.getMemoryLane().trim().toUpperCase());
        ContextVisibility visibility = parseVisibility(item.getVisibility());
        if (lane == MemoryLane.RUNTIME_USER && visibility == ContextVisibility.PRIVATE) {
            if (!RUNTIME_PRIVATE_BIND_TYPES.contains(bindType)) {
                throw new IllegalArgumentException("RUNTIME_USER PRIVATE initial binding must be USER/SESSION/AGENT/PAGE/WORKFLOW");
            }
            if (!initialBindingMatchesScope(bindType, binding.getBindId(), scope)) {
                throw new IllegalArgumentException("initial binding does not match create scope");
            }
        }
    }

    public ContextQueryRequest toCreateScope(ContextItemCreateRequest request) {
        ContextQueryRequest scope = new ContextQueryRequest();
        scope.setTenantId(request.getTenantId());
        scope.setProjectCode(request.getProjectCode());
        scope.setProjectId(request.getProjectId());
        scope.setMemoryLane(request.getMemoryLane());
        scope.setUserId(request.getUserId());
        scope.setSessionId(request.getSessionId());
        scope.setAgentId(request.getAgentId());
        scope.setPageInstanceId(request.getPageInstanceId());
        scope.setWorkflowId(request.getWorkflowId());
        scope.setActorId(request.getCreatedBy());
        scope.setActorType(StringUtils.hasText(request.getCreatedBy()) ? "USER" : "SYSTEM");
        return scope;
    }

    private void validateBindingRequestShape(ContextBindingRequest binding) {
        if (binding == null || !StringUtils.hasText(binding.getBindType()) || !StringUtils.hasText(binding.getBindId())) {
            throw new IllegalArgumentException("bindType and bindId are required");
        }
    }

    private boolean initialBindingMatchesScope(ContextBindType bindType, String bindId, ContextQueryRequest scope) {
        if (!StringUtils.hasText(bindId)) {
            return false;
        }
        String normalizedBindId = bindId.trim();
        return switch (bindType) {
            case USER -> StringUtils.hasText(scope.getUserId()) && normalizedBindId.equals(scope.getUserId().trim());
            case SESSION -> StringUtils.hasText(scope.getSessionId()) && normalizedBindId.equals(scope.getSessionId().trim());
            case AGENT -> StringUtils.hasText(scope.getAgentId()) && normalizedBindId.equals(scope.getAgentId().trim());
            case PAGE -> StringUtils.hasText(scope.getPageInstanceId()) && normalizedBindId.equals(scope.getPageInstanceId().trim());
            case WORKFLOW -> StringUtils.hasText(scope.getWorkflowId()) && normalizedBindId.equals(scope.getWorkflowId().trim());
            default -> false;
        };
    }

    /**
     * Management list boundary check: lane/tenant/project/visibility scope without ACTIVE-only filtering.
     */
    public boolean isItemAccessibleInScope(ContextItemEntity item,
                                             ContextNamespaceEntity namespace,
                                             ContextQueryRequest query) {
        return matchesItemAccessBoundary(item, namespace, query);
    }

    public boolean isItemReadable(ContextItemEntity item,
                                  ContextNamespaceEntity namespace,
                                  ContextQueryRequest query) {
        if (item == null || namespace == null) {
            return false;
        }
        if (!matchesItemAccessBoundary(item, namespace, query)) {
            return false;
        }
        if (!ContextStatus.ACTIVE.name().equals(item.getStatus())) {
            return false;
        }
        if (item.getExpiresAt() != null && !item.getExpiresAt().isAfter(LocalDateTime.now())) {
            return false;
        }
        if (item.getEffectiveFrom() != null && item.getEffectiveFrom().isAfter(LocalDateTime.now())) {
            return false;
        }
        return true;
    }

    private boolean matchesItemAccessBoundary(ContextItemEntity item,
                                              ContextNamespaceEntity namespace,
                                              ContextQueryRequest query) {
        if (item == null || namespace == null || query == null) {
            return false;
        }
        if (!query.getMemoryLane().equalsIgnoreCase(item.getMemoryLane())) {
            return false;
        }
        if (!query.getTenantId().equals(namespace.getTenantId())) {
            return false;
        }

        MemoryLane lane = MemoryLane.valueOf(item.getMemoryLane().trim().toUpperCase());
        ContextVisibility visibility = parseVisibility(item.getVisibility());

        if (lane == MemoryLane.RUNTIME_USER) {
            return matchesRuntimeUserVisibility(item, namespace, query, visibility);
        }
        return matchesProjectDevVisibility(namespace, query, visibility);
    }

    private boolean matchesProjectDevVisibility(ContextNamespaceEntity namespace,
                                                ContextQueryRequest query,
                                                ContextVisibility visibility) {
        return switch (visibility) {
            case GLOBAL, TENANT -> true;
            case PROJECT, PRIVATE -> matchesProject(query, namespace);
        };
    }

    private boolean matchesRuntimeUserVisibility(ContextItemEntity item,
                                                 ContextNamespaceEntity namespace,
                                                 ContextQueryRequest query,
                                                 ContextVisibility visibility) {
        return switch (visibility) {
            case GLOBAL, TENANT -> true;
            case PROJECT -> matchesProject(query, namespace);
            case PRIVATE -> matchesRuntimeUserPrivate(item, namespace, query);
        };
    }

    /**
     * RUNTIME_USER + PRIVATE must match USER/SESSION/AGENT/PAGE/WORKFLOW binding or namespace owner
     * for the identity targets supplied in the query scope.
     */
    private boolean matchesRuntimeUserPrivate(ContextItemEntity item,
                                              ContextNamespaceEntity namespace,
                                              ContextQueryRequest query) {
        if (!hasRuntimeIdentityTarget(query)) {
            return false;
        }
        if (matchesNamespaceOwner(namespace, query)) {
            return true;
        }
        return itemMatchesRuntimePrivateBindings(item.getId(), query);
    }

    private boolean hasRuntimeIdentityTarget(ContextQueryRequest query) {
        return StringUtils.hasText(query.getUserId())
                || StringUtils.hasText(query.getSessionId())
                || StringUtils.hasText(query.getAgentId())
                || StringUtils.hasText(query.getPageInstanceId())
                || StringUtils.hasText(query.getWorkflowId());
    }

    private boolean matchesNamespaceOwner(ContextNamespaceEntity namespace, ContextQueryRequest query) {
        if (!StringUtils.hasText(namespace.getOwnerType()) || !StringUtils.hasText(namespace.getOwnerId())) {
            return false;
        }
        String ownerType = namespace.getOwnerType().trim().toUpperCase();
        String ownerId = namespace.getOwnerId().trim();
        return switch (ownerType) {
            case "USER" -> StringUtils.hasText(query.getUserId()) && ownerId.equals(query.getUserId().trim());
            case "SESSION" -> StringUtils.hasText(query.getSessionId()) && ownerId.equals(query.getSessionId().trim());
            case "AGENT" -> StringUtils.hasText(query.getAgentId()) && ownerId.equals(query.getAgentId().trim());
            case "PAGE" -> StringUtils.hasText(query.getPageInstanceId()) && ownerId.equals(query.getPageInstanceId().trim());
            case "WORKFLOW" -> StringUtils.hasText(query.getWorkflowId()) && ownerId.equals(query.getWorkflowId().trim());
            default -> false;
        };
    }

    private boolean itemMatchesRuntimePrivateBindings(Long itemId, ContextQueryRequest query) {
        List<ContextBindingEntity> bindings = bindingMapper.selectList(
                Wrappers.lambdaQuery(ContextBindingEntity.class)
                        .eq(ContextBindingEntity::getItemId, itemId)
                        .eq(ContextBindingEntity::getStatus, ContextStatus.ACTIVE.name()));
        for (ContextBindingEntity binding : bindings) {
            if (bindingMatchesRuntimePrivateScope(binding, query)) {
                return true;
            }
        }
        return false;
    }

    private boolean bindingMatchesRuntimePrivateScope(ContextBindingEntity binding, ContextQueryRequest query) {
        ContextBindType bindType;
        try {
            bindType = ContextBindType.valueOf(binding.getBindType().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return false;
        }
        if (!RUNTIME_PRIVATE_BIND_TYPES.contains(bindType)) {
            return false;
        }
        return switch (bindType) {
            case USER -> StringUtils.hasText(query.getUserId()) && binding.getBindId().equals(query.getUserId().trim());
            case SESSION -> StringUtils.hasText(query.getSessionId()) && binding.getBindId().equals(query.getSessionId().trim());
            case AGENT -> StringUtils.hasText(query.getAgentId()) && binding.getBindId().equals(query.getAgentId().trim());
            case PAGE -> StringUtils.hasText(query.getPageInstanceId()) && binding.getBindId().equals(query.getPageInstanceId().trim());
            case WORKFLOW -> StringUtils.hasText(query.getWorkflowId()) && binding.getBindId().equals(query.getWorkflowId().trim());
            default -> false;
        };
    }

    private void requireReadableStatus(ContextItemEntity item) {
        if (ContextStatus.REVOKED.name().equals(item.getStatus())
                || ContextStatus.STALE.name().equals(item.getStatus())) {
            throw new IllegalArgumentException("Context item is not readable: " + item.getStatus());
        }
        if (ContextStatus.DELETED.name().equals(item.getStatus())) {
            throw new IllegalArgumentException("Context item not found: " + item.getId());
        }
    }

    private ItemContext loadItemContext(Long itemId) {
        ContextItemEntity item = itemMapper.selectById(itemId);
        if (item == null || ContextStatus.DELETED.name().equals(item.getStatus())) {
            throw new IllegalArgumentException("Context item not found: " + itemId);
        }
        ContextNamespaceEntity namespace = namespaceMapper.selectById(item.getNamespaceId());
        if (namespace == null || ContextStatus.DELETED.name().equals(namespace.getStatus())) {
            throw new IllegalArgumentException("Context namespace not found for item: " + itemId);
        }
        return new ItemContext(item, namespace);
    }

    private boolean matchesProject(ContextQueryRequest query, ContextNamespaceEntity namespace) {
        return ContextProjectIdentity.matches(
                query.getProjectCode(), query.getProjectId(),
                namespace.getProjectCode(), namespace.getProjectId());
    }

    private ContextVisibility parseVisibility(String visibility) {
        if (!StringUtils.hasText(visibility)) {
            return ContextVisibility.PRIVATE;
        }
        return ContextVisibility.valueOf(visibility.trim().toUpperCase());
    }

    private void assertProjectAccess(String projectCode, Long projectId) {
        PlatformPrincipal principal = PlatformAuthContext.get();
        if (principal == null) {
            return;
        }
        if (!StringUtils.hasText(projectCode) && projectId == null) {
            return;
        }
        if (!platformAuthorizationService.canAccessProject(principal, projectId, projectCode)) {
            throw new IllegalArgumentException("No project access for context operation");
        }
    }

    private record ItemContext(ContextItemEntity item, ContextNamespaceEntity namespace) {
    }
}
