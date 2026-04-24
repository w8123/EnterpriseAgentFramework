package com.enterprise.ai.agent.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.agent.persist.AgentVersionEntity;
import com.enterprise.ai.agent.agent.persist.AgentVersionMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 发布版本服务（Phase 3.0）
 * <p>
 * 职责：
 * <ul>
 *   <li>publish: 把当前 {@link AgentDefinition} 冻结快照写入 {@code agent_version}，
 *       新版本默认 status=ACTIVE / rolloutPercent=100；并把历史 ACTIVE 版本置为 RETIRED
 *       （同一 agentId 下仅允许一个 ACTIVE 除非显式灰度）。</li>
 *   <li>rollback: 指定历史 {@code versionId}，把它重新置为 ACTIVE，其余置为 RETIRED。</li>
 *   <li>listVersions: 版本列表（用于前端历史回溯）。</li>
 *   <li>resolveActiveSnapshot: 按 {@code userId hash % 100} 从当前 ACTIVE 版本中选出要执行的一版，
 *       配合 {@code AgentRouter} 在 Gateway 端点做灰度分流。</li>
 * </ul>
 * 所有版本状态变更都是事务性的，避免出现"两个 ACTIVE 而历史未被 RETIRED"的脏态。
 */
@Slf4j
@Service
public class AgentVersionService {

    private final AgentVersionMapper versionMapper;
    private final AgentDefinitionService definitionService;
    private final ObjectMapper objectMapper;

    public AgentVersionService(AgentVersionMapper versionMapper,
                               AgentDefinitionService definitionService) {
        this.versionMapper = versionMapper;
        this.definitionService = definitionService;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * 发布当前 Agent 定义为一个新版本。
     *
     * @param agentId        agent 主键
     * @param version        版本号（v1.0.0 / v1.0.1 等），必须在同一 agentId 下唯一
     * @param rolloutPercent 灰度比例 0-100；100 表示全量
     * @param note           发布说明
     * @param publishedBy    操作者（可空）
     * @return 新创建的版本记录
     */
    @Transactional
    public AgentVersionEntity publish(String agentId, String version,
                                      int rolloutPercent, String note, String publishedBy) {
        AgentDefinition def = definitionService.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Agent 定义不存在: " + agentId));

        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version 不能为空");
        }
        if (rolloutPercent < 0 || rolloutPercent > 100) {
            throw new IllegalArgumentException("rolloutPercent 必须在 0-100 之间");
        }
        AgentVersionEntity duplicate = versionMapper.selectOne(
                Wrappers.<AgentVersionEntity>lambdaQuery()
                        .eq(AgentVersionEntity::getAgentId, agentId)
                        .eq(AgentVersionEntity::getVersion, version));
        if (duplicate != null) {
            throw new IllegalArgumentException("版本号已存在: " + version);
        }

        // 若本次发布是全量（rolloutPercent=100），则把同 agent 所有历史 ACTIVE 置为 RETIRED；
        // 否则仅保留历史 ACTIVE（作为灰度对照组）。
        if (rolloutPercent == 100) {
            retireActiveVersions(agentId);
        }

        String snapshotJson;
        try {
            snapshotJson = objectMapper.writeValueAsString(def);
        } catch (Exception e) {
            throw new IllegalStateException("Agent 定义序列化失败: " + e.getMessage(), e);
        }

        AgentVersionEntity entity = new AgentVersionEntity();
        entity.setAgentId(agentId);
        entity.setVersion(version);
        entity.setSnapshotJson(snapshotJson);
        entity.setRolloutPercent(rolloutPercent);
        entity.setStatus("ACTIVE");
        entity.setPublishedBy(publishedBy);
        entity.setPublishedAt(LocalDateTime.now());
        entity.setNote(note);
        versionMapper.insert(entity);
        log.info("[AgentVersion] 发布: agentId={}, version={}, rollout={}%, publishedBy={}",
                agentId, version, rolloutPercent, publishedBy);
        return entity;
    }

    /** 指定历史版本 → 重新置为 ACTIVE；其余 ACTIVE 置为 RETIRED（全量切换）。 */
    @Transactional
    public AgentVersionEntity rollback(String agentId, Long versionId, String operator) {
        AgentVersionEntity target = versionMapper.selectById(versionId);
        if (target == null || !agentId.equals(target.getAgentId())) {
            throw new IllegalArgumentException("版本不存在或不属于该 Agent: versionId=" + versionId);
        }
        retireActiveVersions(agentId);
        target.setStatus("ACTIVE");
        target.setRolloutPercent(100);
        target.setPublishedAt(LocalDateTime.now());
        target.setPublishedBy(operator);
        versionMapper.updateById(target);
        log.info("[AgentVersion] 回滚: agentId={}, version={}, operator={}",
                agentId, target.getVersion(), operator);
        return target;
    }

    public List<AgentVersionEntity> listVersions(String agentId) {
        return versionMapper.listByAgent(agentId);
    }

    /**
     * 根据 userId 选择一个 ACTIVE 版本的快照；支持灰度分流。
     * <p>
     * 算法：
     * <ol>
     *   <li>取所有 ACTIVE 版本，按 {@code rolloutPercent} 降序；</li>
     *   <li>计算 {@code bucket = (hash(userId) % 100 + 100) % 100}；</li>
     *   <li>累加每个版本的 {@code rolloutPercent}，首个让累计 ≥ bucket+1 的版本胜出；</li>
     *   <li>若无 ACTIVE 版本 → 返回 null，调用方应降级到 {@link AgentDefinitionService#findById} 的当前态。</li>
     * </ol>
     */
    public AgentDefinition resolveActiveSnapshot(String agentId, String userId) {
        List<AgentVersionEntity> active = versionMapper.listActive(agentId);
        if (active.isEmpty()) {
            return null;
        }
        int bucket = Math.floorMod(userId == null ? 0 : userId.hashCode(), 100);
        int accumulated = 0;
        AgentVersionEntity chosen = active.get(0);
        for (AgentVersionEntity v : active) {
            accumulated += Math.max(0, Math.min(100, v.getRolloutPercent() == null ? 0 : v.getRolloutPercent()));
            if (bucket < accumulated) {
                chosen = v;
                break;
            }
        }
        return deserializeSnapshot(chosen);
    }

    private void retireActiveVersions(String agentId) {
        List<AgentVersionEntity> current = versionMapper.listActive(agentId);
        for (AgentVersionEntity v : current) {
            v.setStatus("RETIRED");
            versionMapper.updateById(v);
        }
    }

    private AgentDefinition deserializeSnapshot(AgentVersionEntity entity) {
        try {
            AgentDefinition def = objectMapper.readValue(entity.getSnapshotJson(), AgentDefinition.class);
            Map<String, Object> extra = def.getExtra() == null ? new HashMap<>() : new HashMap<>(def.getExtra());
            extra.put("__version", entity.getVersion());
            extra.put("__versionId", entity.getId());
            def.setExtra(extra);
            return def;
        } catch (Exception e) {
            log.error("[AgentVersion] 快照反序列化失败: versionId={}, err={}", entity.getId(), e.getMessage());
            return null;
        }
    }

    /** 兼容 Jackson 反序列化含未知字段的历史快照（防止未来字段增减打破）。 */
    @SuppressWarnings("unused")
    private Map<String, Object> looseRead(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<>() {});
    }
}
