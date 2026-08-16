# ReachAI Spring Boot Starter

`reachai-spring-boot2-starter` registers the business project and instance,
sends heartbeats, exposes the signed capability-sync callback, scans declared
capabilities, and provides the server-side Embed Token client.

Despite the legacy artifact name, the published starter carries both Spring
Boot 2 `spring.factories` metadata and Spring Boot 3
`AutoConfiguration.imports` metadata. Its bytecode remains Java 8-compatible.

## Dependency

```xml
<dependency>
  <groupId>com.enterprise.ai</groupId>
  <artifactId>reachai-spring-boot2-starter</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

The runnable Spring Boot module needs the starter. Modules that declare
annotations also need `reachai-capability-sdk`. Use the exact artifacts and
hashes declared by the onboarding manifest.

## Minimal configuration

```yaml
reachai:
  registry:
    url: https://reachai.example.com
    app-key: ${REACHAI_REGISTRY_APP_KEY}
    app-secret: ${REACHAI_REGISTRY_APP_SECRET}
  project:
    code: order-service
    name: Order Service
    base-url: https://orders.example.com
  capability:
    scan-mode: ANNOTATED_ONLY
    scan-packages:
      - com.company.order
```

`project.base-url` must be reachable from ReachAI. Capability synchronization
is an explicit task/API Management action; it does not run automatically at
application startup.

## Server-side Embed Token broker

Resolve the current user with the business application's own authentication,
then call the starter client. Never accept `principal` from the browser.

```java
@RestController
public class ReachAiEmbedTokenController {

    private final ReachAiEmbedTokenClient tokenClient;
    private final BusinessCurrentUser currentUser;

    @PostMapping("/api/reachai/embed-token")
    public ReachAiEmbedTokenResult exchange(
            @RequestBody BrowserPageContext page) {
        BusinessUser user = currentUser.requireAuthenticated();
        ReachAiEmbedPrincipal principal = new ReachAiEmbedPrincipal(
                user.id(), user.globalId(), user.displayName(),
                user.roles(), Collections.<String, Object>emptyMap());
        return tokenClient.exchange(new ReachAiEmbedTokenRequest(
                "orders-page-copilot",
                page.getPageKey(),
                page.getPageInstanceId(),
                page.getRoute(),
                page.getOrigin(),
                principal));
    }
}
```

### Public Embed types

| Type | Contract |
| --- | --- |
| `ReachAiEmbedTokenClient` | `ReachAiEmbedTokenResult exchange(ReachAiEmbedTokenRequest request)`; signs and sends the exchange request. |
| `ReachAiEmbedTokenRequest` | `agentId`, optional `pageKey`, `pageInstanceId`, optional `route`, `origin`, and server-resolved `ReachAiEmbedPrincipal`. |
| `ReachAiEmbedPrincipal` | `externalUserId`, optional `globalUserId`, `userName`, `roles`, and attributes. |
| `ReachAiEmbedTokenResult` | Short-lived `token`, `expiresIn`, and non-secret `sessionHint`. |

The broker stays behind normal business authentication. The separate
`/api/reachai/embed/**` proxy must preserve the Embed Bearer and must not pass it
through the business OAuth2 resource-server parser.

## Registry callback boundary

ReachAI calls `POST /reachai/registry/capabilities/sync` with signed
`X-ReachAI-*` headers. Gateways may bypass ordinary business login and CSRF for
that exact callback, but they must preserve the signature headers so the
starter can authenticate it. A login whitelist must not turn the callback into
an unsigned public endpoint.

## Business-memory resolver

A business-memory resolver is a normal read-only Capability with one stronger
contract: it must re-read and re-authorize the source row under the current
signed ReachAI user. The Knowledge hit is discovery data only.

```java
import com.enterprise.ai.reach.sdk.annotation.ReachCapability;
import com.enterprise.ai.reach.sdk.annotation.ReachParam;
import com.enterprise.ai.reach.sdk.annotation.ReachSideEffectLevel;
import com.enterprise.ai.reach.sdk.auth.ReachAiInvocationClaims;
import com.enterprise.ai.reach.sdk.memory.ReachBusinessMemoryAccessScope;
import com.enterprise.ai.reach.sdk.memory.ReachBusinessMemoryResolution;
import com.enterprise.ai.reach.sdk.memory.ReachBusinessMemoryResolverRequest;
import com.enterprise.ai.reach.spring.ReachAiInvocationContextHolder;

import java.util.LinkedHashMap;
import java.util.Map;

@ReachCapability(
        name = "order.memory.resolve",
        title = "Resolve current order for Agent memory",
        description = "Returns only the current order fields visible to the signed business user",
        domain = "order",
        tags = {"business-memory-resolver"},
        sideEffect = ReachSideEffectLevel.READ,
        timeoutMs = 3000)
public ReachBusinessMemoryResolution resolveOrder(
        @ReachParam(name = "request", required = true)
        ReachBusinessMemoryResolverRequest request) {
    ReachAiInvocationClaims claims = ReachAiInvocationContextHolder
            .getRequired()
            .getClaims();
    if (claims.getTenantId() == null || claims.getTenantId().trim().isEmpty()
            || claims.getExternalUserId() == null
            || claims.getExternalUserId().trim().isEmpty()
            || !"order-service".equals(claims.getProjectCode())
            || !"order".equals(request.getResourceType())) {
        throw new SecurityException("business-memory scope denied");
    }

    // This query must include both the authoritative tenant predicate and the
    // business system's normal current-user row-level ACL.
    Order order = orderQuery.requireVisibleTo(
            claims.getTenantId(), request.getResourceId(), claims.getExternalUserId());

    ReachBusinessMemoryAccessScope accessScope =
            ReachBusinessMemoryAccessScope.authorizeRow(
                    claims.getTenantId(),
                    order.getTenantId(), // authoritative row/deployment tenant
                    claims.getExternalUserId(),
                    claims.getProjectCode(),
                    request.getResourceType(),
                    request.getResourceId(),
                    String.valueOf(order.getId()),
                    true);

    Map<String, Object> visible = new LinkedHashMap<String, Object>();
    visible.put("orderNo", order.getOrderNo());
    visible.put("status", order.getStatus());
    visible.put("updatedAt", order.getUpdatedAt());
    return ReachBusinessMemoryResolution.createAuthorized(
            accessScope,
            "order-service",
            String.valueOf(order.getVersion()),
            visible);
}
```

ReachAI Tool 请求体是扁平输入。对于只有一个复杂参数、且 DTO 字段带
`@ReachParam` 的 Capability，Starter 会直接用整个扁平输入绑定该 DTO；显式的
`{"request": {...}}` 包装仍然兼容。普通标量和未声明字段契约的 DTO 不会触发此回退。

`expectedSourceVersion` is useful for observability or conditional reads, but a
version mismatch is not permission to return stale projection data. Return the
current source version. For deleted, forbidden, or unavailable records, return
the business application's normal failure; Runtime removes the projection text
and does not fall back to it.

The Starter treats the `business-memory-resolver` tag, the standard resolver
request type, or the standard resolution return type as an enforced security
contract, so omitting the tag does not bypass the guard. Before business code runs it requires a signed tenant, signed user,
and complete resolver request. After invocation it requires an authorized SDK
scope and exact tenant/project/resource agreement. A multi-tenant host must pass
the tenant read from its authoritative row to `authorizeRow(...)`. A truly
single-tenant host may pass an explicitly configured deployment tenant, but it
must not copy an unchecked tenant from the Knowledge hit or request body.

## Project-signed Business Index sync

The Starter publishes `ReachAiBusinessIndexClient` when the normal Registry
properties are available. It reuses the enrolled project credential but uses a
separate body-bound protocol (`REACHAI_PROJECT_REQUEST_V1`), not the legacy
heartbeat signature:

```java
Map<String, Object> record = new LinkedHashMap<String, Object>();
record.put("bizId", "O-20260815-1");
record.put("sourceVersion", "18");
record.put("fields", Collections.singletonMap("status", "PAID"));

reachAiBusinessIndexClient.upsert("orders_idx", record);
reachAiBusinessIndexClient.batchUpsert("orders_idx", Arrays.asList(record));
reachAiBusinessIndexClient.deleteRecord("orders_idx", "O-20260815-1");
```

Every request signs the exact JSON bytes together with method, canonical public
path, normalized `projectCode`, `appKey`, timestamp, and nonce. Control asks
Capability (the credential owner) to verify the signature, then forwards only a
verified project identity to Knowledge. Knowledge rejects indexes whose stored
`projectCode` differs. Automated attachment upload is intentionally absent;
console attachment upload uses the platform-session BFF until a streaming
detached-signature protocol is defined.
