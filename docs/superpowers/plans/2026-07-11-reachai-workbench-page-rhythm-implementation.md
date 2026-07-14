# ReachAI Workbench Page Rhythm Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Project Management page geometry the single reusable spacing contract for every MainLayout business page while preserving dedicated Studio, viewport, and embedded layouts.

**Architecture:** Add a layout-token foundation and a `WorkbenchPage` root component, make `MainLayout` consume explicit route layout modes, and migrate page roots in domain-sized batches. Page-level spacing and component-internal density remain separate; legacy global selectors and parent-shell compensation are removed only after every consumer is migrated.

**Tech Stack:** Vue 3, TypeScript, Vue Router, SCSS/CSS variables, Element Plus, Node.js contract scripts, Playwright browser verification.

## Global Constraints

- Desktop geometry is exact: content inline `28px`, breadcrumb-to-page `10px`, direct page-section gap `10px`, page end `16px`, breadcrumb height `44px`.
- PageHeader desktop padding is `26px 28px`; project-workbench compact padding is `12px 22px`.
- At `<=900px`: content inline `16px`, page start `10px`, page end `16px`, page gap `10px`, PageHeader padding `18px`.
- At `<=760px`: content inline `12px`, page start `8px`, page end `12px`, page gap `8px`, PageHeader padding `16px`.
- `standard` pages use `WorkbenchPage`; `project-workbench` pages consume the same outer tokens without rewriting internal business layouts; `edge-to-edge` and `studio` pages do not receive standard page spacing.
- MainLayout owns sidebar, breadcrumb and viewport; WorkbenchPage owns page padding and direct-child gap; shared panels own only internal padding.
- Do not change API calls, stores, form validation, event handlers, route paths, user-visible copy, or runtime behavior.
- Preserve all pre-existing staged UI changes. Do not commit and do not push. Stage each completed task on `main` only after its task review passes.
- Every production change follows RED -> GREEN. Mutation/self-tests must prove the checker rejects missing or invalid layout contracts.

---

### Task 1: Add the page-rhythm contract checker in RED state

**Files:**
- Create: `ai-admin-front/scripts/check-page-rhythm-contract.mjs`
- Modify: `ai-admin-front/package.json`

**Interfaces:**
- Produces command: `npm run check:page-rhythm`
- Produces source-of-truth sets: `STANDARD_PAGE_FILES`, `PROJECT_WORKBENCH_PAGE_FILES`, `EXEMPT_PAGE_FILES`
- Consumes no new production component; the first repository run must fail because Task 2 foundations are absent.

- [ ] **Step 1: Write the executable contract and mutation self-tests**

The checker must read Vue SFCs with `@vue/compiler-sfc`, parse route metadata from `src/router/index.ts`, and validate these exact rules:

```js
const REQUIRED_LAYOUT_MODES = new Set(['standard', 'project-workbench', 'edge-to-edge', 'studio'])
const REQUIRED_LAYOUT_TOKENS = new Map([
  ['--layout-breadcrumb-height', '44px'],
  ['--layout-content-inline', '28px'],
  ['--layout-page-start', '10px'],
  ['--layout-page-end', '16px'],
  ['--layout-page-gap', '10px'],
  ['--layout-page-header-padding-block', '26px'],
  ['--layout-page-header-padding-inline', '28px'],
])
```

Self-tests must mutate one valid synthetic fixture at a time and prove rejection of: missing token, wrong token value, illegal layout mode, standard page without WorkbenchPage, standard page root padding, direct page-section margin, project page `:has()` parent control, negative shell compensation, and standard selector matching Studio.

- [ ] **Step 2: Register the command**

Add the exact package script:

```json
"check:page-rhythm": "node scripts/check-page-rhythm-contract.mjs"
```

- [ ] **Step 3: Run the checker and verify RED**

Run: `npm run check:page-rhythm`

Expected: exit `1`; diagnostics include missing `src/styles/tokens/_layout.scss`, missing `src/components/common/WorkbenchPage.vue`, and missing `meta.layoutMode`. Parser/compiler exceptions are not acceptable.

- [ ] **Step 4: Verify checker syntax and mutation coverage**

Run: `node --check scripts/check-page-rhythm-contract.mjs`

Expected: exit `0`.

Run: `npm run check:page-rhythm -- --self-test`

Expected: all nine named mutation proofs pass.

---

### Task 2: Implement layout foundations, WorkbenchPage, and explicit route modes

**Files:**
- Create: `ai-admin-front/src/styles/tokens/_layout.scss`
- Create: `ai-admin-front/src/components/common/WorkbenchPage.vue`
- Modify: `ai-admin-front/src/styles/theme.scss`
- Modify: `ai-admin-front/src/components/common/PageHeader.vue`
- Modify: `ai-admin-front/src/views/layout/MainLayout.vue`
- Modify: `ai-admin-front/src/router/index.ts`

**Interfaces:**
- `WorkbenchPage` consumes the existing `WorkbenchDensity` type from `glassWorkbench.ts`.
- Route meta produces `layoutMode: 'standard' | 'project-workbench' | 'edge-to-edge' | 'studio'`.
- MainLayout consumes `route.meta.layoutMode` and emits `layout-standard`, `layout-project-workbench`, `layout-edge-to-edge`, or `layout-studio` classes.

- [ ] **Step 1: Confirm the repository checker still fails for foundations**

Run: `npm run check:page-rhythm`

Expected: missing layout foundation/component/mode failures from Task 1.

- [ ] **Step 2: Add the layout token foundation**

Define the desktop tokens from Global Constraints, sidebar tokens `256px`, `84px`, `14px`, `14px`, `10px`, compact header tokens `12px` and `22px`, plus exact `900px` and `760px` overrides. Import `_layout.scss` after density foundations and before glass recipes in `theme.scss`.

- [ ] **Step 3: Implement WorkbenchPage**

Use this public contract:

```ts
const props = withDefaults(defineProps<{
  density?: WorkbenchDensity
  fullHeight?: boolean
}>(), {
  density: 'comfortable',
  fullHeight: false,
})
```

The root must render one default slot, add `density-${props.density}`, apply only layout tokens for padding/gap, and add `min-height: 100%` only for `fullHeight`.

- [ ] **Step 4: Make PageHeader consume title-padding tokens**

Replace symmetric `var(--panel-padding)` with block/inline page-header tokens while retaining density for internal control sizes and text gaps.

- [ ] **Step 5: Replace MainLayout path/name inference with route metadata**

Use `route.meta.layoutMode ?? 'standard'`; only `studio` hides ordinary sidebar/breadcrumb. All main content modes retain `padding: 0`. Sidebar width/inset and breadcrumb height/inline padding consume layout tokens. Remove `registry-shell`, `studio-shell`, `isRegistryShell`, `isProjectManagementPage`, and name-based Studio detection.

- [ ] **Step 6: Add exact layoutMode metadata to routes**

Assign `standard` to the standard pages, `project-workbench` to WorkflowList and the project-domain pages, `edge-to-edge` to AgentDebug and ModelPlayground, and `studio` to WorkflowStudio. Redirect-only child records receive their target mode. Login remains outside MainLayout.

- [ ] **Step 7: Verify the foundation is GREEN and page migration remains RED**

Run: `npm run check:page-rhythm`

Expected: no token, WorkbenchPage, MainLayout, or route-mode failures; failures remain only for unmigrated standard/project page sources and legacy global selectors.

Run: `npx vue-tsc --noEmit`

Expected: exit `0`.

---

### Task 3: Migrate the five already-componentized standard pages

**Files:**
- Modify: `ai-admin-front/src/views/agent/AgentEdit.vue`
- Modify: `ai-admin-front/src/views/KnowledgeDetail.vue`
- Modify: `ai-admin-front/src/views/tool/ToolList.vue`
- Modify: `ai-admin-front/src/views/tool/ToolRetrievalTest.vue`
- Modify: `ai-admin-front/src/views/mcp/McpOnboarding.vue`
- Modify: `ai-admin-front/scripts/check-glass-components-contract.mjs`

**Interfaces:**
- Each page imports and renders `WorkbenchPage` as its single business-page root.
- Existing PageHeader/WorkbenchPanel/MetricStrip/DataTableShell structure and every runtime subtree remain unchanged.

- [ ] **Step 1: Add failing page-rhythm expectations for the five pages**

Run: `npm run check:page-rhythm`

Expected: each named file reports missing WorkbenchPage or forbidden root spacing.

- [ ] **Step 2: Replace page roots with WorkbenchPage**

Import `WorkbenchPage`, preserve the current density, pass `full-height` only where the page already fills the viewport, and remove page-root padding/gap plus direct shared-component margins. Do not alter business child markup.

- [ ] **Step 3: Update the existing glass component oracle**

Teach the existing checker that WorkbenchPage is an approved presentation import/root. Preserve all HEAD/runtime invariants and existing mutation proof counts; add safe variants only where wrapper insertion changes normalized topology.

- [ ] **Step 4: Verify both contracts**

Run: `npm run check:glass-components`

Expected: exit `0`, at least the existing `436` mutation proofs and `8` safe variants pass.

Run: `npm run check:page-rhythm`

Expected: no failures for the five Task 3 files.

---

### Task 4: Migrate Dashboard, Agent, Workflow, and RunOps standard pages

**Files:**
- Modify: `ai-admin-front/src/views/dashboard/Dashboard.vue`
- Modify: `ai-admin-front/src/views/agent/AgentList.vue`
- Modify: `ai-admin-front/src/views/workflow/WorkflowVersions.vue`
- Modify: `ai-admin-front/src/views/runops/RunOpsList.vue`
- Modify: `ai-admin-front/src/views/runops/RunOpsDetail.vue`

**Interfaces:**
- All seven pages render WorkbenchPage as the root.
- Existing private headers/cards may remain internally in this spacing task, but their outer margin/padding must be removed so WorkbenchPage is the only page-rhythm owner.

- [ ] **Step 1: Verify RED for the seven named files**

Run: `npm run check:page-rhythm`

Expected: all seven files are reported as unmigrated or owning forbidden outer spacing.

- [ ] **Step 2: Migrate roots and remove outer spacing ownership**

For each page, import WorkbenchPage, replace or wrap the existing root without changing conditional rendering, and remove only page-root padding, page-root gap duplicates, header bottom margin, and direct card sibling margin.

- [ ] **Step 3: Verify the domain batch**

Run: `npm run check:page-rhythm`

Expected: no failures for the seven Task 4 files.

Run: `npx vue-tsc --noEmit`

Expected: exit `0`.

---

### Task 5: Migrate Knowledge, retrieval, business-index, and model standard pages

**Files:**
- Modify: `ai-admin-front/src/views/KnowledgeList.vue`
- Modify: `ai-admin-front/src/views/KnowledgeImport.vue`
- Modify: `ai-admin-front/src/views/FileDetail.vue`
- Modify: `ai-admin-front/src/views/RetrievalTest.vue`
- Modify: `ai-admin-front/src/views/BizIndexList.vue`
- Modify: `ai-admin-front/src/views/BizIndexDetail.vue`
- Modify: `ai-admin-front/src/views/model/ModelInstances.vue`

**Interfaces:**
- All seven pages render WorkbenchPage as the root.
- ModelInstances keeps its complex inner grid and only changes the outer shell contract.

- [ ] **Step 1: Verify RED for the seven named files**

Run: `npm run check:page-rhythm`

Expected: all seven files are reported as unmigrated or owning forbidden outer spacing.

- [ ] **Step 2: Migrate roots and remove outer spacing ownership**

Keep existing tables, forms, tabs, dialogs and responsive inner grids byte-for-byte except for necessary wrapper imports/tags/classes. Remove only page-level padding/gap and first-level header/card margins.

- [ ] **Step 3: Verify the domain batch**

Run: `npm run check:page-rhythm`

Expected: no failures for the seven Task 5 files.

Run: `npx vue-tsc --noEmit`

Expected: exit `0`.

---

### Task 6: Migrate Capability standard pages

**Files:**
- Modify: `ai-admin-front/src/views/capability/CapabilityKernel.vue`
- Modify: `ai-admin-front/src/views/registry/CapabilitySyncDebug.vue`
- Modify: `ai-admin-front/src/views/capability/CapabilityMining.vue`
- Modify: `ai-admin-front/src/views/capability/slot/SlotExtractorList.vue`
- Modify: `ai-admin-front/src/views/capability/slot/SlotDictDept.vue`
- Modify: `ai-admin-front/src/views/capability/slot/SlotDictUser.vue`
- Modify: `ai-admin-front/src/views/capability/slot/SlotExtractLogs.vue`

**Interfaces:**
- The seven unique files cover ten standard component routes.
- Shared route components must not branch spacing by active route; WorkbenchPage stays invariant.

- [ ] **Step 1: Verify RED for all seven unique files**

Run: `npm run check:page-rhythm`

Expected: each unique file reports missing WorkbenchPage or forbidden outer spacing.

- [ ] **Step 2: Migrate roots and remove outer spacing ownership**

Preserve route-driven tabs and API behavior. Replace root layout ownership with WorkbenchPage and remove only external padding/gap/margins.

- [ ] **Step 3: Verify the domain batch**

Run: `npm run check:page-rhythm`

Expected: no failures for Task 6 files.

Run: `npx vue-tsc --noEmit`

Expected: exit `0`.

---

### Task 7: Migrate MCP, A2A, settings, Domain, and Context standard pages

**Files:**
- Modify: `ai-admin-front/src/views/mcp/McpVisibilityBoard.vue`
- Modify: `ai-admin-front/src/views/mcp/McpClientList.vue`
- Modify: `ai-admin-front/src/views/mcp/McpCallMonitor.vue`
- Modify: `ai-admin-front/src/views/a2a/A2aEndpointList.vue`
- Modify: `ai-admin-front/src/views/a2a/A2aSessionMonitor.vue`
- Modify: `ai-admin-front/src/views/settings/PlatformUserSettings.vue`
- Modify: `ai-admin-front/src/views/settings/BusinessUserDirectory.vue`
- Modify: `ai-admin-front/src/views/settings/AuthProviderSettings.vue`
- Modify: `ai-admin-front/src/views/settings/ToolAclList.vue`
- Modify: `ai-admin-front/src/views/domain/DomainList.vue`
- Modify: `ai-admin-front/src/views/domain/DomainAssignmentBoard.vue`
- Modify: `ai-admin-front/src/views/domain/DomainClassifierTest.vue`
- Modify: `ai-admin-front/src/views/context/ContextGovernance.vue`

**Interfaces:**
- All thirteen pages render WorkbenchPage as the root.
- DomainAssignmentBoard and ContextGovernance retain their inner canvas/grid sizing; only the outer shell changes.

- [ ] **Step 1: Verify RED for all thirteen files**

Run: `npm run check:page-rhythm`

Expected: each file reports missing WorkbenchPage or forbidden outer spacing.

- [ ] **Step 2: Migrate roots and remove outer spacing ownership**

Preserve existing CRUD operations, form models, dialogs, tables, inner responsive grids and board behavior. Remove only outer page spacing and sibling spacing now supplied by WorkbenchPage.

- [ ] **Step 3: Verify all current standard pages**

Run: `npm run check:page-rhythm`

Expected: the standard-page section is fully green; only project-workbench and legacy-global failures may remain.

Run: `npx vue-tsc --noEmit`

Expected: exit `0`.

---

### Task 8: Migrate the nine project-workbench outer shells

**Files:**
- Modify: `ai-admin-front/src/views/workflow/WorkflowList.vue`
- Modify: `ai-admin-front/src/views/registry/RegistryProjectList.vue`
- Modify: `ai-admin-front/src/views/registry/RegistryProjectDetail.vue`
- Modify: `ai-admin-front/src/views/settings/EmbedOpsMonitor.vue`
- Modify: `ai-admin-front/src/views/settings/EmbedSessionAudit.vue`
- Modify: `ai-admin-front/src/views/registry/PageAssistantWizard.vue`
- Modify: `ai-admin-front/src/views/registry/SdkAccessWizard.vue`
- Modify: `ai-admin-front/src/views/scan/ScanProjectDetail.vue`
- Modify: `ai-admin-front/src/views/registry/styles/SdkAccessWizard.ai-coding.figma.scss`

**Interfaces:**
- Existing root components keep their internal hero, metric, wizard, table and viewport structures.
- Each root consumes `--layout-content-inline`, `--layout-page-start`, `--layout-page-end`, and `--layout-page-gap` directly or via the shared `project-workbench-page` recipe.

- [ ] **Step 1: Verify RED for project shell coupling**

Run: `npm run check:page-rhythm`

Expected: diagnostics name project pages using private outer values, `:global(.main-layout ... :has(...))`, or negative shell compensation.

- [ ] **Step 2: Apply the project-workbench outer recipe**

Use the same desktop/mobile outer tokens as WorkbenchPage. Preserve RegistryProjectList as the golden geometry. Its compact header uses the compact title-padding tokens; the other pages do not inherit compact behavior unless already present.

- [ ] **Step 3: Remove parent-shell coupling and compensation**

Delete `:global(.main-layout ... .main-content:has(...))` rules, negative outer margins, and hard-coded root padding that compensate for MainLayout. Retain internal wizard/grid dimensions and business scroll containers.

- [ ] **Step 4: Verify project-workbench GREEN**

Run: `npm run check:page-rhythm`

Expected: no project-workbench failures; only legacy global selector failures may remain.

Run: `npx vue-tsc --noEmit`

Expected: exit `0`.

---

### Task 9: Remove legacy spacing rails and close all automated contracts

**Files:**
- Modify: `ai-admin-front/src/styles/index.scss`
- Modify: `ai-admin-front/scripts/check-page-rhythm-contract.mjs`
- Modify: `ai-admin-front/scripts/check-glass-components-contract.mjs` only if final wrapper normalization requires it

**Interfaces:**
- The layout checker becomes the single guard for outer page rhythm.
- Existing glass-theme, dark-theme and component checkers remain green.

- [ ] **Step 1: Confirm legacy selector failures are RED**

Run: `npm run check:page-rhythm`

Expected: failures name old `.main-content > .page-container`, legacy header injection, adjacent-card margin, and non-container `:has()` padding selectors.

- [ ] **Step 2: Remove obsolete global spacing rules**

Delete only the migrated outer-layout rules: duplicate `.page-container` padding, `.page-header` external margin, `.main-content` page padding injection, legacy first-level header margin/padding injection, adjacent-card margin, and parent `:has()` layout inference. Keep shared Element Plus skin, glass surfaces and internal component recipes.

- [ ] **Step 3: Run the full automated suite**

Run:

```powershell
npm run check:glass-theme
npm run check:dark-theme-compatibility
npm run check:glass-components
npm run check:page-rhythm
npx vue-tsc --noEmit
npm run build
```

Expected: every command exits `0`; the build may emit the existing chunk-size advisory but no compile/type error.

- [ ] **Step 4: Check diff hygiene**

Run: `git diff --check` and `git diff --cached --check` from the repository root.

Expected: both exit `0`.

---

### Task 10: Verify computed geometry and interaction in a real browser

**Files:**
- Create only ignored QA artifacts under `output/playwright/` while testing; remove them before final staging.
- Modify production files only if a failing geometry assertion first reproduces the defect.

**Interfaces:**
- Representative routes: `/registry/projects`, `/agent/new/edit`, `/tool`, `/knowledge/:code`, `/registry/projects/:projectCode`, and one ordinary CRUD route with available runtime data.

- [ ] **Step 1: Start the actual frontend and capture baseline runtime state**

Use the repository's current dev command and authenticated local runtime. Confirm there are no new console errors before geometry assertions.

- [ ] **Step 2: Measure exact geometry at desktop widths**

At `1600` and `1280`, in light/dark theme and expanded/collapsed sidebar, assert with `getBoundingClientRect()`:

```js
Math.abs(breadcrumb.left - firstSection.left) <= 1
Math.abs(firstSection.top - breadcrumbRail.bottom - 10) <= 1
Math.abs(secondSection.top - firstSection.bottom - 10) <= 1
Math.abs(viewportWidth - firstSection.right - 28) <= 1
```

For pages whose first section height changes with content, measure direct WorkbenchPage/project-workbench children rather than hard-coding heights.

- [ ] **Step 3: Measure responsive geometry and overflow**

At `900`, expect `16px` inline and `10px` page gap. At `760`, expect `12px` inline and `8px` page gap. Assert `document.documentElement.scrollWidth === document.documentElement.clientWidth`; wide tables may scroll only inside DataTableShell.

- [ ] **Step 4: Smoke-test preserved interactions**

On representative pages exercise search/reset, pagination, one dialog open/Escape close, one edit form control, and one project navigation action. Confirm no new console errors.

- [ ] **Step 5: Clean QA artifacts, stage, and perform final audit**

Remove ignored screenshots/traces, stage only intended source/docs, verify `git status --short` has no unstaged or untracked QA files, and re-run `git diff --cached --check`. Do not commit and do not push.
