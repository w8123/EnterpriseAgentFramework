package com.enterprise.ai.control.context;

import com.enterprise.ai.control.context.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import com.enterprise.ai.control.context.PersonalMemoryService.AuditView;
import com.enterprise.ai.control.context.PersonalMemoryService.EraseAllCommand;
import com.enterprise.ai.control.context.PersonalMemoryService.EraseAllView;
import com.enterprise.ai.control.context.PersonalMemoryService.ExportView;
import com.enterprise.ai.control.context.PersonalMemoryService.ForgetCommand;
import com.enterprise.ai.control.context.PersonalMemoryService.ForgetView;
import com.enterprise.ai.control.context.PersonalMemoryService.MemoryPage;
import com.enterprise.ai.control.context.PersonalMemoryService.MemoryView;
import com.enterprise.ai.control.context.PersonalMemoryService.QueryCommand;
import com.enterprise.ai.control.context.PersonalMemoryService.QueryResult;
import com.enterprise.ai.control.context.PersonalMemoryService.RememberCommand;
import com.enterprise.ai.control.context.PersonalMemoryService.RememberResult;
import com.enterprise.ai.control.context.PersonalMemoryService.UpdateCommand;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class PersonalMemoryController {

    private final PersonalMemoryIdentityResolver identityResolver;
    private final PersonalMemoryService memoryService;

    @GetMapping("/api/context/personal-memories")
    public ResponseEntity<MemoryPage> list(
            HttpServletRequest request,
            @RequestHeader(value = PersonalMemoryIdentityResolver.TENANT_HEADER, required = false) String tenantId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "50") Integer limit,
            @RequestParam(defaultValue = "0") Integer offset) {
        return ResponseEntity.ok(memoryService.list(principal(request, tenantId), type, status, keyword, limit, offset));
    }

    @PostMapping("/api/context/personal-memories")
    public ResponseEntity<RememberResult> remember(
            HttpServletRequest request,
            @RequestHeader(value = PersonalMemoryIdentityResolver.TENANT_HEADER, required = false) String tenantId,
            @RequestBody RememberCommand command) {
        RememberResult result = memoryService.remember(principal(request, tenantId), command);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result);
    }

    @GetMapping("/api/context/personal-memories/{id}")
    public ResponseEntity<MemoryView> get(
            HttpServletRequest request,
            @RequestHeader(value = PersonalMemoryIdentityResolver.TENANT_HEADER, required = false) String tenantId,
            @PathVariable Long id) {
        return ResponseEntity.ok(memoryService.get(principal(request, tenantId), id));
    }

    @PutMapping("/api/context/personal-memories/{id}")
    public ResponseEntity<MemoryView> update(
            HttpServletRequest request,
            @RequestHeader(value = PersonalMemoryIdentityResolver.TENANT_HEADER, required = false) String tenantId,
            @PathVariable Long id,
            @RequestBody UpdateCommand command) {
        return ResponseEntity.ok(memoryService.update(principal(request, tenantId), id, command));
    }

    @DeleteMapping("/api/context/personal-memories/{id}")
    public ResponseEntity<ForgetView> forget(
            HttpServletRequest request,
            @RequestHeader(value = PersonalMemoryIdentityResolver.TENANT_HEADER, required = false) String tenantId,
            @PathVariable Long id,
            @RequestBody(required = false) ForgetCommand command) {
        return ResponseEntity.ok(memoryService.forget(principal(request, tenantId), id, command));
    }

    @PostMapping("/api/context/personal-memories/erase-all")
    public ResponseEntity<EraseAllView> eraseAll(
            HttpServletRequest request,
            @RequestHeader(value = PersonalMemoryIdentityResolver.TENANT_HEADER, required = false) String tenantId,
            @RequestBody EraseAllCommand command) {
        return ResponseEntity.ok(memoryService.eraseAll(principal(request, tenantId), command));
    }

    @PostMapping("/api/context/personal-memories/query")
    public ResponseEntity<QueryResult> query(
            HttpServletRequest request,
            @RequestHeader(value = PersonalMemoryIdentityResolver.TENANT_HEADER, required = false) String tenantId,
            @RequestBody QueryCommand command) {
        return ResponseEntity.ok(memoryService.query(principal(request, tenantId), command));
    }

    @GetMapping("/api/context/personal-memories/audit")
    public ResponseEntity<List<AuditView>> audit(
            HttpServletRequest request,
            @RequestHeader(value = PersonalMemoryIdentityResolver.TENANT_HEADER, required = false) String tenantId,
            @RequestParam(defaultValue = "100") Integer limit) {
        return ResponseEntity.ok(memoryService.audit(principal(request, tenantId), limit));
    }

    @GetMapping(value = "/api/context/personal-memories/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ExportView> export(
            HttpServletRequest request,
            @RequestHeader(value = PersonalMemoryIdentityResolver.TENANT_HEADER, required = false) String tenantId) {
        PersonalMemoryPrincipal principal = principal(request, tenantId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("reachai-personal-memory.json", StandardCharsets.UTF_8)
                .build());
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(memoryService.export(principal), headers, HttpStatus.OK);
    }

    private PersonalMemoryPrincipal principal(HttpServletRequest request, String tenantId) {
        return identityResolver.resolve(request, tenantId);
    }
}
