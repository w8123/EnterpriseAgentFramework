package com.enterprise.ai.agent.identity;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.registry.RegistryCredentialEntity;
import com.enterprise.ai.agent.registry.RegistryCredentialMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class EmbedCorsFilter extends OncePerRequestFilter {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final RegistryCredentialMapper credentialMapper;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI() == null || !request.getRequestURI().startsWith("/api/embed/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (StringUtils.hasText(origin) && isAllowedOrigin(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Vary", "Origin");
            response.setHeader("Access-Control-Allow-Credentials", "false");
            response.setHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
            response.setHeader("Access-Control-Allow-Headers",
                    "Authorization,Content-Type,Accept,X-EAF-App-Key,X-EAF-Timestamp,X-EAF-Nonce,X-EAF-Signature");
            response.setHeader("Access-Control-Expose-Headers", "Content-Type");
            response.setHeader("Access-Control-Max-Age", "600");
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isAllowedOrigin(String origin) {
        List<RegistryCredentialEntity> credentials = credentialMapper.selectList(Wrappers.<RegistryCredentialEntity>lambdaQuery()
                .eq(RegistryCredentialEntity::getStatus, "ACTIVE"));
        for (RegistryCredentialEntity credential : credentials) {
            for (String pattern : readStringList(credential.getAllowedOriginsJson())) {
                if (originAllowed(pattern, origin)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> readStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private boolean originAllowed(String pattern, String origin) {
        if (!StringUtils.hasText(pattern) || !StringUtils.hasText(origin) || "*".equals(pattern.trim())) {
            return false;
        }
        String normalizedPattern = pattern.trim();
        if (!normalizedPattern.contains("*")) {
            return normalizedPattern.equals(origin);
        }
        if (!normalizedPattern.startsWith("https://*.") && !normalizedPattern.startsWith("http://*.")) {
            return false;
        }
        String suffix = normalizedPattern.substring(normalizedPattern.indexOf("*.") + 1);
        int schemeEnd = normalizedPattern.indexOf("://");
        String scheme = normalizedPattern.substring(0, schemeEnd + 3);
        if (!origin.startsWith(scheme) || !origin.endsWith(suffix)) {
            return false;
        }
        String subdomain = origin.substring(scheme.length(), origin.length() - suffix.length());
        return StringUtils.hasText(subdomain) && !subdomain.contains("/") && !subdomain.contains(":");
    }
}
