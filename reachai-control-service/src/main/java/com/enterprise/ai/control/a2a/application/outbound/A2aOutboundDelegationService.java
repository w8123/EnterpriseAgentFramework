package com.enterprise.ai.control.a2a.application.outbound;

import com.enterprise.ai.control.a2a.application.port.A2aOutboundProtocolCodec;
import com.enterprise.ai.control.a2a.application.port.A2aRemoteProtocolTransport;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import static com.enterprise.ai.control.a2a.application.outbound.A2aOutboundDelegationContracts.SendRequest;
import static com.enterprise.ai.control.a2a.application.outbound.A2aOutboundDelegationContracts.SendResponse;
import static com.enterprise.ai.control.a2a.application.port.A2aOutboundProtocolCodec.EncodedOperation;

/** Orchestrates the network call strictly outside database transactions. */
@Service
@RequiredArgsConstructor
public class A2aOutboundDelegationService {

    private final A2aOutboundTaskStateService taskState;
    private final A2aRemoteProtocolTransport transport;
    private final A2aOutboundProtocolCodec protocolCodec;

    public SendResponse send(SendRequest request) {
        A2aOutboundTaskStateService.PreparedSend prepared = taskState.prepare(request);
        if (prepared.replay()) return prepared.replayResponse();
        taskState.markDeliveryStarted(prepared);
        A2aRemoteProtocolTransport.Response response = null;
        try {
            response = transport.sendMessage(
                    prepared.remoteInterface(), prepared.authentication(), prepared.credential(),
                    prepared.encoded().requestBody(), prepared.policy().maxResponseBytes());
            A2aOutboundProtocolCodec.DecodedResponse decoded =
                    protocolCodec.decodeSendResponse(response.body());
            return taskState.complete(prepared, response, decoded);
        } catch (A2aDomainException failure) {
            return taskState.fail(prepared, response, failure);
        } catch (RuntimeException failure) {
            A2aDomainException safe = new A2aDomainException(
                    "A2A_OUTBOUND_CALL_FAILED", "the outbound A2A call failed safely");
            safe.initCause(failure);
            return taskState.fail(prepared, response, safe);
        }
    }

    public SendResponse poll(long taskRefId, String workerId) {
        A2aOutboundTaskStateService.PreparedTaskOperation prepared;
        try {
            prepared = taskState.preparePoll(taskRefId, workerId);
        } catch (A2aDomainException failure) {
            taskState.failClaimedPollPreparation(taskRefId, workerId, failure);
            throw failure;
        }
        if (prepared.replay()) return prepared.replayResponse();
        A2aRemoteProtocolTransport.Response response = null;
        try {
            response = transport.getTask(
                    prepared.remoteInterface(), prepared.authentication(), prepared.credential(),
                    prepared.remoteTaskId(), prepared.outboundExecution().historyLength(),
                    prepared.policy().maxResponseBytes());
            A2aOutboundProtocolCodec.DecodedResponse decoded =
                    protocolCodec.decodeTaskResponse(response.body());
            return taskState.completeTaskOperation(prepared, response, decoded, new byte[0]);
        } catch (A2aDomainException failure) {
            return taskState.failTaskOperation(prepared, response, new byte[0], failure);
        } catch (RuntimeException failure) {
            A2aDomainException safe = new A2aDomainException(
                    "A2A_OUTBOUND_CALL_FAILED", "the outbound A2A poll failed safely");
            safe.initCause(failure);
            return taskState.failTaskOperation(prepared, response, new byte[0], safe);
        }
    }

    public SendResponse cancel(
            String executionId, String actorType, String actorId) {
        A2aOutboundTaskStateService.PreparedTaskOperation prepared =
                taskState.prepareCancellation(executionId, actorType, actorId);
        if (prepared.replay()) return prepared.replayResponse();
        byte[] requestBody = new byte[0];
        A2aRemoteProtocolTransport.Response response = null;
        try {
            EncodedOperation encoded = protocolCodec.encodeCancelTask(
                    prepared.remoteInterface().tenant(), prepared.remoteTaskId());
            requestBody = encoded.requestBody();
            response = transport.cancelTask(
                    prepared.remoteInterface(), prepared.authentication(), prepared.credential(),
                    prepared.remoteTaskId(), requestBody,
                    prepared.policy().maxResponseBytes());
            A2aOutboundProtocolCodec.DecodedResponse decoded =
                    protocolCodec.decodeTaskResponse(response.body());
            return taskState.completeTaskOperation(
                    prepared, response, decoded, requestBody);
        } catch (A2aDomainException failure) {
            return taskState.failTaskOperation(
                    prepared, response, requestBody, failure);
        } catch (RuntimeException failure) {
            A2aDomainException safe = new A2aDomainException(
                    "A2A_OUTBOUND_CALL_FAILED", "the outbound A2A cancel call failed safely");
            safe.initCause(failure);
            return taskState.failTaskOperation(
                    prepared, response, requestBody, safe);
        }
    }

    public boolean expire(String executionId) {
        return taskState.expireOutboundTask(executionId);
    }
}
