# Embed Gateway Examples

The broker endpoint and the Embed proxy have deliberately different security
semantics. The broker reads the current business identity; the proxy forwards a
ReachAI Embed Token. Replace upstream names, hosts and route IDs below with the
business system's real topology.

The onboarding Skill also ships copy-ready files under `examples/gateway/`.
Use those files when editing a project so the security chain, full YAML parent
structure and header-preservation rules are not reconstructed from memory.

## Spring Cloud Gateway with WebFlux Resource Server

Keep the broker on the normal business security chain. Put the Embed proxy on a
higher-priority chain that does not enable `oauth2ResourceServer()`.

```java
@Bean
@Order(Ordered.HIGHEST_PRECEDENCE)
SecurityWebFilterChain reachAiEmbedProxySecurity(ServerHttpSecurity http) {
    return http
        .securityMatcher(ServerWebExchangeMatchers.pathMatchers("/api/reachai/embed/**"))
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .authorizeExchange(exchange -> exchange.anyExchange().permitAll())
        .build();
}
```

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: reachai-embed-proxy
          uri: https://reachai.example.com
          predicates:
            - Path=/api/reachai/embed/**
          filters:
            - RewritePath=/api/reachai/embed/?(?<segment>.*), /api/embed/${segment}
            - DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_FIRST
```

Do not include `/api/reachai/embed/**` in `IgnoreUrlsRemoveJwtFilter`,
`RemoveJwtFilter`, `RemoveRequestHeader=Authorization`, or any custom filter
that clears `Authorization`. The route is anonymous only to business login
validation; it still needs the Embed Token header.

## Nginx

Keep the broker location protected by the existing business authentication
layer. The Embed location forwards the browser's `Authorization` header as-is.

```nginx
location /api/reachai/embed/ {
    proxy_pass https://reachai.example.com/api/embed/;
    proxy_set_header Authorization $http_authorization;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
}

location = /api/reachai/embed-token {
    proxy_pass http://business-bff;
    # Keep the existing business login auth_request/auth_jwt policy here.
}
```

Do not attach the business JWT validation directive to the Embed location if it
would inspect the Embed Token as a business token.

## Kong

Create separate routes: a protected broker route to the business BFF and a
proxy-only Embed route to ReachAI. Do not attach business JWT/OIDC plugins to
the Embed route.

```yaml
services:
  - name: reachai-embed
    url: https://reachai.example.com/api/embed
    routes:
      - name: reachai-embed-route
        paths: ["/api/reachai/embed"]
        strip_path: true
    plugins:
      - name: request-transformer
        config:
          add:
            headers: ["X-Forwarded-Proto:https"]
```

Validate with a real browser request or authorized doctor run: broker uses a
real business login/test authorization; the following Embed request carries
`Authorization: Bearer <embedToken>` and reaches ReachAI unchanged. Doctor
automation does not replace final launcher visibility and interaction checks.
