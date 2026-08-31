package com.enterprise.ai.control.a2a.application.publication;

import com.enterprise.ai.control.a2a.application.port.A2aPublicationRepository;
import com.enterprise.ai.control.a2a.domain.publication.A2aPublicationAddress;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class A2aPublishedCardService {

    private final A2aPublicationRepository repository;

    @Transactional(readOnly = true)
    public Optional<A2aPublicationRepository.PublishedCard> findByHost(String hostHeader) {
        return repository.findPublishedCardByHost(A2aPublicationAddress.normalizeHostHeader(hostHeader));
    }
}
