package com.enterprise.ai.external;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.enterprise.ai.domain.KnowledgeBaseSettings;
import com.enterprise.ai.domain.dto.ExternalKnowledgeConnectionRequest;
import com.enterprise.ai.domain.dto.ExternalKnowledgeView;
import com.enterprise.ai.domain.entity.KnowledgeBase;
import com.enterprise.ai.repository.KnowledgeBaseLookup;
import com.enterprise.ai.repository.KnowledgeBaseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Saved connections, connection tests and searches of external knowledge bases. */
@Service
public class ExternalKnowledgeService {
    private static final int MAX_DATASETS = 20;
    private final Map<String, ExternalKnowledgeProvider> providers = new LinkedHashMap<>();
    private final ExternalKnowledgeSecretCipher cipher;
    private final KnowledgeBaseRepository bases;
    private final KnowledgeBaseLookup lookup;
    private final ObjectMapper mapper = new ObjectMapper();

    public ExternalKnowledgeService(List<ExternalKnowledgeProvider> providers, ExternalKnowledgeSecretCipher cipher,
                                    KnowledgeBaseRepository bases, KnowledgeBaseLookup lookup) {
        providers.forEach(provider -> this.providers.put(provider.type(), provider));
        this.cipher = cipher;
        this.bases = bases;
        this.lookup = lookup;
    }

    /** Providers for the console's picker. */
    public List<Map<String, Object>> providers() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ExternalKnowledgeProvider provider : providers.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", provider.type());
            item.put("name", provider.displayName());
            item.put("defaultBaseUrl", provider.defaultBaseUrl());
            item.put("requiresClientId", provider.requiresClientId());
            list.add(item);
        }
        return list;
    }

    /** Tests a draft (falling back to the saved key of {@code kbCode}) and lists the remote datasets it can read. */
    public List<ExternalKnowledgeConnection.RemoteDataset> testConnection(ExternalKnowledgeConnectionRequest request) {
        KnowledgeBase saved = savedFor(request == null ? null : request.getKbCode());
        ExternalKnowledgeConnection draft = resolveDraft(request, saved == null ? null : connectionOf(saved, false),
                saved == null ? null : saved.getExternalSecret(), false);
        return provider(draft.provider()).listDatasets(draft);
    }

    /** Validated connection for a new external knowledge base. */
    public ExternalKnowledgeConnection newConnection(ExternalKnowledgeConnectionRequest request) {
        return resolveDraft(request, null, null, true);
    }

    /** Writes provider, non-secret config and encrypted key onto a knowledge base row (not yet persisted). */
    public void apply(KnowledgeBase kb, ExternalKnowledgeConnection connection) {
        kb.setSourceType(KnowledgeBaseSettings.SOURCE_EXTERNAL);
        kb.setExternalProvider(connection.provider());
        kb.setExternalConfig(configJson(connection));
        kb.setExternalSecret(cipher.encrypt(connection.apiKey()));
    }

    /** Replaces the connection of an existing external knowledge base; a blank key keeps the saved one. */
    public void updateConnection(String code, ExternalKnowledgeConnectionRequest request) {
        KnowledgeBase current = lookup.requireByCode(code);
        if (!KnowledgeBaseSettings.isExternal(current)) throw new IllegalArgumentException("只有外部知识库可以修改连接");
        ExternalKnowledgeConnection saved = connectionOf(current, false);
        if (request != null && request.getProvider() != null && !request.getProvider().trim().equalsIgnoreCase(saved.provider())) {
            throw new IllegalArgumentException("外部知识库建好后不能更换提供方，请新建一个");
        }
        ExternalKnowledgeConnection next = resolveDraft(request, saved, current.getExternalSecret(), true);
        KnowledgeBase change = new KnowledgeBase();
        change.setExternalConfig(configJson(next));
        change.setExternalSecret(cipher.encrypt(next.apiKey()));
        if (bases.update(change, new LambdaUpdateWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, current.getId()).eq(KnowledgeBase::getCode, current.getCode())) != 1) {
            throw new IllegalStateException("知识库已删除或身份已变化");
        }
    }

    public List<ExternalKnowledgeHit> search(KnowledgeBase kb, String query, int topK, float threshold) {
        ExternalKnowledgeConnection connection = connectionOf(kb, true);
        return provider(connection.provider()).search(connection, query, topK, threshold);
    }

    public ExternalKnowledgeView view(KnowledgeBase kb) {
        if (!KnowledgeBaseSettings.isExternal(kb)) return null;
        ExternalKnowledgeConnection connection = connectionOf(kb, false);
        ExternalKnowledgeProvider provider = providers.get(connection.provider());
        return new ExternalKnowledgeView(connection.provider(), provider == null ? connection.provider() : provider.displayName(),
                connection.baseUrl(), connection.clientId(), connection.datasets(),
                kb.getExternalSecret() != null && !kb.getExternalSecret().isBlank());
    }

    public String providerName(String type) {
        ExternalKnowledgeProvider provider = type == null ? null : providers.get(type);
        return provider == null ? type : provider.displayName();
    }

    private KnowledgeBase savedFor(String kbCode) {
        if (kbCode == null || kbCode.isBlank()) return null;
        KnowledgeBase kb = lookup.requireByCode(kbCode.trim());
        return KnowledgeBaseSettings.isExternal(kb) ? kb : null;
    }

    ExternalKnowledgeConnection connectionOf(KnowledgeBase kb, boolean withKey) {
        JsonNode config;
        try {
            config = kb.getExternalConfig() == null || kb.getExternalConfig().isBlank()
                    ? mapper.createObjectNode() : mapper.readTree(kb.getExternalConfig());
        } catch (Exception invalid) {
            throw new ExternalKnowledgeException("外部知识库连接配置已损坏，请重新填写");
        }
        List<ExternalKnowledgeConnection.RemoteDataset> datasets = new ArrayList<>();
        for (JsonNode dataset : config.path("datasets")) {
            datasets.add(new ExternalKnowledgeConnection.RemoteDataset(dataset.path("id").asText(""), dataset.path("name").asText("")));
        }
        String key = withKey ? cipher.decrypt(kb.getExternalSecret()) : null;
        return new ExternalKnowledgeConnection(kb.getExternalProvider(), text(config, "baseUrl"), text(config, "clientId"), key, datasets);
    }

    /**
     * Normalizes a console draft. A missing API key falls back to the saved connection's key (decrypted here only).
     * {@code requireDatasets}: saving needs at least one selected remote dataset; a connection test does not.
     */
    private ExternalKnowledgeConnection resolveDraft(ExternalKnowledgeConnectionRequest request, ExternalKnowledgeConnection saved,
                                                     String savedSecret, boolean requireDatasets) {
        if (request == null) throw new IllegalArgumentException("请填写外部知识库连接");
        String type = request.getProvider() == null || request.getProvider().isBlank()
                ? (saved == null ? "" : saved.provider()) : request.getProvider().trim().toUpperCase(Locale.ROOT);
        ExternalKnowledgeProvider provider = provider(type);
        String baseUrl = trim(request.getBaseUrl());
        if (baseUrl == null) baseUrl = provider.defaultBaseUrl();
        if (baseUrl == null) throw new IllegalArgumentException("请填写" + provider.displayName() + "的服务地址");
        if (baseUrl.length() > 512) throw new IllegalArgumentException("服务地址太长");
        String clientId = trim(request.getClientId());
        if (provider.requiresClientId() && clientId == null) throw new IllegalArgumentException("请填写 Client ID");
        String apiKey = trim(request.getApiKey());
        if (apiKey == null && saved != null && saved.provider().equals(type)) {
            // The saved key may only be reused against the address it was saved for.
            if (!baseUrl.equals(saved.baseUrl())) throw new IllegalArgumentException("更换服务地址后需要重新填写密钥");
            apiKey = cipher.decrypt(savedSecret);
        }
        if (apiKey == null) throw new IllegalArgumentException("请填写 API Key");
        if (apiKey.length() > 1024 || clientId != null && clientId.length() > 256) throw new IllegalArgumentException("密钥太长");
        List<ExternalKnowledgeConnection.RemoteDataset> datasets = new ArrayList<>();
        if (request.getDatasets() != null) {
            for (var dataset : request.getDatasets()) {
                String id = dataset == null ? null : trim(dataset.getId());
                if (id == null || id.length() > 128) continue;
                String name = trim(dataset.getName());
                if (datasets.stream().noneMatch(existing -> existing.id().equals(id))) {
                    datasets.add(new ExternalKnowledgeConnection.RemoteDataset(id, name == null ? id : abbreviate(name, 128)));
                }
            }
        }
        if (datasets.size() > MAX_DATASETS) throw new IllegalArgumentException("一个外部知识库最多选 " + MAX_DATASETS + " 个远端库");
        if (requireDatasets && datasets.isEmpty()) throw new IllegalArgumentException("请至少选择一个远端知识库");
        return new ExternalKnowledgeConnection(type, baseUrl, clientId, apiKey, datasets);
    }

    private ExternalKnowledgeProvider provider(String type) {
        ExternalKnowledgeProvider provider = type == null ? null : providers.get(type);
        if (provider == null) throw new IllegalArgumentException("不支持的外部知识库类型：" + type + "，可选 " + String.join(" / ", providers.keySet()));
        return provider;
    }

    private String configJson(ExternalKnowledgeConnection connection) {
        ObjectNode node = mapper.createObjectNode();
        node.put("baseUrl", connection.baseUrl());
        if (connection.clientId() != null) node.put("clientId", connection.clientId());
        ArrayNode datasets = node.putArray("datasets");
        connection.datasets().forEach(dataset -> datasets.addObject().put("id", dataset.id()).put("name", dataset.name()));
        return node.toString();
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String abbreviate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
