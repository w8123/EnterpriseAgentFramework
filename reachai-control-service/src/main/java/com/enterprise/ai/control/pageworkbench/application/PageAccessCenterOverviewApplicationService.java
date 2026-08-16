package com.enterprise.ai.control.pageworkbench.application;

import com.enterprise.ai.control.aicoding.application.AiCodingTaskApplicationService;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskTargetView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.FindingView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageAccessActivityView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageAccessCenterOverviewView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageAccessCenterSummaryView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageAccessJourneyView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageAccessNextActionView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageMapSummaryView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PublishedWorkflowView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Projects the existing Control task kernel and Runtime publication state into
 * the page-centered lifecycle shown by Page Access Center. It is deliberately
 * read-only: all state transitions continue to use their owning services.
 */
@Service
@RequiredArgsConstructor
public class PageAccessCenterOverviewApplicationService {

    public static final String SCHEMA = "reachai.page-access-center.overview.v1";

    private static final String PAGE = "PAGE";
    private static final String WORKFLOW = "WORKFLOW";
    private static final String PRIMARY = "PRIMARY";
    private static final String RELATED = "RELATED";
    private static final String COMPLETED = "COMPLETED";
    private static final String FAILED = "FAILED";
    private static final String CANCELLED = "CANCELLED";
    private static final String WAITING_USER = "WAITING_USER";
    private static final String ACCEPTANCE_READY = "ACCEPTANCE_READY";

    private final PageCatalogApplicationService pageCatalog;
    private final PageAnalysisApplicationService pageAnalysis;
    private final AiCodingTaskApplicationService taskService;
    private final PageWorkbenchPublishedApplicationService publishedService;
    private final PageMapSummaryApplicationService pageMapSummaryService;

    public PageAccessCenterOverviewView overview(String projectCode) {
        String code = requireText(projectCode, "projectCode");
        List<PageView> pages = pageCatalog.listPages(code, false);
        List<FindingView> findings = pageAnalysis.list(code, null, null);
        List<TaskView> tasks = taskService.list(null, code, null, null, 200);
        PageMapSummaryView pageMap = pageMapSummaryService.summary(pages, tasks);

        List<PublishedWorkflowView> published = List.of();
        boolean runtimeAvailable = true;
        String runtimeMessage = "运行服务发布数据已同步";
        try {
            published = publishedService.list(code, null);
        } catch (RuntimeException ex) {
            runtimeAvailable = false;
            runtimeMessage = "运行服务的发布与调用数据当前不可用，请稍后刷新状态";
        }

        Map<String, List<FindingView>> findingsByPage = findings.stream()
                .collect(Collectors.groupingBy(
                        FindingView::pageKey,
                        LinkedHashMap::new,
                        Collectors.toList()));
        Map<String, List<TaskView>> tasksByPage = tasks.stream()
                .flatMap(task -> primaryPageKey(task).stream()
                        .map(pageKey -> Map.entry(pageKey, task)))
                .collect(Collectors.groupingBy(
                        Map.Entry::getKey,
                        LinkedHashMap::new,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        Map<String, PublishedWorkflowView> publishedByPage = published.stream()
                .collect(Collectors.toMap(
                        PublishedWorkflowView::pageKey,
                        item -> item,
                        PageAccessCenterOverviewApplicationService::newerPublished,
                        LinkedHashMap::new));
        boolean publishedStateAvailable = runtimeAvailable;

        List<PageAccessJourneyView> journeys = pages.stream()
                .map(page -> journey(
                        page,
                        findingsByPage.getOrDefault(page.pageKey(), List.of()),
                        tasksByPage.getOrDefault(page.pageKey(), List.of()),
                        publishedByPage.get(page.pageKey()),
                        publishedStateAvailable))
                .sorted(journeyComparator())
                .toList();

        return new PageAccessCenterOverviewView(
                SCHEMA,
                code,
                summary(journeys),
                journeys,
                tasks.stream().map(this::activity).toList(),
                published,
                runtimeAvailable,
                runtimeMessage,
                LocalDateTime.now(),
                pages,
                pageMap,
                findings,
                tasks.stream().limit(100).toList());
    }

    private PageAccessJourneyView journey(
            PageView page,
            List<FindingView> findings,
            List<TaskView> tasks,
            PublishedWorkflowView published,
            boolean runtimeAvailable) {
        int unread = (int) findings.stream()
                .filter(item -> "UNREAD".equals(item.status()))
                .count();
        int kept = (int) findings.stream()
                .filter(item -> "KEPT".equals(item.status()))
                .count();
        List<TaskView> orderedTasks = tasks.stream()
                .sorted(Comparator.comparing(
                        TaskView::updatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        Optional<TaskView> latestPageBuildTask = latest(
                orderedTasks,
                task -> isPageBuildTask(task.taskKind()));
        boolean publishedSuperseded = published != null
                && latestPageBuildTask
                        .map(task -> taskSupersedesPublished(task, published))
                        .orElse(false);

        if (published != null && !publishedSuperseded) {
            Optional<TaskView> acceptance = orderedTasks.stream()
                    .filter(task -> "BROWSER_ACCEPTANCE".equals(task.taskKind()))
                    .filter(task -> exactWorkflowTarget(task, published))
                    .findFirst();
            if (acceptance.isPresent()) {
                TaskView task = acceptance.get();
                if (COMPLETED.equals(task.executionStatus())) {
                    return view(page, "COMPLETE", "COMPLETE", 4, 4,
                            "接入完成", "页面接入已完成",
                            "页面操作、工作流发布和真实业务验收均已通过。",
                            action("VIEW_ONLINE", "查看在线能力",
                                    "查看版本、调用表现和最近运行。", true),
                            task, unread, kept, published);
                }
                if (isFailure(task)) {
                    return view(page, "REAL_ACCEPTANCE", "ERROR", 4, 3,
                            "验收未通过", "下一步：重新发起真实验收",
                            taskMessage(task, "上一次真实业务验收未通过。"),
                            action("RETRY_ACCEPTANCE", "重新验收",
                                    "锁定当前页面与工作流版本，再次执行真实浏览器验收。", true),
                            task, unread, kept, published);
                }
                return activeTaskView(page, task, "REAL_ACCEPTANCE", 4, 3,
                        unread, kept, published,
                        "正在进行真实业务验收");
            }
            return view(page, "REAL_ACCEPTANCE", "ACTION_REQUIRED", 4, 3,
                    "等待验收", "下一步：真实业务验收",
                    "工作流已经发布并接入智能体，需要在真实业务页面确认可见结果与运行 Trace。",
                    action("START_ACCEPTANCE", "开始真实验收",
                            "创建锁定当前工作流版本的浏览器验收活动。", true),
                    null, unread, kept, published);
        }

        Optional<TaskView> active = orderedTasks.stream()
                .filter(task -> !isTerminal(task))
                .findFirst();
        if (active.isPresent()) {
            TaskView task = active.get();
            if ("BROWSER_ACCEPTANCE".equals(task.taskKind())) {
                return activeTaskView(page, task, "REAL_ACCEPTANCE", 4, 3,
                        unread, kept, null, "正在进行真实业务验收");
            }
            if ("PRE_RELEASE_CHECK".equals(task.taskKind())) {
                return activeTaskView(page, task, "PUBLISH_CONFIRMATION", 3, 2,
                        unread, kept, null, "正在完成上线前检查");
            }
            return activeTaskView(page, task, "AI_IMPLEMENTATION", 2, 1,
                    unread, kept, null, "AI 正在实施页面接入");
        }

        Optional<TaskView> completedWorkflow = latestPageBuildTask
                .filter(task -> "WORKFLOW_ENGINEERING".equals(task.taskKind()))
                .filter(task -> COMPLETED.equals(task.executionStatus()));
        if (completedWorkflow.isPresent()) {
            TaskView task = completedWorkflow.get();
            if (!runtimeAvailable) {
                return view(page, "PUBLISH_CONFIRMATION", "UNAVAILABLE", 3, 2,
                        "状态暂不可用", "等待运行服务恢复",
                        "页面助手草稿已通过审阅，但当前无法确认它是否已经发布。",
                        action("REFRESH_STATUS", "刷新状态",
                                "运行服务恢复后重新读取发布状态。", false),
                        task, unread, kept, null);
            }
            return view(page, "PUBLISH_CONFIRMATION", "ACTION_REQUIRED", 3, 2,
                    "等待上线", "下一步：确认发布并接入",
                    "页面助手草稿已经通过人工审阅；发布时会重新校验操作、权限与版本。",
                    action("PUBLISH_WORKFLOW", "确认上线",
                            "打开活动结果，确认后发布工作流并接入页面副驾驶。", true),
                    task, unread, kept, null);
        }

        if (!runtimeAvailable) {
            return view(page, "GOAL_SELECTION", "UNAVAILABLE", 1, 0,
                    "状态暂不可用", "等待运行服务恢复",
                    "当前无法确认页面是否已经发布或验收，系统不会根据空结果推断接入状态。",
                    action("REFRESH_STATUS", "刷新状态",
                            "运行服务恢复后重新读取页面接入状态。", false),
                    null, unread, kept, null);
        }

        Optional<TaskView> completedImplementation = latestPageBuildTask
                .filter(task -> "CODE_IMPLEMENTATION".equals(task.taskKind()))
                .filter(task -> COMPLETED.equals(task.executionStatus()));
        if (completedImplementation.isPresent()) {
            TaskView task = completedImplementation.get();
            if (page.actions().isEmpty()) {
                return view(page, "AI_IMPLEMENTATION", "ACTION_REQUIRED", 2, 1,
                        "等待页面操作", "下一步：检查页面操作",
                        "代码实施已经通过审阅，但平台尚未发现可供页面助手调用的有效页面操作。",
                        action("CHECK_PAGE_ACTIONS", "检查页面操作",
                                "核对页面操作登记、SDK 会话与 Page Bridge。", true),
                        task, unread, kept, null);
            }
            return view(page, "AI_IMPLEMENTATION", "ACTION_REQUIRED", 2, 1,
                    "操作已就绪", "下一步：生成页面助手",
                    "页面代码与操作已经完成审阅，可以基于已登记操作生成受治理的页面助手工作流。",
                    action("START_WORKFLOW_ENGINEERING", "生成页面助手",
                            "创建页面工作流建设活动，不会自动发布。", true),
                    task, unread, kept, null);
        }

        Optional<TaskView> failed = orderedTasks.stream()
                .filter(this::isFailure)
                .findFirst();
        if (failed.isPresent()) {
            TaskView task = failed.get();
            int step = "BROWSER_ACCEPTANCE".equals(task.taskKind()) ? 4 : 2;
            return view(page,
                    step == 4 ? "REAL_ACCEPTANCE" : "AI_IMPLEMENTATION",
                    "ERROR", step, Math.max(0, step - 1),
                    "需要处理", "上一次活动未完成",
                    taskMessage(task, "可以查看活动记录并按原目标重新开始。"),
                    action("RETRY_TASK", "查看并重试",
                            "打开上一次活动，确认原因后重新发起。", true),
                    task, unread, kept, null);
        }

        if (kept > 0) {
            return view(page, "GOAL_SELECTION", "ACTION_REQUIRED", 1, 0,
                    "目标待确认", "下一步：确认接入目标",
                    String.format("已选择 %d 个页面改造目标，确认后即可交给 AI 实施。", kept),
                    action("START_IMPLEMENTATION", "确认并开始实施",
                            "基于已选目标创建代码实施活动。", true),
                    null, unread, kept, null);
        }
        return view(page, "GOAL_SELECTION", "WAITING", 1, 0,
                "等待接入", "下一步：确认接入目标",
                unread > 0
                        ? String.format("已有 %d 条有依据的 AI 建议可供选择，也可以直接描述目标。", unread)
                        : "可以直接描述希望 AI 完成的业务操作，或先进行只读页面分析。",
                action("SELECT_GOAL", "开始接入",
                        "选择 AI 建议或直接描述页面接入目标。", true),
                null, unread, kept, null);
    }

    private PageAccessJourneyView activeTaskView(
            PageView page,
            TaskView task,
            String stage,
            int currentStep,
            int completedSteps,
            int unread,
            int kept,
            PublishedWorkflowView published,
            String defaultTitle) {
        boolean actionRequired = WAITING_USER.equals(task.executionStatus())
                || ACCEPTANCE_READY.equals(task.executionStatus());
        String title = actionRequired
                ? (WAITING_USER.equals(task.executionStatus())
                        ? "下一步：回答 AI 的问题"
                        : "下一步：审阅 AI 实施结果")
                : defaultTitle;
        return view(page, stage, actionRequired ? "ACTION_REQUIRED" : "ACTIVE",
                currentStep, completedSteps,
                actionRequired ? "等待你处理" : "正在进行",
                title,
                taskMessage(task, actionRequired
                        ? "活动需要你的输入，处理后 AI 会从原活动继续。"
                        : "进度、问题与结果会持续回到当前页面。"),
                action("OPEN_TASK", actionRequired ? "立即处理" : "查看进度",
                        "打开当前活动详情。", true),
                task, unread, kept, published);
    }

    private PageAccessJourneyView view(
            PageView page,
            String stage,
            String status,
            int currentStep,
            int completedSteps,
            String statusLabel,
            String title,
            String message,
            PageAccessNextActionView nextAction,
            TaskView task,
            int unread,
            int kept,
            PublishedWorkflowView published) {
        LocalDateTime updatedAt = task == null
                ? firstDate(page.lastVerifiedAt(), page.lastDiscoveredAt())
                : task.updatedAt();
        return new PageAccessJourneyView(
                page.id(), page.pageKey(), stage, status,
                currentStep, completedSteps, statusLabel, title, message,
                nextAction,
                task == null ? null : task.taskId(),
                task == null ? null : task.taskKind(),
                task == null ? null : task.executionStatus(),
                unread, kept, page.resources().size(), page.actions().size(),
                published == null ? null : published.workflowId(),
                published == null ? null : published.workflowVersion(),
                updatedAt);
    }

    private PageAccessActivityView activity(TaskView task) {
        return new PageAccessActivityView(
                task.taskId(), primaryPageKey(task).orElse(null),
                task.taskKind(), task.executorProvider(), task.title(),
                task.executionStatus(), task.lastMessage(),
                task.openQuestions() == null ? 0 : task.openQuestions().size(),
                task.connection() == null ? null : task.connection().status(),
                task.updatedAt());
    }

    private PageAccessCenterSummaryView summary(List<PageAccessJourneyView> pages) {
        int awaitingAcceptance = (int) pages.stream()
                .filter(item -> "REAL_ACCEPTANCE".equals(item.stage()))
                .filter(item -> !"COMPLETE".equals(item.status()))
                .filter(item -> !"UNAVAILABLE".equals(item.status()))
                .count();
        int active = (int) pages.stream()
                .filter(item -> "ACTIVE".equals(item.status()))
                .filter(item -> !"REAL_ACCEPTANCE".equals(item.stage()))
                .count();
        int completed = (int) pages.stream()
                .filter(item -> "COMPLETE".equals(item.status()))
                .count();
        int unavailable = (int) pages.stream()
                .filter(item -> "UNAVAILABLE".equals(item.status()))
                .count();
        int waiting = pages.size() - active - awaitingAcceptance - completed - unavailable;
        return new PageAccessCenterSummaryView(
                pages.size(), Math.max(waiting, 0), active,
                awaitingAcceptance, completed, unavailable);
    }

    private Optional<TaskView> latest(List<TaskView> tasks, Predicate<TaskView> predicate) {
        return tasks.stream().filter(predicate).findFirst();
    }

    private boolean isPageBuildTask(String taskKind) {
        return "CODE_IMPLEMENTATION".equals(taskKind)
                || "WORKFLOW_ENGINEERING".equals(taskKind);
    }

    private boolean taskSupersedesPublished(
            TaskView task,
            PublishedWorkflowView published) {
        LocalDateTime taskAt = firstDate(
                task.completedAt(),
                firstDate(task.updatedAt(), task.createdAt()));
        return taskAt != null
                && (published.publishedAt() == null
                        || taskAt.isAfter(published.publishedAt()));
    }

    private boolean exactWorkflowTarget(TaskView task, PublishedWorkflowView published) {
        if (task.targets() == null) {
            return false;
        }
        return task.targets().stream()
                .filter(target -> RELATED.equals(target.targetRole()))
                .filter(target -> WORKFLOW.equals(target.targetType()))
                .filter(target -> published.workflowId().equals(target.targetKey()))
                .anyMatch(target -> target.snapshot() != null
                        && target.snapshot().path("workflowVersionId").asLong(-1L)
                        == published.workflowVersionId());
    }

    private static Optional<String> primaryPageKey(TaskView task) {
        if (task.targets() == null) {
            return Optional.empty();
        }
        return task.targets().stream()
                .filter(target -> PRIMARY.equals(target.targetRole()))
                .filter(target -> PAGE.equals(target.targetType()))
                .map(TaskTargetView::targetKey)
                .filter(StringUtils::hasText)
                .findFirst();
    }

    private boolean isTerminal(TaskView task) {
        return COMPLETED.equals(task.executionStatus()) || isFailure(task);
    }

    private boolean isFailure(TaskView task) {
        return FAILED.equals(task.executionStatus())
                || CANCELLED.equals(task.executionStatus());
    }

    private static PublishedWorkflowView newerPublished(
            PublishedWorkflowView left,
            PublishedWorkflowView right) {
        if (left.publishedAt() == null) {
            return right;
        }
        if (right.publishedAt() == null) {
            return left;
        }
        return left.publishedAt().isAfter(right.publishedAt()) ? left : right;
    }

    private Comparator<PageAccessJourneyView> journeyComparator() {
        Map<String, Integer> priority = Map.of(
                "ACTION_REQUIRED", 0,
                "ERROR", 1,
                "ACTIVE", 2,
                "WAITING", 3,
                "UNAVAILABLE", 4,
                "COMPLETE", 5);
        return Comparator
                .comparingInt((PageAccessJourneyView item) ->
                        priority.getOrDefault(item.status(), 9))
                .thenComparing(
                        PageAccessJourneyView::updatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(PageAccessJourneyView::pageKey);
    }

    private String taskMessage(TaskView task, String fallback) {
        return StringUtils.hasText(task.lastMessage())
                ? task.lastMessage().trim()
                : fallback;
    }

    private static PageAccessNextActionView action(
            String code,
            String label,
            String message,
            boolean enabled) {
        return new PageAccessNextActionView(code, label, message, enabled);
    }

    private static LocalDateTime firstDate(LocalDateTime first, LocalDateTime second) {
        return first == null ? second : first;
    }

    private static String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
