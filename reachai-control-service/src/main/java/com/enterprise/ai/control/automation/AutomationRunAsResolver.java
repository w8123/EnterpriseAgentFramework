package com.enterprise.ai.control.automation;

import com.enterprise.ai.control.identity.BusinessUserSyncService;
import com.enterprise.ai.control.identity.BusinessUserSyncService.BusinessSystemAccount;
import com.enterprise.ai.control.identity.RuntimeUserMappingLookup;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whose business identity a scheduled Automation acts as: the platform user who saved the
 * pinned version, through that user's ACTIVE runtime user mapping for the target business system.
 * The answer is re-derived for every run, so a removed mapping or a disabled business account takes
 * effect on the next occurrence. Any doubt yields no identity and Runtime keeps the service account.
 */
@Service
public class AutomationRunAsResolver {

    public static final String RESOLVED = "RESOLVED";
    public static final String NO_MAPPING = "AUTOMATION_RUN_AS_NO_MAPPING";
    public static final String AMBIGUOUS = "AUTOMATION_RUN_AS_AMBIGUOUS";
    public static final String ACCOUNT_INACTIVE = "AUTOMATION_RUN_AS_ACCOUNT_INACTIVE";
    public static final String INVALID = "AUTOMATION_RUN_AS_INVALID";

    private static final Pattern PLATFORM_ACTOR = Pattern.compile("platform-user:(\\d{1,18})");

    private final RuntimeUserMappingLookup mappings;
    private final BusinessUserSyncService businessUsers;

    public AutomationRunAsResolver(RuntimeUserMappingLookup mappings, BusinessUserSyncService businessUsers) {
        this.mappings = mappings;
        this.businessUsers = businessUsers;
    }

    public Resolution resolve(Request request) {
        if (request == null || !StringUtils.hasText(request.actorId()) || !StringUtils.hasText(request.projectCode())) {
            return Resolution.none(INVALID);
        }
        Matcher actor = PLATFORM_ACTOR.matcher(request.actorId().trim());
        if (!actor.matches()) return Resolution.none(INVALID);
        long platformUserId = Long.parseLong(actor.group(1));
        String tenant = tenant(request.tenantId());
        String projectCode = request.projectCode().trim();
        List<String> externalUsers = mappings.activeExternalUserIds(tenant, platformUserId,
                request.projectId(), projectCode);
        if (externalUsers.isEmpty()) return Resolution.none(NO_MAPPING);
        if (externalUsers.size() > 1) return Resolution.none(AMBIGUOUS);
        Optional<BusinessSystemAccount> account = businessUsers.activeAccountByExternalUser(
                tenant, projectCode, externalUsers.get(0));
        return account.map(found -> new Resolution(RESOLVED, tenant, found.externalUserId(), found.roles()))
                .orElseGet(() -> Resolution.none(ACCOUNT_INACTIVE));
    }

    private static String tenant(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "default";
    }

    public record Request(String tenantId, String actorId, Long projectId, String projectCode) {
    }

    public record Resolution(String code, String tenantId, String externalUserId, List<String> roles) {
        static Resolution none(String code) {
            return new Resolution(code, null, null, List.of());
        }

        public boolean resolved() {
            return RESOLVED.equals(code);
        }
    }
}
