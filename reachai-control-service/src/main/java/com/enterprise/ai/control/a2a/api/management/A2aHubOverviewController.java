package com.enterprise.ai.control.a2a.api.management;

import com.enterprise.ai.control.a2a.application.overview.A2aHubOverviewApplicationService;
import com.enterprise.ai.control.a2a.application.overview.A2aHubOverviewView;
import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/a2a-hub")
@RequiredArgsConstructor
public class A2aHubOverviewController {

    private final A2aHubOverviewApplicationService service;
    private final A2aHubManagementAccess access;

    @GetMapping("/overview")
    public ResponseEntity<A2aHubOverviewView> overview(HttpServletRequest request) {
        access.require(request, A2aHubManagementAccess.READ);
        return ResponseEntity.ok(service.overview());
    }
}
