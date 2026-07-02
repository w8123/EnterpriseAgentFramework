package com.enterprise.ai.control.slot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class SlotManagementController {

    private final SlotDeptMapper deptMapper;
    private final SlotUserMapper userMapper;
    private final SlotExtractLogMapper logMapper;
    private final FieldExtractorBindingMapper bindingMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping("/api/slot-extractors")
    public ResponseEntity<List<SlotExtractorInfo>> listExtractors() {
        return ResponseEntity.ok(List.of(
                new SlotExtractorInfo("DeptSlotExtractor", "部门字典提取器", 100,
                        Map.of("source", "slot_dict_dept", "strategy", "dictionary")),
                new SlotExtractorInfo("UserSlotExtractor", "人员字典提取器", 90,
                        Map.of("source", "slot_dict_user", "strategy", "dictionary")),
                new SlotExtractorInfo("RuleSlotExtractor", "基础规则提取器", 10,
                        Map.of("strategy", "contains"))));
    }

    @PostMapping("/api/slot-extractors/test")
    public ResponseEntity<SlotExtractorTestResponse> testExtractor(@RequestBody SlotExtractorTestRequest request) {
        String userText = request == null || request.userText() == null ? "" : request.userText();
        SlotTestField field = new SlotTestField(
                StringUtils.hasText(request == null ? null : request.fieldKey()) ? request.fieldKey().trim() : "slot",
                StringUtils.hasText(request == null ? null : request.fieldLabel()) ? request.fieldLabel().trim() : "槽位",
                StringUtils.hasText(request == null ? null : request.fieldType()) ? request.fieldType().trim() : "string");
        List<SlotExtractorTestResultRow> results = new ArrayList<>();
        results.add(deptExtractionResult(userText));
        results.add(userExtractionResult(userText));
        results.add(new SlotExtractorTestResultRow(
                "RuleSlotExtractor",
                "基础规则提取器",
                true,
                StringUtils.hasText(userText),
                StringUtils.hasText(userText) ? userText : null,
                StringUtils.hasText(userText) ? 0.5 : 0.0,
                StringUtils.hasText(userText) ? "原文非空" : "原文为空",
                0L,
                null));
        return ResponseEntity.ok(new SlotExtractorTestResponse(field, userText, results));
    }

    @GetMapping("/api/slot-extractors/metrics")
    public ResponseEntity<List<SlotExtractorMetric>> metrics(@RequestParam(defaultValue = "7") int days) {
        LocalDateTime from = LocalDateTime.now().minusDays(Math.max(days, 1));
        Map<String, List<SlotExtractLogEntity>> grouped = new LinkedHashMap<>();
        for (SlotExtractLogEntity log : logMapper.selectList(new LambdaQueryWrapper<SlotExtractLogEntity>()
                .ge(SlotExtractLogEntity::getCreateTime, from)
                .orderByDesc(SlotExtractLogEntity::getCreateTime))) {
            grouped.computeIfAbsent(log.getExtractorName(), ignored -> new ArrayList<>()).add(log);
        }
        return ResponseEntity.ok(grouped.entrySet().stream()
                .map(entry -> metric(entry.getKey(), entry.getValue()))
                .toList());
    }

    @GetMapping("/api/slot-dict/dept")
    public ResponseEntity<PageResult<SlotDeptRow>> pageDept(@RequestParam(defaultValue = "1") int current,
                                                            @RequestParam(defaultValue = "20") int size,
                                                            @RequestParam(required = false) String name) {
        int page = Math.max(current, 1);
        int pageSize = safePageSize(size);
        LambdaQueryWrapper<SlotDeptEntity> wrapper = new LambdaQueryWrapper<SlotDeptEntity>()
                .like(StringUtils.hasText(name), SlotDeptEntity::getName, trim(name));
        long total = valueOrZero(deptMapper.selectCount(wrapper));
        List<SlotDeptRow> records = deptMapper.selectList(wrapper
                        .orderByDesc(SlotDeptEntity::getId)
                        .last("limit " + ((page - 1) * pageSize) + ", " + pageSize))
                .stream()
                .map(this::toDeptRow)
                .toList();
        return ResponseEntity.ok(new PageResult<>(records, total, pageSize, page));
    }

    @GetMapping("/api/slot-dict/dept/all")
    public ResponseEntity<List<SlotDeptRow>> listAllDept() {
        return ResponseEntity.ok(deptMapper.selectList(new LambdaQueryWrapper<SlotDeptEntity>()
                        .eq(SlotDeptEntity::getEnabled, true)
                        .orderByAsc(SlotDeptEntity::getId))
                .stream()
                .map(this::toDeptRow)
                .toList());
    }

    @PostMapping("/api/slot-dict/dept")
    public ResponseEntity<SlotDeptRow> createDept(@RequestBody SlotDeptRow request) {
        SlotDeptEntity entity = new SlotDeptEntity();
        applyDept(entity, request);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        deptMapper.insert(entity);
        return ResponseEntity.ok(toDeptRow(entity));
    }

    @PutMapping("/api/slot-dict/dept/{id}")
    public ResponseEntity<SlotDeptRow> updateDept(@PathVariable Long id, @RequestBody SlotDeptRow request) {
        SlotDeptEntity entity = deptMapper.selectById(id);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        applyDept(entity, request);
        entity.setUpdatedAt(LocalDateTime.now());
        deptMapper.updateById(entity);
        return ResponseEntity.ok(toDeptRow(entity));
    }

    @DeleteMapping("/api/slot-dict/dept/{id}")
    public ResponseEntity<Void> deleteDept(@PathVariable Long id) {
        deptMapper.deleteById(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/slot-dict/dept/import")
    public ResponseEntity<ImportResult> importDept(@RequestParam("file") MultipartFile file) throws IOException {
        int ok = 0;
        int skip = 0;
        for (String line : fileLines(file)) {
            String name = firstColumn(line);
            if (!StringUtils.hasText(name)) {
                skip++;
                continue;
            }
            SlotDeptEntity entity = new SlotDeptEntity();
            entity.setName(name);
            entity.setEnabled(true);
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            deptMapper.insert(entity);
            ok++;
        }
        return ResponseEntity.ok(new ImportResult(ok, skip));
    }

    @GetMapping("/api/slot-dict/user")
    public ResponseEntity<PageResult<SlotUserRow>> pageUser(@RequestParam(defaultValue = "1") int current,
                                                            @RequestParam(defaultValue = "20") int size,
                                                            @RequestParam(required = false) String name,
                                                            @RequestParam(required = false) Long deptId) {
        int page = Math.max(current, 1);
        int pageSize = safePageSize(size);
        LambdaQueryWrapper<SlotUserEntity> wrapper = new LambdaQueryWrapper<SlotUserEntity>()
                .like(StringUtils.hasText(name), SlotUserEntity::getName, trim(name))
                .eq(deptId != null, SlotUserEntity::getDeptId, deptId);
        long total = valueOrZero(userMapper.selectCount(wrapper));
        List<SlotUserRow> records = userMapper.selectList(wrapper
                        .orderByDesc(SlotUserEntity::getId)
                        .last("limit " + ((page - 1) * pageSize) + ", " + pageSize))
                .stream()
                .map(this::toUserRow)
                .toList();
        return ResponseEntity.ok(new PageResult<>(records, total, pageSize, page));
    }

    @PostMapping("/api/slot-dict/user")
    public ResponseEntity<SlotUserRow> createUser(@RequestBody SlotUserRow request) {
        SlotUserEntity entity = new SlotUserEntity();
        applyUser(entity, request);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(entity);
        return ResponseEntity.ok(toUserRow(entity));
    }

    @PutMapping("/api/slot-dict/user/{id}")
    public ResponseEntity<SlotUserRow> updateUser(@PathVariable Long id, @RequestBody SlotUserRow request) {
        SlotUserEntity entity = userMapper.selectById(id);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        applyUser(entity, request);
        entity.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(entity);
        return ResponseEntity.ok(toUserRow(entity));
    }

    @DeleteMapping("/api/slot-dict/user/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        userMapper.deleteById(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/slot-dict/user/import")
    public ResponseEntity<ImportResult> importUser(@RequestParam("file") MultipartFile file) throws IOException {
        int ok = 0;
        int skip = 0;
        for (String line : fileLines(file)) {
            String name = firstColumn(line);
            if (!StringUtils.hasText(name)) {
                skip++;
                continue;
            }
            SlotUserEntity entity = new SlotUserEntity();
            entity.setName(name);
            entity.setEnabled(true);
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            userMapper.insert(entity);
            ok++;
        }
        return ResponseEntity.ok(new ImportResult(ok, skip));
    }

    @GetMapping("/api/slot-extract-logs")
    public ResponseEntity<PageResult<SlotExtractLogRow>> pageLogs(@RequestParam(defaultValue = "1") int current,
                                                                  @RequestParam(defaultValue = "20") int size,
                                                                  @RequestParam(required = false) String extractorName,
                                                                  @RequestParam(required = false) String skillName,
                                                                  @RequestParam(required = false) Boolean hit,
                                                                  @RequestParam(required = false) Integer days) {
        int page = Math.max(current, 1);
        int pageSize = safePageSize(size);
        LambdaQueryWrapper<SlotExtractLogEntity> wrapper = logQuery(extractorName, skillName, hit, days);
        long total = valueOrZero(logMapper.selectCount(wrapper));
        List<SlotExtractLogRow> records = logMapper.selectList(wrapper
                        .orderByDesc(SlotExtractLogEntity::getCreateTime)
                        .orderByDesc(SlotExtractLogEntity::getId)
                        .last("limit " + ((page - 1) * pageSize) + ", " + pageSize))
                .stream()
                .map(this::toLogRow)
                .toList();
        return ResponseEntity.ok(new PageResult<>(records, total, pageSize, page));
    }

    @GetMapping("/api/slot-bindings")
    public ResponseEntity<List<FieldExtractorBindingRow>> listBindings(@RequestParam(required = false) String skillName) {
        return ResponseEntity.ok(bindingMapper.selectList(new LambdaQueryWrapper<FieldExtractorBindingEntity>()
                        .eq(StringUtils.hasText(skillName), FieldExtractorBindingEntity::getSkillName, trim(skillName))
                        .orderByAsc(FieldExtractorBindingEntity::getSkillName)
                        .orderByAsc(FieldExtractorBindingEntity::getFieldKey))
                .stream()
                .map(this::toBindingRow)
                .toList());
    }

    @PostMapping("/api/slot-bindings")
    public ResponseEntity<OkResult> upsertBinding(@RequestBody FieldExtractorBindingUpsertRequest request) {
        FieldExtractorBindingEntity entity = bindingMapper.selectOne(new LambdaQueryWrapper<FieldExtractorBindingEntity>()
                .eq(FieldExtractorBindingEntity::getSkillName, requireText(request.skillName(), "skillName"))
                .eq(FieldExtractorBindingEntity::getFieldKey, requireText(request.fieldKey(), "fieldKey"))
                .last("limit 1"));
        if (entity == null) {
            entity = new FieldExtractorBindingEntity();
            entity.setSkillName(request.skillName().trim());
            entity.setFieldKey(request.fieldKey().trim());
            entity.setCreatedAt(LocalDateTime.now());
        }
        entity.setExtractorNamesJson(toJson(request.extractorNames() == null ? List.of() : request.extractorNames()));
        entity.setUpdatedAt(LocalDateTime.now());
        if (entity.getId() == null) {
            bindingMapper.insert(entity);
        } else {
            bindingMapper.updateById(entity);
        }
        return ResponseEntity.ok(new OkResult(true));
    }

    private SlotExtractorTestResultRow deptExtractionResult(String userText) {
        SlotDeptEntity matched = deptMapper.selectList(new LambdaQueryWrapper<SlotDeptEntity>()
                        .eq(SlotDeptEntity::getEnabled, true))
                .stream()
                .filter(dept -> textMatches(userText, dept.getName(), dept.getAliases(), dept.getPinyin()))
                .findFirst()
                .orElse(null);
        return new SlotExtractorTestResultRow(
                "DeptSlotExtractor",
                "部门字典提取器",
                true,
                matched != null,
                matched == null ? null : matched.getName(),
                matched == null ? 0.0 : 0.95,
                matched == null ? "未命中部门字典" : "命中部门字典: " + matched.getName(),
                0L,
                null);
    }

    private SlotExtractorTestResultRow userExtractionResult(String userText) {
        SlotUserEntity matched = userMapper.selectList(new LambdaQueryWrapper<SlotUserEntity>()
                        .eq(SlotUserEntity::getEnabled, true))
                .stream()
                .filter(user -> textMatches(userText, user.getName(), user.getAliases(), user.getPinyin(), user.getEmployeeNo()))
                .findFirst()
                .orElse(null);
        return new SlotExtractorTestResultRow(
                "UserSlotExtractor",
                "人员字典提取器",
                true,
                matched != null,
                matched == null ? null : matched.getName(),
                matched == null ? 0.0 : 0.95,
                matched == null ? "未命中人员字典" : "命中人员字典: " + matched.getName(),
                0L,
                null);
    }

    private boolean textMatches(String source, String... candidates) {
        String normalizedSource = source == null ? "" : source.toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (!StringUtils.hasText(candidate)) {
                continue;
            }
            for (String part : candidate.split("[,，;；|\\s]+")) {
                if (StringUtils.hasText(part) && normalizedSource.contains(part.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private SlotExtractorMetric metric(String extractorName, List<SlotExtractLogEntity> logs) {
        long total = logs.size();
        long hit = logs.stream().filter(log -> Boolean.TRUE.equals(log.getHit())).count();
        double avgConfidence = logs.stream()
                .filter(log -> log.getConfidence() != null)
                .mapToDouble(SlotExtractLogEntity::getConfidence)
                .average()
                .orElse(0.0);
        List<Long> latencies = logs.stream()
                .map(SlotExtractLogEntity::getLatencyMs)
                .filter(value -> value != null)
                .sorted()
                .toList();
        long p95 = latencies.isEmpty() ? 0L : latencies.get(Math.min(latencies.size() - 1,
                (int) Math.ceil(latencies.size() * 0.95) - 1));
        return new SlotExtractorMetric(
                extractorName,
                total,
                hit,
                total == 0 ? 0.0 : (double) hit / total,
                avgConfidence,
                p95);
    }

    private LambdaQueryWrapper<SlotExtractLogEntity> logQuery(String extractorName, String skillName, Boolean hit, Integer days) {
        LambdaQueryWrapper<SlotExtractLogEntity> wrapper = new LambdaQueryWrapper<SlotExtractLogEntity>()
                .eq(StringUtils.hasText(extractorName), SlotExtractLogEntity::getExtractorName, trim(extractorName))
                .eq(StringUtils.hasText(skillName), SlotExtractLogEntity::getSkillName, trim(skillName))
                .eq(hit != null, SlotExtractLogEntity::getHit, hit);
        if (days != null && days > 0) {
            wrapper.ge(SlotExtractLogEntity::getCreateTime, LocalDateTime.now().minusDays(days));
        }
        return wrapper;
    }

    private void applyDept(SlotDeptEntity entity, SlotDeptRow request) {
        entity.setParentId(request.parentId());
        entity.setName(requireText(request.name(), "name"));
        entity.setPinyin(trim(request.pinyin()));
        entity.setAliases(trim(request.aliases()));
        entity.setProjectScope(request.projectScope());
        entity.setEnabled(request.enabled() == null || request.enabled());
    }

    private void applyUser(SlotUserEntity entity, SlotUserRow request) {
        entity.setDeptId(request.deptId());
        entity.setName(requireText(request.name(), "name"));
        entity.setPinyin(trim(request.pinyin()));
        entity.setEmployeeNo(trim(request.employeeNo()));
        entity.setAliases(trim(request.aliases()));
        entity.setEnabled(request.enabled() == null || request.enabled());
    }

    private SlotDeptRow toDeptRow(SlotDeptEntity entity) {
        return new SlotDeptRow(
                entity.getId(),
                entity.getParentId(),
                entity.getName(),
                entity.getPinyin(),
                entity.getAliases(),
                entity.getProjectScope(),
                entity.getEnabled(),
                instantText(entity.getCreatedAt()),
                instantText(entity.getUpdatedAt()));
    }

    private SlotUserRow toUserRow(SlotUserEntity entity) {
        return new SlotUserRow(
                entity.getId(),
                entity.getDeptId(),
                entity.getName(),
                entity.getPinyin(),
                entity.getEmployeeNo(),
                entity.getAliases(),
                entity.getEnabled(),
                instantText(entity.getCreatedAt()),
                instantText(entity.getUpdatedAt()));
    }

    private SlotExtractLogRow toLogRow(SlotExtractLogEntity entity) {
        return new SlotExtractLogRow(
                entity.getId(),
                entity.getTraceId(),
                entity.getSkillName(),
                entity.getFieldKey(),
                entity.getExtractorName(),
                Boolean.TRUE.equals(entity.getHit()),
                entity.getValue(),
                entity.getConfidence(),
                entity.getEvidence(),
                entity.getUserText(),
                entity.getLatencyMs(),
                instantText(entity.getCreateTime()));
    }

    private FieldExtractorBindingRow toBindingRow(FieldExtractorBindingEntity entity) {
        return new FieldExtractorBindingRow(
                entity.getId(),
                entity.getSkillName(),
                entity.getFieldKey(),
                entity.getExtractorNamesJson());
    }

    private List<String> fileLines(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            return List.of();
        }
        return new String(file.getBytes(), StandardCharsets.UTF_8).lines()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .filter(line -> !line.startsWith("#"))
                .toList();
    }

    private String firstColumn(String line) {
        if (line == null) {
            return null;
        }
        String[] parts = line.split("[,，\\t]", 2);
        return parts.length == 0 ? null : parts[0].trim();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("slot payload json is invalid", ex);
        }
    }

    private String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : value;
    }

    private int safePageSize(int size) {
        return Math.min(Math.max(size, 1), 200);
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private String instantText(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant().toString();
    }

    public record PageResult<T>(List<T> records, long total, int size, int current) {
    }

    public record SlotExtractorInfo(String name, String displayName, int priority, Map<String, Object> metadata) {
    }

    public record SlotExtractorTestRequest(
            String userText,
            String fieldKey,
            String fieldLabel,
            String fieldType,
            String llmExtractHint,
            String userId,
            String userDeptId
    ) {
    }

    public record SlotTestField(String key, String label, String type) {
    }

    public record SlotExtractorTestResultRow(
            String extractorName,
            String displayName,
            boolean accepts,
            boolean hit,
            Object value,
            Double confidence,
            String evidence,
            Long latencyMs,
            String error
    ) {
    }

    public record SlotExtractorTestResponse(
            SlotTestField field,
            String userText,
            List<SlotExtractorTestResultRow> results
    ) {
    }

    public record SlotExtractorMetric(
            String extractorName,
            long total,
            long hit,
            double hitRate,
            double avgConfidence,
            long p95LatencyMs
    ) {
    }

    public record SlotDeptRow(
            Long id,
            Long parentId,
            String name,
            String pinyin,
            String aliases,
            Long projectScope,
            Boolean enabled,
            String createdAt,
            String updatedAt
    ) {
    }

    public record SlotUserRow(
            Long id,
            Long deptId,
            String name,
            String pinyin,
            String employeeNo,
            String aliases,
            Boolean enabled,
            String createdAt,
            String updatedAt
    ) {
    }

    public record SlotExtractLogRow(
            Long id,
            String traceId,
            String skillName,
            String fieldKey,
            String extractorName,
            boolean hit,
            String value,
            Double confidence,
            String evidence,
            String userText,
            Long latencyMs,
            String createTime
    ) {
    }

    public record FieldExtractorBindingRow(
            Long id,
            String skillName,
            String fieldKey,
            String extractorNamesJson
    ) {
    }

    public record FieldExtractorBindingUpsertRequest(
            String skillName,
            String fieldKey,
            List<String> extractorNames
    ) {
    }

    public record OkResult(boolean ok) {
    }

    public record ImportResult(int ok, int skip) {
    }
}
