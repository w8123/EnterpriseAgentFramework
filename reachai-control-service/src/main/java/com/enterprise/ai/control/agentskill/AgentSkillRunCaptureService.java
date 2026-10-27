package com.enterprise.ai.control.agentskill;

import com.enterprise.ai.control.agentskill.AgentSkillContracts.ImportCommand;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.ImportResult;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.SkillSummary;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.mcp.application.calllog.McpCallLogReader;
import com.enterprise.ai.control.mcp.application.port.McpEmployeeAccessRepository;
import com.enterprise.ai.control.mcp.application.port.McpPublicationRepository;
import com.enterprise.ai.control.mcp.application.schema.McpDottedArgumentShape;
import com.enterprise.ai.control.mcp.domain.employee.McpEmployeeAccess;
import com.enterprise.ai.control.mcp.domain.publication.McpPublication;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationRevision;
import com.enterprise.ai.control.mcp.domain.publication.McpToolProjection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Turns real run records into a standard Agent Skill draft (SKILL.md) that employees can load into
 * their own Claude Code / Codex. Two sources: tool calls an employee's AI tool made over MCP, and one
 * RunOps trace. The draft names the tools exactly as the employee's AI tool sees them and lists only
 * argument names and types; recorded argument values never enter a distributable package.
 */
@Service
public class AgentSkillRunCaptureService {

    public static final String SOURCE_MCP_CALLS = "MCP_CALLS";
    public static final String SOURCE_RUN = "RUN";
    public static final String SOURCE_TYPE = "RUN_CAPTURE";
    static final int MAX_CALLS = 30;
    static final String APPROVAL_STATUS_TOOL = "reachai_approval_status";

    private static final Pattern PUBLISHER = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");
    private static final Pattern SEMVER = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)$");
    private static final Pattern FRONTMATTER_NAME = Pattern.compile("(?m)^name:\\s*\"?([^\"\\r\\n]+?)\"?\\s*$");

    private final McpCallLogReader callLogReader;
    private final McpEmployeeAccessRepository accessRepository;
    private final McpPublicationRepository publicationRepository;
    private final RuntimeProxyClient runtimeClient;
    private final AgentSkillCatalogService catalogService;
    private final ObjectMapper objectMapper;

    public AgentSkillRunCaptureService(McpCallLogReader callLogReader,
                                       McpEmployeeAccessRepository accessRepository,
                                       McpPublicationRepository publicationRepository,
                                       RuntimeProxyClient runtimeClient,
                                       AgentSkillCatalogService catalogService,
                                       ObjectMapper objectMapper) {
        this.callLogReader = callLogReader;
        this.accessRepository = accessRepository;
        this.publicationRepository = publicationRepository;
        this.runtimeClient = runtimeClient;
        this.catalogService = catalogService;
        this.objectMapper = objectMapper;
    }

    public Draft preview(PreviewRequest request) {
        Collected collected = collect(request);
        List<String> warnings = new ArrayList<>(collected.warnings());
        Optional<McpEmployeeAccess> access = accessRepository.findByProjectCode(collected.projectCode());
        String systemName = access.map(McpEmployeeAccess::label).orElse(collected.projectCode());
        List<McpToolProjection> tools = access.flatMap(this::liveTools).orElse(List.of());
        if (access.isEmpty() || !access.get().enabled()) {
            warnings.add("「" + systemName + "」还没有开通员工 AI 工具使用，员工暂时调不到 Skill 里的工具；"
                    + "请先在「MCP 开放 → MCP 服务 → 接入与使用 → 员工使用」开通。");
        } else if (tools.isEmpty()) {
            warnings.add("「" + systemName + "」的员工发布当前没有上线版本，工具说明无法补全。");
        }
        boolean approvalOn = access.map(McpEmployeeAccess::writeApprovalRequired).orElse(true);

        List<Step> steps = new ArrayList<>();
        for (RecordedCall call : collected.calls()) {
            if (!steps.isEmpty() && steps.get(steps.size() - 1).toolName().equals(resolvedName(call, tools))) {
                steps.set(steps.size() - 1, steps.get(steps.size() - 1).withMoreFields(call.argumentNames()));
                continue;
            }
            steps.add(step(steps.size() + 1, call, tools, approvalOn, warnings));
        }
        if (steps.isEmpty()) {
            throw AgentSkillException.invalidPackage("所选记录里没有成功的工具调用，无法生成 Skill");
        }
        Step main = mainStep(steps);
        String goal = trim(request.goal(), 80);
        // A tool without a description (e.g. one employees cannot call) never becomes the title verbatim.
        String displayName = goal != null ? goal : "在" + systemName + " 中" + shortText(main.description(), "办理业务");
        String name = standardName(collected.projectCode(), main.toolName());
        String description = trim(displayName + "。员工让 AI 工具在" + systemName + "里" + shortText(main.description(), "办理这件事")
                + "时使用；通过 ReachAI 以员工本人身份调用" + (steps.stream().anyMatch(Step::requiresApproval)
                ? "，写操作提交后需审批。" : "。"), 1000);
        String skillMd = skillMarkdown(name, displayName, description, collected, systemName, steps);
        Long projectId = collected.projectId() != null ? collected.projectId()
                : access.map(McpEmployeeAccess::projectId).orElse(null);
        return new Draft(collected.projectCode(), projectId, systemName, name, displayName, description, skillMd,
                steps, List.copyOf(new LinkedHashSet<>(warnings)), workflowInstruction(systemName, steps, collected),
                collected.sourceRef(), nextVersion(collected.projectCode(), name));
    }

    /** Packs the (possibly edited) SKILL.md and enters it into the normal review flow as REVIEW_PENDING. */
    public ImportResult create(CreateRequest request, String operator, Long operatorUserId) {
        String projectCode = trim(request == null ? null : request.projectCode(), 96);
        if (projectCode == null) {
            throw AgentSkillException.invalidPackage("projectCode is required");
        }
        String skillMd = request.skillMd() == null ? "" : request.skillMd().replace("\r\n", "\n");
        Matcher declared = FRONTMATTER_NAME.matcher(frontmatter(skillMd));
        if (!declared.find()) {
            throw AgentSkillException.invalidPackage("SKILL.md 开头需要 name 字段");
        }
        String name = declared.group(1).trim();
        if (!AgentSkillPackageInspector.STANDARD_NAME.matcher(name).matches() || name.length() > 64) {
            throw AgentSkillException.invalidPackage("Skill 名称只能用小写字母、数字和单个连字符，最长 64 位");
        }
        String visibility = StringUtils.hasText(request.visibility())
                ? request.visibility().trim().toUpperCase(Locale.ROOT) : "PROJECT";
        String version = StringUtils.hasText(request.version()) ? request.version().trim() : nextVersion(projectCode, name);
        return catalogService.importPackage(zip(name, skillMd), new ImportCommand(
                publisher(projectCode),
                version,
                trim(request.displayName(), 128),
                visibility,
                SOURCE_TYPE,
                trim(request.sourceRef(), 512),
                operator,
                operatorUserId,
                "PROJECT".equals(visibility) ? projectCode : null));
    }

    // ---- collecting run records -------------------------------------------------------------

    private Collected collect(PreviewRequest request) {
        String source = request == null || request.source() == null ? "" : request.source().trim().toUpperCase(Locale.ROOT);
        return switch (source) {
            case SOURCE_MCP_CALLS -> collectCalls(request.callLogIds());
            case SOURCE_RUN -> collectRun(request.traceId());
            default -> throw AgentSkillException.invalidPackage("source must be MCP_CALLS or RUN");
        };
    }

    private Collected collectCalls(List<Long> ids) {
        List<Long> distinct = ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            throw AgentSkillException.invalidPackage("请至少选择一条调用记录");
        }
        if (distinct.size() > MAX_CALLS) {
            throw AgentSkillException.invalidPackage("一次最多选择 " + MAX_CALLS + " 条调用记录");
        }
        List<McpCallLogReader.Entry> entries = new ArrayList<>();
        for (Long id : distinct) {
            McpCallLogReader.Entry entry = callLogReader.detail(id);
            if (entry == null) {
                throw AgentSkillException.notFound("调用记录不存在：" + id);
            }
            entries.add(entry);
        }
        entries.sort((left, right) -> {
            int byTime = Objects.compare(left.createdAt(), right.createdAt(),
                    java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()));
            return byTime != 0 ? byTime : Long.compare(left.id(), right.id());
        });
        Set<String> projects = new LinkedHashSet<>();
        Long projectId = null;
        List<RecordedCall> calls = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int skipped = 0;
        for (McpCallLogReader.Entry entry : entries) {
            if (!"tools/call".equals(entry.method()) || !StringUtils.hasText(entry.toolName())) {
                continue;
            }
            if (!Boolean.TRUE.equals(entry.success())) {
                skipped++;
                continue;
            }
            if (StringUtils.hasText(entry.projectCode())) {
                projects.add(entry.projectCode().trim());
            }
            if (projectId == null) {
                projectId = entry.projectId();
            }
            calls.add(new RecordedCall(entry.toolName().trim(), argumentNames(callArguments(entry.requestBody()))));
        }
        if (projects.size() > 1) {
            throw AgentSkillException.invalidPackage("所选记录来自多个业务系统，请只选同一个系统的调用");
        }
        if (projects.isEmpty()) {
            throw AgentSkillException.invalidPackage("所选记录里没有成功的业务工具调用");
        }
        if (skipped > 0) {
            warnings.add("已跳过 " + skipped + " 条失败的调用。");
        }
        String ref = "mcp-calls:" + String.join(",", distinct.stream().map(String::valueOf).toList());
        return new Collected(projects.iterator().next(), projectId, calls, warnings, trim(ref, 512), null);
    }

    private Collected collectRun(String traceId) {
        String id = trim(traceId, 64);
        if (id == null) {
            throw AgentSkillException.invalidPackage("traceId is required");
        }
        ResponseEntity<Map<String, Object>> response = runtimeClient.runOpsDetail(id);
        Map<String, Object> detail = response == null ? null : response.getBody();
        if (response == null || !response.getStatusCode().is2xxSuccessful() || detail == null) {
            throw AgentSkillException.notFound("运行记录不存在：" + id);
        }
        Map<String, Object> summary = map(detail.get("summary"));
        String projectCode = text(summary.get("projectCode"));
        if (!StringUtils.hasText(projectCode)) {
            throw AgentSkillException.invalidPackage("这次运行没有所属业务系统，无法生成 Skill");
        }
        List<RecordedCall> calls = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int skipped = 0;
        for (Object raw : list(detail.get("toolCalls"))) {
            Map<String, Object> call = map(raw);
            String toolName = text(call.get("toolName"));
            if (!StringUtils.hasText(toolName)) {
                continue;
            }
            if (!Boolean.TRUE.equals(call.get("success"))) {
                skipped++;
                continue;
            }
            calls.add(new RecordedCall(toolName.trim(), argumentNames(parse(text(call.get("argsJson"))))));
        }
        if (skipped > 0) {
            warnings.add("已跳过 " + skipped + " 条失败的调用。");
        }
        return new Collected(projectCode.trim(), longValue(summary.get("projectId")), calls, warnings, "trace:" + id, trim(text(summary.get("inputSummary")), 200));
    }

    // ---- building the draft -----------------------------------------------------------------

    private Optional<List<McpToolProjection>> liveTools(McpEmployeeAccess access) {
        Optional<McpPublication> publication = publicationRepository.findById(access.publicationId())
                .filter(found -> found.currentRevisionId() != null);
        return publication.flatMap(found -> publicationRepository.findRevision(found.id(), found.currentRevisionId()))
                .map(McpPublicationRevision::tools)
                .map(tools -> tools.stream()
                        .filter(tool -> !"IRREVERSIBLE".equalsIgnoreCase(String.valueOf(tool.riskLevel())))
                        .toList());
    }

    private String resolvedName(RecordedCall call, List<McpToolProjection> tools) {
        return match(call.toolName(), tools).map(McpToolProjection::name).orElse(call.toolName());
    }

    /** Employee calls carry the exposed tool name; RunOps calls may carry the capability source ref. */
    static Optional<McpToolProjection> match(String toolName, List<McpToolProjection> tools) {
        for (McpToolProjection tool : tools) {
            if (tool.name().equals(toolName) || tool.sourceRef().equals(toolName)) {
                return Optional.of(tool);
            }
        }
        for (McpToolProjection tool : tools) {
            String ref = tool.sourceRef();
            String method = ref.contains(":") ? ref.substring(ref.lastIndexOf(':') + 1) : ref;
            if (method.equals(toolName) || toolName.endsWith(":" + method) || tool.name().endsWith("_" + toolName)) {
                return Optional.of(tool);
            }
        }
        return Optional.empty();
    }

    private Step step(int order, RecordedCall call, List<McpToolProjection> tools, boolean approvalOn,
                      List<String> warnings) {
        if (APPROVAL_STATUS_TOOL.equals(call.toolName())) {
            return new Step(order, APPROVAL_STATUS_TOOL, "查询写操作审批结果", "READ", false,
                    List.of(new Field("approvalId", "integer", false, "审批单号；不填则列出最近 10 张", false)), true);
        }
        Optional<McpToolProjection> tool = match(call.toolName(), tools);
        if (tool.isEmpty()) {
            warnings.add("工具 " + call.toolName() + " 不在员工可用的工具里，员工的 AI 工具调不到它，请确认后再发布。");
            return new Step(order, call.toolName(), null, null, false,
                    call.argumentNames().stream().map(name -> new Field(name, null, false, null, true)).toList(), false);
        }
        String risk = tool.get().riskLevel() == null || tool.get().riskLevel().isBlank()
                ? "WRITE" : tool.get().riskLevel().trim().toUpperCase(Locale.ROOT);
        return new Step(order, tool.get().name(), tool.get().description(), risk,
                approvalOn && !"READ".equals(risk), fields(tool.get().inputSchemaJson(), call.argumentNames()), true);
    }

    private List<Field> fields(String inputSchemaJson, Set<String> used) {
        JsonNode schema = McpDottedArgumentShape.nest(parse(inputSchemaJson));
        List<Field> fields = new ArrayList<>();
        if (schema != null && schema.isObject()) {
            flatten(schema, "", fields, used);
        }
        Set<String> known = new LinkedHashSet<>();
        fields.forEach(field -> known.add(field.name()));
        for (String name : used) {
            if (!known.contains(name) && known.stream().noneMatch(field -> field.startsWith(name + "."))) {
                fields.add(new Field(name, null, false, null, true));
            }
        }
        return fields.stream().filter(field -> field.required() || field.usedInRun()).toList();
    }

    private static void flatten(JsonNode schema, String prefix, List<Field> fields, Set<String> used) {
        JsonNode properties = schema.path("properties");
        if (!properties.isObject()) {
            return;
        }
        Set<String> required = new LinkedHashSet<>();
        schema.path("required").forEach(item -> required.add(item.asText()));
        Iterator<Map.Entry<String, JsonNode>> iterator = properties.fields();
        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> entry = iterator.next();
            String path = prefix + entry.getKey();
            JsonNode property = entry.getValue();
            if (property.path("properties").isObject() && property.path("properties").size() > 0) {
                flatten(property, path + ".", fields, used);
                continue;
            }
            String description = property.path("description").asText(null);
            fields.add(new Field(path, property.path("type").asText(null), required.contains(entry.getKey()),
                    description == null || description.isBlank() ? null : description.trim(),
                    used.contains(path)));
        }
    }

    private static Step mainStep(List<Step> steps) {
        for (int i = steps.size() - 1; i >= 0; i--) {
            Step step = steps.get(i);
            if (step.available() && !APPROVAL_STATUS_TOOL.equals(step.toolName()) && !"READ".equals(step.riskLevel())) {
                return step;
            }
        }
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (steps.get(i).available() && !APPROVAL_STATUS_TOOL.equals(steps.get(i).toolName())) {
                return steps.get(i);
            }
        }
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (!APPROVAL_STATUS_TOOL.equals(steps.get(i).toolName())) {
                return steps.get(i);
            }
        }
        return steps.get(steps.size() - 1);
    }

    private String skillMarkdown(String name, String displayName, String description, Collected collected,
                                 String systemName, List<Step> steps) {
        StringBuilder md = new StringBuilder();
        md.append("---\n")
                .append("name: ").append(name).append('\n')
                .append("description: ").append(yamlString(description)).append('\n')
                .append("metadata:\n")
                .append("  source: ").append(yamlString("reachai-run-capture")).append('\n')
                .append("  business-system: ").append(yamlString(collected.projectCode())).append('\n')
                .append("---\n\n")
                .append("# ").append(displayName).append("\n\n")
                .append("这份做法整理自员工在").append(systemName).append("的真实操作记录（")
                .append(LocalDate.now()).append("，由 ReachAI 生成）。\n\n");
        if (collected.exampleRequest() != null) {
            md.append("用户当时的说法：").append(collected.exampleRequest()).append("\n\n");
        }
        md.append("## 使用前提\n\n")
                .append("- 已在").append(systemName).append("的「在我的 AI 工具中使用」里生成连接，并把它加到了当前 AI 工具。\n")
                .append("- 所有调用都以员工本人的身份执行，权限和员工在系统里一样；没有权限时如实告诉用户。\n\n")
                .append("## 步骤\n\n");
        for (Step step : steps) {
            md.append(step.order()).append(". ");
            md.append("**").append(shortText(step.description(), "调用工具")).append("**：调用 `").append(step.toolName()).append('`');
            if (step.requiresApproval()) {
                md.append("。这是写操作，调用后会先提交审批，返回审批单号");
            }
            md.append("。\n");
            for (Field field : step.fields()) {
                md.append("   - `").append(field.name()).append('`');
                List<String> notes = new ArrayList<>();
                if (field.type() != null) notes.add(typeLabel(field.type()));
                if (field.required()) notes.add("必填");
                if (field.description() != null) notes.add(field.description());
                if (!notes.isEmpty()) md.append("：").append(String.join("，", notes));
                md.append('\n');
            }
            if (!step.available()) {
                md.append("   - 注意：这个工具目前不在员工可用的工具里。\n");
            }
        }
        md.append("\n## 注意\n\n")
                .append("- 参数缺失或含糊时先问用户，不要编造；只填用户明确给出的值。\n");
        if (steps.stream().anyMatch(Step::requiresApproval)) {
            md.append("- 写操作提交后告诉用户审批单号，用 `").append(APPROVAL_STATUS_TOOL)
                    .append("` 查询结果；审批通过前不要重复提交同一操作。\n");
        }
        md.append("- 工具调用失败时，把错误原因原样告诉用户，不要自行换别的工具绕过。\n");
        return md.toString();
    }

    private static String workflowInstruction(String systemName, List<Step> steps, Collected collected) {
        StringBuilder text = new StringBuilder("按员工在").append(systemName).append("的真实操作生成 Workflow：");
        int index = 1;
        for (Step step : steps) {
            if (APPROVAL_STATUS_TOOL.equals(step.toolName())) {
                continue;
            }
            text.append(index++).append(". 调用业务能力「").append(shortText(step.description(), step.toolName()))
                    .append("」（").append(step.toolName()).append("）");
            List<String> names = step.fields().stream().map(Field::name).toList();
            if (!names.isEmpty()) {
                text.append("，参数 ").append(String.join("、", names));
            }
            text.append("；");
        }
        text.append("参数从用户输入里取，最后把结果整理成一句话回复用户。");
        if (collected.exampleRequest() != null) {
            text.append("示例输入：").append(collected.exampleRequest());
        }
        return text.toString();
    }

    String nextVersion(String projectCode, String name) {
        String publisher = publisher(projectCode);
        int[] best = null;
        for (SkillSummary skill : catalogService.list(name, null)) {
            if (!name.equals(skill.name()) || !publisher.equals(skill.publisher())
                    || !"PROJECT".equalsIgnoreCase(skill.visibility())
                    || !projectCode.equalsIgnoreCase(String.valueOf(skill.projectCode()))) {
                continue;
            }
            for (var version : catalogService.detail(skill.id()).versions()) {
                Matcher semver = SEMVER.matcher(version.version());
                if (!semver.matches()) continue;
                int[] parts = {Integer.parseInt(semver.group(1)), Integer.parseInt(semver.group(2)),
                        Integer.parseInt(semver.group(3))};
                if (best == null || java.util.Arrays.compare(parts, best) > 0) best = parts;
            }
        }
        return best == null ? "1.0.0" : best[0] + "." + best[1] + "." + (best[2] + 1);
    }

    static String publisher(String projectCode) {
        String candidate = projectCode == null ? "" : projectCode.trim().toLowerCase(Locale.ROOT);
        return PUBLISHER.matcher(candidate).matches() && !"reachai".equals(candidate) ? candidate : "community";
    }

    static String standardName(String projectCode, String toolName) {
        String system = kebab(projectCode);
        String method = toolName;
        String prefix = projectCode.replace('-', '_') + "_";
        if (method.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
            method = method.substring(prefix.length());
        }
        String name = kebab(method);
        if (!system.isEmpty() && !name.startsWith(system)) {
            name = name.isEmpty() ? system : system + "-" + name;
        }
        if (name.isEmpty()) name = "business-skill";
        if (name.length() > 64) name = name.substring(0, 64).replaceAll("-+$", "");
        return name;
    }

    private static String kebab(String value) {
        if (value == null) return "";
        String spaced = value.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
        return spaced.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    private static String typeLabel(String type) {
        return switch (type) {
            case "string" -> "文本";
            case "integer" -> "整数";
            case "number" -> "数字";
            case "boolean" -> "是/否";
            case "array" -> "列表";
            case "object" -> "对象";
            default -> type;
        };
    }

    private static String shortText(String text, String fallback) {
        if (!StringUtils.hasText(text)) return fallback;
        String first = text.trim().split("[。；;\\n]")[0].trim();
        if (first.isEmpty()) return fallback;
        return first.length() > 40 ? first.substring(0, 40) : first;
    }

    private static String yamlString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }

    private static String frontmatter(String skillMd) {
        if (!skillMd.startsWith("---\n")) return "";
        int end = skillMd.indexOf("\n---", 4);
        return end < 0 ? "" : skillMd.substring(4, end);
    }

    private static byte[] zip(String name, String skillMd) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                ZipEntry entry = new ZipEntry(name + "/SKILL.md");
                entry.setTimeLocal(java.time.LocalDateTime.of(1980, 1, 1, 0, 0));
                zip.putNextEntry(entry);
                zip.write(skillMd.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            return output.toByteArray();
        } catch (IOException failure) {
            throw AgentSkillException.artifactFailure("Skill could not be packaged: " + name, failure);
        }
    }

    // ---- small JSON helpers -----------------------------------------------------------------

    private JsonNode callArguments(String requestBody) {
        JsonNode body = parse(requestBody);
        return body == null ? null : body.path("params").path("arguments");
    }

    static Set<String> argumentNames(JsonNode arguments) {
        Set<String> names = new LinkedHashSet<>();
        if (arguments != null && arguments.isObject()) {
            collectNames(arguments, "", names);
        }
        return names;
    }

    private static void collectNames(JsonNode node, String prefix, Set<String> names) {
        node.fieldNames().forEachRemaining(key -> {
            JsonNode value = node.get(key);
            if (value.isObject() && value.size() > 0) {
                collectNames(value, prefix + key + ".", names);
            } else {
                names.add(prefix + key);
            }
        });
    }

    private JsonNode parse(String json) {
        if (!StringUtils.hasText(json)) return null;
        try {
            return objectMapper.readTree(json);
        } catch (IOException unreadable) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> raw ? raw : List.of();
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String trim(String value, int max) {
        if (!StringUtils.hasText(value)) return null;
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    // ---- contracts --------------------------------------------------------------------------

    record RecordedCall(String toolName, Set<String> argumentNames) {
    }

    record Collected(String projectCode, Long projectId, List<RecordedCall> calls, List<String> warnings,
                     String sourceRef, String exampleRequest) {
    }

    public record PreviewRequest(String source, List<Long> callLogIds, String traceId, String goal) {
    }

    public record CreateRequest(String projectCode, String displayName, String skillMd, String visibility,
                                String version, String sourceRef) {
    }

    public record Field(String name, String type, boolean required, String description, boolean usedInRun) {
    }

    public record Step(int order, String toolName, String description, String riskLevel, boolean requiresApproval,
                       List<Field> fields, boolean available) {

        Step withMoreFields(Set<String> names) {
            Map<String, Field> merged = new LinkedHashMap<>();
            fields.forEach(field -> merged.put(field.name(), field));
            for (String name : names) {
                merged.computeIfPresent(name, (key, field) -> new Field(key, field.type(), field.required(),
                        field.description(), true));
            }
            return new Step(order, toolName, description, riskLevel, requiresApproval, List.copyOf(merged.values()),
                    available);
        }
    }

    public record Draft(String projectCode, Long projectId, String systemName, String name, String displayName, String description,
                        String skillMd, List<Step> steps, List<String> warnings, String workflowInstruction,
                        String sourceRef, String version) {
    }
}
