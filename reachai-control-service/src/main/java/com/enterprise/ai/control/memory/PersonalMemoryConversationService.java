package com.enterprise.ai.control.memory;

import com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import com.enterprise.ai.control.memory.PersonalMemoryRememberExtractor.ExtractedCandidate;
import com.enterprise.ai.control.memory.PersonalMemoryService.RememberCommand;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Keeps what a user explicitly asked a ReachAI Agent to remember ("记住……", "以后……"). The user's own
 * words are the consent, so the memory takes effect at once and can be edited or deleted afterwards.
 */
@Service
public class PersonalMemoryConversationService {

    private final PersonalMemoryService memoryService;
    private final PersonalMemoryRememberExtractor extractor;

    public PersonalMemoryConversationService(PersonalMemoryService memoryService,
                                             PersonalMemoryRememberExtractor extractor) {
        this.memoryService = memoryService;
        this.extractor = extractor;
    }

    /** Returns true when the turn carried an explicit remember instruction that was kept. */
    public boolean rememberExplicitIfPresent(String identitySource,
                                             String tenantId,
                                             String runtimeUserId,
                                             ObservationCommand command) {
        if (command == null) {
            return false;
        }
        if (!extractor.hasExplicitRememberSignal(command.userMessage())) {
            if (extractor.hasExplicitRememberPrefix(command.userMessage())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "explicit personal memory content is unsafe or invalid");
            }
            return false;
        }
        if ("EMBED_SESSION".equalsIgnoreCase(identitySource)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "业务系统身份域不可用，未保存个人记忆");
        }
        if (!isTrustedSource(identitySource) || !StringUtils.hasText(runtimeUserId)) return false;
        return rememberExplicitIfPresent(principal(tenantId, runtimeUserId), command);
    }

    /** The caller must construct this owner from a verified session or connection. */
    public boolean rememberExplicitIfPresent(PersonalMemoryPrincipal owner, ObservationCommand command) {
        if (owner == null || command == null || !extractor.hasExplicitRememberSignal(command.userMessage())) {
            if (command != null && extractor.hasExplicitRememberPrefix(command.userMessage())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "explicit personal memory content is unsafe or invalid");
            }
            return false;
        }
        // A failed write must not be reported to the user as successfully remembered.
        for (ExtractedCandidate value : extractor.extract(command.userMessage())) {
            String content = PersonalMemoryContentPolicy.validate(value.content());
            String turn = firstText(command.traceId(), command.sessionId());
            memoryService.remember(owner, new RememberCommand(
                    value.type(), value.semanticKey(), value.title(), content, null, null,
                    "turn:" + PersonalMemoryService.sha256(turn + "\n" + PersonalMemoryService.contentHash(content)),
                    PersonalMemoryService.SOURCE_CONVERSATION,
                    command.sessionId(), command.traceId(), command.agentId(), null, null));
        }
        return true;
    }

    private static PersonalMemoryPrincipal principal(String tenantId, String runtimeUserId) {
        return new PersonalMemoryPrincipal(
                tenantId == null || tenantId.isBlank() ? "default" : tenantId.trim().toLowerCase(),
                runtimeUserId.trim(), null, null, null);
    }

    private static boolean isTrustedSource(String value) {
        return "AGENT".equalsIgnoreCase(value);
    }

    private static String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first.trim() : (StringUtils.hasText(second) ? second.trim() : "");
    }

    /** One completed Agent turn. Explicit memories land in the personal layer; the user can move them. */
    public record ObservationCommand(String userMessage, String answer, String sessionId, String traceId,
                                     String agentId) {
    }
}
