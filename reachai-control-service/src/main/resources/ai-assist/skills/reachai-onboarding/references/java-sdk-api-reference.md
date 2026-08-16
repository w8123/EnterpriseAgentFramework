# Java SDK API Reference

This is the public, source-independent API reference for the Java artifacts
declared by the onboarding manifest.

## Capability declaration SDK

Add `reachai-capability-sdk` to each module that declares capabilities.

```java
@ReachCapability(
    name = "order.query",
    title = "Query order",
    description = "Read one authorized order",
    domain = "order",
    sideEffect = ReachSideEffectLevel.READ,
    requiredRoles = {"ORDER_READ"},
    timeoutMs = 3000,
    retryLimit = 0)
public OrderView query(
    @ReachParam(name = "orderId", required = true) String orderId) {
    return orderService.query(orderId);
}
```

`@ReachCapability` targets methods and exposes:

```java
String name() default "";
String title() default "";
String description() default "";
String domain() default "";
String module() default "";
String[] tags() default {};
ReachSideEffectLevel sideEffect() default ReachSideEffectLevel.WRITE;
String[] requiredRoles() default {};
int timeoutMs() default 0;
int retryLimit() default -1;
```

`@ReachParam` targets parameters or request fields and exposes:

```java
String name() default "";
String description() default "";
boolean required() default false;
String example() default "";
String sourceHint() default "";
String dictType() default "";
boolean sensitive() default false;
```

`@ReachOutput` targets response DTO fields only and exposes:

```java
String description() default "";
String example() default "";
boolean sensitive() default false;
```

Always set an explicit `@ReachParam(name = "...")` for simple parameters in
JDK 8 builds. Keep business authorization inside the business method; metadata
does not replace it.

## Business-memory resolver API

An Agent-enabled Knowledge business index must point to a read-only resolver
Capability. Use the public JDK 8 DTOs:

```java
ReachBusinessMemoryResolverRequest request;
ReachBusinessMemoryResolution resolution;
```

`ReachBusinessMemoryResolverRequest` exposes `resourceType`, `resourceId`, and
`expectedSourceVersion`; its fields already carry `@ReachParam` metadata.
Use `ReachBusinessMemoryAccessScope.authorizeRow(...)` with the signed tenant,
the tenant read from the authoritative business row (or an explicitly configured
single-tenant deployment binding), the signed current user, and the actual row
id/visibility result. Then call
`ReachBusinessMemoryResolution.createAuthorized(...)` with source system,
current source version, and a map containing only fields visible to the signed
current user. The compatibility `create(...)` factory is rejected by the Starter
for capabilities tagged `business-memory-resolver` or using the standard
resolver request/return types because it carries no row-authorization proof.
Resolution fields carry `@ReachOutput` metadata.

Inside the resolver, read `ReachAiInvocationContextHolder.getRequired()` from
the starter, enforce the business application's tenant predicate and normal
current-user row-level ACL, and re-read the source row. The version in the
request is only an index hint. For a
deleted, forbidden, or unavailable row, return the normal business failure;
never fall back to Knowledge projection text.

## Spring Boot Starter Embed API

The runnable application module can inject:

```java
ReachAiEmbedTokenResult ReachAiEmbedTokenClient.exchange(
    ReachAiEmbedTokenRequest request);
```

The request constructor is:

```java
new ReachAiEmbedTokenRequest(
    String agentId,
    String pageKey,
    String pageInstanceId,
    String route,
    String origin,
    ReachAiEmbedPrincipal principal);
```

The principal constructor is:

```java
new ReachAiEmbedPrincipal(
    String externalUserId,
    String globalUserId,
    String userName,
    List<String> roles,
    Map<String, Object> attributes);
```

Resolve the principal from the authenticated server request. Never deserialize
it from browser input. Return only `ReachAiEmbedTokenResult.getToken()`,
`getExpiresIn()` and, when useful, `getSessionHint()` to the browser.

## Installation and configuration

Use the manifest `sdkArtifacts` entries and their SHA-256 values. For module
placement, configuration, scan boundaries, callback routing and verification,
continue with `references/java-sdk-access.md`.
