package com.enterprise.ai.control.aicoding.application;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.AnswerQuestionCommand;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskArtifactMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEventMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskQuestionEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskQuestionMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskTargetMapper;
import com.enterprise.ai.control.aicoding.provider.AiCodingTaskProviderRegistry;
import com.enterprise.ai.control.aicoding.security.AiCodingSensitiveJsonSanitizer;
import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiCodingTaskAnswerConcurrencyTest {

    private final AiCodingTaskMapper taskMapper = mock(AiCodingTaskMapper.class);
    private final AiCodingTaskEventMapper eventMapper =
            mock(AiCodingTaskEventMapper.class);
    private final AiCodingTaskQuestionMapper questionMapper =
            mock(AiCodingTaskQuestionMapper.class);
    private final AiCodingSensitiveJsonSanitizer sanitizer =
            mock(AiCodingSensitiveJsonSanitizer.class);
    private final AiCodingTaskApplicationService service =
            new AiCodingTaskApplicationService(
                    taskMapper,
                    mock(AiCodingTaskTargetMapper.class),
                    eventMapper,
                    questionMapper,
                    mock(AiCodingTaskArtifactMapper.class),
                    mock(AiCodingTaskProviderRegistry.class),
                    mock(AiCodingArtifactProviderExecutor.class),
                    mock(AiCodingHandoffApplicationService.class),
                    mock(CapabilityProjectOnboardingClient.class),
                    sanitizer,
                    new ObjectMapper());

    @BeforeEach
    void setUp() {
        when(taskMapper.selectById("task-1")).thenReturn(waitingTask());
        when(sanitizer.sanitizeText(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void rejectsDifferentAnswerWhenConditionalUpdateLosesConcurrentRace() {
        AiCodingTaskQuestionEntity stale = openQuestion();
        AiCodingTaskQuestionEntity persisted = answeredQuestion("先到的答案");
        when(questionMapper.selectById("question-1"))
                .thenReturn(stale, persisted);
        when(questionMapper.update(any(), any())).thenReturn(0);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.answerQuestion(
                        "task-1",
                        "question-1",
                        new AnswerQuestionCommand("后到的答案", "reviewer-b")));

        assertEquals(
                "question was already answered with different content",
                error.getMessage());
        verify(eventMapper, never()).insert(any());
        verify(taskMapper, never()).updateById(any());
    }

    @Test
    void returnsSameConcurrentAnswerWithoutDuplicatingAuditEvent() {
        AiCodingTaskQuestionEntity stale = openQuestion();
        AiCodingTaskQuestionEntity persisted = answeredQuestion("相同答案");
        when(questionMapper.selectById("question-1"))
                .thenReturn(stale, persisted);
        when(questionMapper.update(any(), any())).thenReturn(0);

        var result = service.answerQuestion(
                "task-1",
                "question-1",
                new AnswerQuestionCommand("相同答案", "reviewer-b"));

        assertEquals("相同答案", result.answer());
        assertEquals("reviewer-a", result.answeredBy());
        verify(eventMapper, never()).insert(any());
        verify(taskMapper, never()).updateById(any());
    }

    private static AiCodingTaskEntity waitingTask() {
        AiCodingTaskEntity task = new AiCodingTaskEntity();
        task.setTaskId("task-1");
        task.setExecutionStatus("WAITING_USER");
        task.setExecutorProvider("CODEX");
        task.setLockVersion(0L);
        return task;
    }

    private static AiCodingTaskQuestionEntity openQuestion() {
        AiCodingTaskQuestionEntity question = baseQuestion();
        question.setStatus("OPEN");
        return question;
    }

    private static AiCodingTaskQuestionEntity answeredQuestion(String answer) {
        AiCodingTaskQuestionEntity question = baseQuestion();
        question.setStatus("ANSWERED");
        question.setAnswer(answer);
        question.setAnsweredBy("reviewer-a");
        return question;
    }

    private static AiCodingTaskQuestionEntity baseQuestion() {
        AiCodingTaskQuestionEntity question = new AiCodingTaskQuestionEntity();
        question.setQuestionId("question-1");
        question.setTaskId("task-1");
        question.setTitle("确认范围");
        question.setBody("请选择范围");
        question.setOptionsJson("[]");
        return question;
    }
}
