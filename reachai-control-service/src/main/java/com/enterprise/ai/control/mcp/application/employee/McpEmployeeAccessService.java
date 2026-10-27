package com.enterprise.ai.control.mcp.application.employee;

import com.enterprise.ai.control.mcp.application.port.McpEmployeeAccessRepository;
import com.enterprise.ai.control.mcp.application.port.McpPublicationRepository;
import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.domain.employee.McpEmployeeAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Operators choose which publication employees of a business system may use from their own AI tools. */
@Service
public class McpEmployeeAccessService {

    private final McpEmployeeAccessRepository accessRepository;
    private final McpPublicationRepository publicationRepository;

    public McpEmployeeAccessService(McpEmployeeAccessRepository accessRepository,
                                    McpPublicationRepository publicationRepository) {
        this.accessRepository = accessRepository;
        this.publicationRepository = publicationRepository;
    }

    public Optional<McpEmployeeAccess> find(long publicationId) {
        requirePublication(publicationId);
        return accessRepository.findByPublicationId(publicationId);
    }

    @Transactional
    public McpEmployeeAccess save(long publicationId, Long projectId, String projectCode, String displayName,
                                  boolean enabled, Boolean writeApprovalRequired) {
        requirePublication(publicationId);
        Optional<McpEmployeeAccess> existing = accessRepository.findByPublicationId(publicationId);
        // Omitted keeps the current choice; a new system starts with approval on.
        boolean approval = writeApprovalRequired != null ? writeApprovalRequired
                : existing.map(McpEmployeeAccess::writeApprovalRequired).orElse(true);
        return accessRepository.save(new McpEmployeeAccess(existing.map(McpEmployeeAccess::id).orElse(null),
                publicationId, projectId != null && projectId > 0 ? projectId : null, projectCode, displayName,
                enabled, approval));
    }

    private void requirePublication(long publicationId) {
        if (publicationRepository.findById(publicationId).isEmpty()) {
            throw new McpDomainException("MCP_PUBLICATION_NOT_FOUND", "publication not found");
        }
    }
}
