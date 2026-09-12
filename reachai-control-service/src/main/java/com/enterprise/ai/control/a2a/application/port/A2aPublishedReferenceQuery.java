package com.enterprise.ai.control.a2a.application.port;

import java.util.List;

/** Published A2A revisions pin an exact Agent configuration owned by Runtime. */
public interface A2aPublishedReferenceQuery {
    Evidence inspect();

    record Binding(Long publicationId, String name, String agentId, Long agentConfigVersionId) { }

    record Evidence(boolean complete, List<Binding> bindings) {
        public Evidence { bindings = List.copyOf(bindings); }
    }
}
