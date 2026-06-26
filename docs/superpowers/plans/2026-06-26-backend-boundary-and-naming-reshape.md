# Backend Boundary And Naming Reshape Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reshape ReachAI backend architecture expression from ambiguous `model/skills/agent` service naming to clear logical domains without changing current runtime behavior, routes, ports, page layout, or UI structure.

**Architecture:** Keep the three Spring Boot deployment units in place for this pass. Document and enforce five logical domains: Model Gateway, Knowledge/Retrieval, Capability Catalog, Runtime Host, and Platform Control. Rename user-facing and documentation-level `Skill` service wording to Knowledge/Retrieval while preserving legacy SQL/API/class names that remain active contracts.

**Tech Stack:** Java 17, Spring Boot 3.4, Vue 3, Vite, Element Plus, Markdown docs, Node.js verification script.

---

## File Structure

- Create `docs/16-后端逻辑边界与命名重塑.md`: authoritative architecture note for the current reshape.
- Create `scripts/check-backend-boundary-naming.mjs`: read-only verification script covering docs, frontend proxy invariants, and naming guardrails.
- Modify `README.md`: add current deployment unit vs logical domain mapping and align quick start service descriptions.
- Modify `docs/README.md`: add the new architecture note to the reading order.
- Modify `docs/01-平台定位与架构总览.md`: separate deployment units from logical domains and remove the old "still needs boundary clarification" state.
- Modify `docs/05-知识模型与企业资产.md`: describe `ai-skills-service` as the current Knowledge/Retrieval deployment unit.
- Modify `docs/06-项目背景技术与功能说明.md`: update service/domain wording and Workflow Studio naming.
- Modify `docs/ai-memory/PROJECT-MEMORY.md`: preserve future AI-agent context for the new boundary model.
- Modify `docs/ai-memory/DECISIONS.md`: add the current decision that logical boundaries are primary while physical services remain stable.
- Modify `AGENTS.md`: update project-level agent rules so future agents follow the new boundary vocabulary.
- Modify `ai-agent-service/README.md`: replace stale historical-scanner framing with current control/runtime/capability boundary framing.
- Modify `ai-skills-service/README.md`: rename the service description to Knowledge/Retrieval while preserving artifactId and `/ai` routes.
- Modify `ai-skills-service/pom.xml`: update Maven display name/description only; do not change artifactId.
- Modify `ai-admin-front/README.md`: update service descriptions while keeping proxy path documentation unchanged.
- Modify `ai-admin-front/src/api/request.ts`: update comments only; keep base URLs unchanged.
- Modify `ai-admin-front/src/views/dashboard/Dashboard.vue`: update visible service label from generic knowledge wording to "Knowledge / Retrieval"; keep service health keys and health paths unchanged.

## Task 1: Add Boundary Verification Script

**Files:**
- Create: `scripts/check-backend-boundary-naming.mjs`

- [ ] **Step 1: Write the failing verification script**

```javascript
import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
let failures = 0;

function read(rel) {
  return fs.readFileSync(path.join(root, rel), 'utf8');
}

function assertFile(rel) {
  if (!fs.existsSync(path.join(root, rel))) {
    console.error(`[missing] ${rel}`);
    failures += 1;
  }
}

function assertIncludes(rel, needle) {
  const text = read(rel);
  if (!text.includes(needle)) {
    console.error(`[missing text] ${rel}: ${needle}`);
    failures += 1;
  }
}

function assertNotIncludes(rel, needle) {
  const text = read(rel);
  if (text.includes(needle)) {
    console.error(`[stale text] ${rel}: ${needle}`);
    failures += 1;
  }
}

assertFile('docs/16-后端逻辑边界与命名重塑.md');
assertIncludes('README.md', '当前部署单元与目标逻辑域');
assertIncludes('README.md', 'Knowledge / Retrieval');
assertIncludes('README.md', 'Capability Catalog');
assertIncludes('README.md', 'Runtime Host');
assertIncludes('README.md', 'Platform Control');
assertIncludes('docs/01-平台定位与架构总览.md', '五个长期逻辑域');
assertIncludes('docs/16-后端逻辑边界与命名重塑.md', '不先拆服务，先拆职责');
assertIncludes('ai-skills-service/pom.xml', '<name>ReachAI Knowledge Retrieval Service</name>');
assertNotIncludes('ai-skills-service/pom.xml', '<name>AI Skills Service</name>');
assertNotIncludes('ai-skills-service/README.md', '# AI Skills Service');
assertIncludes('ai-skills-service/README.md', '# ReachAI Knowledge / Retrieval Service');
assertIncludes('ai-admin-front/src/api/request.ts', 'Knowledge / Retrieval deployment unit');
assertIncludes('ai-admin-front/src/views/dashboard/Dashboard.vue', 'Knowledge / Retrieval');
assertIncludes('ai-admin-front/src/views/dashboard/Dashboard.vue', "name: 'ai-skills-service'");
assertIncludes('ai-admin-front/src/views/dashboard/Dashboard.vue', "healthPath: '/ai/actuator/health'");
assertIncludes('ai-admin-front/vite.config.ts', "target: 'http://localhost:18602'");
assertIncludes('ai-admin-front/vite.config.ts', "target: 'http://localhost:18603'");
assertIncludes('ai-admin-front/vite.config.ts', "target: 'http://localhost:18601'");

if (failures > 0) {
  console.error(`backend boundary naming check failed: ${failures} issue(s)`);
  process.exit(1);
}

console.log('backend boundary naming check passed');
```

- [ ] **Step 2: Run the script and verify it fails**

Run: `node scripts/check-backend-boundary-naming.mjs`

Expected: FAIL because `docs/16-后端逻辑边界与命名重塑.md` and the new boundary wording do not exist yet.

## Task 2: Update Architecture Documentation

**Files:**
- Create: `docs/16-后端逻辑边界与命名重塑.md`
- Modify: `README.md`
- Modify: `docs/README.md`
- Modify: `docs/01-平台定位与架构总览.md`
- Modify: `docs/05-知识模型与企业资产.md`
- Modify: `docs/06-项目背景技术与功能说明.md`
- Modify: `docs/ai-memory/PROJECT-MEMORY.md`
- Modify: `docs/ai-memory/DECISIONS.md`
- Modify: `AGENTS.md`

- [ ] **Step 1: Write the architecture note**

The note must define the five logical domains, explain current deployment units, list "move now" vs "do not move now", and explicitly state that this pass does not alter routes, ports, SQL, or frontend layout.

- [ ] **Step 2: Update entry docs**

Update the README and docs index so readers can navigate from current deployment units to logical domains.

- [ ] **Step 3: Update AI memory docs**

Update project memory and decisions so future AI agents keep the new boundaries when editing code.

## Task 3: Update Service And Frontend Naming

**Files:**
- Modify: `ai-agent-service/README.md`
- Modify: `ai-skills-service/README.md`
- Modify: `ai-skills-service/pom.xml`
- Modify: `ai-admin-front/README.md`
- Modify: `ai-admin-front/src/api/request.ts`
- Modify: `ai-admin-front/src/views/dashboard/Dashboard.vue`

- [ ] **Step 1: Rename current service descriptions**

Keep artifactIds, routes, health keys, ports, and proxies unchanged. Rename only display names, README language, and comments.

- [ ] **Step 2: Preserve UI structure**

In `Dashboard.vue`, only change the visible service label text. Do not change layout classes, icons, health-key names, or fetch paths.

## Task 4: Verify

**Files:**
- Read: all modified files

- [ ] **Step 1: Run boundary naming check**

Run: `node scripts/check-backend-boundary-naming.mjs`

Expected: PASS with `backend boundary naming check passed`.

- [ ] **Step 2: Run doc diff hygiene**

Run: `git diff --check`

Expected: exit code 0.

- [ ] **Step 3: Run frontend build**

Run: `npm run build` in `ai-admin-front`.

Expected: exit code 0. This verifies the dashboard text/comment changes did not break current page rendering.

- [ ] **Step 4: Inspect git diff**

Run: `git diff --stat` and `git diff --name-only`.

Expected: no changes to service application YAML, SQL schema, API route definitions, Vue layout structure, or generated artifacts.
