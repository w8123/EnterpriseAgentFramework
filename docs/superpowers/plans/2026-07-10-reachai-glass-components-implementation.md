# ReachAI Glass Public Components Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Phase 2 public Glass Workbench component layer, prove it on one real route for every page archetype, and keep the implementation reusable across all seven brand palettes, light/dark themes, three densities, and transparency/motion fallbacks.

**Architecture:** Keep business data, API calls, route contracts, and page-specific layout in the current views. Add small Vue wrappers that compose Element Plus with the Phase 1 semantic tokens and the four shared glass recipes; page migrations may rearrange presentation markup but must retain existing handlers, API calls, route names, and data models. A source-and-compiled-CSS Node contract is written before each slice, then `vue-tsc`, focused legacy checks, production build, and real-route Playwright QA provide the integration gates.

**Tech Stack:** Vue 3.5 SFCs, TypeScript 5.6, Element Plus 2.9, Sass 1.83, Vite 6, Node contract scripts, Playwright MCP/CLI, existing Figma file `dLSJOki5yBoBeEx2GEZHU5`.

## Global Constraints

- Work only in `D:\work\.codex-worktrees\EnterpriseAgentFramework\reachai-glass-foundation`; do not stage, commit, push, merge, or rewrite unrelated work.
- C3 全域强玻璃 applies to page headers, panels, controls, tables, dialogs, wizard dialogs, and drawers, with no more than three visually obvious depth layers in one region.
- `tech-purple`, `metro-green`, `aurora-cyan`, `nebula-violet`, `coral-rose`, `solar-gold`, and `deep-ocean` must all drive actions, focus, selected states, and brand emphasis through existing `--brand-*` variables; status colors remain semantic and brand-independent.
- Light is the polished visual target. Dark must remain readable and fully operable, without a separate pixel-perfect redesign.
- `compact`, `comfortable`, and `spacious` consume the existing `--control-height`, `--row-height`, `--section-gap`, and `--panel-padding` roles; tables/monitoring use compact, normal lists/forms use comfortable, and onboarding uses spacious.
- Reduced transparency and unsupported blur must fall back through the existing `.glass-surface-*` recipes; reduced motion must use existing `--motion-duration-*` variables. Public components must not declare their own `backdrop-filter` or hard-coded fallback surface.
- Element Plus remains the only UI framework. Component mappings belong in `src/styles/_element-plus.scss`, not in migrated pages.
- Workflow Studio and Agent Studio retain their canvas, rails, nodes, zoom, debug, and panel layout. This plan may replace only shared dialogs/drawers and consume shared tokens/controls there.
- Non-goals: business logic, API payloads, router records, backend services, SQL, data migrations, and full Figma copies of business screens.
- The already refined registry routes `/registry/projects`, `/registry/projects/:projectCode`, and `/registry/projects/:projectCode/sdk-access` are regression targets only, not migration pilots.

## Verified Route and File Inventory

The following paths come from `ai-admin-front/src/router/index.ts`; do not substitute guessed paths during implementation.

| Archetype | Real route | Current view | Phase 2 proof |
| --- | --- | --- | --- |
| List workbench | `/tool` | `src/views/tool/ToolList.vue` | PageHeader, FilterBar, DataTableShell, StatusTag; opens the Tool WizardDialog |
| Detail workbench | `/knowledge/:code` | `src/views/KnowledgeDetail.vue` | PageHeader, MetricStrip, WorkbenchPanel, AppDialog |
| Form workbench | `/agent/:id/edit` (`/agent/new/edit` is the existing new-state convention) | `src/views/agent/AgentEdit.vue` | PageHeader and comfortable WorkbenchPanel sections |
| Step onboarding | `/mcp/onboarding` | `src/views/mcp/McpOnboarding.vue` | spacious PageHeader/WorkbenchPanel composition and shared code shell |
| Debug/test workbench | `/tool/retrieval` | `src/views/tool/ToolRetrievalTest.vue` | input/result hierarchy plus AppDialog |
| Monitoring/audit | `/runops` | `src/views/runops/RunOpsList.vue` | compact MetricStrip, filters, diagnostics, and table |
| Studio special canvas | `/workflows/:workflowId/studio` | `src/views/workflow/WorkflowStudio.vue` | only AppDialog/AppDrawer adoption; no canvas layout change |
| Drawer overlay proof | `/a2a/monitor` | `src/views/a2a/A2aSessionMonitor.vue` | compact monitor plus AppDrawer payload detail |

Existing public assets to reuse rather than duplicate:

- Vue metric icon base: `src/components/common/MetricIconBg.vue`.
- Backward-compatible status entry point: `src/components/CommonStatusTag.vue` and `src/utils/uiLabels.ts`.
- Figma sidebar component `760:291`, metric icon component `768:129`, and pagination component `785:415`.
- Existing checks: `npm run check:glass-theme`, `npm run check:dark-theme-compatibility`, `npm run check:studio-canvas`, `npm run check:sdk-access-workbench`, `npm run check:page-assistant-progress`, and `npm run check:scan-project-detail-ui`.

## File Structure

- `src/components/common/glassWorkbench.ts`: shared density, metric, status, and wizard contracts only.
- `src/components/common/PageHeader.vue`, `MetricStrip.vue`, `WorkbenchPanel.vue`: page composition layer.
- `src/components/common/FilterBar.vue`, `DataTableShell.vue`, `StatusTag.vue`: data workbench layer.
- `src/components/common/AppDialog.vue`, `WizardDialog.vue`, `AppDrawer.vue`: Element Plus overlay adapters.
- `src/components/CommonStatusTag.vue`: compatibility adapter delegating to `StatusTag`.
- `src/styles/_element-plus.scss`: only global Element Plus variable mappings needed by the public components.
- `scripts/check-glass-components-contract.mjs`: closed-world source/SFC/CSS and representative-consumer contract.
- The eight verified views above: presentation-only pilot consumers.

---

### Task 1: Establish the public-component contract harness

**Files:**
- Create: `ai-admin-front/scripts/check-glass-components-contract.mjs`
- Modify: `ai-admin-front/package.json`

**Interfaces:**
- Consumes: the existing `check-glass-theme-contract.mjs` pattern and `@vue/compiler-sfc`/Sass dependencies already used by that script.
- Produces: `npm run check:glass-components`, which validates files, public interfaces, compiled selectors, token-only visual ownership, and pilot consumption.

- [ ] **Step 1: Add the failing package command and closed-world file list**

Add `"check:glass-components": "node scripts/check-glass-components-contract.mjs"` after `check:glass-theme`. In the new script, define these exact expected component paths and fail for every missing file:

```js
const componentFiles = [
  'src/components/common/glassWorkbench.ts',
  'src/components/common/PageHeader.vue',
  'src/components/common/MetricStrip.vue',
  'src/components/common/WorkbenchPanel.vue',
  'src/components/common/FilterBar.vue',
  'src/components/common/DataTableShell.vue',
  'src/components/common/StatusTag.vue',
  'src/components/common/AppDialog.vue',
  'src/components/common/WizardDialog.vue',
  'src/components/common/AppDrawer.vue',
]
```

The script must use `parse` plus `compileStyleAsync` from `@vue/compiler-sfc` and Sass to inspect compiled selectors. For every new common SFC, reject hex/rgb/hsl color literals, `backdrop-filter`, `box-shadow` literals, and page-owned `[data-theme]`/`[data-brand]` selectors. Allow `rgb(var(--brand-primary-rgb) / alpha)` because it is token-derived.

- [ ] **Step 2: Add mutation proofs**

Run the contract against in-memory mutations and require each to fail: remove a `glass-surface-*` class; replace `var(--text-primary)` with `#111827`; add `backdrop-filter: blur(20px)`; remove a density class; replace a representative consumer import with a raw `el-card`. Print one deterministic failure per mutation.

- [ ] **Step 3: Run RED**

Run: `cd ai-admin-front; npm run check:glass-components`

Expected: exit `1`, listing the ten missing component/type files. It must not fail because of a parser exception.

- [ ] **Step 4: Record without committing**

Run: `git diff --check; git status --short`

Expected: only `package.json`, the new checker, and pre-existing foundation changes are listed; nothing is staged.

---

### Task 2: Define shared contracts and build PageHeader plus WorkbenchPanel

**Files:**
- Create: `ai-admin-front/src/components/common/glassWorkbench.ts`
- Create: `ai-admin-front/src/components/common/PageHeader.vue`
- Create: `ai-admin-front/src/components/common/WorkbenchPanel.vue`
- Modify: `ai-admin-front/scripts/check-glass-components-contract.mjs`

**Interfaces:**
- Consumes: `.glass-surface-panel`, semantic text/border/motion roles, and density classes from Phase 1.
- Produces: `WorkbenchDensity`, `densityClass()`, `<PageHeader>`, and `<WorkbenchPanel>`.

- [ ] **Step 1: Extend the checker before implementation**

Require the following public type surface and component contracts:

```ts
export type WorkbenchDensity = 'compact' | 'comfortable' | 'spacious'
export type StatusTone = 'success' | 'warning' | 'danger' | 'info' | 'neutral'
export type MetricTone = StatusTone | 'brand'
export function densityClass(density: WorkbenchDensity): `density-${WorkbenchDensity}`
```

`PageHeader` must expose props `title`, `eyebrow?`, `description?`, `density?`, `backLabel?`, `showBack?`; slots `leading`, `tags`, `meta`, `actions`; and emit `back`. `showBack` is the reactive visibility contract for the optional back button; consumers that bind `@back` must also set `show-back`. `WorkbenchPanel` must expose props `title?`, `description?`, `density?`, `level?` where level is `panel | control`; slots `header`, `actions`, default, `footer`.

- [ ] **Step 2: Run RED**

Run: `cd ai-admin-front; npm run check:glass-components`

Expected: exit `1` naming the missing contracts and both missing component recipes.

- [ ] **Step 3: Implement the exact shared types**

Use literal unions, `withDefaults`, and this density helper; do not add a store or composable:

```ts
export function densityClass(density: WorkbenchDensity): `density-${WorkbenchDensity}` {
  return `density-${density}`
}
```

Both components must render BEM roots (`app-page-header`, `workbench-panel`), include `glass-surface-panel`, apply `densityClass(props.density)`, use semantic variables only, and preserve visible focus on the optional back button. `WorkbenchPanel level="control"` switches to `glass-surface-control` and must not add another shadow.

- [ ] **Step 4: Run GREEN**

Run: `cd ai-admin-front; npm run check:glass-components; npx vue-tsc --noEmit`

Expected: the two component contracts pass; the overall checker still exits `1` only for later missing components; `vue-tsc` exits `0`.

- [ ] **Step 5: Record without committing**

Run: `git diff --check; git status --short`

---

### Task 3: Build MetricStrip and semantic StatusTag without duplicating existing assets

**Files:**
- Modify: `ai-admin-front/src/components/common/glassWorkbench.ts`
- Create: `ai-admin-front/src/components/common/MetricStrip.vue`
- Create: `ai-admin-front/src/components/common/StatusTag.vue`
- Modify: `ai-admin-front/src/components/common/MetricIconBg.vue`
- Modify: `ai-admin-front/src/components/CommonStatusTag.vue`
- Modify: `ai-admin-front/scripts/check-glass-components-contract.mjs`

**Interfaces:**
- Consumes: `MetricIconBg`, the five `--status-*-soft` roles, `commonStatusTagType()`, and `formatCommonStatusLabel()`.
- Produces: `MetricStripItem`, `<MetricStrip :items>`, and `<StatusTag tone label>`; existing `<CommonStatusTag status>` remains source-compatible.

- [ ] **Step 1: Add RED assertions for the public data shapes**

```ts
export interface MetricStripItem {
  key: string
  label: string
  value: string | number
  hint?: string
  tone?: MetricTone
  iconKey?: string
}
```

Require `MetricStrip` to use one `glass-surface-panel` root, internal dividers rather than independent card shadows, `MetricIconBg` for every icon, and `aria-label` support. Require `StatusTag` to accept `label`, `tone?`, `size?`, and `pulse?`, and map tone to Element Plus types without embedding business enum names.

- [ ] **Step 2: Run RED**

Run: `cd ai-admin-front; npm run check:glass-components`

Expected: exit `1` for missing MetricStrip/StatusTag and hard-coded visual literals in `MetricIconBg.vue`.

- [ ] **Step 3: Implement and adapt**

`MetricStrip` defaults to comfortable density and renders each item with `<MetricIconBg :icon-key="item.iconKey" :tone="item.tone ?? 'brand'" />`. Convert all MetricIconBg brand/status fills, borders, shadows, and text to existing brand/status/surface variables; keep its SVG geometry intact. `StatusTag` maps `success`, `warning`, `danger`, and `info` to matching Element Plus types and maps `neutral` to `info` plus the `status-tag--neutral` semantic class.

Change `CommonStatusTag.vue` into a compatibility adapter:

```vue
<StatusTag :label="label" :tone="tone" />
```

Its computed `tone` converts the existing `commonStatusTagType()` result to `StatusTone`; do not change `uiLabels.ts` or existing call sites.

- [ ] **Step 4: Run GREEN and regress existing consumers**

Run: `cd ai-admin-front; npm run check:glass-components; npm run check:dark-theme-compatibility; npx vue-tsc --noEmit`

Expected: no hard-coded MetricIconBg visual literal remains; compatibility consumers type-check; the overall checker may remain RED only for later components.

- [ ] **Step 5: Record without committing**

Run: `git diff --check; git status --short`

---

### Task 4: Build FilterBar and DataTableShell and finish Element Plus mappings

**Files:**
- Create: `ai-admin-front/src/components/common/FilterBar.vue`
- Create: `ai-admin-front/src/components/common/DataTableShell.vue`
- Modify: `ai-admin-front/src/styles/_element-plus.scss`
- Modify: `ai-admin-front/scripts/check-glass-components-contract.mjs`

**Interfaces:**
- Consumes: Element Plus buttons, inputs, table, empty, pagination, compact/comfortable density tokens, and shared glass recipes.
- Produces: slot-based filter composition and a table shell with controlled pagination.

- [ ] **Step 1: Add failing interface and mapping assertions**

`FilterBar` props: `density?`, `loading?`, `showReset?`, `queryLabel?`, `resetLabel?`; slots: default and `actions`; emits: `query`, `reset`. Submitting the root `<form>` emits `query`. `DataTableShell` props: `density?`, `loading?`, `empty?`, `emptyDescription?`, `currentPage?`, `pageSize?`, `total?`, `pageSizes?`, `paginationLayout?`; slots: `toolbar`, default, `empty`, `pagination`; emits `update:currentPage`, `update:pageSize`, `pageChange`, and `sizeChange`.

Require `_element-plus.scss` to map `.el-pagination`, `.el-tabs`, `.el-dialog`, `.el-drawer`, and `.el-table` to semantic surface/text/border/focus roles. No page-scoped Element Plus skin is allowed in the two new components.

- [ ] **Step 2: Run RED**

Run: `cd ai-admin-front; npm run check:glass-components`

Expected: exit `1` for the two missing components and incomplete pagination/tabs/overlay mappings.

- [ ] **Step 3: Implement slot ownership**

`FilterBar` uses a `glass-surface-control` root and places its default fields in `.filter-bar__fields`; the default actions contain Reset and Query buttons with exactly one primary action. `DataTableShell` uses a `glass-surface-panel` root, never creates an `el-table`, and owns only toolbar, table slot, empty state, and pagination. Show empty content only when `empty && !loading`; show pagination only when `total > 0` unless a custom pagination slot is present.

- [ ] **Step 4: Run GREEN**

Run: `cd ai-admin-front; npm run check:glass-theme; npm run check:glass-components; npm run check:dark-theme-compatibility; npx vue-tsc --noEmit`

Expected: all theme/component checks pass except assertions for overlays not yet created; TypeScript exits `0`.

- [ ] **Step 5: Record without committing**

Run: `git diff --check; git status --short`

---

### Task 5: Build AppDialog and AppDrawer as transparent Element Plus adapters

**Files:**
- Create: `ai-admin-front/src/components/common/AppDialog.vue`
- Create: `ai-admin-front/src/components/common/AppDrawer.vue`
- Modify: `ai-admin-front/scripts/check-glass-components-contract.mjs`

**Interfaces:**
- Consumes: Element Plus focus trap, teleport, close behavior, `v-model`, and overlay recipe.
- Produces: wrappers that forward `$attrs` and events without replacing Element Plus accessibility behavior.

- [ ] **Step 1: Add RED assertions**

Both wrappers must call `defineOptions({ inheritAttrs: false })`, bind `$attrs` to the Element Plus root, expose `modelValue`, title/description, size (`width` or `size`), and emit `update:modelValue`. `AppDialog` exposes slots header/default/footer. `AppDrawer` exposes header/default/footer. Both roots include `glass-surface-overlay`; neither implements its own mask, teleport, Escape handling, focus trap, or blur.

- [ ] **Step 2: Run RED**

Run: `cd ai-admin-front; npm run check:glass-components`

Expected: exit `1` naming both missing adapters.

- [ ] **Step 3: Implement minimal wrappers**

Forward `@update:model-value` directly. Use semantic description text and a semantic divider; let Element Plus own title IDs, close buttons, lifecycle events (`open`, `opened`, `close`, `closed`), destroy-on-close, append-to-body, and loading content supplied by consumers.

- [ ] **Step 4: Run GREEN**

Run: `cd ai-admin-front; npm run check:glass-components; npx vue-tsc --noEmit`

Expected: only WizardDialog/pilot assertions remain RED; wrapper contracts and SFC compilation pass.

- [ ] **Step 5: Record without committing**

Run: `git diff --check; git status --short`

---

### Task 6: Build WizardDialog with one unambiguous progression model

**Files:**
- Modify: `ai-admin-front/src/components/common/glassWorkbench.ts`
- Create: `ai-admin-front/src/components/common/WizardDialog.vue`
- Modify: `ai-admin-front/scripts/check-glass-components-contract.mjs`

**Interfaces:**
- Consumes: `AppDialog`, `StatusTone`, spacious density, and Element Plus buttons.
- Produces: `WizardStep`, accessible step rail, content, validation messaging, and fixed footer semantics.

- [ ] **Step 1: Add RED assertions for exact step state**

```ts
export type WizardStepState = 'pending' | 'current' | 'complete' | 'error' | 'skipped'
export interface WizardStep {
  key: string
  title: string
  description?: string
  state: WizardStepState
  disabled?: boolean
}
```

Props: `modelValue`, `title`, `description?`, `steps`, `activeStep`, `navigable?`, `canAdvance?`, `loading?`, `finishLabel?`; emits `update:modelValue`, `stepChange`, `back`, `next`, `cancel`, `finish`; slots `summary`, default, `footer`. Assert that intermediate steps expose one primary `next`, final step exposes one primary `finish`, and Save/Next/Finish never appear as simultaneous primary actions.

- [ ] **Step 2: Run RED**

Run: `cd ai-admin-front; npm run check:glass-components`

Expected: exit `1` for missing WizardDialog and step contracts.

- [ ] **Step 3: Implement progression and accessibility**

Use `<ol aria-label="步骤">`, `aria-current="step"` on the active item, disabled buttons for inaccessible steps, a polite live region for error state, a scrollable main body, and a fixed semantic footer. The step rail uses `glass-surface-control`; the outer overlay comes only from `AppDialog`. Defaults: spacious density, non-navigable rail, `canAdvance=true`, and `finishLabel='完成'`.

- [ ] **Step 4: Run GREEN**

Run: `cd ai-admin-front; npm run check:glass-components; npx vue-tsc --noEmit`

Expected: all nine shared components exist and pass their contract; failures may now name only missing pilot consumption.

- [ ] **Step 5: Record without committing**

Run: `git diff --check; git status --short`

---

### Task 7: Migrate the Tool list and its four-step Tool wizard

**Files:**
- Modify: `ai-admin-front/src/views/tool/ToolList.vue:1-520,982-1551`
- Modify: `ai-admin-front/scripts/check-glass-components-contract.mjs`

**Interfaces:**
- Consumes: PageHeader, FilterBar, DataTableShell, StatusTag, WizardDialog, AppDialog.
- Produces: the list-workbench proof and the wizard-dialog proof on `/tool`.

- [ ] **Step 1: Add consumer assertions and run RED**

Require direct imports of all six components, a comfortable page root, compact DataTableShell, semantic StatusTag uses for source/catalog state, WizardDialog with the existing `toolEditorSteps`, and AppDialog for the existing test dialog. Reject the legacy root classes `page-header`, `tool-filter`, `pagination-wrap`, `tool-editor-rail`, and raw `<el-dialog` in this view.

Run: `cd ai-admin-front; npm run check:glass-components`

Expected: exit `1` naming `/tool` as the missing list/wizard consumer.

- [ ] **Step 2: Replace only presentation shells**

Use this structure while retaining all current `el-table-column`, form fields, event handlers, computed values, and API functions byte-for-byte:

```vue
<PageHeader title="Tool 管理" description="管理平台可被 Agent 调用的 Tool、语义信息与运行开关">
  <template #actions>新建与刷新按钮</template>
</PageHeader>
<FilterBar :loading="loading" @query="handleSearch" @reset="resetFilters">筛选控件</FilterBar>
<DataTableShell v-model:current-page="pagination.current" v-model:page-size="pagination.size" density="compact" :loading="loading" :empty="tools.length === 0" :total="total" :page-sizes="[10, 20, 50, 100]" @page-change="fetchTools" @size-change="handlePageSizeChange">现有 Tool 表格</DataTableShell>
<WizardDialog v-model="formDialogVisible" :title="formDialogTitle" :steps="toolEditorStepsWithState" :active-step="activeToolStep" :can-advance="canAdvanceToolStep" finish-label="保存" @back="activeToolStep -= 1" @next="advanceToolStep" @finish="handleSave" @cancel="formDialogVisible = false">现有四个表单步骤</WizardDialog>
<AppDialog v-model="testDialogVisible" :title="`测试工具 — ${testingTool?.name}`" width="600px" append-to-body>现有测试表单与结果</AppDialog>
```

The prose labels in the snippet denote exact retained existing subtrees, not new text nodes. Add `toolEditorStepsWithState` as a computed projection of the existing four steps. `canAdvanceToolStep` requires name and description only on identity; later steps remain accessible. `advanceToolStep` warns and stays on identity if validation fails. Only the final step shows the primary Save action.

- [ ] **Step 3: Delete private visual recipes, keep layout rules**

Remove page-owned surface colors, borders, shadows, blur, light-theme override, wizard rail surface, and dialog skin. Keep grid, widths, overflow, responsive breakpoints, table cell truncation, and Markdown layout. Replace visual literals with semantic variables where content-specific styling remains.

- [ ] **Step 4: Run GREEN**

Run: `cd ai-admin-front; npm run check:glass-components; npm run check:glass-theme; npx vue-tsc --noEmit; npm run build`

Expected: all exit `0`; create/edit/test Tool handlers and API imports are unchanged.

- [ ] **Step 5: Record without committing**

Run: `git diff --check; git status --short`

---

### Task 8: Migrate the knowledge detail workbench and ordinary dialogs

**Files:**
- Modify: `ai-admin-front/src/views/KnowledgeDetail.vue:1-313,682-810`
- Modify: `ai-admin-front/scripts/check-glass-components-contract.mjs`

**Interfaces:**
- Consumes: PageHeader, MetricStrip, WorkbenchPanel, DataTableShell, StatusTag, AppDialog.
- Produces: detail-workbench proof on `/knowledge/:code` without changing knowledge APIs or tab state.

- [ ] **Step 1: Add RED assertions**

Require a PageHeader with `show-back` and back emit, a five-item MetricStrip derived from the existing `stats`, WorkbenchPanel for each overview section, compact table shells inside tabs, StatusTag for semantic states, and AppDialog for all three existing dialogs. Reject raw `<el-card` and `<el-dialog` only in this pilot after migration.

Run: `cd ai-admin-front; npm run check:glass-components`

Expected: exit `1` naming the detail pilot.

- [ ] **Step 2: Migrate structure without changing data behavior**

Create `knowledgeMetrics` as a computed array with keys `files`, `chunks`, `active`, `questions`, and `hits`, using the current counts. Keep all seven tab names, selections, pagination/fetch functions, and three form-submit handlers unchanged. AppDialog must forward the current widths and footer buttons. Page-specific SCSS may keep overview grid, toolbar layout, tag swatches from backend data, and responsive rules; replace private surface/text/shadow literals with semantic roles.

- [ ] **Step 3: Run GREEN**

Run: `cd ai-admin-front; npm run check:glass-components; npx vue-tsc --noEmit; npm run build`

Expected: exit `0`; the detail route still reads `route.params.code` and no API/type file changes.

- [ ] **Step 4: Record without committing**

Run: `git diff --check; git status --short`

---

### Task 9: Migrate the Agent form workbench

**Files:**
- Modify: `ai-admin-front/src/views/agent/AgentEdit.vue:1-147,379-502`
- Modify: `ai-admin-front/scripts/check-glass-components-contract.mjs`

**Interfaces:**
- Consumes: PageHeader, WorkbenchPanel, StatusTag, comfortable density.
- Produces: form-workbench proof on `/agent/new/edit` and `/agent/:id/edit`.

- [ ] **Step 1: Add RED assertions and run them**

Require PageHeader actions to retain Copy/Bindings/Studio/Save conditions, a primary WorkbenchPanel for identity/strategy, a conditional secondary panel only in edit mode, and no fixed empty second column in new mode. Reject `#f6f8fc`, `#fff`, `#e5eaf4`, and the legacy `.workbench-header`, `.panel`, `.summary-panel` surface recipe.

Run: `cd ai-admin-front; npm run check:glass-components`

Expected: exit `1` naming AgentEdit hard-coded surfaces.

- [ ] **Step 2: Migrate presentation only**

Keep the current `el-form`, rules, JSON parsing, project/model loading, save, copy, bindings, and studio handlers. Use PageHeader tags for kind/enabled; use WorkbenchPanel title/description/actions for the enable switch; keep the responsive two-column field grid. Do not add a column when `isNew` is true.

- [ ] **Step 3: Run GREEN**

Run: `cd ai-admin-front; npm run check:glass-components; npx vue-tsc --noEmit; npm run build`

Expected: all exit `0`; no API, type, router, or Workflow Studio file changes in this task.

- [ ] **Step 4: Record without committing**

Run: `git diff --check; git status --short`

---

### Task 10: Migrate the MCP onboarding page at spacious density

**Files:**
- Modify: `ai-admin-front/src/views/mcp/McpOnboarding.vue:1-130`
- Modify: `ai-admin-front/scripts/check-glass-components-contract.mjs`

**Interfaces:**
- Consumes: PageHeader, WorkbenchPanel, CodeSnippetBlock, spacious density.
- Produces: step-onboarding proof on `/mcp/onboarding` without changing generated endpoint/config text.

- [ ] **Step 1: Add RED assertions and run them**

Require `density-spacious`, one PageHeader, five ordered WorkbenchPanels, and CodeSnippetBlock for Cursor, Claude, generic HTTP, and curl examples. Reject direct `<el-card`, raw `<pre>`, and local light-theme overrides.

Run: `cd ai-admin-front; npm run check:glass-components`

Expected: exit `1` naming the onboarding pilot and hard-coded code surfaces.

- [ ] **Step 2: Migrate while preserving exact examples**

Keep `jsonrpcUrl`, `manifestUrl`, all four computed examples, and `copy()` unchanged. Use the existing ordered numbers in panel titles and preserve the warning content/link. Page SCSS may keep only two-column responsive layout and code wrapping; CodeSnippetBlock owns code surface and copy behavior.

- [ ] **Step 3: Run GREEN**

Run: `cd ai-admin-front; npm run check:glass-components; npx vue-tsc --noEmit; npm run build`

Expected: exit `0`; generated strings still contain `Authorization: Bearer YOUR_API_KEY` and the existing endpoints.

- [ ] **Step 4: Record without committing**

Run: `git diff --check; git status --short`

---

### Task 11: Migrate the Tool retrieval test workbench

**Files:**
- Modify: `ai-admin-front/src/views/tool/ToolRetrievalTest.vue:1-152,330-357`
- Modify: `ai-admin-front/scripts/check-glass-components-contract.mjs`

**Interfaces:**
- Consumes: PageHeader, WorkbenchPanel, DataTableShell, StatusTag, AppDialog.
- Produces: debug/test-workbench proof on `/tool/retrieval`.

- [ ] **Step 1: Add RED assertions and run them**

Require stable input/config, run action, results, task state, and rebuild AppDialog sections in that order. Reject raw `<el-card`/`<el-dialog` and layout-shifting conditional wrappers outside fixed WorkbenchPanel shells.

Run: `cd ai-admin-front; npm run check:glass-components`

Expected: exit `1` naming the debug/test pilot.

- [ ] **Step 2: Migrate the shells**

Keep search/rebuild requests, polling timer lifecycle, provider filtering, task percentage, and all existing form controls unchanged. Results use compact DataTableShell; task state uses semantic StatusTag; AppDialog forwards `destroy-on-close` and `@open="loadEmbeddingInstances"` through `$attrs`.

- [ ] **Step 3: Run GREEN**

Run: `cd ai-admin-front; npm run check:glass-components; npx vue-tsc --noEmit; npm run build`

Expected: all exit `0`; `onUnmounted` still clears polling and no API code changes.

- [ ] **Step 4: Record without committing**

Run: `git diff --check; git status --short`

---

### Task 12: Migrate RunOps monitoring and the A2A detail drawer

**Files:**
- Modify: `ai-admin-front/src/views/runops/RunOpsList.vue:1-270,459-623`
- Modify: `ai-admin-front/src/views/a2a/A2aSessionMonitor.vue:1-154`
- Modify: `ai-admin-front/scripts/check-glass-components-contract.mjs`

**Interfaces:**
- Consumes: all data workbench components plus AppDrawer at compact density.
- Produces: monitoring/audit proof on `/runops` and drawer proof on `/a2a/monitor`.

- [ ] **Step 1: Add RED assertions and run them**

Require `density-compact` on both page roots. RunOps must use PageHeader, MetricStrip, FilterBar, WorkbenchPanel, DataTableShell, and StatusTag. A2A must use PageHeader, FilterBar, DataTableShell, StatusTag, and AppDrawer. Reject `var(--fill-color-light)`, `var(--border-color)`, `var(--card-bg)`, raw `<el-card`, and raw `<el-drawer` in the two pilots.

Run: `cd ai-admin-front; npm run check:glass-components`

Expected: exit `1` naming both monitor consumers.

- [ ] **Step 2: Migrate RunOps without changing diagnostics**

Project existing `kpis` into MetricStrip items; retain `filteredRuns`, trend calculation, diagnostics tables, pagination, trace navigation, and all watchers. Use semantic status roles for bars and tags. Use compact DataTableShell for failures, comparisons, and recent runs; page CSS keeps grids/chart geometry only.

- [ ] **Step 3: Migrate A2A and its drawer**

Move header filters to FilterBar, table/pagination to DataTableShell, success to StatusTag, and the detail subtree to AppDrawer. Preserve `openDetail`, `goTrace`, `pretty`, API pagination, row click, and exact 50/100/200 page-size options. Payload blocks use `glass-surface-control` with semantic text/border roles and no copy action.

- [ ] **Step 4: Run GREEN**

Run: `cd ai-admin-front; npm run check:glass-components; npx vue-tsc --noEmit; npm run build`

Expected: all exit `0`; no runops/a2a API or type files changed.

- [ ] **Step 5: Record without committing**

Run: `git diff --check; git status --short`

---

### Task 13: Adopt shared overlays in Workflow Studio without changing its layout

**Files:**
- Modify: `ai-admin-front/src/views/workflow/WorkflowStudio.vue:1268-1293,1729-1738,1980-2050`
- Modify: `ai-admin-front/scripts/check-glass-components-contract.mjs`

**Interfaces:**
- Consumes: AppDialog and AppDrawer only.
- Produces: Studio shared-control proof while preserving the entire special canvas archetype.

- [ ] **Step 1: Freeze the boundary in the checker**

Require the existing root `studio-page`, Vue Flow canvas, node rail, zoom controls, debug drawer, graph state/composables, and route parameter use to remain. Require only the `nodeSearchOpen` dialog to use AppDialog and the `jsonDrawerVisible` drawer to use AppDrawer. Reject changes to canvas container class names and do not require PageHeader, MetricStrip, FilterBar, or DataTableShell inside Studio.

- [ ] **Step 2: Run RED**

Run: `cd ai-admin-front; npm run check:glass-components`

Expected: exit `1` only for the two selected raw overlay consumers.

- [ ] **Step 3: Replace the two outer tags only**

Keep every child node, handler, class, width/size, and v-model intact. Add imports for AppDialog/AppDrawer. Remove only redundant local background/border/shadow declarations for those two overlay roots; do not move or resize panels, canvas, rail, nodes, minimap, zoom, or debug UI.

- [ ] **Step 4: Run Studio regression gates**

Run: `cd ai-admin-front; npm run check:glass-components; npm run check:studio-canvas; npx vue-tsc --noEmit; npm run build`

Expected: all exit `0`; the Studio canvas contract remains unchanged.

- [ ] **Step 5: Record without committing**

Run: `git diff --check; git status --short`

---

### Task 14: Sync stable component masters to Figma without blocking local delivery

**Files / External artifacts:**
- Modify existing Figma file: `dLSJOki5yBoBeEx2GEZHU5`
- Reuse Foundations page: `269:284`
- Reuse component nodes: sidebar `760:291`, metric icon `768:129`, pagination `785:415`
- Update local-only context if IDs/decisions change: `D:\work\EnterpriseAgentFramework\UI_AGENT.local.md`

**Interfaces:**
- Consumes: reviewed Vue public interfaces and exact foundation variables/styles.
- Produces: Figma component masters/pattern frames matching code; no business-screen duplicates.

- [ ] **Step 1: Load required Figma skills and inspect before writing**

Read `figma:figma-use` and `figma:figma-generate-library` completely. Inspect local variables, styles, Foundations, existing main components, and instances. Use the existing file; never create a replacement file because authentication failed.

- [ ] **Step 2: Reconcile component masters and variants**

Create or update masters for PageHeader, MetricStrip, WorkbenchPanel, FilterBar, DataTableShell, StatusTag, AppDialog, WizardDialog, and AppDrawer using the established variable collections. MetricStrip instances must contain instances of metric icon `768:129`; DataTableShell patterns must contain pagination `785:415`; any pattern frame needing navigation must contain an instance of sidebar `760:291`. Do not detach or copy those main components.

- [ ] **Step 3: Add archetype pattern frames, not product-screen copies**

Create one compact skeleton frame for each of the seven archetypes using neutral labels and component instances. Do not recreate Tool, knowledge, Agent, MCP, RunOps, or Studio business data/screens. Studio pattern shows only shared controls beside a placeholder canvas region.

- [ ] **Step 4: Handle OAuth failure explicitly**

If Figma reports OAuth/authentication unavailable, record `Figma component sync: deferred - OAuth required` plus the intended component names in the local-only `UI_AGENT.local.md`, keep all frontend tasks moving, and retry once during final acceptance. Do not mark frontend build/contract work failed solely because the external write is blocked.

- [ ] **Step 5: Verify IDs and local-only hygiene**

At 100% zoom verify all created/updated masters and two instances each where present. Record new node IDs in `UI_AGENT.local.md`. Run `git status --short`; the local file must not be staged or copied into the worktree.

---

### Task 15: Run the final contract, real-route visual matrix, and sensitive-output cleanup

**Files:**
- Modify only for verified corrections: files from Tasks 1-13.
- Temporary ignored QA directory: `.cursor/playwright-output/glass-components-qa`

**Interfaces:**
- Consumes: every public component, eight pilot routes, three refined registry regression routes, all theme/fallback modes.
- Produces: reproducible acceptance evidence with no retained credentials or private payload artifacts.

- [ ] **Step 1: Run the complete static/build matrix from a fresh shell**

```powershell
cd D:\work\.codex-worktrees\EnterpriseAgentFramework\reachai-glass-foundation\ai-admin-front
npm run check:glass-theme
npm run check:glass-components
npm run check:dark-theme-compatibility
npm run check:studio-canvas
npm run check:sdk-access-workbench
npm run check:page-assistant-progress
npm run check:scan-project-detail-ui
npx vue-tsc --noEmit
npm run build
```

Expected: every command exits `0`.

- [ ] **Step 2: Start the QA server and use an authenticated local browser session**

Run: `npm run dev -- --host 127.0.0.1 --port 5200`

Use Playwright at exactly `1600 x 1000`. Do not put platform tokens, cookies, API keys, passwords, Authorization headers, request/response bodies, localStorage dumps, or browser storage state in prompts, filenames, screenshots, traces, HAR, console exports, or the plan. If the browser redirects to login and no existing safe local session is available, report the authentication blocker; do not request or persist credentials.

- [ ] **Step 3: Verify light-polished pilot routes**

Open `/tool`, `/agent/new/edit`, `/mcp/onboarding`, `/tool/retrieval`, `/runops`, and `/a2a/monitor`. Open the first existing knowledge entry from `/knowledge` to reach the real `/knowledge/:code` route. Open the first existing workflow from `/workflows` to reach `/workflows/:workflowId/studio`; if no real row exists, record a seed-data blocker and rely only on static Studio checks rather than fabricating data.

For each available route verify: no horizontal overflow at 1600×1000, no clipped footer/actions, one coherent depth hierarchy, readable focus, stable loading/empty state, and no page-owned flat white/gray surface. On `/tool`, open the create wizard and traverse all four steps; on `/tool/retrieval`, open/close rebuild dialog; on `/a2a/monitor`, open the first real row drawer when data exists.

- [ ] **Step 4: Verify brand, dark, and fallback samples**

- On `/tool`, switch all seven `data-brand` values and confirm primary action, selected/focus states, wizard current step, and metric/icon emphasis follow the brand while success/warning/danger/info remain unchanged.
- On all available pilot routes, set `data-theme="dark"` and confirm text, forms, tables, dialog/drawer bodies, masks, disabled controls, focus, and scrolling remain usable.
- On `/tool`, `/agent/new/edit`, and `/a2a/monitor`, set `data-reduce-transparency="true"`; confirm shell/panel/control/overlay become solid with no layout shift.
- Emulate `prefers-reduced-motion: reduce` on `/tool` and `/a2a/monitor`; confirm transitions settle immediately and wizard/drawer operation remains clear.
- Recheck `/registry/projects`, a real `/registry/projects/:projectCode`, and its `/sdk-access` child at 1600×1000 to catch foundation regressions without migrating them.

- [ ] **Step 5: Clean sensitive Playwright output safely**

Create only `.cursor/playwright-output/glass-components-qa` for temporary screenshots. Do not enable trace, video, HAR, or storage-state recording. After review, resolve both repository and QA paths, verify the QA path is a descendant of the repository, then remove only that directory:

```powershell
$repo = (Resolve-Path 'D:\work\.codex-worktrees\EnterpriseAgentFramework\reachai-glass-foundation').Path
$qa = (Resolve-Path (Join-Path $repo '.cursor\playwright-output\glass-components-qa')).Path
if (-not $qa.StartsWith($repo + [IO.Path]::DirectorySeparatorChar)) { throw "Refusing to remove output outside repository: $qa" }
Remove-Item -LiteralPath $qa -Recurse -Force
```

Expected: no screenshot, DOM snapshot, trace, HAR, storage state, console dump, or payload file remains. If a screenshot accidentally contains a token, user identity, Tool secret, request body, response body, knowledge content, or private Agent prompt, delete it immediately before any review handoff.

- [ ] **Step 6: Final diff hygiene without staging or committing**

```powershell
cd D:\work\.codex-worktrees\EnterpriseAgentFramework\reachai-glass-foundation
git diff --check
git status --short
git diff --name-only
git diff --cached --name-only
```

Expected: no whitespace errors; cached output is empty; no backend, SQL, API, router, or business type file appears; `UI_AGENT.local.md` is not staged; temporary QA output is absent. Keep all accepted changes uncommitted for the user.

---

## Self-Review Result

- Spec coverage: all nine requested public components, seven page archetypes, the separate drawer proof, C3 glass, all seven brands, light/dark, all three densities, transparency/motion fallback, Element Plus mappings, Studio boundary, Figma checkpoint, real-route QA, and sensitive-output cleanup have explicit tasks and gates.
- Scope boundary: the three already refined registry pages are regression-only; business logic, APIs, routes, backend, and SQL are explicitly excluded.
- Interface consistency: all later consumers use the types, props, slots, and emits defined in Tasks 2-6; CommonStatusTag remains backward compatible.
- Placeholder scan: implementation steps use exact files, interfaces, commands, retained stable boundaries, and expected RED/GREEN outcomes; no unresolved implementation marker remains.
- Git policy: every task records the diff only; no task stages, commits, pushes, merges, or creates a PR.
