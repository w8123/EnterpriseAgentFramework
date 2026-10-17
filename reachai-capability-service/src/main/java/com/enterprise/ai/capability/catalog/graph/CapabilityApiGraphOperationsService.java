package com.enterprise.ai.capability.catalog.graph;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphEdgeEntity;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphEdgeMapper;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphLayoutEntity;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphLayoutMapper;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphNodeEntity;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphNodeMapper;
import com.enterprise.ai.capability.catalog.enrichment.CatalogSubject;
import com.enterprise.ai.capability.catalog.enrichment.CatalogOwnerFingerprintReader;
import com.enterprise.ai.capability.catalog.enrichment.CatalogSubjectResolver;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
    private final CatalogSubjectResolver subjects;
    private final ObjectMapper objectMapper;
    private final CatalogOwnerFingerprintReader fingerprints;

    public List<CapabilityApiGraphSnapshotView.EdgeView> listCandidates(Long projectId,
                                                                        String status,
                                                                        Double minConfidence) {
        String normalizedStatus = safeNonBlank(status, STATUS_CANDIDATE).toUpperCase(Locale.ROOT);
        double min = minConfidence == null ? 0.0 : minConfidence;
        return edgeMapper.selectList(new LambdaQueryWrapper<ApiGraphEdgeEntity>()
                        .eq(ApiGraphEdgeEntity::getProjectId, projectId)
                        .eq(ApiGraphEdgeEntity::getEnabled, true)
                        .in(ApiGraphEdgeEntity::getKind, REQUEST_REF, RESPONSE_REF)
                        .and(q -> { q.eq(ApiGraphEdgeEntity::getStatus, normalizedStatus);
                            if (STATUS_CONFIRMED.equals(normalizedStatus)) q.or().isNull(ApiGraphEdgeEntity::getStatus).or().eq(ApiGraphEdgeEntity::getStatus, ""); })
                        .and(q -> q.isNull(ApiGraphEdgeEntity::getConfidence).or().ge(ApiGraphEdgeEntity::getConfidence, min))
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

    public List<CapabilityApiGraphParamSourceHintView> listParamHints(Long projectId, String subjectType, Long subjectId) {
        return paramHints(subjects.resolve(projectId, subjectType, subjectId)).hints();
    }

    public String paramHintState(CatalogSubject subject) { return paramHints(subject).state(); }

    public record ParamHints(String state, List<CapabilityApiGraphParamSourceHintView> hints) { }

    /** One subject node, its input fields and connected endpoints; unrelated contracts are never hydrated. */
    @Transactional(readOnly = true)
    public ParamHints paramHints(CatalogSubject subject) {
        if (!subject.operation() || !subject.accepted() || CatalogSubject.SOURCE_OBSERVATION.equals(subject.subjectType()))
            return new ParamHints("UNACCEPTED", List.of());
        Long projectId = subject.projectId();
        List<ApiGraphNodeEntity> operationNodes = nodeMapper.selectList(new LambdaQueryWrapper<ApiGraphNodeEntity>()
                .eq(ApiGraphNodeEntity::getProjectId, projectId).eq(ApiGraphNodeEntity::getKind, NODE_API)
                .eq(ApiGraphNodeEntity::getNodeKey, CatalogSubjectResolver.digest("operation:" + subject.subjectType() + ":" + subject.subjectKey())))
                .stream().filter(node -> NODE_API.equals(node.getKind()))
                .filter(node -> Objects.equals(subject.subjectType(), readJsonMap(node.getPropsJson()).get("subjectType"))
                        && Objects.equals(String.valueOf(subject.subjectId()), String.valueOf(readJsonMap(node.getPropsJson()).get("subjectId")))).toList();
        if (operationNodes.isEmpty()) return new ParamHints("NOT_BUILT", List.of());
        boolean stale = operationNodes.stream().anyMatch(node -> !Boolean.TRUE.equals(node.getActive())
                || !Objects.equals(subject.contractHash(), readJsonMap(node.getPropsJson()).get("contractHash")));
        Set<Long> operationIds = operationNodes.stream().map(ApiGraphNodeEntity::getId).collect(java.util.stream.Collectors.toSet());
        List<ApiGraphNodeEntity> inputFields = nodeMapper.selectList(new LambdaQueryWrapper<ApiGraphNodeEntity>()
                .eq(ApiGraphNodeEntity::getProjectId, projectId).eq(ApiGraphNodeEntity::getKind, FIELD_IN)
                .in(ApiGraphNodeEntity::getRefId, operationIds)).stream()
                .filter(node -> FIELD_IN.equals(node.getKind()) && operationIds.contains(node.getRefId())).toList();
        if (inputFields.isEmpty()) return new ParamHints(stale ? "STALE" : "CURRENT", List.of());
        Set<Long> inputIds = inputFields.stream().map(ApiGraphNodeEntity::getId).collect(java.util.stream.Collectors.toSet());
        List<ApiGraphEdgeEntity> edges = edgeMapper.selectList(new LambdaQueryWrapper<ApiGraphEdgeEntity>()
                .eq(ApiGraphEdgeEntity::getProjectId, projectId).eq(ApiGraphEdgeEntity::getKind, REQUEST_REF)
                .in(ApiGraphEdgeEntity::getTargetNodeId, inputIds)
                .and(q -> q.eq(ApiGraphEdgeEntity::getStatus, STATUS_CONFIRMED).or().isNull(ApiGraphEdgeEntity::getStatus)
                        .or().eq(ApiGraphEdgeEntity::getStatus, ""))).stream()
                .filter(edge -> REQUEST_REF.equals(edge.getKind()) && STATUS_CONFIRMED.equalsIgnoreCase(statusOrConfirmed(edge))
                        && inputIds.contains(edge.getTargetNodeId())).toList();
        if (edges.isEmpty()) return new ParamHints(stale ? "STALE" : "CURRENT", List.of());
        Set<Long> endpointIds = new LinkedHashSet<>(inputIds);
        edges.forEach(edge -> endpointIds.add(edge.getSourceNodeId()));
        Map<Long, ApiGraphNodeEntity> byId = nodeMapper.selectList(new LambdaQueryWrapper<ApiGraphNodeEntity>()
                .eq(ApiGraphNodeEntity::getProjectId, projectId).in(ApiGraphNodeEntity::getId, endpointIds)).stream()
                .filter(node -> endpointIds.contains(node.getId())).collect(java.util.stream.Collectors.toMap(ApiGraphNodeEntity::getId, node -> node));
        operationNodes.forEach(node -> byId.put(node.getId(), node));
        Set<CatalogOwnerFingerprintReader.Identity> identities = new LinkedHashSet<>();
        for (ApiGraphNodeEntity node : byId.values()) {
            Map<String, Object> props = readJsonMap(node.getPropsJson());
            Object type = props.get("subjectType"), id = props.get("subjectId");
            if (type == null || id == null) continue;
            try { identities.add(new CatalogOwnerFingerprintReader.Identity(String.valueOf(type), Long.valueOf(String.valueOf(id)))); }
            catch (NumberFormatException invalid) { /* Missing identity cannot establish current evidence. */ }
        }
        Map<String, CatalogOwnerFingerprintReader.Fingerprint> owners = fingerprints.read(projectId, identities);
        stale |= edges.stream().anyMatch(edge -> !Boolean.TRUE.equals(edge.getEnabled()) || !currentEvidence(edge, byId, owners));
        Set<Long> labelIds = byId.values().stream().map(ApiGraphNodeEntity::getRefId).filter(Objects::nonNull)
                .filter(id -> !byId.containsKey(id)).collect(java.util.stream.Collectors.toSet());
        if (!labelIds.isEmpty()) nodeMapper.selectList(new LambdaQueryWrapper<ApiGraphNodeEntity>()
                .eq(ApiGraphNodeEntity::getProjectId, projectId).in(ApiGraphNodeEntity::getId, labelIds)).stream()
                .filter(node -> labelIds.contains(node.getId())).forEach(node -> byId.put(node.getId(), node));
        List<CapabilityApiGraphParamSourceHintView> hints = edges.stream().filter(edge -> Boolean.TRUE.equals(edge.getEnabled()))
                .filter(edge -> currentEvidence(edge, byId, owners))
                .sorted(Comparator.comparing(ApiGraphEdgeEntity::getConfidence, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ApiGraphEdgeEntity::getId)).limit(5).map(edge -> toParamSourceHint(edge, byId))
                .filter(Objects::nonNull).toList();
        return new ParamHints(stale ? "STALE" : "CURRENT", hints);
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
            Map<String, Object> props = readJsonMap(field.getPropsJson());
            if (!Boolean.TRUE.equals(props.get("composite")) && (isPrimitive(simpleType) || !looksLikeCompositeName(simpleType))) {
                continue;
            }
            String schemaHash = String.valueOf(props.getOrDefault("schemaHash", ""));
            bucketByType.computeIfAbsent(safeNonNull(field.getTypeName()) + ":" + schemaHash, key -> new ArrayList<>()).add(field);
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
                    edge.setEvidenceJson(evidence(left, right, Map.of("by", "shared_type", "type", entry.getKey())));
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
                edge.setEvidenceJson(evidence(source, target, Map.of(
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
        ApiGraphNodeEntity source = requireNode(projectId, edge.getSourceNodeId());
        ApiGraphNodeEntity target = requireNode(projectId, edge.getTargetNodeId());
        edge.setEvidenceJson(evidence(source, target, readJsonMap(edge.getEvidenceJson())));
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
        ApiGraphNodeEntity source = requireNode(projectId, request.sourceNodeId());
        ApiGraphNodeEntity target = requireNode(projectId, request.targetNodeId());
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
        edge.setEvidenceJson(evidence(source, target, Map.of("by", "manual")));
        edge.setEnabled(Boolean.TRUE);
        return toEdgeView(upsertByIdentity(edge));
    }

    @Transactional
    public boolean deleteEdge(Long projectId, Long edgeId) {
        if (edgeId == null) {
            return false;
        }
        ApiGraphEdgeEntity edge = edgeMapper.selectById(edgeId);
        if (edge == null) return false;
        if (!Objects.equals(projectId, edge.getProjectId())) throw new IllegalArgumentException("关系不属于当前项目");
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
            requireNode(projectId, position.nodeId());
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
            // Inference cannot override a human-confirmed or rejected decision, including its old contract evidence.
            if (SOURCE_AUTO.equals(edge.getSource()) && (STATUS_CONFIRMED.equals(existing.getStatus())
                    || STATUS_REJECTED.equals(existing.getStatus()))
                    && List.of(REQUEST_REF, RESPONSE_REF).contains(edge.getKind())) return existing;
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
                .eq(ApiGraphNodeEntity::getActive, true)
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

    private ApiGraphNodeEntity requireNode(Long projectId, Long nodeId) {
        ApiGraphNodeEntity node = nodeMapper.selectById(nodeId);
        if (node == null || !Objects.equals(projectId, node.getProjectId()) || !Boolean.TRUE.equals(node.getActive()))
            throw new IllegalArgumentException("节点不存在、已失效或不属于当前项目");
        return node;
    }

    private String evidence(ApiGraphNodeEntity source, ApiGraphNodeEntity target, Map<String, Object> extras) {
        Map<String, Object> result = new LinkedHashMap<>(extras);
        result.put("sourceContractHash", readJsonMap(source.getPropsJson()).getOrDefault("contractHash", ""));
        result.put("targetContractHash", readJsonMap(target.getPropsJson()).getOrDefault("contractHash", ""));
        return writeJsonOrNull(result);
    }

    private boolean currentEvidence(ApiGraphEdgeEntity edge, Map<Long, ApiGraphNodeEntity> nodes, Map<String, CatalogOwnerFingerprintReader.Fingerprint> owners) {
        ApiGraphNodeEntity source = nodes.get(edge.getSourceNodeId()), target = nodes.get(edge.getTargetNodeId());
        if (source == null || target == null || !FIELD_OUT.equals(source.getKind()) || !FIELD_IN.equals(target.getKind())) return false;
        Map<String, Object> evidence = readJsonMap(edge.getEvidenceJson());
        return fieldCurrent(source, evidence.get("sourceContractHash"), owners) && fieldCurrent(target, evidence.get("targetContractHash"), owners);
    }

    private boolean fieldCurrent(ApiGraphNodeEntity field, Object expectedHash, Map<String, CatalogOwnerFingerprintReader.Fingerprint> owners) {
        Map<String, Object> props = readJsonMap(field.getPropsJson());
        if (!Boolean.TRUE.equals(props.get("accepted")) || expectedHash == null || String.valueOf(expectedHash).isBlank()
                || !Objects.equals(expectedHash, props.get("contractHash"))) return false;
        CatalogOwnerFingerprintReader.Fingerprint owner = owners.get(props.get("subjectType") + ":" + props.get("subjectId"));
        return Boolean.TRUE.equals(field.getActive()) && owner != null && owner.accepted() && Objects.equals(expectedHash, owner.contractHash());
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
