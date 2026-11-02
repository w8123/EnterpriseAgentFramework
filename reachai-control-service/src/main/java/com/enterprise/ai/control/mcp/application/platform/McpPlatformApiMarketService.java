package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformApiMarketPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Selecting a market API for a project records integration intent in the configuring state (making it ready still needs
 * a verified credential), and marking an integration broken or disabled only stops its use, so both run directly. A lost
 * result is reported as unknown, never resent.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformApiMarketService {
    private final McpPlatformApiMarketPort market;

    public ResponseEntity<?> read(ConnectionIdentity actor, McpPlatformApiMarketPort.Read read, String key, Map<String, String> query) {
        return market.read(actor.session(), read, key, query);
    }

    public ResponseEntity<?> select(ConnectionIdentity actor, String entryKey, Map<String, Object> body) {
        return PlatformAiDirectWrites.once(() -> market.select(actor.session(), entryKey, body),
                "选择结果未确认，请用 reachai_api_market_integrations 查看这个项目的接入；不要直接重试。");
    }

    public ResponseEntity<?> updateStatus(ConnectionIdentity actor, long integrationId, Map<String, Object> body) {
        return PlatformAiDirectWrites.once(() -> market.updateStatus(actor.session(), integrationId, body),
                "状态修改结果未确认，请用 reachai_api_market_integration_get 重新读取。");
    }
}
