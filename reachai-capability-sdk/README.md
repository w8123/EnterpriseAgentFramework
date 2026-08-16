# ReachAI Capability SDK

`reachai-capability-sdk` is the JDK 8-compatible declaration contract used by
business modules that expose capabilities to ReachAI. It has no Spring runtime
dependency.

## Dependency

```xml
<dependency>
  <groupId>com.enterprise.ai</groupId>
  <artifactId>reachai-capability-sdk</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Use the version and installation command declared by the ReachAI onboarding
manifest. Do not guess a Maven repository URL.

## Minimal capability

```java
import com.enterprise.ai.reach.sdk.annotation.ReachCapability;
import com.enterprise.ai.reach.sdk.annotation.ReachOutput;
import com.enterprise.ai.reach.sdk.annotation.ReachParam;
import com.enterprise.ai.reach.sdk.annotation.ReachSideEffectLevel;

public class OrderQueryService {

    @ReachCapability(
            name = "order.query",
            title = "Query order",
            description = "Read one order visible to the current business user",
            domain = "order",
            sideEffect = ReachSideEffectLevel.READ,
            requiredRoles = {"ORDER_READ"},
            timeoutMs = 3000)
    public OrderView query(
            @ReachParam(name = "orderId", required = true) String orderId) {
        return loadAuthorizedOrder(orderId);
    }

    public static class OrderView {
        @ReachOutput(description = "Stable business order id")
        private String orderId;

        @ReachOutput(description = "Current order status")
        private String status;
    }
}
```

## Annotation reference

### `@ReachCapability` — methods only

| Member | Type | Default | Meaning |
| --- | --- | --- | --- |
| `name` | `String` | `""` | Stable capability key. Prefer an explicit domain name such as `order.query`. |
| `title` | `String` | `""` | Human-readable title. |
| `description` | `String` | `""` | Business behavior and authorization boundary. |
| `domain` | `String` | `""` | Business domain. |
| `module` | `String` | `""` | Owning business module. |
| `tags` | `String[]` | `{}` | Search and governance tags. |
| `sideEffect` | `ReachSideEffectLevel` | `WRITE` | `READ`, `WRITE`, or `IRREVERSIBLE`. Set it explicitly. |
| `requiredRoles` | `String[]` | `{}` | Business roles required by the capability. The host must still enforce authorization. |
| `timeoutMs` | `int` | `0` | Timeout override; `0` uses platform policy. |
| `retryLimit` | `int` | `-1` | Retry override; `-1` uses platform policy. |

### `@ReachParam` — parameters or request fields

| Member | Type | Default | Meaning |
| --- | --- | --- | --- |
| `name` | `String` | `""` | Stable input name. Always set it for simple parameters in JDK 8 builds. |
| `description` | `String` | `""` | Business meaning and constraints. |
| `required` | `boolean` | `false` | Whether the value is required. |
| `example` | `String` | `""` | Non-secret example value. |
| `sourceHint` | `String` | `""` | Suggested source of the value. |
| `dictType` | `String` | `""` | Optional dictionary/catalog key. |
| `sensitive` | `boolean` | `false` | Marks data that must be redacted or handled as sensitive. |

### `@ReachOutput` — response DTO fields only

| Member | Type | Default | Meaning |
| --- | --- | --- | --- |
| `description` | `String` | `""` | Meaning of the output field. |
| `example` | `String` | `""` | Non-secret example value. |
| `sensitive` | `boolean` | `false` | Marks output requiring sensitive-data handling. |

Do not put `@ReachOutput` on methods. Do not treat annotation roles as a
replacement for the business application's existing authorization checks.

## Business-memory resolver contracts

Use these JDK 8 DTOs when a Knowledge business index is enabled for Agent
memory:

| Type | Purpose |
| --- | --- |
| `ReachBusinessMemoryResolverRequest` | `resourceType`, `resourceId`, and the non-authoritative `expectedSourceVersion` index hint. Its fields already carry `@ReachParam` metadata. |
| `ReachBusinessMemoryAccessScope` | Fail-closed tenant, user, resource-id, and row-visibility assertion created from the authoritative business row or an explicitly configured single-tenant deployment binding. |
| `ReachBusinessMemoryResolution` | The current, user-authorized source record and current `sourceVersion`. Its fields carry `@ReachOutput` metadata. |

The resolver is still an ordinary read-only `@ReachCapability`. The business
host must verify the short-lived `X-ReachAI-Invocation-Token`, enforce tenant and
row-level authorization for its current user, create the result with
`ReachBusinessMemoryResolution.createAuthorized(...)`, and return only fields
that user may see. The Spring Starter enforces this contract for capabilities
tagged `business-memory-resolver` or using the standard resolver request/return
types; the compatibility `create(...)` factory does not carry row-authorization
proof and is rejected on that HTTP invocation path.
Never treat `expectedSourceVersion` or Knowledge projection text as current
truth. See the starter README for a complete resolver example.

## Body-bound project request signing

`ReachAiProjectRequestSigner` and `ReachAiProjectRequestHeaders` are JDK 8
helpers for project-scoped business mutation APIs. The canonical
`REACHAI_PROJECT_REQUEST_V1` message includes method, path, projectCode, appKey,
timestamp, nonce, and SHA-256 of the exact transmitted body. Sign only after
serialization, then transmit those same bytes; reserializing an object after
signing is expected to fail verification. The Spring Starter's
`ReachAiBusinessIndexClient` implements this contract for structured Business
Index upsert, batch upsert, and delete.
