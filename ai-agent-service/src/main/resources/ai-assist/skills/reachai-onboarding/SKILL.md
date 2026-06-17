---
name: reachai-onboarding
description: Integrate Java business systems with ReachAI SDK registration and capability sync. Use when asked to connect a Spring Boot service to ReachAI, add reachai-capability-sdk or reachai-spring-boot2-starter, configure reachai.registry/reachai.project/reachai.capability, expose @ReachCapability methods, or verify SDK onboarding from a ReachAI manifest.
---

# ReachAI Onboarding

## Operating Rules

Treat the current business repository as the source of truth. Inspect its Maven modules, Java version, Spring Boot version, configuration files, existing controller/service boundaries, and test commands before editing.

Never paste, print, or commit the registry app secret. Use the environment variable named by the manifest, normally `REACHAI_REGISTRY_APP_SECRET`.

Prefer minimal, reviewable changes:
- Add ReachAI dependencies only to the modules that need them.
- Put `reachai-spring-boot2-starter` in the runnable Spring Boot application module.
- Put `reachai-capability-sdk` in modules that declare `@ReachCapability` methods or DTO field metadata.
- Avoid changing unrelated business logic, package structure, formatting, or dependency versions.
- For automatic Spring MVC scanning, restrict ReachAI to business-owned packages. Do not sync framework, platform, third-party, starter, or shared infrastructure controllers as business APIs.

## Workflow

1. Read the ReachAI onboarding manifest URL from the user prompt.
2. Download this skill package if it is not already installed, then read the reference files only as needed.
3. Detect the project layout:
   - Maven root and child modules.
   - Java source level.
   - Spring Boot version.
   - Runnable application module.
   - Business-owned Java base packages from application classes, controllers, services, and module names.
   - Framework/platform packages that should be excluded from ReachAI scanning.
   - Existing `application.yml`, `bootstrap.yml`, profile-specific config, or config-center conventions.
4. Add dependencies using `references/java-sdk-access.md` and `templates/pom-dependencies.xml`.
5. Add configuration using `templates/application-reachai.yml`, replacing the package placeholders with narrow business package allow-lists and framework/package deny-lists.
6. Select one or two low-risk query-style business methods and annotate them with `@ReachCapability` / `@ReachParam`. Use `templates/reach-capability-example.java` only as a style example.
7. Inspect the business gateway boundary before declaring onboarding complete:
   - Spring Cloud Gateway, Nginx, backend-for-frontend, or front-end dev proxy configuration.
   - Existing authentication headers and current-user extraction.
   - Whether a server-side token broker already exists.
8. Add or update the gateway route/token broker:
   - Route ReachAI capability traffic to the business service and preserve `X-ReachAI-Invocation-Token`, `X-ReachAI-Trace-Id`, `X-ReachAI-Run-Id`, and the business identity headers required by the service.
   - Expose a front-end token endpoint such as `/api/reachai/embed-token`.
   - Implement the token endpoint server-side so it maps the current business user to `principal.externalUserId`, signs the platform call with the project `appKey/appSecret`, and calls ReachAI `POST /api/embed/token/exchange`.
   - Keep `/api/reachai/embed-token` on the normal business login token path. It reads the current business user and exchanges that identity for a ReachAI embed token.
   - Add the gateway authentication whitelist or dedicated security chain for `/api/reachai/embed/**`. This path carries ReachAI embed tokens, so business OAuth/JWT filters must not validate it as a business login token; forward `Authorization: Bearer <embedToken>` unchanged to ReachAI.
   - Inspect whitelist/anonymous filters that remove or rewrite JWT headers, such as `IgnoreUrlsRemoveJwtFilter`, `RemoveJwtFilter`, `RemoveRequestHeader=Authorization`, or security filters that call `mutate().header("Authorization", "")`. Do not apply that header-clearing behavior to `/api/reachai/embed/**`; skipping business authentication must still preserve the embed token `Authorization` header.
   - If Spring Cloud Gateway proxies `/api/reachai/embed/**`, dedupe duplicate CORS response headers when both the gateway and ReachAI write them. A typical route filter is `DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_FIRST`.
   - Before front-end embed work, call `manifest.agentProvisioning.provisionAgentUrl` when present. It is idempotent and creates or reuses the project `PAGE_COPILOT` Agent plus its default Workflow binding.
   - Use the provisioning response `agent.keySlug` as the front-end `agentId`. Fall back to `manifest.agentProvisioning.defaultKeySlug`, `manifest.agentWorkflow.globalAgentKeySlug`, or `manifest.embed.defaultAgentKeySlug` only when the provisioning API is unavailable.
   - Do not ask the business user to manually create, choose, or configure the page copilot Agent during SDK onboarding.
   - Treat that Agent as the single embedded page copilot entry. Page-specific behavior is selected later by `pageKey` through Agent/Workflow bindings, not by rendering one button per workflow.
   - Never move `appSecret` into browser code.
9. Add or update the business front-end integration:
   - Add the ReachAI chat/embed entry in a real business page or shared shell, not only in documentation.
   - Configure `apiBase`, `projectCode`, `agentId`, and a `tokenProvider` that calls the business gateway token broker. Use the provisioned page copilot Agent `keySlug` for `agentId`.
   - Pass `pageKey`, `pageInstanceId`, `route`, and `origin` into the token request so ReachAI can isolate sessions and route the current page to the right Workflow.
   - Pass the same stable `pageKey` through `createEafChat({ page: { pageKey, routePattern } })`; the browser SDK will create the chat session with `pageKey`, `route`, `pageInstanceId`, and registered page actions.
   - Do not reuse the business login token for ReachAI chat session or message calls. Use the broker-returned short-lived embed token for `/api/reachai/embed/**`, `/api/embed/chat/sessions`, and message APIs.
   - Chat message calls must use `POST /api/embed/chat/sessions/{sessionId}/messages` or the `/messages/stream` variant with body `{ "message": "..." }`.
   - Do not send ReachAI chat requests as `{ "content": "..." }`, `{ "text": "..." }`, or `{ "question": "..." }`; map any business UI field to `message` at the ReachAI API boundary.
   - Cache embed tokens only until before their `expiresIn` boundary. If a session or message request returns `embed token is expired`, clear the cached embed token, call the broker again, and retry once.
10. Run the smallest meaningful verification commands for the touched backend, gateway, and front-end modules.
11. Call the manifest's `sdkAccessCheckUrl` only after local compile/config succeeds, including `gatewayBaseUrl` and `embedTokenPath`, or explain why a live check cannot run.
12. If the prompt or manifest provides an access session URL under `/api/ai-assist/projects/{projectId}/access-sessions/...`, report progress after each major step. Use step keys such as `project-manifest`, `backend-sdk`, `reachai-config`, `capability-scan`, `gateway-route`, `embed-token-broker`, `gateway-whitelist`, `frontend-embed`, `connectivity-check`, and `handoff-summary`.
13. Report changed files, commands run, results, scan package choices, gateway route/token broker status, front-end integration status, and manual secrets still required.

## References

- For dependency and Java/Spring guidance, read `references/java-sdk-access.md`.
- For platform API contracts, read `references/platform-apis.md`.
- For credential handling and prompt safety, read `references/security.md`.
- For ready-to-copy snippets, use files under `templates/`.
- For an optional local verification helper, run `scripts/verify-reachai-access.py`.

## Output Contract

End with:
- Files changed.
- Dependency/configuration summary.
- Gateway route and embed token broker summary.
- Front-end embed/chat integration summary.
- Capability annotations added.
- Verification commands and results.
- Whether `REACHAI_REGISTRY_APP_SECRET` still needs to be configured outside the repository.
- Whether access session progress was reported to `/api/ai-assist/.../access-sessions/...`.
