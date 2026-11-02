package com.enterprise.ai.control.identity;

import com.enterprise.ai.control.memory.PersonalMemoryIdentityLinkService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

/** Administrative actions use the authenticated platform actor and retain verification evidence. */
@RestController
@RequestMapping("/api/platform/personal-memory-identity-links")
@RequiredArgsConstructor
public class PersonalMemoryIdentityAdminController {
    private final PlatformRequestAuthorization authorization;
    private final PersonalMemoryIdentityLinkService links;

    @GetMapping
    public LinkPage list(HttpServletRequest request, @RequestParam long bindingId,
                         @RequestParam(defaultValue = "0") int offset) {
        var actor = authorization.requireGlobalPermission(request, PlatformPermissions.BUSINESS_USER_MANAGE);
        var rows = links.linksForAdministrator(actor, bindingId, offset);
        boolean hasMore = rows.size() > 50;
        return new LinkPage(hasMore ? rows.subList(0, 50) : rows, hasMore);
    }

    @PostMapping
    public LinkResult create(HttpServletRequest request, @RequestBody CreateCommand command) {
        var actor = authorization.requireGlobalPermission(request, PlatformPermissions.BUSINESS_USER_MANAGE);
        if (command == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "identities are required");
        return new LinkResult(links.linkByAdministrator(actor, command.firstBindingId(), command.secondBindingId(), command.evidence()));
    }

    @PostMapping("/{linkId}/revoke")
    public void revoke(HttpServletRequest request, @PathVariable long linkId, @RequestBody RevokeCommand command) {
        var actor = authorization.requireGlobalPermission(request, PlatformPermissions.BUSINESS_USER_MANAGE);
        links.revokeByAdministrator(actor, linkId, command == null ? null : command.evidence());
    }

    public record CreateCommand(long firstBindingId, long secondBindingId, String evidence) { }
    public record RevokeCommand(String evidence) { }
    public record LinkResult(long id) { }
    public record LinkPage(List<PersonalMemoryIdentityLinkService.AdminLink> items, boolean hasMore) { }
}
