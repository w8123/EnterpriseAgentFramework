package com.enterprise.ai.capability.catalog.graph;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphEdgeEntity;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphEdgeMapper;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphLayoutEntity;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphLayoutMapper;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphNodeEntity;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphNodeMapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CapabilityApiGraphOperationsService {

    private static final String NODE_API = "API";
    private static final String FIELD_IN = "FIELD_IN";
    private static final String FIELD_OUT = "FIELD_OUT";
    private static final String REQUEST_REF = "REQUEST_REF";
    private static final String RESPONSE_REF = "RESPONSE_REF";
    private static final String MODEL_REF = "MODEL_REF";
    private static final String BELONGS_TO = "BELONGS_TO";
    private static final String STATUS_CANDIDATE = "CANDIDATE";
    private static final String STATUS_CONFIRMED = "CONFIRMED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String SOURCE_AUTO = "auto";
    private static final String SOURCE_MANUAL = "manual";
    private static final String STRATEGY_DTO_MATCH = "dto_match";
    private static final String STRATEGY_SCHEMA_MATCH = "schema_match";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Set<String> PRIMITIVE_TYPE_NAMES = Set.of(
            "string", "integer", "int", "long", "short", "byte", "float", "double",
            "decimal", "number", "boolean", "bool", "date", "datetime", "time",
            "object", "json", "map", "any", "void", "null", "char");

    private final ApiGraphEdgeMapper edgeMapper;
    private final ApiGraphLayoutMapper layoutMapper;
    private final ApiGraphNodeMapper nodeMapper;
    private final ToolDefinitionMapper toolDefinitionMapper;
    private final ObjectMapper objectMapper;

    public List<CapabilityApiGraphSnapshotView.EdgeView> listCandidates(Long projectId,
                                                                        String status,
                                                                        Double minConfidence) {
        String normalizedStatus = safeNonBlank(status, STATUS_CANDIDATE).toUpperCase(Locale.ROOT);
        double min = minConfidence == null ? 0.0 : minConfidence;
        return edgeMapper.selectList(new LambdaQueryWrapper<ApiGraphEdgeEntity>()
                        .eq(ApiGraphEdgeEntity::getProjectId, projectId)
                        .eq(ApiGraphEdgeEntity::getEnabled, true)
                        .orderByAsc(ApiGraphEdgeEntity::getId))
                .stream()
                .filter(edge -> List.of(REQUEST_REF, RESPONSE_REF).contains(edge.getKind()))
                .filter(edge -> normalizedStatus.equalsIgnoreCase(statusOrConfirmed(edge)))
                .filter(edge -> edge.getConfidence() == null || edge.getConfidence() >= min)
                .sorted(Comparator.comparing(ApiGraphEdgeEntity::getConfidence,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ApiGraphEdgeEntity::getId))
                .map(CapabilityApiGraphOperationsService::toEdgeView)
                .toList();
    }

    public List<CapabilityApiGraphParamSourceHintView> listParamHints(Long projectId, String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return List.of();
        }
        Optional<ToolDefinitionEntity> tool = findToolByName(toolName);
        if (tool.isEmpty()) {
            return List.of();
        }
        List<ApiGraphNodeEntity> nodes = nodeMapper.selectList(new LambdaQueryWrapper<ApiGraphNodeEntity>()
                .eq(ApiGraphNodeEntity::getProjectId, projectId)
                .orderByAsc(ApiGraphNodeEntity::getId));
        Map<Long, ApiGraphNodeEntity> nodeById = nodes.stream()
                .collect(LinkedHashMap::new, (map, node) -> map.put(node.getId(), node), LinkedHashMap::putAll);
        Set<Long> apiNodeIds = nodes.stream()
                .filter(node -> NODE_API.equals(node.getKind()))
                .filter(node -> Objects.equals(String.valueOf(tool.get().getId()),
                        String.valueOf(readJsonMap(node.getPropsJson()).get("globalToolDefinitionId"))))
                .map(ApiGraphNodeEntity::getId)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (apiNodeIds.isEmpty()) {
            return List.of();
        }
        return edgeMapper.selectList(new LambdaQueryWrapper<ApiGraphEdgeEntity>()
                        .eq(ApiGraphEdgeEntity::getProjectId, projectId)
                        .eq(ApiGraphEdgeEntity::getEnabled, true)
                        .orderByAsc(ApiGraphEdgeEntity::getId))
                .stream()
                .filter(edge -> REQUEST_REF.equals(edge.getKind()))
                .filter(edge -> STATUS_CONFIRMED.equalsIgnoreCase(statusOrConfirmed(edge)))
                .filter(edge -> {
                    ApiGraphNodeEntity target = nodeById.get(edge.getTargetNodeId());
                    return target != null && apiNodeIds.contains(target.getRefId());
                })
                .sorted(Comparator.comparing(ApiGraphEdgeEntity::getConfidence,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(5)
                .map(edge -> toParamSourceHint(edge, nodeById))
                .filter(Objects::nonNull)
                .toList();
    }

    @Transactional
    public CapabilityApiGraphRequests.InferResultDTO inferModelRefEdges(Long projectId) {
        edgeMapper.delete(new LambdaQueryWrapper<ApiGraphEdgeEntity>()
                .eq(ApiGraphEdgeEntity::getProjectId, projectId)
                .eq(ApiGraphEdgeEntity::getKind, MODEL_REF)
                .eq(ApiGraphEdgeEntity::getSource, SOURCE_AUTO));
        List<ApiGraphNodeEntity> all = listNodesByProject(projectId).stream()
                .filter(node -> FIELD_IN.equals(node.getKind()) || FIELD_OUT.equals(node.getKind()))
                .toList();
        Map<String, List<ApiGraphNodeEntity>> bucketByType = new LinkedHashMap<>();
        for (ApiGraphNodeEntity field : all) {
            String simpleType = simpleTypeName(field.getTypeName());
            if (isPrimitive(simpleType) || !looksLikeCompositeName(simpleType)) {
                continue;
            }
            bucketByType.computeIfAbsent(simpleType, key -> new ArrayList<>()).add(field);
        }

        int generated = 0;
        for (Map.Entry<String, List<ApiGraphNodeEntity>> entry : bucketByType.entrySet()) {
            List<ApiGraphNodeEntity> fields = entry.getValue();
            for (int i = 0; i < fields.size(); i++) {
                for (int j = i + 1; j < fields.size(); j++) {
                    ApiGraphNodeEntity left = fields.get(i);
                    ApiGraphNodeEntity right = fields.get(j);
                    if (Objects.equals(left.getRefId(), right.getRefId()) ||
                            Objects.equals(left.getKind(), right.getKind())) {
                        continue;
                    }
                    ApiGraphEdgeEntity edge = new ApiGraphEdgeEntity();
                    edge.setProjectId(projectId);
                    edge.setSourceNodeId(left.getId());
                    edge.setTargetNodeId(right.getId());
                    edge.setKind(MODEL_REF);
                    edge.setSource(SOURCE_AUTO);
                    edge.setConfidence(1.0);
                    edge.setStatus(STATUS_CONFIRMED);
                    edge.setInferStrategy(STRATEGY_DTO_MATCH);
                    edge.setEvidenceJson(writeJsonOrNull(Map.of("by", "shared_type", "type", entry.getKey())));
                    edge.setEnabled(Boolean.TRUE);
                    upsertByIdentity(edge);
                    generated++;
                }
            }
        }
        return new CapabilityApiGraphRequests.InferResultDTO(generated);
    }

    @Transactional
    public CapabilityApiGraphRequests.InferResultDTO inferRequestResponseEdges(Long projectId) {
        List<ApiGraphNodeEntity> all = listNodesByProject(projectId);
        List<ApiGraphNodeEntity> inputFields = all.stream()
                .filter(node -> FIELD_IN.equals(node.getKind()))
                .toList();
        List<ApiGraphNodeEntity> outputFields = all.stream()
                .filter(node -> FIELD_OUT.equals(node.getKind()))
                .toList();
        if (inputFields.isEmpty() || outputFields.isEmpty()) {
            return new CapabilityApiGraphRequests.InferResultDTO(0);
        }

        Set<String> rejectedIdentities = new LinkedHashSet<>();
        for (ApiGraphEdgeEntity edge : listEnabledEdgesByProject(projectId)) {
            if (STATUS_REJECTED.equalsIgnoreCase(edge.getStatus())) {
                rejectedIdentities.add(edgeIdentity(edge.getKind(), edge.getSourceNodeId(), edge.getTargetNodeId()));
            }
        }

        int generated = 0;
        for (ApiGraphNodeEntity source : outputFields) {
            for (ApiGraphNodeEntity target : inputFields) {
                if (Objects.equals(source.getRefId(), target.getRefId()) ||
                        rejectedIdentities.contains(edgeIdentity(REQUEST_REF, source.getId(), target.getId()))) {
                    continue;
                }
                double confidence = scoreFieldMatch(source, target);
                if (confidence < 0.65) {
                    continue;
                }
                ApiGraphEdgeEntity edge = new ApiGraphEdgeEntity();
                edge.setProjectId(projectId);
                edge.setSourceNodeId(source.getId());
                edge.setTargetNodeId(target.getId());
                edge.setKind(REQUEST_REF);
                edge.setSource(SOURCE_AUTO);
                edge.setConfidence(roundConfidence(confidence));
                edge.setStatus(STATUS_CANDIDATE);
                edge.setInferStrategy(STRATEGY_SCHEMA_MATCH);
                edge.setEvidenceJson(writeJsonOrNull(Map.of(
                        "by", "field_schema_match",
                        "sourcePath", fieldPath(source),
                        "targetPath", fieldPath(target),
                        "sourceType", safeNonNull(source.getTypeName()),
                        "targetType", safeNonNull(target.getTypeName()))));
                edge.setEnabled(Boolean.TRUE);
                upsertByIdentity(edge);
                generated++;
            }
        }
        return new CapabilityApiGraphRequests.InferResultDTO(generated);
    }

    @Transactional
    public CapabilityApiGraphSnapshotView.EdgeView confirmCandidate(
            Long projectId,
            Long edgeId,
            CapabilityApiGraphRequests.CandidateConfirmRequest request) {
        ApiGraphEdgeEntity edge = requireProjectEdge(projectId, edgeId);
        edge.setStatus(STATUS_CONFIRMED);
        edge.setConfirmedBy(safeNonBlank(request == null ? null : request.confirmedBy(), "operator"));
        edge.setConfirmedAt(LocalDateTime.now());
        edge.setRejectReason(null);
        edge.setEnabled(Boolean.TRUE);
        edgeMapper.updateById(edge);
        return toEdgeView(edge);
    }

    @Transactional
    public CapabilityApiGraphSnapshotView.EdgeView rejectCandidate(
            Long projectId,
            Long edgeId,
            CapabilityApiGraphRequests.CandidateRejectRequest request) {
        ApiGraphEdgeEntity edge = requireProjectEdge(projectId, edgeId);
        edge.setStatus(STATUS_REJECTED);
        edge.setRejectReason(request == null ? null : request.rejectReason());
        edge.setEnabled(Boolean.TRUE);
        edgeMapper.updateById(edge);
        return toEdgeView(edge);
    }

    @Transactional
    public CapabilityApiGraphSnapshotView.EdgeView upsertEdge(
            Long projectId,
            CapabilityApiGraphRequests.EdgeUpsertRequest request) {
        if (request.sourceNodeId() == null || request.targetNodeId() == null) {
            throw new IllegalArgumentException("source / target 节点不能为空");
        }
        if (Objects.equals(request.sourceNodeId(), request.targetNodeId())) {
            throw new IllegalArgumentException("不能连接自身");
        }
        ApiGraphEdgeEntity edge = new ApiGraphEdgeEntity();
        edge.setProjectId(projectId);
        edge.setSourceNodeId(request.sourceNodeId());
        edge.setTargetNodeId(request.targetNodeId());
        edge.setKind(normalizeKind(request.kind()));
        edge.setSource(SOURCE_MANUAL);
        edge.setConfidence(1.0);
        edge.setStatus(STATUS_CONFIRMED);
        edge.setConfirmedAt(LocalDateTime.now());
        edge.setNote(request.note());
        edge.setEnabled(Boolean.TRUE);
        return toEdgeView(upsertByIdentity(edge));
    }

    @Transactional
    public boolean deleteEdge(Long edgeId) {
        if (edgeId == null) {
            return false;
        }
        return edgeMapper.deleteById(edgeId) > 0;
    }

    @Transactional
    public void saveLayout(Long projectId, CapabilityApiGraphRequests.LayoutSaveRequest request) {
        if (request == null || request.positions() == null) {
            return;
        }
        for (CapabilityApiGraphRequests.LayoutPositionDTO position : request.positions()) {
            if (position == null || position.nodeId() == null) {
                continue;
            }
            ApiGraphLayoutEntity layout = new ApiGraphLayoutEntity();
            layout.setProjectId(projectId);
            layout.setNodeId(position.nodeId());
            layout.setX(position.x() == null ? 0.0 : position.x());
            layout.setY(position.y() == null ? 0.0 : position.y());
            layout.setExtJson(position.extJson());
            upsertLayout(layout);
        }
    }

    private ApiGraphEdgeEntity upsertByIdentity(ApiGraphEdgeEntity edge) {
        ApiGraphEdgeEntity existing = edgeMapper.selectOne(new LambdaQueryWrapper<ApiGraphEdgeEntity>()
                .eq(ApiGraphEdgeEntity::getProjectId, edge.getProjectId())
                .eq(ApiGraphEdgeEntity::getKind, edge.getKind())
                .eq(ApiGraphEdgeEntity::getSourceNodeId, edge.getSourceNodeId())
                .eq(ApiGraphEdgeEntity::getTargetNodeId, edge.getTargetNodeId())
                .eq(ApiGraphEdgeEntity::getSource, edge.getSource())
                .last("limit 1"));
        if (existing != null) {
            existing.setConfidence(edge.getConfidence());
            existing.setEvidenceJson(edge.getEvidenceJson());
            existing.setStatus(edge.getStatus());
            existing.setInferStrategy(edge.getInferStrategy());
            existing.setConfirmedBy(edge.getConfirmedBy());
            existing.setConfirmedAt(edge.getConfirmedAt());
            existing.setRejectReason(edge.getRejectReason());
            existing.setEnabled(Boolean.TRUE);
            if (edge.getNote() != null) {
                existing.setNote(edge.getNote());
            }
            edgeMapper.updateById(existing);
            return existing;
        }
        edgeMapper.insert(edge);
        return edge;
    }

    private void upsertLayout(ApiGraphLayoutEntity layout) {
        ApiGraphLayoutEntity existing = layoutMapper.selectOne(new LambdaQueryWrapper<ApiGraphLayoutEntity>()
                .eq(ApiGraphLayoutEntity::getProjectId, layout.getProjectId())
                .eq(ApiGraphLayoutEntity::getNodeId, layout.getNodeId())
                .last("limit 1"));
        if (existing != null) {
            existing.setX(layout.getX());
            existing.setY(layout.getY());
            existing.setExtJson(layout.getExtJson());
            layoutMapper.updateById(existing);
            return;
        }
        layoutMapper.insert(layout);
    }

    private ApiGraphEdgeEntity requireProjectEdge(Long projectId, Long edgeId) {
        ApiGraphEdgeEntity edge = edgeMapper.selectById(edgeId);
        if (edge == null) {
            throw new IllegalArgumentException("候选边不存在");
        }
        if (!Objects.equals(projectId, edge.getProjectId())) {
            throw new IllegalArgumentException("候选边不属于当前项目");
        }
        return edge;
    }

    private List<ApiGraphNodeEntity> listNodesByProject(Long projectId) {
        List<ApiGraphNodeEntity> nodes = nodeMapper.selectList(new LambdaQueryWrapper<ApiGraphNodeEntity>()
                .eq(ApiGraphNodeEntity::getProjectId, projectId)
                .orderByAsc(ApiGraphNodeEntity::getId));
        return nodes == null ? List.of() : nodes;
    }

    private List<ApiGraphEdgeEntity> listEnabledEdgesByProject(Long projectId) {
        List<ApiGraphEdgeEntity> edges = edgeMapper.selectList(new LambdaQueryWrapper<ApiGraphEdgeEntity>()
                .eq(ApiGraphEdgeEntity::getProjectId, projectId)
                .eq(ApiGraphEdgeEntity::getEnabled, true)
                .orderByAsc(ApiGraphEdgeEntity::getId));
        return edges == null ? List.of() : edges;
    }

    private Optional<ToolDefinitionEntity> findToolByName(String name) {
        return Optional.ofNullable(toolDefinitionMapper.selectOne(new LambdaQueryWrapper<ToolDefinitionEntity>()
                .eq(ToolDefinitionEntity::getName, name)
                .last("limit 1")));
    }

    private CapabilityApiGraphParamSourceHintView toParamSourceHint(
            ApiGraphEdgeEntity edge,
            Map<Long, ApiGraphNodeEntity> nodeById) {
        ApiGraphNodeEntity source = nodeById.get(edge.getSourceNodeId());
        ApiGraphNodeEntity target = nodeById.get(edge.getTargetNodeId());
        if (source == null || target == null) {
            return null;
        }
        ApiGraphNodeEntity sourceApi = nodeById.get(source.getRefId());
        ApiGraphNodeEntity targetApi = nodeById.get(target.getRefId());
        return new CapabilityApiGraphParamSourceHintView(
                fieldPath(target),
                target.getLabel(),
                targetApi == null ? "" : targetApi.getLabel(),
                fieldPath(source),
                source.getLabel(),
                sourceApi == null ? "" : sourceApi.getLabel(),
                edge.getConfidence());
    }

    private String fieldPath(ApiGraphNodeEntity node) {
        Object value = readJsonMap(node.getPropsJson()).get("paramPath");
        return value == null ? "" : String.valueOf(value);
    }

    private double scoreFieldMatch(ApiGraphNodeEntity source, ApiGraphNodeEntity target) {
        double score = 0.0;
        String sourceName = normalizedFieldName(source);
        String targetName = normalizedFieldName(target);
        if (!sourceName.isBlank() && sourceName.equals(targetName)) {
            score += 0.35;
        } else if (isLikelyIdAlias(sourceName, targetName)) {
            score += 0.25;
        }
        if (isTypeCompatible(source.getTypeName(), target.getTypeName())) {
            score += 0.25;
        }
        String sourceSimple = simpleTypeName(source.getTypeName());
        String targetSimple = simpleTypeName(target.getTypeName());
        if (!sourceSimple.isBlank() && sourceSimple.equals(targetSimple) && looksLikeCompositeName(sourceSimple)) {
            score += 0.15;
        }
        if (fieldPath(source).equalsIgnoreCase(fieldPath(target)) && !fieldPath(source).isBlank()) {
            score += 0.1;
        }
        return Math.min(score, 0.95);
    }

    private String normalizedFieldName(ApiGraphNodeEntity node) {
        Object paramPath = readJsonMap(node.getPropsJson()).get("paramPath");
        String raw = safeNonBlank(paramPath == null ? null : String.valueOf(paramPath), node.getLabel());
        int dot = raw.lastIndexOf('.');
        if (dot >= 0 && dot < raw.length() - 1) {
            raw = raw.substring(dot + 1);
        }
        return raw.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }

    private String writeJsonOrNull(Map<String, ?> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception ex) {
            return null;
        }
    }

    private Map<String, Object> readJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static String normalizeKind(String value) {
        String kind = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!List.of(REQUEST_REF, RESPONSE_REF, MODEL_REF, BELONGS_TO).contains(kind)) {
            throw new IllegalArgumentException("未知的边类型: " + value);
        }
        return kind;
    }

    private static String edgeIdentity(String kind, Long sourceNodeId, Long targetNodeId) {
        return kind + ":" + sourceNodeId + "->" + targetNodeId;
    }

    private static boolean isLikelyIdAlias(String left, String right) {
        if (left.isBlank() || right.isBlank()) {
            return false;
        }
        if ("id".equals(left) && right.endsWith("id")) {
            return true;
        }
        if ("id".equals(right) && left.endsWith("id")) {
            return true;
        }
        return left.endsWith("id") && right.endsWith("id") &&
                (left.contains(right.replace("id", "")) || right.contains(left.replace("id", "")));
    }

    private static boolean isTypeCompatible(String left, String right) {
        String l = simpleTypeName(left).toLowerCase(Locale.ROOT);
        String r = simpleTypeName(right).toLowerCase(Locale.ROOT);
        if (l.isBlank() || r.isBlank()) {
            return false;
        }
        if (l.equals(r)) {
            return true;
        }
        Set<String> numeric = Set.of("integer", "int", "long", "short", "byte", "number");
        return numeric.contains(l) && numeric.contains(r);
    }

    private static String simpleTypeName(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return "";
        }
        String type = rawType.trim();
        int lt = type.indexOf('<');
        int gt = type.lastIndexOf('>');
        if (lt >= 0 && gt > lt) {
            type = type.substring(lt + 1, gt).trim();
        }
        while (type.endsWith("[]")) {
            type = type.substring(0, type.length() - 2).trim();
        }
        int comma = type.indexOf(',');
        if (comma >= 0) {
            type = type.substring(0, comma).trim();
        }
        int dot = type.lastIndexOf('.');
        if (dot >= 0) {
            type = type.substring(dot + 1);
        }
        return type;
    }

    private static boolean isPrimitive(String simpleType) {
        if (simpleType == null || simpleType.isBlank()) {
            return true;
        }
        return PRIMITIVE_TYPE_NAMES.contains(simpleType.toLowerCase(Locale.ROOT));
    }

    private static boolean looksLikeCompositeName(String simpleType) {
        if (simpleType == null || simpleType.isBlank() || isPrimitive(simpleType)) {
            return false;
        }
        return Character.isUpperCase(simpleType.charAt(0));
    }

    private static double roundConfidence(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String statusOrConfirmed(ApiGraphEdgeEntity edge) {
        return safeNonBlank(edge.getStatus(), STATUS_CONFIRMED);
    }

    private static String safeNonNull(String value) {
        return value == null ? "" : value;
    }

    static CapabilityApiGraphSnapshotView.EdgeView toEdgeView(ApiGraphEdgeEntity entity) {
        return new CapabilityApiGraphSnapshotView.EdgeView(
                entity.getId(),
                entity.getProjectId(),
                entity.getSourceNodeId(),
                entity.getTargetNodeId(),
                entity.getKind(),
                entity.getSource(),
                entity.getConfidence(),
                entity.getStatus(),
                entity.getInferStrategy(),
                entity.getConfirmedBy(),
                entity.getConfirmedAt() == null ? null : entity.getConfirmedAt().toString(),
                entity.getRejectReason(),
                entity.getEvidenceJson(),
                entity.getNote(),
                Boolean.TRUE.equals(entity.getEnabled()));
    }

    private static String safeNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return "";
    }
}
