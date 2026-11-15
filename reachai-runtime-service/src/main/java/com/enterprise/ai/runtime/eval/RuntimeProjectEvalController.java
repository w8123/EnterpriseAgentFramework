package com.enterprise.ai.runtime.eval;

import com.enterprise.ai.runtime.eval.RuntimeProjectEvalService.ProjectSummary;
import com.enterprise.ai.runtime.eval.RuntimeProjectEvalService.SettingRequest;
import com.enterprise.ai.runtime.eval.RuntimeProjectEvalService.SettingView;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Per-system automatic evaluation; Control checks the console user's project permission before proxying. */
@RestController
@RequiredArgsConstructor
public class RuntimeProjectEvalController {

    private final RuntimeProjectEvalService service;
    private final Environment environment;

    @GetMapping("/api/runtime/project-evals")
    public Map<String, Object> summary(@RequestParam(required = false) String projectCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workerEnabled", environment.getProperty(RuntimeEvalTaskWorker.ENABLED_PROPERTY, Boolean.class, false));
        body.put("projects", service.summary(projectCode));
        return body;
    }

    @GetMapping("/api/runtime/project-evals/{projectCode}/setting")
    public SettingView setting(@PathVariable String projectCode) {
        return service.setting(projectCode);
    }

    @PutMapping("/api/runtime/project-evals/{projectCode}/setting")
    public SettingView save(@PathVariable String projectCode, @RequestBody Map<String, Object> body) {
        return service.save(projectCode, new SettingRequest(
                text(body.get("agentId")), text(body.get("evalUserId")), text(body.get("tenantId")),
                bool(body.get("enabled")), integer(body.get("runHour")), integer(body.get("questionCount")),
                bool(body.get("autoEnableReadonlyFlows"))), text(body.get("updatedBy")));
    }

    @PostMapping("/api/runtime/project-evals/{projectCode}/questions")
    public SettingView generate(@PathVariable String projectCode, @RequestBody(required = false) Map<String, Object> body) {
        return service.generate(projectCode, body == null ? null : text(body.get("actor")));
    }

    @PostMapping("/api/runtime/project-evals/{projectCode}/runs")
    public SettingView run(@PathVariable String projectCode, @RequestBody(required = false) Map<String, Object> body) {
        return service.run(projectCode, body == null ? null : text(body.get("actor")), false);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException invalid) {
        return ResponseEntity.badRequest().body(Map.of(
                "code", "PROJECT_EVAL_INVALID",
                "message", invalid.getMessage() == null ? "请求无效" : invalid.getMessage()));
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Boolean bool(Object value) {
        return value instanceof Boolean flag ? flag : value == null ? null : Boolean.valueOf(String.valueOf(value));
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null || String.valueOf(value).isBlank()) return null;
        return Integer.valueOf(String.valueOf(value).trim());
    }
}
