package com.enterprise.ai.runtime.runops.consolecapability;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable idempotency and result record for one Console business-method trial call. */
@Data
@TableName("runtime_console_capability_invocation")
public class ConsoleCapabilityInvocationEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String invocationId;
    private String platformActorId;
    private Long projectId;
    private String projectCode;
    private String qualifiedName;
    private String targetType;
    private String environment;
    private String sourceSetRevision;
    private Long connectionRevision;
    private String credentialRevision;
    private Integer httpStatus;
    private String expectedContractHash;
    private String expectedExecutionRevision;
    private String inputFingerprint;
    private Long deadlineEpochMs;
    private Long runId;
    private String traceId;
    private String identityMode;
    private String sideEffect;
    private Boolean confirmedSideEffect;
    private String status;
    private String dispatchStage;
    private String resultJson;
    private Boolean resultTruncated;
    private LocalDateTime resultExpiresAt;
    private String errorCode;
    private String errorMessage;
    private Long latencyMs;
    private LocalDateTime startedAt;
    private LocalDateTime dispatchedAt;
    private LocalDateTime endedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
