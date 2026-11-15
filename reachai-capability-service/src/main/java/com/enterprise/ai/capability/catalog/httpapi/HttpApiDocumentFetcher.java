package com.enterprise.ai.capability.catalog.httpapi;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Downloads an API document (e.g. {@code https://erp.example.com/v3/api-docs}) for the batch import.
 *
 * <p>Only the person with project write permission reaches this (Control's project proxy checks it). Business
 * systems usually live on the intranet, so private addresses are allowed; loopback, link-local (cloud metadata),
 * multicast and wildcard addresses are not. Every redirect hop is checked again, and an Authorization value is
 * only sent to the host the person typed.</p>
 */
@Component
public class HttpApiDocumentFetcher {
    private static final int MAX_REDIRECTS = 3;
    private static final Set<String> BLOCKED_HOSTS = Set.of("metadata.google.internal", "metadata", "100.100.100.200");
    private static final long ALLOWLIST_CACHE_MS = 15_000;
    private final boolean allowLoopback;
    private final Supplier<List<String>> consoleAllowlist;
    private final HttpClient client;
    private volatile Set<String> allowedHosts = Set.of();
    private volatile long allowedHostsAt;

    @Autowired
    public HttpApiDocumentFetcher(@Value("${reachai.http-api-import.allow-loopback:false}") boolean allowLoopback,
                                  ObjectProvider<CapabilityHttpEgressHostClient> egressHosts) {
        this(allowLoopback, () -> {
            CapabilityHttpEgressHostClient client = egressHosts.getIfAvailable();
            return client == null ? List.of() : client.allowedHosts();
        });
    }

    public HttpApiDocumentFetcher(boolean allowLoopback) {
        this(allowLoopback, List::of);
    }

    HttpApiDocumentFetcher(boolean allowLoopback, Supplier<List<String>> consoleAllowlist) {
        this.allowLoopback = allowLoopback;
        this.consoleAllowlist = consoleAllowlist;
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(6)).build();
    }

    public Fetched fetch(String rawUrl, String authorization) {
        URI first = parse(rawUrl);
        try {
            return download(first, authorization);
        } catch (HtmlPage page) {
            // People paste the Swagger UI / Knife4j page itself; try the document endpoints next to it.
            for (URI guess : guesses(first)) {
                try { return download(guess, authorization); }
                catch (IllegalArgumentException | HtmlPage ignored) { /* try the next guess */ }
            }
            throw new IllegalArgumentException("这个地址返回的是网页，不是接口文档。Swagger 页面请改用 /v3/api-docs 或 /v2/api-docs 地址");
        }
    }

    /**
     * Reads a JSON API response (Dify app info, n8n workflow list) with the given headers. Same host checks as
     * documents; the headers are only sent to the host the person typed.
     */
    public Fetched fetchJson(String rawUrl, Map<String, String> headers) {
        try {
            return download(parse(rawUrl), headers);
        } catch (HtmlPage page) {
            throw new IllegalArgumentException("这个地址返回的是网页，不是接口。请确认填的是 API 地址");
        }
    }

    private Fetched download(URI start, String authorization) {
        return download(start, StringUtils.hasText(authorization) ? Map.of("Authorization", authorization) : Map.of());
    }

    private Fetched download(URI start, Map<String, String> headers) {
        URI current = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            checkHost(current);
            HttpRequest.Builder request = HttpRequest.newBuilder(current).timeout(Duration.ofSeconds(15)).GET()
                    .header("Accept", "application/json, application/yaml, text/yaml, */*;q=0.5")
                    .header("User-Agent", "ReachAI-API-Import");
            if (sameOrigin(start, current)) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    if (!StringUtils.hasText(header.getValue())) continue;
                    String value = header.getValue().trim();
                    if (value.length() > 4096 || value.chars().anyMatch(ch -> ch < 32 || ch == 127)) throw new IllegalArgumentException(header.getKey() + " 取值无效");
                    request.header(header.getKey(), value);
                }
            }
            HttpResponse<InputStream> response;
            try {
                response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException failed) {
                throw new IllegalArgumentException("无法访问文档地址：" + (failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage()));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalArgumentException("下载文档被中断");
            }
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                close(response.body());
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null) throw new IllegalArgumentException("文档地址返回了跳转，但没有目标地址");
                current = parse(current.resolve(location).toString());
                continue;
            }
            if (status == 401 || status == 403) {
                close(response.body());
                throw new IllegalArgumentException("文档地址需要登录（HTTP " + status + "）。可以填写 Authorization，或在浏览器里打开地址另存为文件后上传");
            }
            if (status != 200) {
                close(response.body());
                throw new IllegalArgumentException("文档地址返回 HTTP " + status);
            }
            String content = read(response.body());
            String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            if (type.contains("text/html") || content.stripLeading().startsWith("<")) throw new HtmlPage();
            return new Fetched(current.toString(), content);
        }
        throw new IllegalArgumentException("文档地址跳转次数过多");
    }

    static URI parse(String raw) {
        if (!StringUtils.hasText(raw) || raw.length() > 2048) throw new IllegalArgumentException("请填写文档地址");
        URI uri;
        try { uri = URI.create(raw.trim()); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("文档地址无法解析"); }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!Set.of("http", "https").contains(scheme) || !StringUtils.hasText(uri.getHost())) {
            throw new IllegalArgumentException("文档地址需要以 http:// 或 https:// 开头");
        }
        if (uri.getRawUserInfo() != null) throw new IllegalArgumentException("文档地址里不要带账号密码");
        return uri;
    }

    private void checkHost(URI uri) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (BLOCKED_HOSTS.contains(host)) throw new IllegalArgumentException("不能从这个地址下载文档");
        InetAddress[] addresses;
        try { addresses = InetAddress.getAllByName(host); }
        catch (UnknownHostException unknown) { throw new IllegalArgumentException("找不到文档地址的主机：" + host); }
        for (InetAddress address : addresses) {
            if (!blocked(address)) continue;
            // A host on the administrators' intranet allowlist may be this machine; nothing else is ever opened.
            if (address.isLoopbackAddress() && allowlisted(host)) continue;
            throw new IllegalArgumentException(address.isLoopbackAddress()
                    ? "不能从本机地址下载文档。业务系统和 ReachAI 装在同一台机器上时，请平台管理员在「接口鉴权 · 内网地址白名单」里加上 "
                            + host + "，加完马上生效；也可以把文档另存为文件后上传"
                    : "不能从云平台内部地址下载文档，请把文档另存为文件后上传");
        }
    }

    /** The console allowlist, refreshed every 15 seconds; when Runtime cannot be asked nothing extra is allowed. */
    private boolean allowlisted(String host) {
        long now = System.currentTimeMillis();
        if (now - allowedHostsAt > ALLOWLIST_CACHE_MS) {
            try {
                List<String> hosts = consoleAllowlist.get();
                Set<String> fresh = new java.util.HashSet<>();
                if (hosts != null) for (String item : hosts) if (StringUtils.hasText(item)) fresh.add(item.trim().toLowerCase(Locale.ROOT));
                allowedHosts = Set.copyOf(fresh);
            } catch (RuntimeException unavailable) {
                allowedHosts = Set.of();
            }
            allowedHostsAt = now;
        }
        return allowedHosts.contains(host);
    }

    boolean blocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()) return true;
        if (address.isLoopbackAddress()) return !allowLoopback;
        byte[] raw = address.getAddress();
        if (address instanceof Inet6Address && raw.length == 16) {
            // fd00:ec2::254 (AWS IMDS over IPv6) and IPv4-mapped forms of blocked IPv4 addresses.
            if ((raw[0] & 0xff) == 0xfd && (raw[1] & 0xff) == 0x00 && (raw[2] & 0xff) == 0x0e && (raw[3] & 0xff) == 0xc2) return true;
            boolean mapped = true;
            for (int i = 0; i < 10; i++) if (raw[i] != 0) mapped = false;
            if (mapped && (raw[10] & 0xff) == 0xff && (raw[11] & 0xff) == 0xff) {
                try { return blocked(InetAddress.getByAddress(new byte[]{raw[12], raw[13], raw[14], raw[15]})); }
                catch (UnknownHostException impossible) { return true; }
            }
        }
        return raw.length == 4 && (raw[0] & 0xff) == 100 && (raw[1] & 0xff) == 100 && (raw[2] & 0xff) == 100 && (raw[3] & 0xff) == 200;
    }

    static List<URI> guesses(URI page) {
        String path = page.getPath() == null ? "" : page.getPath();
        String base = path;
        for (String marker : List.of("/swagger-ui", "/doc.html", "/swagger-resources")) {
            int index = path.indexOf(marker);
            if (index >= 0) { base = path.substring(0, index); break; }
        }
        if (base.equals(path)) base = "";
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        List<URI> result = new ArrayList<>();
        for (String doc : List.of("/v3/api-docs", "/v2/api-docs")) {
            result.add(page.resolve(base + doc));
        }
        return result;
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost()) && a.getPort() == b.getPort();
    }

    private static String read(InputStream body) {
        try (InputStream input = body) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > HttpApiDocumentImportService.MAX_CHARS) throw new IllegalArgumentException("文档不能超过 5MB");
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException failed) {
            throw new IllegalArgumentException("读取文档失败：" + failed.getMessage());
        }
    }

    private static void close(InputStream body) {
        try { body.close(); } catch (IOException ignored) { /* nothing to do */ }
    }

    public record Fetched(String url, String content) { }

    private static final class HtmlPage extends RuntimeException {
        HtmlPage() { super(null, null, false, false); }
    }
}
