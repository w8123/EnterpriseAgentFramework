package com.enterprise.ai.control.a2a.application.port;

import com.enterprise.ai.control.a2a.domain.trust.A2aTrustProfile;

import java.util.List;
import java.util.Optional;

public interface A2aTrustProfileRepository {

    Optional<A2aTrustProfile> findById(long id);

    Optional<A2aTrustProfile> findActiveById(long id);

    boolean existsByProfileKey(String profileKey, Long excludingId);

    Page findPage(String search, String status, int limit, int offset);

    A2aTrustProfile save(A2aTrustProfile profile);

    record Page(List<A2aTrustProfile> items, long total) {
        public Page {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
