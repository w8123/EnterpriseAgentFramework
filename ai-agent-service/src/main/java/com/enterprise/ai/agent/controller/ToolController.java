package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.tools.ToolRegistry;
import com.enterprise.ai.skill.AiTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Tool 管理 API — 暴露 ToolRegistry 中已注册工具的元信息与测试能力
 */
@Slf4j
@RestController
@RequestMapping("/api/tools")
@RequiredArgsConstructor
public class ToolController {

    private final ToolRegistry toolRegistry;

    @GetMapping
    public ResponseEntity<List<ToolInfoDTO>> list() {
        List<ToolInfoDTO> tools = toolRegistry.getAllTools().stream()
                .map(ToolInfoDTO::from)
                .toList();
        return ResponseEntity.ok(tools);
    }

    @GetMapping("/{name}")
    public ResponseEntity<ToolInfoDTO> get(@PathVariable String name) {
        if (!toolRegistry.contains(name)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ToolInfoDTO.from(toolRegistry.get(name)));
    }

    @PostMapping("/{name}/test")
    public ResponseEntity<ToolTestResultDTO> test(@PathVariable String name,
                                                  @RequestBody ToolTestRequest request) {
        if (!toolRegistry.contains(name)) {
            return ResponseEntity.notFound().build();
        }

        long start = System.currentTimeMillis();
        try {
            Object result = toolRegistry.execute(name, request.args() != null ? request.args() : Map.of());
            long duration = System.currentTimeMillis() - start;
            log.info("[ToolController] 测试工具 {} 成功, 耗时 {}ms", name, duration);
            return ResponseEntity.ok(new ToolTestResultDTO(true, String.valueOf(result), null, duration));
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.warn("[ToolController] 测试工具 {} 失败: {}", name, e.getMessage());
            return ResponseEntity.ok(new ToolTestResultDTO(false, null, e.getMessage(), duration));
        }
    }

    record ToolInfoDTO(String name, String description, List<ToolParameterDTO> parameters) {
        static ToolInfoDTO from(AiTool tool) {
            List<ToolParameterDTO> params = tool.parameters().stream()
                    .map(p -> new ToolParameterDTO(p.name(), p.type(), p.description(), p.required()))
                    .toList();
            return new ToolInfoDTO(tool.name(), tool.description(), params);
        }
    }

    record ToolParameterDTO(String name, String type, String description, boolean required) {}

    record ToolTestRequest(Map<String, Object> args) {}

    record ToolTestResultDTO(boolean success, String result, String errorMessage, long durationMs) {}
}
