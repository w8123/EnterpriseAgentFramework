package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.mcp.application.port.McpEmployeeAccessRepository;
import com.enterprise.ai.control.memory.PersonalMemoryProjectCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** Project memory layers follow the business systems opened to employees' AI tools. */
@Component
@RequiredArgsConstructor
public class EmployeeSystemMemoryProjectCatalog implements PersonalMemoryProjectCatalog {

    private final McpEmployeeAccessRepository accessRepository;

    @Override
    public List<ProjectOption> projects() {
        return accessRepository.findEnabled().stream()
                .map(access -> new ProjectOption(access.projectCode(), access.label()))
                .toList();
    }
}
