---
name: generate-skill
description: Use when generating a Skill Service from a legacy Spring MVC or OpenAPI project in this repository, especially when the user mentions ai-skill-scanner, Tool Manifest, generated skill-services modules, or turning business APIs into AiTool implementations.
---
# Generate Skill

## Overview

This repository now has a scanner-first path for legacy project integration:

`OpenAPI/Controller -> Tool Manifest -> generated skill module -> ai-agent-service integration`

Use that path before hand-writing a new Skill Service. Hand-written tools are the fallback, not the default.

## When To Use

- The user wants to turn an old Spring MVC/OpenAPI project into `AiTool` implementations.
- The request mentions `ai-skill-scanner`, `Tool Manifest`, `skill-services/`, or auto-generating Skill Service code.
- You need to validate a legacy API contract before wiring it into `ai-agent-service`.

Do not use this skill for:

- Generic Tool refactors unrelated to scanner output
- `/api/ai/*` capability design
- Dynamic AgentScope adapter work not tied to generated skills

## Workflow

1. Pick the scan source:
   - Prefer `scan-openapi` when the legacy project has `openapi.yaml/json`
   - Use `scan-controller` for Spring MVC source without Swagger
2. Build a manifest first. Do not generate code directly from controllers or Swagger.
3. Keep manifest files under `docs/generated-manifests/` when you want repo-visible examples.
4. Generate the module under `skill-services/skill-<project-name>/`.
5. If the generated module should be consumed by `ai-agent-service`, add it to the root `pom.xml` reactor and as a dependency in `ai-agent-service/pom.xml`.
6. Verify registration through `com.enterprise.ai.agent.tools.ToolRegistry`.

## Required Constraints

- Manifest contract lives in `docs/ToolManifest契约.md`. Follow it exactly.
- Generated modules must stay compatible with `ai-skill-sdk` and the current jar-loading model.
- Generated HTTP clients align with the current repository pattern and use `RestClient`.
- `path` excludes `contextPath`; runtime client prepends `contextPath` before issuing the HTTP call.

## Current Limitation

Generated skills register into `ToolRegistry`, so they work for Tool management and direct registry execution.

They do **not** automatically become AgentScope ReAct tools yet. Until `ToolRegistryAdapter` is made dynamic, full Agent usage still requires:

- adding matching bridge methods in `ai-agent-service/src/main/java/com/enterprise/ai/agent/agentscope/adapter/ToolRegistryAdapter.java`
- and, if needed for lightweight chat, updating `ai-agent-service/src/main/java/com/enterprise/ai/agent/service/LightweightToolCaller.java`

Do not promise “full Agent auto-wiring” unless you verified those two integration points.

## Quick Reference

- Scanner module: `ai-skill-scanner/`
- Manifest examples: `docs/generated-manifests/`
- Templates: `templates/skill-service/`
- Generated module root: `skill-services/`
- Exact commands and an example flow: see [reference.md](reference.md)

## Common Mistakes

- Skipping the manifest and generating code straight from ad-hoc assumptions.
- Forgetting to clean stale generated files when regenerating the same module.
- Treating generated modules as standalone apps instead of host-loaded jars.
- Claiming the generated tool is available to ReAct Agents without checking `ToolRegistryAdapter`.
