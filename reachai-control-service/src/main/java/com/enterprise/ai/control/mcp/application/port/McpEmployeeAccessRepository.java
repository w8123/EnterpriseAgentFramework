package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.mcp.domain.employee.McpEmployeeAccess;

import java.util.List;
import java.util.Optional;

public interface McpEmployeeAccessRepository {

    List<McpEmployeeAccess> findEnabled();

    Optional<McpEmployeeAccess> findByProjectCode(String projectCode);

    Optional<McpEmployeeAccess> findByPublicationId(long publicationId);

    McpEmployeeAccess save(McpEmployeeAccess access);
}
