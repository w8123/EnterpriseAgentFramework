package com.enterprise.ai.control.a2a.application.port;

import java.net.URI;

public interface A2aRemoteCardFetcher {

    URI normalize(URI cardUri);

    FetchResult fetch(URI cardUri);

    record FetchResult(
            URI normalizedUri,
            byte[] body,
            String contentType,
            String httpEtag,
            String httpLastModified,
            String tlsIdentitySha256,
            String networkEvidenceJson) {

        public FetchResult {
            body = body == null ? new byte[0] : body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }
}
