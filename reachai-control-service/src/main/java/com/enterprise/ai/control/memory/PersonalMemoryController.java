package com.enterprise.ai.control.memory;

import com.enterprise.ai.control.memory.PersonalMemoryGroupService.AddProjectCommand;
import com.enterprise.ai.control.memory.PersonalMemoryGroupService.CreateGroupCommand;
import com.enterprise.ai.control.memory.PersonalMemoryGroupService.GroupView;
import com.enterprise.ai.control.memory.PersonalMemoryGroupService.LayerView;
import com.enterprise.ai.control.memory.PersonalMemoryGroupService.TreeView;
import com.enterprise.ai.control.memory.PersonalMemoryGroupService.UpdateGroupCommand;
import com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import com.enterprise.ai.control.memory.PersonalMemoryService.EraseAllCommand;
import com.enterprise.ai.control.memory.PersonalMemoryService.EraseAllView;
import com.enterprise.ai.control.memory.PersonalMemoryService.ExportView;
import com.enterprise.ai.control.memory.PersonalMemoryService.ForgetView;
import com.enterprise.ai.control.memory.PersonalMemoryService.MemoryPage;
import com.enterprise.ai.control.memory.PersonalMemoryService.MemoryView;
import com.enterprise.ai.control.memory.PersonalMemoryService.RememberCommand;
import com.enterprise.ai.control.memory.PersonalMemoryService.RememberResult;
import com.enterprise.ai.control.memory.PersonalMemoryService.UpdateCommand;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * The signed-in person's own memories (我的 · 我的记忆): add, edit and delete memories, and arrange them
 * into groups under the personal layer and per-project layers.
 */
@RestController
@RequestMapping("/api/me/memories")
@RequiredArgsConstructor
public class PersonalMemoryController {

    private final PersonalMemoryIdentityResolver identityResolver;
    private final PersonalMemoryService memoryService;
    private final PersonalMemoryGroupService groupService;

    @GetMapping("/tree")
    public TreeView tree(HttpServletRequest request) {
        return groupService.tree(principal(request));
    }

    @GetMapping
    public MemoryPage list(HttpServletRequest request,
                           @RequestParam(required = false) String scope,
                           @RequestParam(required = false) Long groupId,
                           @RequestParam(required = false) String agentId,
                           @RequestParam(required = false) String keyword,
                           @RequestParam(defaultValue = "50") Integer limit,
                           @RequestParam(defaultValue = "0") Integer offset) {
        return memoryService.list(principal(request), scope, groupId, agentId, keyword, limit, offset);
    }

    @PostMapping
    public ResponseEntity<RememberResult> remember(HttpServletRequest request, @RequestBody RememberCommand command) {
        RememberResult result = memoryService.remember(principal(request), manual(command));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result);
    }

    @GetMapping("/{id}")
    public MemoryView get(HttpServletRequest request, @PathVariable Long id) {
        return memoryService.get(principal(request), id);
    }

    @PutMapping("/{id}")
    public MemoryView update(HttpServletRequest request, @PathVariable Long id, @RequestBody UpdateCommand command) {
        return memoryService.update(principal(request), id, command);
    }

    @DeleteMapping("/{id}")
    public ForgetView forget(HttpServletRequest request, @PathVariable Long id) {
        return memoryService.forget(principal(request), id);
    }

    @PostMapping("/erase-all")
    public EraseAllView eraseAll(HttpServletRequest request, @RequestBody EraseAllCommand command) {
        return memoryService.eraseAll(principal(request), command);
    }

    @PostMapping("/groups")
    public GroupView createGroup(HttpServletRequest request, @RequestBody CreateGroupCommand command) {
        return groupService.createGroup(principal(request), command);
    }

    @PutMapping("/groups/{id}")
    public GroupView updateGroup(HttpServletRequest request, @PathVariable Long id,
                                 @RequestBody UpdateGroupCommand command) {
        return groupService.updateGroup(principal(request), id, command);
    }

    @DeleteMapping("/groups/{id}")
    public ResponseEntity<Void> deleteGroup(HttpServletRequest request, @PathVariable Long id) {
        groupService.deleteGroup(principal(request), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/projects")
    public LayerView addProject(HttpServletRequest request, @RequestBody AddProjectCommand command) {
        return groupService.addProject(principal(request), command == null ? null : command.projectCode());
    }

    @GetMapping(value = "/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ExportView> export(HttpServletRequest request) {
        PersonalMemoryPrincipal principal = principal(request);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("reachai-personal-memory.json", StandardCharsets.UTF_8)
                .build());
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(memoryService.export(principal), headers, HttpStatus.OK);
    }

    /** A memory added on a page is always the owner's own manual entry, whatever the body claims. */
    static RememberCommand manual(RememberCommand command) {
        return command == null ? null : new RememberCommand(command.type(), command.semanticKey(),
                command.title(), command.content(), command.tags(), command.expiresAt(), command.clientRequestId(),
                PersonalMemoryService.SOURCE_MANUAL, null, null, null, command.projectCode(), command.groupId());
    }

    private PersonalMemoryPrincipal principal(HttpServletRequest request) {
        return identityResolver.resolve(request, request.getHeader(PersonalMemoryIdentityResolver.TENANT_HEADER));
    }
}
