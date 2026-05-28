package com.enterprise.ai.agent.platform.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class HeaderPlatformAuthProvider implements PlatformAuthProvider {

    private final PlatformAuthProperties properties;

    @Override
    public String providerType() {
        return "HEADER";
    }

    @Override
    public PlatformUserProfile authenticate(PlatformLoginRequest request) {
        PlatformAuthProperties.Header header = resolveConfig(request);
        if (!header.isEnabled()) {
            throw new IllegalArgumentException("HEADER provider is disabled");
        }
        String username = header(request.getHeaders(), header.getUsernameHeader());
        String subject = header(request.getHeaders(), header.getSubjectHeader());
        if (!StringUtils.hasText(username) || !StringUtils.hasText(subject)) {
            throw new IllegalArgumentException("trusted identity headers are required");
        }
        return new PlatformUserProfile(
                "HEADER",
                subject,
                username,
                header(request.getHeaders(), header.getDisplayNameHeader()),
                header(request.getHeaders(), header.getEmailHeader()),
                header(request.getHeaders(), header.getMobileHeader()),
                parseRoles(header(request.getHeaders(), header.getRolesHeader()), header.getRolesDelimiter()));
    }

    private PlatformAuthProperties.Header resolveConfig(PlatformLoginRequest request) {
        MapReader config = new MapReader(request == null ? null : request.getProviderConfig());
        PlatformAuthProperties.Header base = properties.getHeader();
        PlatformAuthProperties.Header header = new PlatformAuthProperties.Header();
        header.setEnabled(config.bool("enabled", base.isEnabled()));
        header.setUsernameHeader(config.text("usernameHeader", base.getUsernameHeader()));
        header.setSubjectHeader(config.text("subjectHeader", base.getSubjectHeader()));
        header.setDisplayNameHeader(config.text("displayNameHeader", base.getDisplayNameHeader()));
        header.setEmailHeader(config.text("emailHeader", base.getEmailHeader()));
        header.setMobileHeader(config.text("mobileHeader", base.getMobileHeader()));
        header.setRolesHeader(config.text("rolesHeader", base.getRolesHeader()));
        header.setRolesDelimiter(config.text("rolesDelimiter", base.getRolesDelimiter()));
        return header;
    }

    private Set<String> parseRoles(String raw, String delimiter) {
        if (!StringUtils.hasText(raw)) {
            return Set.of();
        }
        String regex = StringUtils.hasText(delimiter) ? java.util.regex.Pattern.quote(delimiter) : ",";
        return Arrays.stream(raw.split(regex))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
    }

    private String header(Map<String, String> headers, String name) {
        if (headers == null || !StringUtils.hasText(name)) {
            return null;
        }
        String direct = headers.get(name);
        if (StringUtils.hasText(direct)) {
            return direct;
        }
        String target = name.toLowerCase(Locale.ROOT);
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getKey().toLowerCase(Locale.ROOT).equals(target))
                .map(Map.Entry::getValue)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }

    private record MapReader(Map<String, Object> values) {
        private String text(String key, String fallback) {
            Object value = values == null ? null : values.get(key);
            return value == null || !StringUtils.hasText(String.valueOf(value)) ? fallback : String.valueOf(value);
        }

        private boolean bool(String key, boolean fallback) {
            Object value = values == null ? null : values.get(key);
            return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
        }
    }
}
