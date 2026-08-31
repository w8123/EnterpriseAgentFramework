package com.enterprise.ai.control.pageworkbench.application;

import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PublishedWorkflowView;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;

@Service
@RequiredArgsConstructor
public class PageWorkbenchPublishedApplicationService {

    private final PageWorkbenchRuntimePort runtimeClient;

    public List<PublishedWorkflowView> list(String projectCode, String pageKey) {
        if (!StringUtils.hasText(projectCode)) {
            throw new IllegalArgumentException("projectCode is required");
        }
        try {
            List<PublishedWorkflowView> body = runtimeClient.pageWorkbenchPublished(
                    projectCode.trim(),
                    StringUtils.hasText(pageKey) ? pageKey.trim() : null).getBody();
            return body == null ? List.of() : body;
        } catch (FeignException ex) {
            throw new ResponseStatusException(
                    BAD_GATEWAY,
                    "Runtime published business-page workflows are unavailable: " + ex.status(),
                    ex);
        }
    }
}
