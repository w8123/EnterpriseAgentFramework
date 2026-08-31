package com.enterprise.ai.control.a2a.api.protocol;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Selects a response representation without coupling protocol handlers to a single HTTP client.
 * Explicit A2A media-type requests retain the protocol representation while generic clients and
 * gateways using an absent or wildcard Accept header receive ordinary JSON.
 */
final class A2aResponseMediaTypes {

    private static final MediaType A2A =
            MediaType.parseMediaType(A2aAgentCardController.A2A_MEDIA_TYPE);

    private A2aResponseMediaTypes() {
    }

    static MediaType negotiate(HttpServletRequest request) {
        String accept = request == null ? null : request.getHeader(HttpHeaders.ACCEPT);
        if (accept == null || accept.isBlank()) {
            return MediaType.APPLICATION_JSON;
        }
        try {
            List<MediaType> accepted = new ArrayList<>(MediaType.parseMediaTypes(accept));
            accepted.sort(Comparator
                    .comparingDouble(MediaType::getQualityValue).reversed()
                    .thenComparing(Comparator.comparingInt(A2aResponseMediaTypes::specificity)
                            .reversed()));
            for (MediaType candidate : accepted) {
                if (candidate.getQualityValue() <= 0) {
                    continue;
                }
                if (candidate.isWildcardType() && candidate.isWildcardSubtype()) {
                    return MediaType.APPLICATION_JSON;
                }
                if (candidate.isCompatibleWith(A2A)) {
                    return A2A;
                }
                if (candidate.isCompatibleWith(MediaType.APPLICATION_JSON)) {
                    return MediaType.APPLICATION_JSON;
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Spring will normally reject an invalid Accept header before controller dispatch.
        }
        return A2A;
    }

    private static int specificity(MediaType mediaType) {
        if (mediaType.isWildcardType()) {
            return 0;
        }
        return mediaType.isWildcardSubtype() ? 1 : 2;
    }
}
