package com.enterprise.ai.agent.platform.control.a2a;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ai.agent.runtime.AgentRuntimeProfile;
import com.enterprise.ai.agent.workflow.AgentEntryEntity;
import com.enterprise.ai.agent.workflow.AgentEntryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class A2aEndpointService {

    private final A2aEndpointMapper endpointMapper;
    private final AgentEntryService agentEntryService;
    private final ObjectMapper objectMapper;

    public Page<A2aEndpointEntity> page(int pageNum, int pageSize, String agentKey, Boolean enabled) {
        Page<A2aEndpointEntity> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<A2aEndpointEntity> qw = new LambdaQueryWrapper<>();
        if (agentKey != null && !agentKey.isBlank()) {
            qw.like(A2aEndpointEntity::getAgentKey, agentKey);
        }
        if (enabled != null) {
            qw.eq(A2aEndpointEntity::getEnabled, enabled);
        }
        qw.orderByDesc(A2aEndpointEntity::getId);
        return endpointMapper.selectPage(page, qw);
    }

    public Optional<A2aEndpointEntity> findByAgentKey(String agentKey) {
        if (agentKey == null || agentKey.isBlank()) return Optional.empty();
        return Optional.ofNullable(endpointMapper.selectOne(new LambdaQueryWrapper<A2aEndpointEntity>()
                .eq(A2aEndpointEntity::getAgentKey, agentKey)));
    }

    public Optional<A2aEndpointEntity> findById(Long id) {
        return Optional.ofNullable(endpointMapper.selectById(id));
    }

    @Transactional
    public A2aEndpointEntity upsertForAgent(String agentId, Map<String, Object> cardOverrides, Boolean enabled) {
        AgentEntryEntity entry = agentEntryService.findById(agentId)
                .or(() -> agentEntryService.findByKeySlug(agentId))
                .orElseThrow(() -> new IllegalArgumentException("Agent 不存在: " + agentId));
        A2aEndpointEntity existed = endpointMapper.selectOne(new LambdaQueryWrapper<A2aEndpointEntity>()
                .eq(A2aEndpointEntity::getAgentId, entry.getId()));

        Map<String, Object> baseCard = buildDefaultCard(entry);
        if (cardOverrides != null && !cardOverrides.isEmpty()) {
            baseCard.putAll(cardOverrides);
        }

        String cardJson = writeJson(baseCard);
        LocalDateTime now = LocalDateTime.now();
        if (existed == null) {
            A2aEndpointEntity en = new A2aEndpointEntity();
            en.setAgentId(entry.getId());
            en.setAgentKey(entry.getKeySlug());
            en.setProjectId(entry.getProjectId());
            en.setProjectCode(entry.getProjectCode());
            en.setEnvironment(null);
            en.setTenantId(null);
            en.setCardJson(cardJson);
            en.setEnabled(enabled == null ? true : enabled);
            en.setCreatedAt(now);
            en.setUpdatedAt(now);
            endpointMapper.insert(en);
            log.info("[A2aEndpoint] 新建：agentKey={}", entry.getKeySlug());
            return en;
        }
        existed.setAgentKey(entry.getKeySlug());
        existed.setProjectId(entry.getProjectId());
        existed.setProjectCode(entry.getProjectCode());
        existed.setCardJson(cardJson);
        if (enabled != null) {
            existed.setEnabled(enabled);
        }
        existed.setUpdatedAt(now);
        endpointMapper.updateById(existed);
        log.info("[A2aEndpoint] 更新：agentKey={}", entry.getKeySlug());
        return existed;
    }

    @Transactional
    public void delete(Long id) {
        endpointMapper.deleteById(id);
    }

    @Transactional
    public void setEnabled(Long id, boolean enabled) {
        A2aEndpointEntity en = endpointMapper.selectById(id);
        if (en == null) {
            throw new IllegalArgumentException("A2A endpoint 不存在: " + id);
        }
        en.setEnabled(enabled);
        en.setUpdatedAt(LocalDateTime.now());
        endpointMapper.updateById(en);
    }

    public Map<String, Object> parseCard(A2aEndpointEntity endpoint) {
        if (endpoint == null || endpoint.getCardJson() == null || endpoint.getCardJson().isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(endpoint.getCardJson(), Map.class);
        } catch (Exception e) {
            log.warn("[A2aEndpoint] cardJson 解析失败: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private Map<String, Object> buildDefaultCard(AgentEntryEntity entry) {
        AgentRuntimeProfile profile = AgentRuntimeProfile.fromAgentEntry(entry, objectMapper);
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("name", entry.getName() == null ? entry.getKeySlug() : entry.getName());
        card.put("description", entry.getDescription() == null ? "" : entry.getDescription());
        card.put("version", "1.0.0");
        card.put("protocolVersion", "0.2.0");
        card.put("defaultInputModes", List.of("text"));
        card.put("defaultOutputModes", List.of("text"));

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("streaming", false);
        capabilities.put("pushNotifications", false);
        capabilities.put("stateTransitionHistory", true);
        card.put("capabilities", capabilities);

        Map<String, Object> skill = new LinkedHashMap<>();
        skill.put("id", entry.getKeySlug());
        skill.put("name", entry.getName());
        skill.put("description", entry.getDescription());
        skill.put("tags", profile.getTools() == null ? List.of() : profile.getTools());
        skill.put("examples", List.of("请帮我处理一项业务请求"));
        skill.put("inputModes", List.of("text"));
        skill.put("outputModes", List.of("text"));
        card.put("skills", List.of(skill));

        return card;
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
