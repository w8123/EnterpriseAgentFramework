package com.enterprise.ai.control.mcp.api.management;

import com.enterprise.ai.control.mcp.application.overview.McpHubOverviewReader;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mcp/overview")
public class McpHubOverviewController {

    private final McpHubOverviewReader overviewReader;
    private final McpHubManagementAccess access;

    public McpHubOverviewController(McpHubOverviewReader overviewReader,
                                    McpHubManagementAccess access) {
        this.overviewReader = overviewReader;
        this.access = access;
    }

    @GetMapping
    public McpHubOverviewReader.McpHubOverviewView overview(
            HttpServletRequest request,
            @RequestParam(required = false) Integer days) {
        access.require(request, McpHubManagementAccess.READ);
        return overviewReader.read(days == null ? 7 : days);
    }
}
