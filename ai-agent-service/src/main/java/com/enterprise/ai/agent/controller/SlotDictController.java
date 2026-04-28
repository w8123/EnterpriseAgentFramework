package com.enterprise.ai.agent.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ai.agent.skill.slot.dict.SlotDeptEntity;
import com.enterprise.ai.agent.skill.slot.dict.SlotDeptMapper;
import com.enterprise.ai.agent.skill.slot.dict.SlotUserEntity;
import com.enterprise.ai.agent.skill.slot.dict.SlotUserMapper;
import lombok.RequiredArgsConstructor;
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
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 部门 / 人员字典 CRUD + CSV 批量导入。
 */
@RestController
@RequestMapping("/api/slot-dict")
@RequiredArgsConstructor
public class SlotDictController {

    private final SlotDeptMapper deptMapper;
    private final SlotUserMapper userMapper;

    // ===== 部门 ============================================================
    @GetMapping("/dept")
    public ResponseEntity<Page<SlotDeptEntity>> deptPage(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String name) {
        LambdaQueryWrapper<SlotDeptEntity> w = new LambdaQueryWrapper<>();
        if (name != null && !name.isBlank()) {
            w.like(SlotDeptEntity::getName, name);
        }
        w.orderByAsc(SlotDeptEntity::getId);
        return ResponseEntity.ok(deptMapper.selectPage(new Page<>(current, size), w));
    }

    @GetMapping("/dept/all")
    public ResponseEntity<List<SlotDeptEntity>> deptAll() {
        LambdaQueryWrapper<SlotDeptEntity> w = new LambdaQueryWrapper<>();
        w.eq(SlotDeptEntity::getEnabled, true).last("LIMIT 5000");
        return ResponseEntity.ok(deptMapper.selectList(w));
    }

    @PostMapping("/dept")
    public ResponseEntity<SlotDeptEntity> deptCreate(@RequestBody SlotDeptEntity body) {
        if (body.getEnabled() == null) body.setEnabled(true);
        deptMapper.insert(body);
        return ResponseEntity.ok(body);
    }

    @PutMapping("/dept/{id}")
    public ResponseEntity<SlotDeptEntity> deptUpdate(@PathVariable Long id, @RequestBody SlotDeptEntity body) {
        body.setId(id);
        deptMapper.updateById(body);
        return ResponseEntity.ok(deptMapper.selectById(id));
    }

    @DeleteMapping("/dept/{id}")
    public ResponseEntity<Map<String, Object>> deptDelete(@PathVariable Long id) {
        deptMapper.deleteById(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * 部门 CSV 导入：表头 {@code parent_id,name,pinyin,aliases,project_scope}。
     * 一行一条；空行 / 缺字段忽略。
     */
    @PostMapping("/dept/import")
    public ResponseEntity<Map<String, Object>> deptImport(@RequestParam("file") MultipartFile file) throws Exception {
        int ok = 0;
        int skip = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                if (header) { header = false; continue; }
                String[] parts = line.split(",", -1);
                if (parts.length < 2 || parts[1].trim().isBlank()) { skip++; continue; }
                SlotDeptEntity row = new SlotDeptEntity();
                row.setParentId(parseLong(parts[0]));
                row.setName(parts[1].trim());
                row.setPinyin(parts.length > 2 ? trimOrNull(parts[2]) : null);
                row.setAliases(parts.length > 3 ? trimOrNull(parts[3]) : null);
                row.setProjectScope(parts.length > 4 ? parseLong(parts[4]) : null);
                row.setEnabled(true);
                deptMapper.insert(row);
                ok++;
            }
        }
        Map<String, Object> resp = new HashMap<>();
        resp.put("ok", ok);
        resp.put("skip", skip);
        return ResponseEntity.ok(resp);
    }

    // ===== 人员 ============================================================
    @GetMapping("/user")
    public ResponseEntity<Page<SlotUserEntity>> userPage(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Long deptId) {
        LambdaQueryWrapper<SlotUserEntity> w = new LambdaQueryWrapper<>();
        if (name != null && !name.isBlank()) {
            w.like(SlotUserEntity::getName, name);
        }
        if (deptId != null) {
            w.eq(SlotUserEntity::getDeptId, deptId);
        }
        w.orderByAsc(SlotUserEntity::getId);
        return ResponseEntity.ok(userMapper.selectPage(new Page<>(current, size), w));
    }

    @PostMapping("/user")
    public ResponseEntity<SlotUserEntity> userCreate(@RequestBody SlotUserEntity body) {
        if (body.getEnabled() == null) body.setEnabled(true);
        userMapper.insert(body);
        return ResponseEntity.ok(body);
    }

    @PutMapping("/user/{id}")
    public ResponseEntity<SlotUserEntity> userUpdate(@PathVariable Long id, @RequestBody SlotUserEntity body) {
        body.setId(id);
        userMapper.updateById(body);
        return ResponseEntity.ok(userMapper.selectById(id));
    }

    @DeleteMapping("/user/{id}")
    public ResponseEntity<Map<String, Object>> userDelete(@PathVariable Long id) {
        userMapper.deleteById(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/user/import")
    public ResponseEntity<Map<String, Object>> userImport(@RequestParam("file") MultipartFile file) throws Exception {
        int ok = 0;
        int skip = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                if (header) { header = false; continue; }
                String[] parts = line.split(",", -1);
                if (parts.length < 2 || parts[1].trim().isBlank()) { skip++; continue; }
                SlotUserEntity row = new SlotUserEntity();
                row.setDeptId(parseLong(parts[0]));
                row.setName(parts[1].trim());
                row.setPinyin(parts.length > 2 ? trimOrNull(parts[2]) : null);
                row.setEmployeeNo(parts.length > 3 ? trimOrNull(parts[3]) : null);
                row.setAliases(parts.length > 4 ? trimOrNull(parts[4]) : null);
                row.setEnabled(true);
                userMapper.insert(row);
                ok++;
            }
        }
        Map<String, Object> resp = new HashMap<>();
        resp.put("ok", ok);
        resp.put("skip", skip);
        return ResponseEntity.ok(resp);
    }

    private static Long parseLong(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        try { return Long.parseLong(t); } catch (NumberFormatException e) { return null; }
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
