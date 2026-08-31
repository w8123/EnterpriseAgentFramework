package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.mcp.domain.client.McpClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface McpClientRepository {

    Optional<McpClient> findById(long id);

    Optional<McpClient> findByApiKeyHash(String apiKeyHash);

    List<McpClient> findByPublication(long publicationId);

    McpClient save(McpClient client);

    void touchLastUsed(long clientId, LocalDateTime now);
}
