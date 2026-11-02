package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformAiCodingPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Creating a task only records work for a coding agent, and cancelling a task or answering its question is what the task
 * owner does on the console, so these run directly. Issuing a handoff (a task credential for an external coding agent),
 * starting managed execution, resolving its approvals and accepting the result stay with people: they hand out
 * credentials, start code execution, or sign off AI work. A lost result is reported as unknown, never resent.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformAiCodingService {
    private final McpPlatformAiCodingPort tasks;

    public ResponseEntity<?> list(ConnectionIdentity actor, Long projectId, String projectCode, String taskKind, String executionStatus, int limit) {
        return tasks.list(actor.session(), projectId, projectCode, taskKind, executionStatus, limit);
    }

    public ResponseEntity<?> read(ConnectionIdentity actor, String taskId, McpPlatformAiCodingPort.Read read) {
        return tasks.read(actor.session(), taskId, read);
    }

    public ResponseEntity<?> create(ConnectionIdentity actor, Map<String, Object> command) {
        return PlatformAiDirectWrites.once(() -> tasks.create(actor.session(), command),
                "任务创建结果未确认，请先用 reachai_ai_coding_tasks 查看再决定；不要直接重试。");
    }

    public ResponseEntity<?> cancel(ConnectionIdentity actor, String taskId) {
        return PlatformAiDirectWrites.once(() -> tasks.cancel(actor.session(), taskId), "取消结果未确认，请重新读取任务。");
    }

    public ResponseEntity<?> answer(ConnectionIdentity actor, String taskId, String questionId, String answer) {
        return PlatformAiDirectWrites.once(() -> tasks.answer(actor.session(), taskId, questionId, answer),
                "回答结果未确认，请重新读取任务里的问题；不要直接重试。");
    }
}
