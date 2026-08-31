package com.enterprise.ai.control.a2a.application.port;

import com.enterprise.ai.control.a2a.domain.identity.A2aCredential;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteInterface;

/** HTTPS A2A protocol client boundary. Credential plaintext never crosses this port. */
public interface A2aRemoteProtocolTransport {

    Response sendMessage(
            A2aRemoteInterface target,
            A2aRemoteAuthenticationPlanner.Binding authentication,
            A2aCredential credential,
            byte[] requestBody,
            int maxResponseBytes);

    Response getTask(
            A2aRemoteInterface target,
            A2aRemoteAuthenticationPlanner.Binding authentication,
            A2aCredential credential,
            String remoteTaskId,
            int historyLength,
            int maxResponseBytes);

    Response cancelTask(
            A2aRemoteInterface target,
            A2aRemoteAuthenticationPlanner.Binding authentication,
            A2aCredential credential,
            String remoteTaskId,
            byte[] requestBody,
            int maxResponseBytes);

    record Response(
            byte[] body,
            String contentType,
            int httpStatus,
            String tlsIdentitySha256,
            long latencyMs) {

        public Response {
            body = body == null ? new byte[0] : body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }
}
