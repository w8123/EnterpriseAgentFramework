package com.enterprise.ai.control.model;

import com.enterprise.ai.control.client.model.ControlModelCatalogClient;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Normal console entry for configuring the model required by a published Agent. */
@RestController
@RequestMapping("/model/instances")
@RequiredArgsConstructor
public class ControlModelInstanceController {
    private final ControlModelCatalogClient models;
    private final PlatformRequestAuthorization authorization;
    private static final Set<String> CREATE_FIELDS = Set.of("id", "name", "provider", "modelType",
            "modelName", "protocol", "projectCode", "connection", "defaultOptions", "paramsSchema", "status", "remark");

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(HttpServletRequest request,
                                                   @RequestParam Map<String, String> parameters) {
        var session = authorization.requirePermission(request, PlatformPermissions.PLATFORM_READ);
        if (!Set.of("projectCode", "modelType", "provider").containsAll(parameters.keySet())) return invalid();
        String project = projectCode(parameters.get("projectCode"));
        if (project != null) requireScope(request, PlatformPermissions.PLATFORM_READ, project);
        var owner = models.list(project, parameters.get("modelType"), parameters.get("provider"));
        if (!owner.getStatusCode().is2xxSuccessful() || owner.getBody() == null) return owner;
        Object raw = owner.getBody().get("data");
        if (!(raw instanceof List<?> items)) return ownerFailure();
        // An unscoped picker may aggregate only scopes the server-attested session can read.
        // The Model owner supplies already-masked connection data; Control never decrypts it.
        var visible = items.stream().filter(item -> item instanceof Map<?, ?> model
                && canRead(session, model)).toList();
        var body = new LinkedHashMap<>(owner.getBody());
        body.put("data", visible);
        return ResponseEntity.status(owner.getStatusCode()).body(body);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(HttpServletRequest request, @PathVariable String id) {
        authorization.requirePermission(request, PlatformPermissions.PLATFORM_READ);
        if (!validId(id)) return invalid();
        var owner = models.get(id);
        if (!owner.getStatusCode().is2xxSuccessful() || owner.getBody() == null) return owner;
        if (!(owner.getBody().get("data") instanceof Map<?, ?> model)) return ownerFailure();
        requireScope(request, PlatformPermissions.PLATFORM_READ, modelProject(model));
        return owner;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(HttpServletRequest request,
                                                     @RequestBody Map<String, Object> body) {
        authorization.requirePermission(request, PlatformPermissions.PLATFORM_WRITE);
        if (body == null || !CREATE_FIELDS.containsAll(body.keySet())
                || body.get("projectCode") != null && !(body.get("projectCode") instanceof String)) return invalid();
        String project = projectCode((String) body.get("projectCode"));
        requireScope(request, PlatformPermissions.PLATFORM_WRITE, project);
        var command = new LinkedHashMap<>(body);
        command.put("projectCode", project);
        return models.create(command);
    }

    private boolean canRead(PlatformAuthenticatedSession session, Map<?, ?> model) {
        String project = modelProject(model);
        return project == null ? session.hasGlobalPermission(PlatformPermissions.PLATFORM_READ)
                : session.hasResourcePermission(PlatformPermissions.PLATFORM_READ, "PROJECT", null, project);
    }

    private String modelProject(Map<?, ?> model) {
        Object raw = model.get("projectCode");
        if (raw != null && !(raw instanceof String)) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Model owner returned an invalid project scope");
        return projectCode((String) raw);
    }

    private void requireScope(HttpServletRequest request, String permission, String project) {
        if (project == null) authorization.requireGlobalPermission(request, permission);
        else authorization.requireResourcePermission(request, permission, "PROJECT", null, project);
    }

    private String projectCode(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private boolean validId(String value) { return value != null && value.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}"); }
    private ResponseEntity<Map<String, Object>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("code", "MODEL_INSTANCE_REQUEST_INVALID",
                "message", "请使用当前模型实例与服务器授权的项目范围；不能提交自制身份"));
    }
    private ResponseEntity<Map<String, Object>> ownerFailure() {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("code", "MODEL_INSTANCE_OWNER_UNAVAILABLE",
                "message", "模型目录响应不完整，请稍后重试"));
    }
}
