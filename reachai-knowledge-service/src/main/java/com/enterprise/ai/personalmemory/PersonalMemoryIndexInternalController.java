package com.enterprise.ai.personalmemory;

import com.enterprise.ai.personalmemory.PersonalMemoryIndexService.ApplyResult;
import com.enterprise.ai.personalmemory.PersonalMemoryIndexService.IndexEvent;
import com.enterprise.ai.personalmemory.PersonalMemoryIndexService.QueryRequest;
import com.enterprise.ai.personalmemory.PersonalMemoryIndexService.QueryResult;
import com.enterprise.ai.personalmemory.PersonalMemoryIndexService.OwnerStatus;
import com.enterprise.ai.personalmemory.PersonalMemoryIndexService.OwnerStatusRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HMAC-filtered Control-only projection endpoint. */
@RestController
@RequestMapping("/internal/knowledge/personal-memories")
@RequiredArgsConstructor
public class PersonalMemoryIndexInternalController {

    private final PersonalMemoryIndexService service;

    @PostMapping("/events")
    public ResponseEntity<ApplyResult> apply(@RequestBody IndexEvent event) {
        return ResponseEntity.ok(service.apply(event));
    }

    @PostMapping("/query")
    public ResponseEntity<QueryResult> query(@RequestBody QueryRequest request) {
        return ResponseEntity.ok(service.query(request));
    }

    @PostMapping("/owner-status")
    public ResponseEntity<OwnerStatus> ownerStatus(@RequestBody OwnerStatusRequest request) {
        return ResponseEntity.ok(service.ownerStatus(request));
    }
}
