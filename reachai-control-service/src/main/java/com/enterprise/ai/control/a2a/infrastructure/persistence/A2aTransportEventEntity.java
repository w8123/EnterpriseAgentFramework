package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_a2a_transport_event")
public class A2aTransportEventEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String requestId;
    private String direction;
    private Long principalId;
    private Long publicationId;
    private Long remoteAgentId;
    private Long taskRefId;
    private String protocolBinding;
    private String protocolVersion;
    private String operation;
    private Boolean success;
    private Integer httpStatus;
    private String protocolErrorCode;
    private Long latencyMs;
    private Long requestBytes;
    private Long responseBytes;
    private String requestPayloadSha256;
    private String responsePayloadSha256;
    private String requestSummaryJson;
    private String responseSummaryJson;
    private String remoteAddressSha256;
    private String errorSummary;
    private String traceId;
    private LocalDateTime createdAt;
}
