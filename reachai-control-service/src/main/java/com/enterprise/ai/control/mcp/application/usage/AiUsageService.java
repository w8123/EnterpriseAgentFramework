package com.enterprise.ai.control.mcp.application.usage;

import com.enterprise.ai.control.mcp.application.port.McpEmployeeAccessRepository;
import com.enterprise.ai.control.mcp.application.port.McpPublicationRepository;
import com.enterprise.ai.control.mcp.application.usage.AiUsageReader.AccountKey;
import com.enterprise.ai.control.mcp.application.usage.AiUsageReader.AccountRow;
import com.enterprise.ai.control.mcp.application.usage.AiUsageReader.ApprovalRow;
import com.enterprise.ai.control.mcp.application.usage.AiUsageReader.CallRow;
import com.enterprise.ai.control.mcp.application.usage.AiUsageReader.ConnectionRow;
import com.enterprise.ai.control.mcp.application.usage.EmbedUsageSource.EmbedUserRow;
import com.enterprise.ai.control.mcp.domain.employee.McpEmployeeAccess;
import com.enterprise.ai.control.mcp.domain.publication.McpPublication;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationRevision;
import com.enterprise.ai.control.mcp.domain.publication.McpToolProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 「AI 使用情况」: who used AI, with which tool (the embedded chat or their own Claude Code / Codex), what they did
 * in which business system, and how their write requests were decided. Read-only; no request or response bodies.
 */
@Service
public class AiUsageService {

    private static final Logger log = LoggerFactory.getLogger(AiUsageService.class);

    public static final String CHANNEL_EMBED = "EMBED";
    static final List<String> CHANNELS = List.of(CHANNEL_EMBED, "CLAUDE_CODE", "CODEX", "OTHER");
    static final int MAX_PEOPLE = 200;
    static final int MAX_OPERATIONS = 20;
    static final int MAX_PERSON_OPERATIONS = 5;
    static final int MAX_SKILLS = 10;

    private final AiUsageReader reader;
    private final EmbedUsageSource embedSource;
    private final McpEmployeeAccessRepository accessRepository;
    private final McpPublicationRepository publicationRepository;

    public AiUsageService(AiUsageReader reader,
                          EmbedUsageSource embedSource,
                          McpEmployeeAccessRepository accessRepository,
                          McpPublicationRepository publicationRepository) {
        this.reader = reader;
        this.embedSource = embedSource;
        this.accessRepository = accessRepository;
        this.publicationRepository = publicationRepository;
    }

    /**
     * @param includeEmbed false when the viewer may not read run records; the embedded chat is then left out
     *                     and {@code embedNotice} says why.
     */
    public Report report(Integer days, String projectCode, boolean includeEmbed) {
        int safeDays = days == null || days <= 0 ? 30 : Math.min(days, 90);
        String project = StringUtils.hasText(projectCode) ? projectCode.trim() : null;
        LocalDateTime since = LocalDateTime.now().minusDays(safeDays);

        Map<String, String> systemNames = new LinkedHashMap<>();
        Map<String, List<McpToolProjection>> toolsBySystem = new HashMap<>();
        for (McpEmployeeAccess access : accessRepository.findEnabled()) {
            systemNames.put(access.projectCode(), access.label());
            toolsBySystem.put(access.projectCode(), liveTools(access));
        }

        List<CallRow> calls = reader.employeeCalls(since, project);
        List<ApprovalRow> approvals = reader.approvals(since, project);
        Set<Long> connectionIds = new HashSet<>();
        calls.forEach(row -> addIfPresent(connectionIds, row.connectionId()));
        approvals.forEach(row -> addIfPresent(connectionIds, row.connectionId()));
        Map<Long, ConnectionRow> connections = new HashMap<>();
        if (!connectionIds.isEmpty()) {
            reader.connections(connectionIds).forEach(row -> connections.put(row.id(), row));
        }

        List<EmbedUserRow> embedRows = List.of();
        String embedNotice = null;
        if (!includeEmbed) {
            embedNotice = project == null
                    ? "你没有查看运行记录的权限，这里不含业务系统里的 AI 对话。"
                    : "你没有查看该系统运行记录的权限，这里不含业务系统里的 AI 对话。";
        } else {
            try {
                embedRows = embedSource.embedUsers(project, safeDays);
            } catch (RuntimeException failure) {
                log.warn("[AiUsage] embedded chat usage unavailable: {}", failure.getMessage());
                embedNotice = "暂时读不到业务系统里的 AI 对话数据，下面只统计员工自己的 AI 工具。";
            }
        }
        Map<AccountKey, AccountRow> accounts = new HashMap<>();
        if (!embedRows.isEmpty()) {
            List<AccountKey> keys = embedRows.stream()
                    .map(row -> new AccountKey(row.appId(), row.externalUserId())).distinct().toList();
            reader.accounts(keys).forEach(row -> accounts.put(new AccountKey(row.appId(), row.externalUserId()), row));
        }

        Map<String, PersonAcc> people = new LinkedHashMap<>();
        Map<String, ChannelAcc> channels = new LinkedHashMap<>();
        CHANNELS.forEach(channel -> channels.put(channel, new ChannelAcc(channel)));
        Map<String, OperationAcc> operations = new LinkedHashMap<>();
        Counts approvalTotals = new Counts();
        long toolCalls = 0;
        long toolFailed = 0;
        long embedRuns = 0;
        long embedSessions = 0;

        for (CallRow row : calls) {
            ConnectionRow connection = connections.get(row.connectionId());
            String channel = channel(connection == null ? null : connection.toolKind());
            PersonAcc person = person(people, connection == null ? null : connection.businessUserId(),
                    connection == null ? null : connection.appId(),
                    connection == null ? "连接 " + row.connectionId() : connection.externalUserId());
            if (connection != null) {
                person.offerName(connection.userName());
                person.connectionIds.add(connection.id());
            }
            person.channels.add(channel);
            person.toolCalls += row.calls();
            person.toolFailed += row.failed();
            person.touch(row.lastAt());
            person.systems.add(row.projectCode());
            person.operation(row.projectCode(), row.toolName()).add(row.calls(), row.failed());
            ChannelAcc channelAcc = channels.get(channel);
            channelAcc.people.add(person.key);
            channelAcc.uses += row.calls();
            channelAcc.failed += row.failed();
            OperationAcc operation = operations.computeIfAbsent(operationKey(row.projectCode(), row.toolName()),
                    ignored -> new OperationAcc(row.projectCode(), row.toolName()));
            operation.calls += row.calls();
            operation.failed += row.failed();
            operation.people.add(person.key);
            toolCalls += row.calls();
            toolFailed += row.failed();
        }

        Map<String, String> approvalDescriptions = new HashMap<>();
        for (ApprovalRow row : approvals) {
            ConnectionRow connection = connections.get(row.connectionId());
            String appId = row.appId() != null ? row.appId() : connection == null ? null : connection.appId();
            String channel = channel(row.toolKind());
            PersonAcc person = person(people, row.businessUserId(), appId, row.externalUserId());
            person.offerName(row.userName());
            addIfPresent(person.connectionIds, row.connectionId());
            person.channels.add(channel);
            person.approvals.add(row.state(), row.count());
            person.touch(row.lastAt());
            person.systems.add(row.projectCode());
            person.operation(row.projectCode(), row.toolName()).writes += row.count();
            channels.get(channel).approvals.add(row.state(), row.count());
            channels.get(channel).people.add(person.key);
            approvalTotals.add(row.state(), row.count());
            OperationAcc operation = operations.computeIfAbsent(operationKey(row.projectCode(), row.toolName()),
                    ignored -> new OperationAcc(row.projectCode(), row.toolName()));
            operation.writes += row.count();
            operation.people.add(person.key);
            if (StringUtils.hasText(row.toolDescription())) {
                approvalDescriptions.putIfAbsent(operationKey(row.projectCode(), row.toolName()), row.toolDescription());
            }
            if (StringUtils.hasText(row.systemName())) {
                systemNames.putIfAbsent(row.projectCode(), row.systemName());
            }
        }

        for (EmbedUserRow row : embedRows) {
            AccountRow account = accounts.get(new AccountKey(row.appId(), row.externalUserId()));
            PersonAcc person = person(people, account == null ? null : account.businessUserId(),
                    row.appId(), row.externalUserId());
            if (account != null) {
                person.offerName(account.name());
            }
            person.channels.add(CHANNEL_EMBED);
            person.embedRuns += row.runs();
            person.embedSessions += row.sessions();
            person.embedFailed += row.failed();
            person.touch(row.lastAt());
            if (StringUtils.hasText(row.projectCode())) {
                person.systems.add(row.projectCode());
            }
            ChannelAcc channelAcc = channels.get(CHANNEL_EMBED);
            channelAcc.people.add(person.key);
            channelAcc.uses += row.runs();
            channelAcc.failed += row.failed();
            embedRuns += row.runs();
            embedSessions += row.sessions();
        }

        Set<Long> businessUserIds = new HashSet<>();
        people.values().forEach(person -> addIfPresent(businessUserIds, person.businessUserId));
        Map<Long, String> businessNames = businessUserIds.isEmpty() ? Map.of() : reader.businessUserNames(businessUserIds);

        java.util.function.BiFunction<String, String, String> label = (system, tool) -> {
            String described = describe(toolsBySystem.get(system), tool);
            if (described == null) {
                described = approvalDescriptions.get(operationKey(system, tool));
            }
            return described == null ? tool : described;
        };
        java.util.function.Function<String, String> systemName = code ->
                code == null ? "-" : systemNames.getOrDefault(code, code);

        List<Person> personViews = people.values().stream()
                .sorted(Comparator.comparingLong(PersonAcc::activity).reversed()
                        .thenComparing(PersonAcc::lastAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_PEOPLE)
                .map(person -> person.view(person.businessUserId == null ? null : businessNames.get(person.businessUserId),
                        systemName, label))
                .toList();
        List<Channel> channelViews = channels.values().stream().map(ChannelAcc::view).toList();
        List<Operation> operationViews = operations.values().stream()
                .sorted(Comparator.comparingLong((OperationAcc op) -> op.calls + op.writes).reversed())
                .limit(MAX_OPERATIONS)
                .map(op -> new Operation(op.projectCode, systemName.apply(op.projectCode), op.toolName,
                        label.apply(op.projectCode, op.toolName), op.calls, op.failed, op.writes, op.people.size()))
                .toList();
        List<Skill> skills = reader.capturedSkills(since, project, MAX_SKILLS).stream()
                .map(row -> new Skill(row.skillId(), row.standardName(), row.displayName(), row.version(),
                        row.status(), row.projectCode(), systemName.apply(row.projectCode()), row.createdBy(),
                        row.createdAt()))
                .toList();

        Set<String> systemCodes = new LinkedHashSet<>(systemNames.keySet());
        people.values().forEach(person -> person.systems.stream().filter(Objects::nonNull).forEach(systemCodes::add));
        List<SystemOption> systems = systemCodes.stream()
                .map(code -> new SystemOption(code, systemName.apply(code))).toList();

        Summary summary = new Summary(people.size(), embedRuns, embedSessions, toolCalls, toolFailed,
                reader.activeConnections(project), approvalTotals.view(), reader.capturedSkillCount(since, project));
        return new Report(safeDays, since, project, summary, channelViews, personViews, operationViews, skills,
                systems, includeEmbed && embedNotice == null, embedNotice);
    }

    private List<McpToolProjection> liveTools(McpEmployeeAccess access) {
        try {
            return publicationRepository.findById(access.publicationId())
                    .filter(found -> found.currentRevisionId() != null)
                    .flatMap((McpPublication found) -> publicationRepository.findRevision(found.id(), found.currentRevisionId()))
                    .map(McpPublicationRevision::tools)
                    .orElse(List.of());
        } catch (RuntimeException failure) {
            log.warn("[AiUsage] tool names of {} unavailable: {}", access.projectCode(), failure.getMessage());
            return List.of();
        }
    }

    static String describe(List<McpToolProjection> tools, String toolName) {
        if (tools == null || toolName == null) {
            return null;
        }
        return tools.stream()
                .filter(tool -> toolName.equals(tool.name()) || toolName.equals(tool.sourceRef()))
                .map(McpToolProjection::description)
                .filter(StringUtils::hasText)
                .map(description -> firstSentence(description.trim()))
                .findFirst()
                .orElse(null);
    }

    /** Tool descriptions are written for AI tools and can run long; the first clause is the human label. */
    static String firstSentence(String description) {
        int end = description.length();
        for (String stop : List.of("。", "\n", "；", ". ")) {
            int at = description.indexOf(stop);
            if (at > 0 && at < end) {
                end = at;
            }
        }
        String head = description.substring(0, end).trim();
        return head.length() > 40 ? head.substring(0, 40) + "…" : head;
    }

    static String channel(String toolKind) {
        if (toolKind == null) {
            return "OTHER";
        }
        String normalized = toolKind.trim().toUpperCase(java.util.Locale.ROOT);
        return CHANNELS.contains(normalized) && !CHANNEL_EMBED.equals(normalized) ? normalized : "OTHER";
    }

    private static PersonAcc person(Map<String, PersonAcc> people, Long businessUserId, String appId,
                                    String externalUserId) {
        String key = businessUserId != null ? "u:" + businessUserId : "a:" + appId + ":" + externalUserId;
        PersonAcc person = people.computeIfAbsent(key, PersonAcc::new);
        person.businessUserId = businessUserId;
        if (StringUtils.hasText(externalUserId)) {
            person.accounts.add((StringUtils.hasText(appId) ? appId + " · " : "") + externalUserId);
        }
        return person;
    }

    private static String operationKey(String projectCode, String toolName) {
        return projectCode + "\u0000" + toolName;
    }

    private static <T> void addIfPresent(Set<T> target, T value) {
        if (value != null) {
            target.add(value);
        }
    }

    // ---- accumulators ------------------------------------------------------------------------

    private static final class Counts {
        long submitted;
        long succeeded;
        long failed;
        long rejected;
        long pending;
        long expired;

        void add(String state, long count) {
            submitted += count;
            switch (state == null ? "" : state.trim().toUpperCase(java.util.Locale.ROOT)) {
                case "SUCCEEDED" -> succeeded += count;
                case "FAILED" -> failed += count;
                case "REJECTED" -> rejected += count;
                case "EXPIRED" -> expired += count;
                default -> pending += count;
            }
        }

        ApprovalCounts view() {
            return new ApprovalCounts(submitted, succeeded, failed, rejected, pending, expired);
        }
    }

    private static final class ChannelAcc {
        final String channel;
        final Set<String> people = new HashSet<>();
        long uses;
        long failed;
        final Counts approvals = new Counts();

        ChannelAcc(String channel) {
            this.channel = channel;
        }

        Channel view() {
            return new Channel(channel, people.size(), uses, failed, approvals.view());
        }
    }

    private static final class OperationAcc {
        final String projectCode;
        final String toolName;
        long calls;
        long failed;
        long writes;
        final Set<String> people = new HashSet<>();

        OperationAcc(String projectCode, String toolName) {
            this.projectCode = projectCode;
            this.toolName = toolName;
        }

        void add(long moreCalls, long moreFailed) {
            calls += moreCalls;
            failed += moreFailed;
        }
    }

    private static final class PersonAcc {
        final String key;
        Long businessUserId;
        String name;
        final Set<String> accounts = new LinkedHashSet<>();
        final Set<String> channels = new LinkedHashSet<>();
        final Set<String> systems = new LinkedHashSet<>();
        final Set<Long> connectionIds = new LinkedHashSet<>();
        final Map<String, OperationAcc> operations = new LinkedHashMap<>();
        final Counts approvals = new Counts();
        long embedRuns;
        long embedSessions;
        long embedFailed;
        long toolCalls;
        long toolFailed;
        LocalDateTime lastAt;

        PersonAcc(String key) {
            this.key = key;
        }

        void offerName(String candidate) {
            if (name == null && StringUtils.hasText(candidate)) {
                name = candidate.trim();
            }
        }

        void touch(LocalDateTime at) {
            if (at != null && (lastAt == null || at.isAfter(lastAt))) {
                lastAt = at;
            }
        }

        OperationAcc operation(String projectCode, String toolName) {
            return operations.computeIfAbsent(operationKey(projectCode, toolName),
                    ignored -> new OperationAcc(projectCode, toolName));
        }

        long activity() {
            return embedRuns + toolCalls;
        }

        LocalDateTime lastAt() {
            return lastAt;
        }

        Person view(String businessName, java.util.function.Function<String, String> systemName,
                    java.util.function.BiFunction<String, String, String> label) {
            String display = StringUtils.hasText(businessName) ? businessName.trim()
                    : name != null ? name : accounts.stream().findFirst().orElse(key);
            List<String> orderedChannels = CHANNELS.stream().filter(channels::contains).toList();
            List<Operation> top = operations.values().stream()
                    .sorted(Comparator.comparingLong((OperationAcc op) -> op.calls + op.writes).reversed())
                    .limit(MAX_PERSON_OPERATIONS)
                    .map(op -> new Operation(op.projectCode, systemName.apply(op.projectCode), op.toolName,
                            label.apply(op.projectCode, op.toolName), op.calls, op.failed, op.writes, 1))
                    .toList();
            return new Person(key, display, businessUserId, new ArrayList<>(accounts), orderedChannels,
                    systems.stream().filter(Objects::nonNull).map(systemName).distinct().toList(),
                    new ArrayList<>(connectionIds), embedRuns, embedSessions, embedFailed, toolCalls, toolFailed,
                    approvals.view(), lastAt, top);
        }
    }

    // ---- views -------------------------------------------------------------------------------

    public record Report(int days, LocalDateTime since, String projectCode, Summary summary,
                         List<Channel> channels, List<Person> people, List<Operation> operations,
                         List<Skill> skills, List<SystemOption> systems,
                         boolean embedIncluded, String embedNotice) {
    }

    public record Summary(long activePeople, long embedRuns, long embedSessions, long toolCalls,
                          long toolFailedCalls, long activeConnections, ApprovalCounts approvals,
                          long capturedSkills) {
    }

    /** {@code pending} also counts approvals that are executing right now. */
    public record ApprovalCounts(long submitted, long succeeded, long failed, long rejected, long pending,
                                 long expired) {
    }

    /** channel: EMBED (the business system's own AI chat), CLAUDE_CODE, CODEX or OTHER. */
    public record Channel(String channel, long people, long uses, long failed, ApprovalCounts approvals) {
    }

    public record Person(String key, String name, Long businessUserId, List<String> accounts,
                         List<String> channels, List<String> systems, List<Long> connectionIds,
                         long embedRuns, long embedSessions, long embedFailed, long toolCalls, long toolFailed,
                         ApprovalCounts approvals, LocalDateTime lastActiveAt, List<Operation> topOperations) {
    }

    /** label: what the operation does in words (the tool's description), falling back to the tool name. */
    public record Operation(String projectCode, String systemName, String toolName, String label, long calls,
                            long failed, long writes, long people) {
    }

    public record Skill(Long skillId, String name, String displayName, String version, String status,
                        String projectCode, String systemName, String createdBy, LocalDateTime createdAt) {
    }

    public record SystemOption(String projectCode, String name) {
    }
}
