package com.enterprise.ai.control.a2a.api.protocol;

import com.enterprise.ai.control.a2a.application.identity.A2aInboundCallContext;
import com.enterprise.ai.control.a2a.application.task.A2aTaskApplicationService;
import com.enterprise.ai.control.a2a.application.task.A2aTaskCompletionWaiter;
import com.enterprise.ai.control.a2a.application.task.A2aTaskContracts.ListQuery;
import com.enterprise.ai.control.a2a.application.task.A2aTaskContracts.ListResult;
import com.enterprise.ai.control.a2a.application.task.A2aTaskContracts.TaskResource;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.A2aTaskState;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static com.enterprise.ai.control.a2a.application.protocol.A2aProtocolModels.CancelTaskRequest;
import static com.enterprise.ai.control.a2a.application.protocol.A2aProtocolModels.ListTasksResponse;
import static com.enterprise.ai.control.a2a.application.protocol.A2aProtocolModels.SendMessageRequest;
import static com.enterprise.ai.control.a2a.application.protocol.A2aProtocolModels.SendMessageResponse;
import static com.enterprise.ai.control.a2a.application.protocol.A2aProtocolModels.Task;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "reachai.a2a-hub", name = "enabled", havingValue = "true")
public class A2aHttpJsonController {

    private static final String A2A = A2aAgentCardController.A2A_MEDIA_TYPE;

    private final A2aProtocolRequestGuard guard;
    private final A2aHttpJsonMapper mapper;
    private final A2aTaskApplicationService service;
    private final A2aTaskCompletionWaiter completionWaiter;

    @PostMapping(value = "/a2a/v1/message:send",
            produces = {A2A, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<SendMessageResponse> send(
            HttpServletRequest httpRequest,
            @RequestBody SendMessageRequest request) {
        A2aInboundCallContext call = guard.open(httpRequest, true);
        var command = mapper.toCommand(request);
        TaskResource resource = service.send(call, command);
        if (!command.returnImmediately()) {
            completionWaiter.await(call, resource.taskId());
            resource = service.resultForSend(call, resource.taskId(), command.historyLength());
        }
        return ok(httpRequest, new SendMessageResponse(mapper.toTask(resource), null));
    }

    @GetMapping(value = "/a2a/v1/tasks/{id}", produces = {A2A, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<Task> get(
            HttpServletRequest httpRequest,
            @PathVariable String id,
            @RequestParam(required = false) String tenant,
            @RequestParam(required = false) Integer historyLength) {
        A2aInboundCallContext call = guard.open(httpRequest, false);
        return ok(httpRequest, mapper.toTask(service.get(call, id, tenant, historyLength)));
    }

    @GetMapping(value = "/a2a/v1/tasks", produces = {A2A, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<ListTasksResponse> list(
            HttpServletRequest httpRequest,
            @RequestParam(required = false) String tenant,
            @RequestParam(required = false) String contextId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String pageToken,
            @RequestParam(required = false) Integer historyLength,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime statusTimestampAfter,
            @RequestParam(required = false, defaultValue = "false") boolean includeArtifacts) {
        A2aInboundCallContext call = guard.open(httpRequest, false);
        int requestedPageSize = pageSize == null ? 50 : pageSize;
        if (requestedPageSize < 1 || requestedPageSize > 100) {
            throw new A2aDomainException("A2A_INVALID_ARGUMENT",
                    "pageSize must be between 1 and 100");
        }
        A2aTaskState parsedState = status == null || status.isBlank()
                ? null : A2aTaskState.parse(status);
        if (parsedState == A2aTaskState.TASK_STATE_UNSPECIFIED) {
            throw new A2aDomainException("A2A_INVALID_ARGUMENT",
                    "TASK_STATE_UNSPECIFIED is not a list filter");
        }
        ListResult result = service.list(call, new ListQuery(
                tenant, contextId, parsedState, requestedPageSize, mapper.decodeOffset(pageToken),
                mapper.historyLength(historyLength),
                statusTimestampAfter == null ? null
                        : statusTimestampAfter.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(),
                includeArtifacts));
        return ok(httpRequest, mapper.toListResponse(
                result.tasks(), result.totalSize(), result.pageSize(), result.offset()));
    }

    @PostMapping(value = "/a2a/v1/tasks/{id}:cancel",
            produces = {A2A, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<Task> cancel(
            HttpServletRequest httpRequest,
            @PathVariable String id,
            @RequestBody(required = false) CancelTaskRequest request) {
        A2aInboundCallContext call = guard.open(httpRequest, request != null);
        if (request != null && request.id() != null && !request.id().equals(id)) {
            throw new A2aDomainException("A2A_INVALID_ARGUMENT",
                    "request id must match the task path id");
        }
        return ok(httpRequest,
                mapper.toTask(service.cancel(call, id, request == null ? null : request.tenant())));
    }

    @PostMapping(value = "/a2a/v1/message:stream")
    public ResponseEntity<Void> unsupportedStream(HttpServletRequest request) {
        guard.open(request, true);
        throw unsupported("streaming is not enabled for this publication");
    }

    @PostMapping(value = "/a2a/v1/tasks/{id}:subscribe")
    public ResponseEntity<Void> unsupportedSubscribe(HttpServletRequest request, @PathVariable String id) {
        guard.open(request, false);
        throw unsupported("task subscription is not enabled for this publication");
    }

    @GetMapping(value = "/a2a/v1/extendedAgentCard")
    public ResponseEntity<Void> unsupportedExtendedCard(HttpServletRequest request) {
        guard.open(request, false);
        throw new A2aDomainException("A2A_EXTENDED_AGENT_CARD_NOT_CONFIGURED",
                "an extended Agent Card is not configured");
    }

    @RequestMapping("/a2a/v1/tasks/{id}/pushNotificationConfigs/**")
    public ResponseEntity<Void> unsupportedPush(HttpServletRequest request, @PathVariable String id) {
        guard.open(request, false);
        throw new A2aDomainException("A2A_PUSH_NOTIFICATION_NOT_SUPPORTED",
                "push notifications are not enabled for this publication");
    }

    private <T> ResponseEntity<T> ok(HttpServletRequest request, T body) {
        return ResponseEntity.ok()
                .contentType(A2aResponseMediaTypes.negotiate(request))
                .header(A2aProtocolRequestGuard.VERSION_HEADER, "1.0")
                .body(body);
    }

    private A2aDomainException unsupported(String message) {
        return new A2aDomainException("A2A_UNSUPPORTED_OPERATION", message);
    }
}
