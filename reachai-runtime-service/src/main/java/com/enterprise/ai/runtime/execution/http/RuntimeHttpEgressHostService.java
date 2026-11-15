package com.enterprise.ai.runtime.execution.http;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Intranet hosts a platform administrator allowed from the console. Most enterprise systems live on
 * private addresses, and an allowlist that only exists in a server environment variable stops a
 * customer at the first step. A host listed here may resolve to a private or loopback address; cloud
 * metadata and link-local addresses stay blocked no matter what is listed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeHttpEgressHostService {

    static final int MAX_HOSTS = 200;
    private static final long CACHE_MS = 15_000;
    private static final Pattern HOST = Pattern.compile(
            "^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$");
    private static final Set<String> NEVER = Set.of("metadata", "metadata.google.internal", "metadata.azure.com",
            "169.254.169.254", "0.0.0.0");

    private final RuntimeHttpEgressHostMapper mapper;
    private volatile Set<String> cached = Set.of();
    private volatile long cachedAt;

    /** Hosts allowed right now; other instances pick up a change within the cache time. */
    public Set<String> hosts() {
        long now = System.currentTimeMillis();
        if (now - cachedAt > CACHE_MS) {
            try {
                Set<String> fresh = new LinkedHashSet<>();
                for (RuntimeHttpEgressHostEntity row : mapper.selectList(null)) {
                    if (StringUtils.hasText(row.getHost())) fresh.add(row.getHost());
                }
                cached = Set.copyOf(fresh);
            } catch (RuntimeException unavailable) {
                // Before the table exists the environment allowlist alone applies.
                log.debug("HTTP egress host list unavailable: {}", unavailable.getMessage());
            }
            cachedAt = now;
        }
        return cached;
    }

    public List<RuntimeHttpEgressHostEntity> list() {
        return mapper.selectList(Wrappers.<RuntimeHttpEgressHostEntity>lambdaQuery()
                .orderByAsc(RuntimeHttpEgressHostEntity::getHost));
    }

    public RuntimeHttpEgressHostEntity add(String rawHost, String note, String actor) {
        String host = normalize(rawHost);
        RuntimeHttpEgressHostEntity existing = mapper.selectOne(Wrappers.<RuntimeHttpEgressHostEntity>lambdaQuery()
                .eq(RuntimeHttpEgressHostEntity::getHost, host).last("LIMIT 1"));
        if (existing != null) return existing;
        if (mapper.selectCount(null) >= MAX_HOSTS) {
            throw new IllegalArgumentException("白名单最多 " + MAX_HOSTS + " 个地址");
        }
        RuntimeHttpEgressHostEntity row = new RuntimeHttpEgressHostEntity();
        row.setHost(host);
        row.setNote(StringUtils.hasText(note) ? limit(note.trim(), 200) : null);
        row.setCreatedBy(StringUtils.hasText(actor) ? limit(actor.trim(), 128) : null);
        row.setCreatedAt(LocalDateTime.now());
        mapper.insert(row);
        cachedAt = 0;
        return row;
    }

    public void remove(Long id) {
        if (id != null) mapper.deleteById(id);
        cachedAt = 0;
    }

    /** A bare host name or IPv4 address; a pasted URL is reduced to its host. */
    static String normalize(String rawHost) {
        String host = rawHost == null ? "" : rawHost.trim().toLowerCase(Locale.ROOT);
        if (host.contains("://")) host = host.substring(host.indexOf("://") + 3);
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        if (host.contains("@")) throw new IllegalArgumentException("地址里不要带账号密码");
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) host = host.substring(0, colon);
        if (!HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("请填写主机名或 IP，例如 erp.corp.local 或 192.168.1.20");
        }
        if (NEVER.contains(host) || host.startsWith("169.254.")) {
            throw new IllegalArgumentException("这个地址是云平台内部地址，不能加入白名单");
        }
        return host;
    }

    private static String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
