# ReachAI Glass Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first independently testable slice of the ReachAI C3 global strong-glass design system: seven brand primitives, light/dark semantic tokens, three density modes, Element Plus mappings, glass fallbacks, two real shared-shell consumers, and matching Figma foundations.

**Architecture:** Split the current monolithic `theme.scss` token declarations into focused Sass partials with a one-way dependency chain: brand primitives -> semantic roles -> density/glass recipes -> Element Plus aliases -> shared consumers. Keep the existing component-level light/dark selectors in `theme.scss` during this slice so behavior stays stable; later component-migration plans remove them area by area. Validate the source contract with the repository's existing Node check-script pattern, then validate compiled output and real routes.

**Tech Stack:** Vue 3.5, TypeScript 5.6, Sass `^1.83.0`, Element Plus `^2.9.1`, Vite 6, Node.js contract scripts, Figma Variables/Modes/Styles.

## Global Constraints

- Use C3 `全域强玻璃`, but show no more than three visually distinct depth layers in one region.
- Keep all seven brand palettes: `tech-purple`, `metro-green`, `aurora-cyan`, `nebula-violet`, `coral-rose`, `solar-gold`, `deep-ocean`.
- Primary actions, selected states, focus rings, and AI emphasis must follow the active `data-brand`; business success, warning, danger, info, and neutral states must not reuse brand meaning.
- Polish light mode; keep dark mode functionally compatible. Keep the existing light-mode lock and disabled sidebar mode controls in this slice.
- Provide `compact`, `comfortable`, and `spacious` density contracts. Default to `comfortable`.
- Apply real `backdrop-filter` only to shell, panel, and overlay containers; table cells and repeated rows use precomputed translucent or solid surfaces.
- Provide unsupported-blur, reduced-transparency, and reduced-motion fallbacks.
- Do not replace Element Plus or introduce a second UI framework.
- Do not redesign Workflow Studio or Agent Studio layouts; they only consume shared tokens and controls in later slices.
- Do not change business logic, routes, API contracts, SQL, or backend code.
- Create and use branch `codex/reachai-glass-foundation`, but do not create Git commits; keep all implementation changes reviewable in the worktree.
- Do not commit `UI_AGENT.local.md`; update it locally only when Figma node IDs, shared components, or durable design decisions change.

---

## File Structure

- `ai-admin-front/src/styles/tokens/_brand.scss`: seven brand primitive modes only.
- `ai-admin-front/src/styles/tokens/_semantic.scss`: light/dark semantic roles plus temporary compatibility aliases for existing pages.
- `ai-admin-front/src/styles/tokens/_density.scss`: density source values and active density aliases.
- `ai-admin-front/src/styles/_glass.scss`: reusable shell/panel/control/overlay recipes and accessibility/performance fallbacks.
- `ai-admin-front/src/styles/_element-plus.scss`: the sole global mapping from ReachAI semantic roles to Element Plus variables.
- `ai-admin-front/src/styles/theme.scss`: imports the new layers and temporarily retains only legacy component-level selectors.
- `ai-admin-front/scripts/check-glass-theme-contract.mjs`: deterministic source-level architecture check.
- `ai-admin-front/src/components/common/AppPageBackground.vue`: first page-canvas consumer.
- `ai-admin-front/src/components/common/AppSidebar.vue`: first strong-glass shell consumer.
- `ai-admin-front/package.json`: exposes `npm run check:glass-theme`.
- Figma file `dLSJOki5yBoBeEx2GEZHU5`, Foundations page `269:284`: mirrors variables, modes, paint styles, and effect styles.

---

### Task 1: Extract the seven brand primitive modes behind a contract test

**Files:**
- Create: `ai-admin-front/scripts/check-glass-theme-contract.mjs`
- Create: `ai-admin-front/src/styles/tokens/_brand.scss`
- Modify: `ai-admin-front/package.json`

**Interfaces:**
- Consumes: `html[data-brand]` values emitted by `src/composables/useTheme.ts`.
- Produces: `--brand-primary`, `--brand-hover`, `--brand-active`, `--brand-disabled`, `--brand-selected`, and RGB channel variables for semantic tokens and Element Plus.

- [ ] **Step 1: Add the package command and write the failing brand contract**

Add this script entry after `build`:

```json
"check:glass-theme": "node scripts/check-glass-theme-contract.mjs"
```

Create the checker with this initial content:

```js
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import * as sass from 'sass'

const root = process.cwd()
const failures = []

function read(relativePath) {
  const absolutePath = resolve(root, relativePath)
  if (!existsSync(absolutePath)) {
    failures.push(`${relativePath} is missing`)
    return ''
  }
  return readFileSync(absolutePath, 'utf8')
}

function expectIncludes(source, needle, message) {
  if (!source.includes(needle)) failures.push(message)
}

function expectSassCompiles(relativePath) {
  const absolutePath = resolve(root, relativePath)
  if (!existsSync(absolutePath)) return
  try {
    sass.compile(absolutePath, { style: 'compressed' })
  } catch (error) {
    failures.push(`${relativePath} does not compile: ${error.message}`)
  }
}

const brand = read('src/styles/tokens/_brand.scss')
const brandModes = [
  'tech-purple',
  'metro-green',
  'aurora-cyan',
  'nebula-violet',
  'coral-rose',
  'solar-gold',
  'deep-ocean',
]

for (const mode of brandModes) {
  expectIncludes(brand, `[data-brand='${mode}']`, `brand mode ${mode} is missing`)
}

for (const token of [
  '--brand-primary:',
  '--brand-hover:',
  '--brand-active:',
  '--brand-disabled:',
  '--brand-selected:',
  '--brand-primary-rgb:',
  '--brand-hover-rgb:',
  '--brand-active-rgb:',
  '--brand-selected-rgb:',
]) {
  expectIncludes(brand, token, `brand primitive ${token} is missing`)
}

expectSassCompiles('src/styles/tokens/_brand.scss')

if (failures.length) {
  console.error(failures.join('\n'))
  process.exit(1)
}

console.log('ReachAI glass theme contract is aligned.')
```

- [ ] **Step 2: Run the contract and confirm it fails for the missing partial**

Run: `cd ai-admin-front; npm run check:glass-theme`

Expected: exit code `1` and `src/styles/tokens/_brand.scss is missing`.

- [ ] **Step 3: Create the brand primitive partial**

Create `_brand.scss` with the exact mode values below. Keep page, glass, status, text, shadow, and component meanings out of this file.

```scss
:root,
[data-brand='tech-purple'] {
  --brand-primary: #6366f1;
  --brand-hover: #8b5cf6;
  --brand-active: #4f46e5;
  --brand-disabled: #a5b4fc;
  --brand-selected: #ede9fe;
  --brand-primary-rgb: 99 102 241;
  --brand-hover-rgb: 139 92 246;
  --brand-active-rgb: 79 70 229;
  --brand-selected-rgb: 237 233 254;
}

[data-brand='metro-green'] {
  --brand-primary: #0b7a59;
  --brand-hover: #2d9375;
  --brand-active: #096247;
  --brand-disabled: #71c6ac;
  --brand-selected: #e3f7f2;
  --brand-primary-rgb: 11 122 89;
  --brand-hover-rgb: 45 147 117;
  --brand-active-rgb: 9 98 71;
  --brand-selected-rgb: 227 247 242;
}

[data-brand='aurora-cyan'] {
  --brand-primary: #0891b2;
  --brand-hover: #22d3ee;
  --brand-active: #0e7490;
  --brand-disabled: #a5f3fc;
  --brand-selected: #ecfeff;
  --brand-primary-rgb: 8 145 178;
  --brand-hover-rgb: 34 211 238;
  --brand-active-rgb: 14 116 144;
  --brand-selected-rgb: 236 254 255;
}

[data-brand='nebula-violet'] {
  --brand-primary: #7c3aed;
  --brand-hover: #ec4899;
  --brand-active: #6d28d9;
  --brand-disabled: #c4b5fd;
  --brand-selected: #f3e8ff;
  --brand-primary-rgb: 124 58 237;
  --brand-hover-rgb: 236 72 153;
  --brand-active-rgb: 109 40 217;
  --brand-selected-rgb: 243 232 255;
}

[data-brand='coral-rose'] {
  --brand-primary: #e11d48;
  --brand-hover: #fb7185;
  --brand-active: #be123c;
  --brand-disabled: #fda4af;
  --brand-selected: #ffe4e6;
  --brand-primary-rgb: 225 29 72;
  --brand-hover-rgb: 251 113 133;
  --brand-active-rgb: 190 18 60;
  --brand-selected-rgb: 255 228 230;
}

[data-brand='solar-gold'] {
  --brand-primary: #b45309;
  --brand-hover: #f59e0b;
  --brand-active: #92400e;
  --brand-disabled: #fcd34d;
  --brand-selected: #fef3c7;
  --brand-primary-rgb: 180 83 9;
  --brand-hover-rgb: 245 158 11;
  --brand-active-rgb: 146 64 14;
  --brand-selected-rgb: 254 243 199;
}

[data-brand='deep-ocean'] {
  --brand-primary: #1d4ed8;
  --brand-hover: #06b6d4;
  --brand-active: #1e40af;
  --brand-disabled: #93c5fd;
  --brand-selected: #dbeafe;
  --brand-primary-rgb: 29 78 216;
  --brand-hover-rgb: 6 182 212;
  --brand-active-rgb: 30 64 175;
  --brand-selected-rgb: 219 234 254;
}

:root,
[data-brand] {
  --brand-selected-bg: var(--brand-selected);
  --brand-primary-gradient: linear-gradient(135deg, var(--brand-primary), var(--brand-hover));
}
```

- [ ] **Step 4: Run the brand contract and compile the partial through the Node Sass API**

Run: `cd ai-admin-front; npm run check:glass-theme`

Expected: exit code `0`; the checker compiles the partial and prints `ReachAI glass theme contract is aligned.`

- [ ] **Step 5: Record the independently passing brand diff without committing**

```powershell
git diff --check
git status --short
```

---

### Task 2: Add light/dark semantic roles and adaptive density

**Files:**
- Create: `ai-admin-front/src/styles/tokens/_semantic.scss`
- Create: `ai-admin-front/src/styles/tokens/_density.scss`
- Modify: `ai-admin-front/scripts/check-glass-theme-contract.mjs`

**Interfaces:**
- Consumes: all `--brand-*` primitives from Task 1.
- Produces: `--surface-*`, `--border-*`, `--shadow-*`, `--glass-*`, `--text-*`, `--status-*`, `--motion-*`, and active `--density-*` roles; retains existing aliases such as `--bg-primary` and `--text-primary` while pages migrate.

- [ ] **Step 1: Extend the checker with semantic and density requirements**

Insert after the brand assertions:

```js
const semantic = read('src/styles/tokens/_semantic.scss')
const density = read('src/styles/tokens/_density.scss')

for (const selector of [":root,", "[data-theme='light']", "[data-theme='dark']"]) {
  expectIncludes(semantic, selector, `semantic theme selector ${selector} is missing`)
}

for (const token of [
  '--surface-page-background:',
  '--surface-glass-shell:',
  '--surface-glass-panel:',
  '--surface-glass-control:',
  '--surface-glass-overlay:',
  '--surface-glass-selected:',
  '--surface-glass-disabled:',
  '--surface-solid-disabled:',
  '--surface-fallback-shell:',
  '--surface-fallback-panel:',
  '--surface-fallback-overlay:',
  '--border-subtle:',
  '--border-readable:',
  '--border-focus:',
  '--border-divider:',
  '--inner-highlight:',
  '--shadow-shell:',
  '--shadow-panel:',
  '--shadow-overlay:',
  '--glass-blur-shell:',
  '--glass-blur-panel:',
  '--glass-blur-overlay:',
  '--text-primary:',
  '--text-secondary:',
  '--text-muted:',
  '--text-disabled:',
  '--text-inverse:',
  '--text-link:',
  '--status-success:',
  '--status-warning:',
  '--status-danger:',
  '--status-info:',
  '--status-neutral:',
]) {
  expectIncludes(semantic, token, `semantic token ${token} is missing`)
}

for (const mode of ['compact', 'comfortable', 'spacious']) {
  expectIncludes(density, `[data-density='${mode}']`, `density mode ${mode} is missing`)
}
for (const token of ['--control-height:', '--row-height:', '--section-gap:', '--panel-padding:']) {
  expectIncludes(density, token, `active density token ${token} is missing`)
}

expectSassCompiles('src/styles/tokens/_semantic.scss')
expectSassCompiles('src/styles/tokens/_density.scss')
```

- [ ] **Step 2: Run the extended contract and confirm both partials are reported missing**

Run: `cd ai-admin-front; npm run check:glass-theme`

Expected: exit code `1`, naming `_semantic.scss` and `_density.scss`.

- [ ] **Step 3: Create the semantic theme partial**

Use the following exact role values. Keep gradients only on `--surface-glass-*`; every `--surface-solid-*` remains valid for CSS `background-color` and Element Plus variables.

```scss
:root,
[data-theme='light'] {
  color-scheme: light;
  --surface-page-canvas: #eaf4fb;
  --surface-page-background:
    radial-gradient(720px 520px at 54% -12%, rgb(var(--brand-primary-rgb) / 0.16), transparent 70%),
    radial-gradient(660px 560px at 82% 24%, rgb(var(--brand-hover-rgb) / 0.13), transparent 72%),
    radial-gradient(760px 420px at 50% 62%, rgb(255 255 255 / 0.42), transparent 74%),
    linear-gradient(150deg, #dceefa 0%, #f5fbff 50%, #e8f7f2 100%);
  --surface-solid-page: #edf6fb;
  --surface-solid-panel: rgb(255 255 255 / 0.82);
  --surface-solid-control: rgb(255 255 255 / 0.76);
  --surface-solid-overlay: rgb(250 253 255 / 0.94);
  --surface-solid-disabled: #eef2f6;
  --surface-glass-shell: linear-gradient(145deg, rgb(255 255 255 / 0.72), rgb(var(--brand-selected-rgb) / 0.42));
  --surface-glass-panel: linear-gradient(145deg, rgb(255 255 255 / 0.78), rgb(239 248 253 / 0.54));
  --surface-glass-control: linear-gradient(145deg, rgb(255 255 255 / 0.82), rgb(238 247 252 / 0.62));
  --surface-glass-overlay: linear-gradient(145deg, rgb(255 255 255 / 0.92), rgb(238 247 252 / 0.78));
  --surface-glass-selected: linear-gradient(145deg, rgb(255 255 255 / 0.76), rgb(var(--brand-selected-rgb) / 0.76));
  --surface-glass-disabled: linear-gradient(145deg, rgb(248 250 252 / 0.62), rgb(226 232 240 / 0.46));
  --surface-fallback-shell: #eef5fb;
  --surface-fallback-panel: #f7fbfd;
  --surface-fallback-control: #f8fbfd;
  --surface-fallback-overlay: #f7fbfd;
  --border-subtle: rgb(174 205 230 / 0.42);
  --border-readable: rgb(162 194 221 / 0.7);
  --border-focus: rgb(var(--brand-primary-rgb) / 0.72);
  --border-divider: rgb(176 204 226 / 0.42);
  --inner-highlight: inset 0 1px 0 rgb(255 255 255 / 0.82);
  --shadow-shell: 0 24px 64px rgb(37 72 122 / 0.12), var(--inner-highlight);
  --shadow-panel: 0 14px 38px rgb(37 72 122 / 0.09), var(--inner-highlight);
  --shadow-overlay: 0 28px 80px rgb(24 45 78 / 0.2), var(--inner-highlight);
  --shadow-active: 0 10px 30px rgb(var(--brand-primary-rgb) / 0.2), var(--inner-highlight);
  --shadow-danger: 0 10px 30px rgb(225 29 72 / 0.18);
  --glass-blur-shell: 28px;
  --glass-blur-panel: 22px;
  --glass-blur-overlay: 32px;
  --glass-saturation: 1.18;
  --text-primary: #14233d;
  --text-secondary: #465a74;
  --text-muted: #71839c;
  --text-disabled: #a4b1c3;
  --text-inverse: #ffffff;
  --text-link: var(--brand-active);
  --status-success: #169b6b;
  --status-warning: #d97706;
  --status-danger: #d92d4f;
  --status-info: #367fbd;
  --status-neutral: #64748b;
  --status-success-soft: rgb(22 155 107 / 0.12);
  --status-warning-soft: rgb(217 119 6 / 0.12);
  --status-danger-soft: rgb(217 45 79 / 0.12);
  --status-info-soft: rgb(54 127 189 / 0.12);
  --status-neutral-soft: rgb(100 116 139 / 0.12);
  --radius-sm: 6px;
  --radius-md: 10px;
  --radius-lg: 16px;
  --radius-xl: 20px;
  --motion-duration-fast: 150ms;
  --motion-duration-normal: 240ms;
  --motion-duration-slow: 400ms;
  --motion-easing-standard: cubic-bezier(0.4, 0, 0.2, 1);
}

[data-theme='dark'] {
  color-scheme: dark;
  --surface-page-canvas: #08121c;
  --surface-page-background:
    radial-gradient(720px 520px at 54% -12%, rgb(var(--brand-primary-rgb) / 0.22), transparent 70%),
    radial-gradient(660px 560px at 82% 24%, rgb(var(--brand-hover-rgb) / 0.14), transparent 72%),
    linear-gradient(150deg, #07111d 0%, #0a1422 50%, #071a1a 100%);
  --surface-solid-page: #0b1622;
  --surface-solid-panel: rgb(16 27 42 / 0.9);
  --surface-solid-control: rgb(20 32 49 / 0.88);
  --surface-solid-overlay: rgb(15 25 39 / 0.96);
  --surface-solid-disabled: #1a2839;
  --surface-glass-shell: linear-gradient(145deg, rgb(24 37 56 / 0.9), rgb(10 22 34 / 0.84));
  --surface-glass-panel: linear-gradient(145deg, rgb(24 37 56 / 0.82), rgb(12 24 38 / 0.74));
  --surface-glass-control: linear-gradient(145deg, rgb(30 44 64 / 0.84), rgb(17 30 47 / 0.78));
  --surface-glass-overlay: linear-gradient(145deg, rgb(25 39 58 / 0.94), rgb(12 23 36 / 0.9));
  --surface-glass-selected: linear-gradient(145deg, rgb(var(--brand-primary-rgb) / 0.24), rgb(var(--brand-active-rgb) / 0.16));
  --surface-glass-disabled: linear-gradient(145deg, rgb(36 49 66 / 0.54), rgb(18 29 43 / 0.52));
  --surface-fallback-shell: #111e2d;
  --surface-fallback-panel: #142235;
  --surface-fallback-control: #18283b;
  --surface-fallback-overlay: #111e2d;
  --border-subtle: rgb(148 163 184 / 0.16);
  --border-readable: rgb(148 163 184 / 0.28);
  --border-focus: rgb(var(--brand-disabled-rgb, var(--brand-primary-rgb)) / 0.8);
  --border-divider: rgb(148 163 184 / 0.16);
  --inner-highlight: inset 0 1px 0 rgb(255 255 255 / 0.06);
  --shadow-shell: 0 24px 64px rgb(0 0 0 / 0.42), var(--inner-highlight);
  --shadow-panel: 0 14px 38px rgb(0 0 0 / 0.32), var(--inner-highlight);
  --shadow-overlay: 0 28px 80px rgb(0 0 0 / 0.56), var(--inner-highlight);
  --shadow-active: 0 10px 30px rgb(var(--brand-primary-rgb) / 0.26), var(--inner-highlight);
  --shadow-danger: 0 10px 30px rgb(244 63 94 / 0.2);
  --glass-blur-shell: 26px;
  --glass-blur-panel: 20px;
  --glass-blur-overlay: 30px;
  --glass-saturation: 1.08;
  --text-primary: #e8eef8;
  --text-secondary: #b7c3d4;
  --text-muted: #8291a8;
  --text-disabled: #526178;
  --text-inverse: #08121c;
  --text-link: var(--brand-disabled);
  --status-success: #45c995;
  --status-warning: #f6ad3c;
  --status-danger: #fb7185;
  --status-info: #72b6e8;
  --status-neutral: #94a3b8;
  --status-success-soft: rgb(69 201 149 / 0.16);
  --status-warning-soft: rgb(246 173 60 / 0.16);
  --status-danger-soft: rgb(251 113 133 / 0.16);
  --status-info-soft: rgb(114 182 232 / 0.16);
  --status-neutral-soft: rgb(148 163 184 / 0.16);
}

:root,
[data-theme] {
  --brand-page-bg: var(--surface-page-background);
  --brand-page-frame-border: var(--border-subtle);
  --brand-glass-bg: var(--surface-glass-shell);
  --brand-glass-card-bg: var(--surface-glass-panel);
  --brand-soft-bg: rgb(var(--brand-selected-rgb) / 0.68);
  --brand-decorative-grid:
    linear-gradient(135deg, rgb(var(--brand-primary-rgb) / 0.18), rgb(var(--brand-hover-rgb) / 0.1)),
    linear-gradient(rgb(var(--brand-primary-rgb) / 0.14) 1px, transparent 1px),
    linear-gradient(90deg, rgb(var(--brand-hover-rgb) / 0.12) 1px, transparent 1px);
  --bg-primary: var(--surface-solid-page);
  --bg-secondary: var(--surface-solid-panel);
  --bg-tertiary: var(--surface-solid-control);
  --bg-card: var(--surface-solid-panel);
  --bg-card-hover: var(--surface-solid-control);
  --border-glass: var(--border-subtle);
  --border-glass-hover: var(--border-readable);
  --accent-gradient: var(--brand-primary-gradient);
  --accent-color: var(--brand-primary);
  --accent-light: rgb(var(--brand-primary-rgb) / 0.12);
  --success-color: var(--status-success);
  --warning-color: var(--status-warning);
  --danger-color: var(--status-danger);
  --info-color: var(--status-info);
  --glow-primary: 0 0 20px rgb(var(--brand-primary-rgb) / 0.24);
  --glow-card: 0 0 30px rgb(var(--brand-primary-rgb) / 0.08);
  --shadow-card: var(--shadow-panel);
  --shadow-card-hover: var(--shadow-active);
  --transition-fast: var(--motion-duration-fast) ease;
  --transition-normal: var(--motion-duration-normal) ease;
  --transition-slow: var(--motion-duration-slow) var(--motion-easing-standard);
  --reachai-workbench-breadcrumb-height: 44px;
  --reachai-workbench-title-gap: 10px;
  --reachai-workbench-page-padding: var(--reachai-workbench-title-gap) 28px 16px;
  --reachai-workbench-title-height: 120px;
  --reachai-workbench-title-padding: 26px 28px;
  --reachai-workbench-title-radius: var(--radius-lg);
}
```

- [ ] **Step 4: Create the density partial**

```scss
:root {
  --density-compact-control-height: 32px;
  --density-compact-row-height: 40px;
  --density-compact-section-gap: 12px;
  --density-compact-panel-padding: 16px;
  --density-comfortable-control-height: 38px;
  --density-comfortable-row-height: 48px;
  --density-comfortable-section-gap: 20px;
  --density-comfortable-panel-padding: 24px;
  --density-spacious-control-height: 44px;
  --density-spacious-row-height: 56px;
  --density-spacious-section-gap: 28px;
  --density-spacious-panel-padding: 32px;
}

:root,
[data-density='comfortable'],
.density-comfortable {
  --control-height: var(--density-comfortable-control-height);
  --row-height: var(--density-comfortable-row-height);
  --section-gap: var(--density-comfortable-section-gap);
  --panel-padding: var(--density-comfortable-panel-padding);
}

[data-density='compact'],
.density-compact {
  --control-height: var(--density-compact-control-height);
  --row-height: var(--density-compact-row-height);
  --section-gap: var(--density-compact-section-gap);
  --panel-padding: var(--density-compact-panel-padding);
}

[data-density='spacious'],
.density-spacious {
  --control-height: var(--density-spacious-control-height);
  --row-height: var(--density-spacious-row-height);
  --section-gap: var(--density-spacious-section-gap);
  --panel-padding: var(--density-spacious-panel-padding);
}
```

- [ ] **Step 5: Run the contract and compile both new partials through the Node Sass API**

Run: `cd ai-admin-front; npm run check:glass-theme`

Expected: the contract compiles both Sass partials and exits `0`.

- [ ] **Step 6: Record the semantic and density diff without committing**

```powershell
git diff --check
git status --short
```

---

### Task 3: Add reusable strong-glass recipes and fallbacks

**Files:**
- Create: `ai-admin-front/src/styles/_glass.scss`
- Modify: `ai-admin-front/scripts/check-glass-theme-contract.mjs`

**Interfaces:**
- Consumes: semantic surface, border, shadow, blur, and motion roles from Task 2.
- Produces: `.glass-surface-shell`, `.glass-surface-panel`, `.glass-surface-control`, and `.glass-surface-overlay` recipes.

- [ ] **Step 1: Add glass recipe assertions to the checker**

```js
const glass = read('src/styles/_glass.scss')
for (const recipe of [
  '.glass-surface-shell',
  '.glass-surface-panel',
  '.glass-surface-control',
  '.glass-surface-overlay',
]) {
  expectIncludes(glass, recipe, `glass recipe ${recipe} is missing`)
}
for (const fallback of [
  '@supports not',
  "[data-reduce-transparency='true']",
  '(prefers-reduced-transparency: reduce)',
  '(prefers-reduced-motion: reduce)',
]) {
  expectIncludes(glass, fallback, `glass fallback ${fallback} is missing`)
}

expectSassCompiles('src/styles/_glass.scss')
```

- [ ] **Step 2: Run the contract and confirm `_glass.scss` is missing**

Run: `cd ai-admin-front; npm run check:glass-theme`

Expected: exit code `1` and `src/styles/_glass.scss is missing`.

- [ ] **Step 3: Create the glass recipe file**

```scss
.glass-surface-shell,
.glass-surface-panel,
.glass-surface-overlay {
  border: 1px solid var(--border-subtle);
  -webkit-backdrop-filter: blur(var(--glass-blur-panel)) saturate(var(--glass-saturation));
  backdrop-filter: blur(var(--glass-blur-panel)) saturate(var(--glass-saturation));
}

.glass-surface-shell {
  background: var(--surface-glass-shell);
  box-shadow: var(--shadow-shell);
  -webkit-backdrop-filter: blur(var(--glass-blur-shell)) saturate(var(--glass-saturation));
  backdrop-filter: blur(var(--glass-blur-shell)) saturate(var(--glass-saturation));
}

.glass-surface-panel {
  background: var(--surface-glass-panel);
  box-shadow: var(--shadow-panel);
}

.glass-surface-control {
  border: 1px solid var(--border-readable);
  background: var(--surface-glass-control);
  box-shadow: var(--inner-highlight);
}

.glass-surface-overlay {
  background: var(--surface-glass-overlay);
  box-shadow: var(--shadow-overlay);
  -webkit-backdrop-filter: blur(var(--glass-blur-overlay)) saturate(var(--glass-saturation));
  backdrop-filter: blur(var(--glass-blur-overlay)) saturate(var(--glass-saturation));
}

@supports not ((-webkit-backdrop-filter: blur(1px)) or (backdrop-filter: blur(1px))) {
  .glass-surface-shell { background: var(--surface-fallback-shell); }
  .glass-surface-panel { background: var(--surface-fallback-panel); }
  .glass-surface-control { background: var(--surface-fallback-control); }
  .glass-surface-overlay { background: var(--surface-fallback-overlay); }
}

[data-reduce-transparency='true'] {
  .glass-surface-shell,
  .glass-surface-panel,
  .glass-surface-control,
  .glass-surface-overlay {
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
  }
  .glass-surface-shell { background: var(--surface-fallback-shell); }
  .glass-surface-panel { background: var(--surface-fallback-panel); }
  .glass-surface-control { background: var(--surface-fallback-control); }
  .glass-surface-overlay { background: var(--surface-fallback-overlay); }
}

@media (prefers-reduced-transparency: reduce) {
  .glass-surface-shell,
  .glass-surface-panel,
  .glass-surface-control,
  .glass-surface-overlay {
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
  }
  .glass-surface-shell { background: var(--surface-fallback-shell); }
  .glass-surface-panel { background: var(--surface-fallback-panel); }
  .glass-surface-control { background: var(--surface-fallback-control); }
  .glass-surface-overlay { background: var(--surface-fallback-overlay); }
}

@media (prefers-reduced-motion: reduce) {
  :root {
    --motion-duration-fast: 0.01ms;
    --motion-duration-normal: 0.01ms;
    --motion-duration-slow: 0.01ms;
  }
}
```

- [ ] **Step 4: Run the glass contract and compile the recipe through the Node Sass API**

Run: `cd ai-admin-front; npm run check:glass-theme`

Expected: both commands exit `0`.

- [ ] **Step 5: Record the glass recipe diff without committing**

```powershell
git diff --check
git status --short
```

---

### Task 4: Make semantic tokens the only global Element Plus mapping source

**Files:**
- Create: `ai-admin-front/src/styles/_element-plus.scss`
- Modify: `ai-admin-front/src/styles/theme.scss:1-181,771-930,1539-1757`
- Modify: `ai-admin-front/scripts/check-glass-theme-contract.mjs`

**Interfaces:**
- Consumes: brand, semantic, and density roles from Tasks 1-2.
- Produces: global Element Plus primary/status/surface/text/border/fill/shadow/menu roles plus component-scoped table/card/dialog/input/select/tag/popover/drawer aliases that override Element Plus component defaults at the correct cascade level.

- [ ] **Step 1: Add mapping and monolith-boundary assertions**

```js
const elementPlus = read('src/styles/_element-plus.scss')
const theme = read('src/styles/theme.scss')

for (const mapping of [
  '--el-color-primary: var(--brand-primary);',
  '--el-color-success-light-3:',
  '--el-color-warning-light-9:',
  '--el-color-danger-dark-2:',
  '--el-color-info-light-7:',
  '--el-color-error: var(--status-danger);',
  '--el-color-error-light-9:',
  '--el-color-error-dark-2:',
  '--el-bg-color: var(--surface-solid-panel);',
  '--el-bg-color-overlay: var(--surface-solid-overlay);',
  '--el-text-color-primary: var(--text-primary);',
  '--el-border-color: var(--border-readable);',
  '--el-disabled-bg-color: var(--surface-solid-disabled);',
  '--el-dialog-bg-color: var(--surface-solid-overlay);',
  '--el-input-bg-color: var(--surface-solid-control);',
]) {
  expectIncludes(elementPlus, mapping, `Element Plus mapping ${mapping} is missing`)
}

for (const selector of [
  "[data-theme='dark'] {",
  '.el-table {',
  '.el-card {',
  '.el-dialog {',
  '.el-input,',
  '.el-select {',
  '.el-tag {',
  '.el-popover.el-popper {',
  '.el-drawer {',
]) {
  expectIncludes(elementPlus, selector, `Element Plus component mapping ${selector} is missing`)
}

const darkElementPlusBlock = elementPlus.match(/\[data-theme='dark'\]\s*\{([\s\S]*?)\n\}/)?.[1] ?? ''
for (const darkMapping of [
  '--el-color-primary-light-9:',
  '--el-color-success-light-9:',
  '--el-color-success-dark-2:',
  '--el-color-warning-light-9:',
  '--el-color-warning-dark-2:',
  '--el-color-danger-light-9:',
  '--el-color-danger-dark-2:',
  '--el-color-info-light-9:',
  '--el-color-info-dark-2:',
]) {
  expectIncludes(darkElementPlusBlock, darkMapping, `dark Element Plus mapping ${darkMapping} is missing`)
}

if (elementPlus.includes("[data-theme='dark'] .el-tag")) {
  failures.push('dark tag mapping overrides status-specific tag semantics')
}

for (const moduleImport of [
  "@use './tokens/brand';",
  "@use './tokens/semantic';",
  "@use './tokens/density';",
  "@use './glass';",
  "@use './element-plus';",
]) {
  expectIncludes(theme, moduleImport, `theme entry import ${moduleImport} is missing`)
}

for (const legacyDeclaration of ['--bg-primary:', '--brand-primary:', '--el-bg-color:']) {
  if (theme.includes(legacyDeclaration)) {
    failures.push(`theme.scss still owns extracted declaration ${legacyDeclaration}`)
  }
}


expectSassCompiles('src/styles/_element-plus.scss')
expectSassCompiles('src/styles/theme.scss')
```

- [ ] **Step 2: Run the contract and confirm the mapping/import failures**

Run: `cd ai-admin-front; npm run check:glass-theme`

Expected: exit code `1`, naming `_element-plus.scss` and the missing `@use` entries.

- [ ] **Step 3: Create `_element-plus.scss`**

```scss
:root {
  --el-color-primary: var(--brand-primary);
  --el-color-primary-light-3: var(--brand-hover);
  --el-color-primary-light-5: var(--brand-disabled);
  --el-color-primary-light-7: rgb(var(--brand-selected-rgb) / 0.82);
  --el-color-primary-light-8: rgb(var(--brand-selected-rgb) / 0.9);
  --el-color-primary-light-9: var(--brand-selected);
  --el-color-primary-dark-2: var(--brand-active);
  --el-color-success: var(--status-success);
  --el-color-success-light-3: color-mix(in srgb, var(--status-success) 70%, white);
  --el-color-success-light-5: color-mix(in srgb, var(--status-success) 48%, white);
  --el-color-success-light-7: color-mix(in srgb, var(--status-success) 28%, white);
  --el-color-success-light-8: color-mix(in srgb, var(--status-success) 18%, white);
  --el-color-success-light-9: color-mix(in srgb, var(--status-success) 10%, white);
  --el-color-success-dark-2: color-mix(in srgb, var(--status-success) 82%, black);
  --el-color-warning: var(--status-warning);
  --el-color-warning-light-3: color-mix(in srgb, var(--status-warning) 70%, white);
  --el-color-warning-light-5: color-mix(in srgb, var(--status-warning) 48%, white);
  --el-color-warning-light-7: color-mix(in srgb, var(--status-warning) 28%, white);
  --el-color-warning-light-8: color-mix(in srgb, var(--status-warning) 18%, white);
  --el-color-warning-light-9: color-mix(in srgb, var(--status-warning) 10%, white);
  --el-color-warning-dark-2: color-mix(in srgb, var(--status-warning) 82%, black);
  --el-color-danger: var(--status-danger);
  --el-color-danger-light-3: color-mix(in srgb, var(--status-danger) 70%, white);
  --el-color-danger-light-5: color-mix(in srgb, var(--status-danger) 48%, white);
  --el-color-danger-light-7: color-mix(in srgb, var(--status-danger) 28%, white);
  --el-color-danger-light-8: color-mix(in srgb, var(--status-danger) 18%, white);
  --el-color-danger-light-9: color-mix(in srgb, var(--status-danger) 10%, white);
  --el-color-danger-dark-2: color-mix(in srgb, var(--status-danger) 82%, black);
  --el-color-error: var(--status-danger);
  --el-color-error-light-3: var(--el-color-danger-light-3);
  --el-color-error-light-5: var(--el-color-danger-light-5);
  --el-color-error-light-7: var(--el-color-danger-light-7);
  --el-color-error-light-8: var(--el-color-danger-light-8);
  --el-color-error-light-9: var(--el-color-danger-light-9);
  --el-color-error-dark-2: var(--el-color-danger-dark-2);
  --el-color-info: var(--status-info);
  --el-color-info-light-3: color-mix(in srgb, var(--status-info) 70%, white);
  --el-color-info-light-5: color-mix(in srgb, var(--status-info) 48%, white);
  --el-color-info-light-7: color-mix(in srgb, var(--status-info) 28%, white);
  --el-color-info-light-8: color-mix(in srgb, var(--status-info) 18%, white);
  --el-color-info-light-9: color-mix(in srgb, var(--status-info) 10%, white);
  --el-color-info-dark-2: color-mix(in srgb, var(--status-info) 82%, black);
  --el-bg-color: var(--surface-solid-panel);
  --el-bg-color-overlay: var(--surface-solid-overlay);
  --el-bg-color-page: var(--surface-solid-page);
  --el-text-color-primary: var(--text-primary);
  --el-text-color-regular: var(--text-secondary);
  --el-text-color-secondary: var(--text-muted);
  --el-text-color-placeholder: var(--text-muted);
  --el-text-color-disabled: var(--text-disabled);
  --el-border-color: var(--border-readable);
  --el-border-color-light: var(--border-subtle);
  --el-border-color-lighter: var(--border-subtle);
  --el-border-color-extra-light: var(--border-subtle);
  --el-border-color-dark: var(--border-readable);
  --el-fill-color: var(--surface-solid-control);
  --el-fill-color-light: var(--surface-solid-control);
  --el-fill-color-lighter: var(--surface-solid-panel);
  --el-fill-color-extra-light: var(--surface-solid-panel);
  --el-fill-color-dark: var(--surface-solid-control);
  --el-fill-color-blank: var(--surface-solid-panel);
  --el-mask-color: rgb(8 18 28 / 0.58);
  --el-mask-color-extra-light: rgb(8 18 28 / 0.26);
  --el-box-shadow: var(--shadow-panel);
  --el-box-shadow-light: var(--shadow-panel);
  --el-box-shadow-lighter: var(--inner-highlight);
  --el-box-shadow-dark: var(--shadow-overlay);
  --el-disabled-bg-color: var(--surface-solid-disabled);
  --el-disabled-text-color: var(--text-disabled);
  --el-disabled-border-color: var(--border-subtle);
  --el-overlay-color: rgb(8 18 28 / 0.58);
  --el-overlay-color-light: rgb(8 18 28 / 0.38);
  --el-overlay-color-lighter: rgb(8 18 28 / 0.22);
  --el-menu-bg-color: transparent;
  --el-menu-text-color: var(--text-secondary);
  --el-menu-active-color: var(--text-primary);
  --el-menu-hover-bg-color: rgb(var(--brand-primary-rgb) / 0.08);
  --el-menu-hover-text-color: var(--text-primary);
  --el-component-size: var(--control-height);
}

[data-theme='dark'] {
  --el-color-primary-light-3: color-mix(in srgb, var(--brand-primary) 68%, var(--surface-solid-page));
  --el-color-primary-light-5: color-mix(in srgb, var(--brand-primary) 50%, var(--surface-solid-page));
  --el-color-primary-light-7: color-mix(in srgb, var(--brand-primary) 34%, var(--surface-solid-page));
  --el-color-primary-light-8: color-mix(in srgb, var(--brand-primary) 24%, var(--surface-solid-page));
  --el-color-primary-light-9: color-mix(in srgb, var(--brand-primary) 16%, var(--surface-solid-page));
  --el-color-primary-dark-2: color-mix(in srgb, var(--brand-primary) 82%, white);
  --el-color-success-light-3: color-mix(in srgb, var(--status-success) 64%, var(--surface-solid-page));
  --el-color-success-light-5: color-mix(in srgb, var(--status-success) 48%, var(--surface-solid-page));
  --el-color-success-light-7: color-mix(in srgb, var(--status-success) 32%, var(--surface-solid-page));
  --el-color-success-light-8: color-mix(in srgb, var(--status-success) 24%, var(--surface-solid-page));
  --el-color-success-light-9: color-mix(in srgb, var(--status-success) 16%, var(--surface-solid-page));
  --el-color-success-dark-2: color-mix(in srgb, var(--status-success) 82%, white);
  --el-color-warning-light-3: color-mix(in srgb, var(--status-warning) 64%, var(--surface-solid-page));
  --el-color-warning-light-5: color-mix(in srgb, var(--status-warning) 48%, var(--surface-solid-page));
  --el-color-warning-light-7: color-mix(in srgb, var(--status-warning) 32%, var(--surface-solid-page));
  --el-color-warning-light-8: color-mix(in srgb, var(--status-warning) 24%, var(--surface-solid-page));
  --el-color-warning-light-9: color-mix(in srgb, var(--status-warning) 16%, var(--surface-solid-page));
  --el-color-warning-dark-2: color-mix(in srgb, var(--status-warning) 82%, white);
  --el-color-danger-light-3: color-mix(in srgb, var(--status-danger) 64%, var(--surface-solid-page));
  --el-color-danger-light-5: color-mix(in srgb, var(--status-danger) 48%, var(--surface-solid-page));
  --el-color-danger-light-7: color-mix(in srgb, var(--status-danger) 32%, var(--surface-solid-page));
  --el-color-danger-light-8: color-mix(in srgb, var(--status-danger) 24%, var(--surface-solid-page));
  --el-color-danger-light-9: color-mix(in srgb, var(--status-danger) 16%, var(--surface-solid-page));
  --el-color-danger-dark-2: color-mix(in srgb, var(--status-danger) 82%, white);
  --el-color-info-light-3: color-mix(in srgb, var(--status-info) 64%, var(--surface-solid-page));
  --el-color-info-light-5: color-mix(in srgb, var(--status-info) 48%, var(--surface-solid-page));
  --el-color-info-light-7: color-mix(in srgb, var(--status-info) 32%, var(--surface-solid-page));
  --el-color-info-light-8: color-mix(in srgb, var(--status-info) 24%, var(--surface-solid-page));
  --el-color-info-light-9: color-mix(in srgb, var(--status-info) 16%, var(--surface-solid-page));
  --el-color-info-dark-2: color-mix(in srgb, var(--status-info) 82%, white);
}

.el-table {
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-header-bg-color: var(--surface-solid-control);
  --el-table-row-hover-bg-color: rgb(var(--brand-primary-rgb) / 0.06);
  --el-table-header-text-color: var(--text-muted);
  --el-table-text-color: var(--text-secondary);
  --el-table-border-color: var(--border-subtle);
  --el-table-current-row-bg-color: rgb(var(--brand-primary-rgb) / 0.08);
  --el-table-fixed-box-shadow: var(--shadow-panel);
}

.el-card {
  --el-card-bg-color: var(--surface-solid-panel);
  --el-card-border-color: var(--border-subtle);
}

.el-dialog {
  --el-dialog-bg-color: var(--surface-solid-overlay);
  --el-dialog-border-radius: var(--radius-lg);
}

.el-input,
.el-textarea {
  --el-input-bg-color: var(--surface-solid-control);
  --el-input-hover-border-color: rgb(var(--brand-primary-rgb) / 0.48);
  --el-input-focus-border-color: var(--brand-primary);
  --el-input-text-color: var(--text-primary);
  --el-input-placeholder-color: var(--text-muted);
  --el-input-border-color: var(--border-readable);
}

.el-select {
  --el-select-border-color-hover: rgb(var(--brand-primary-rgb) / 0.48);
}

.el-tag {
  --el-tag-bg-color: rgb(var(--brand-primary-rgb) / 0.1);
  --el-tag-border-color: rgb(var(--brand-primary-rgb) / 0.2);
  --el-tag-text-color: var(--brand-active);
}

.el-popover.el-popper {
  --el-popover-bg-color: var(--surface-solid-overlay);
  --el-popover-border-color: var(--border-readable);
}

.el-drawer {
  --el-drawer-bg-color: var(--surface-solid-overlay);
}

.el-loading-mask {
  background-color: color-mix(in srgb, var(--surface-solid-page) 78%, transparent);
}
```

- [ ] **Step 4: Convert `theme.scss` into the module entry plus legacy structural selectors**

Add these five lines at the beginning:

```scss
@use './tokens/brand';
@use './tokens/semantic';
@use './tokens/density';
@use './glass';
@use './element-plus';
```

Delete only the three declaration regions identified by their stable boundaries:

1. The opening `:root { ... }` block ending immediately before `// Element Plus 组件级覆盖`.
2. The `[data-theme="light"] { ... }` token block ending immediately before `// 日间模式 组件级覆盖`.
3. The complete `// Brand palette layer` region through the final `[data-brand]` block.

Retain the dark and light component-level selector blocks between those regions. Replace the deleted region headers with this explicit migration marker:

```scss
// Legacy component selectors below remain temporarily while each public recipe
// and page archetype migrates to semantic tokens in later implementation slices.
```

- [ ] **Step 5: Run contract, TypeScript, and production build**

Run: `cd ai-admin-front; npm run check:glass-theme; npx vue-tsc --noEmit; npm run build`

Expected: all three commands exit `0`; Sass emits no missing-variable error.

- [ ] **Step 6: Record the mapping extraction without committing**

```powershell
git diff --check
git status --short
```

---

### Task 5: Migrate the page canvas and sidebar shell as first real consumers

**Files:**
- Modify: `ai-admin-front/src/components/common/AppPageBackground.vue:11-54`
- Modify: `ai-admin-front/src/components/common/AppSidebar.vue:1-1087`
- Modify: `ai-admin-front/scripts/check-glass-theme-contract.mjs`

**Interfaces:**
- Consumes: page and shell roles plus `.glass-surface-shell`.
- Produces: a brand-reactive canvas and navigation shell without component-owned light/dark surface recipes.

- [ ] **Step 1: Add consumer assertions**

```js
const pageBackground = read('src/components/common/AppPageBackground.vue')
const sidebar = read('src/components/common/AppSidebar.vue')

expectIncludes(pageBackground, 'background: var(--surface-page-background);', 'page background must consume the semantic canvas')
expectIncludes(pageBackground, 'var(--border-subtle)', 'page background must consume the semantic frame border')
if (pageBackground.includes(":global([data-theme='dark'])")) {
  failures.push('AppPageBackground still owns a dark-mode recipe')
}
expectIncludes(sidebar, 'glass-surface-shell', 'sidebar root must consume the shell recipe')
expectIncludes(sidebar, '--sb-title: var(--text-primary);', 'sidebar title must map to semantic text')
if (sidebar.includes("[data-theme='dark'] .app-sidebar {")) {
  failures.push('AppSidebar still owns a root dark-mode shell recipe')
}
if (sidebar.includes('backdrop-filter: blur(12px)')) {
  failures.push('AppSidebar still owns a hardcoded shell blur')
}
if (sidebar.includes('background: var(--sb-surface);')) {
  failures.push('AppSidebar overrides the shared shell background and its transparency fallback')
}
```

- [ ] **Step 2: Run the contract and confirm both consumers fail**

Run: `cd ai-admin-front; npm run check:glass-theme`

Expected: exit code `1`, naming the canvas, shell recipe, and root dark-mode recipe.

- [ ] **Step 3: Replace the full `AppPageBackground` scoped style**

```scss
.app-page-background {
  position: absolute;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  pointer-events: none;
  background: var(--surface-page-background);
  background-size: var(--brand-page-bg-size, auto);
  box-shadow: inset 0 0 0 1px var(--border-subtle), var(--inner-highlight);
  transition:
    background var(--motion-duration-normal) ease,
    box-shadow var(--motion-duration-normal) ease;
}

.app-page-background.is-local {
  border-radius: inherit;
}
```

- [ ] **Step 4: Map the sidebar root to semantic roles without overriding the shared recipe**

Add `glass-surface-shell` to the root `nav` element's static class list. Replace the `.app-sidebar` local variables with:

```scss
--sb-radius: var(--radius-lg);
--sb-title: var(--text-primary);
--sb-subtitle: var(--text-muted);
--sb-caption: var(--text-muted);
--sb-text: var(--text-secondary);
--sb-child: var(--text-muted);
--sb-icon: var(--text-muted);
--sb-divider: var(--border-divider);
--sb-hover-bg: rgb(var(--brand-primary-rgb) / 0.08);
--sb-parent-active-bg: rgb(var(--brand-primary-rgb) / 0.1);
--sb-parent-active-text: var(--brand-active);
--sb-child-active-bg: rgb(var(--brand-primary-rgb) / 0.12);
--sb-child-active-text: var(--brand-active);
--sb-active-icon: var(--brand-primary);
--sb-rail: var(--brand-primary);
```

Delete the root `.app-sidebar` declarations for `background`, `border`, `box-shadow`, and `backdrop-filter`; `.glass-surface-shell` must own all four so unsupported-blur and reduced-transparency fallbacks cannot be overridden. Keep `border-radius: var(--sb-radius)`, layout, overflow, and the ambient pseudo-elements. Delete only the root `[data-theme='dark'] .app-sidebar`, `.app-sidebar::before`, and `.app-sidebar::after` recipe block. Keep nested control selectors for a later public-control migration.

- [ ] **Step 5: Verify theme controls remain intentionally frozen**

Run: `cd ai-admin-front; npm run check:glass-theme; node scripts/check-sidebar-theme-mode-disabled.mjs`

Expected: both scripts exit `0`; the second prints `Sidebar theme mode controls are frozen.`

- [ ] **Step 6: Build and run the existing project workbench checks**

Run: `cd ai-admin-front; npm run check:sdk-access-workbench; npm run check:scan-project-detail-ui; npm run build`

Expected: both UI contract scripts and the build exit `0`.

- [ ] **Step 7: Record the first-consumer diff without committing**

```powershell
git diff --check
git status --short
```

---

### Task 6: Mirror the stable foundation contract into the existing Figma file

**Files / External artifacts:**
- Modify Figma file: `dLSJOki5yBoBeEx2GEZHU5`
- Modify Foundations page: node `269:284`
- Verify shared sidebar main component: node `760:291`
- Update local-only file when node IDs or durable guidance change: `UI_AGENT.local.md`

**Interfaces:**
- Consumes: exact CSS role names and values from Tasks 1-5.
- Produces: machine-mappable Figma collections and styles; does not create business-screen copies.

- [ ] **Step 1: Load the required Figma skills before any canvas write**

Read `figma:figma-use` and `figma:figma-generate-library` completely. Use the existing file; do not create a new Figma file.

- [ ] **Step 2: Inspect existing local variables, styles, and the Foundations page**

Record name collisions and reuse matching collections/styles. Do not duplicate `Global Navigation / Sidebar`, `ReachAI/MetricIconBg/BrandSoft`, or `ReachAI/Pagination/CompactGlass`.

- [ ] **Step 3: Create or reconcile the exact variable collections**

Create/reuse these collections and modes:

- `ReachAI Brand`: modes `Tech Purple`, `Metro Green`, `Aurora Cyan`, `Nebula Violet`, `Coral Rose`, `Solar Gold`, `Deep Ocean`; variables `brand/primary`, `brand/hover`, `brand/active`, `brand/disabled`, `brand/selected`.
- `ReachAI Semantic`: modes `Light`, `Dark`; variables `surface/page/canvas`, `surface/panel/solid`, `surface/control/solid`, `surface/overlay/solid`, `border/subtle`, `border/readable`, `border/focus`, `border/divider`, `text/primary`, `text/secondary`, `text/muted`, `text/disabled`, `text/inverse`, `status/success`, `status/warning`, `status/danger`, `status/info`, `status/neutral`.
- `ReachAI Density`: modes `Compact`, `Comfortable`, `Spacious`; variables `density/control-height`, `density/row-height`, `density/section-gap`, `density/panel-padding` with values `32/40/12/16`, `38/48/20/24`, and `44/56/28/32` respectively.

- [ ] **Step 4: Create or reconcile the exact shared Styles**

- Paint styles: `ReachAI/Glass/Shell`, `ReachAI/Glass/Panel`, `ReachAI/Glass/Control`, `ReachAI/Glass/Overlay`, `ReachAI/Glass/Selected`, `ReachAI/Page/Background`.
- Effect styles: `ReachAI/Blur/Shell 28`, `ReachAI/Blur/Panel 22`, `ReachAI/Blur/Overlay 32`, `ReachAI/Shadow/Shell`, `ReachAI/Shadow/Panel`, `ReachAI/Shadow/Overlay`, `ReachAI/Inner Highlight`.
- Apply the Shell paint/effect/border variables to main component `760:291`, then verify its existing instances update rather than detach.

- [ ] **Step 5: Add a compact token specimen frame on Foundations**

Create one frame named `ReachAI / Glass Foundation / C3` containing: the four surface depths, five status samples, seven brand swatches, and three density rows. This is a foundation specimen, not a new product screen.

- [ ] **Step 6: Verify and record Figma outputs**

Verify the Foundations page and sidebar main component visually at 100% zoom. Record any new collection/style/specimen node IDs in `UI_AGENT.local.md` without staging the file.

---

### Task 7: Run the foundation acceptance matrix and hand off the next slice

**Files:**
- Modify only if results require corrections: files from Tasks 1-5.
- Create after acceptance: `docs/superpowers/plans/2026-07-10-reachai-glass-components-implementation.md`

**Interfaces:**
- Consumes: the compiled foundation and Figma mirror.
- Produces: verified Phase 0-1 baseline and the Phase 2 public-component migration plan.

- [ ] **Step 1: Run all foundation checks from a clean command session**

```powershell
cd D:\work\EnterpriseAgentFramework\ai-admin-front
npm run check:glass-theme
node scripts/check-sidebar-theme-mode-disabled.mjs
npm run check:sdk-access-workbench
npm run check:scan-project-detail-ui
npx vue-tsc --noEmit
npm run build
```

Expected: every command exits `0`.

- [ ] **Step 2: Start an exact-size visual QA server**

Run: `npm run dev -- --host 127.0.0.1 --port 5200`

Open these routes at `1600 x 1000`:

- `http://127.0.0.1:5200/registry/projects`
- `http://127.0.0.1:5200/registry/projects/bzjs1`
- `http://127.0.0.1:5200/registry/projects/bzjs1/sdk-access`

- [ ] **Step 3: Verify representative brand and fallback states**

For `tech-purple`, `metro-green`, and `deep-ocean`, set `data-brand` on `<html>` and verify page ambient color, primary action, selected menu, focus ring, and AI emphasis all change together while status colors remain unchanged. Set `data-reduce-transparency="true"` and verify text remains readable and all four glass recipes become solid without layout shift. Temporarily set `data-theme="dark"` in DevTools and verify the three routes remain usable even though the product control remains locked to light.

- [ ] **Step 4: Check diff hygiene**

Run: `git diff --check; git status --short`

Expected: no whitespace errors; `UI_AGENT.local.md` is not staged; no backend, SQL, route, or business-logic file appears.

- [ ] **Step 5: Keep acceptance corrections in the worktree**

Do not stage or commit. Re-run the focused checks for every corrected file, then record the remaining worktree diff:

```powershell
git diff --check
git status --short
```

- [ ] **Step 6: Write the next independent plan**

The next plan must implement `PageHeader`, `MetricStrip`, `WorkbenchPanel`, `FilterBar`, `DataTableShell`, `StatusTag`, `AppDialog`, `WizardDialog`, and `AppDrawer`, then migrate one representative page per archetype. It must keep Studio layout out of scope and use the same Node-contract, build, and real-route validation pattern.

---

## Self-Review Result

- Spec coverage for this slice: brand modes, semantic theme roles, density modes, strong-glass depth, Element Plus mapping, blur/transparency/motion fallbacks, first real consumers, Figma mapping, dark compatibility, and representative brand validation are each assigned to a task.
- Deliberately deferred to later independently testable plans: full public-component library, route-by-route page migration, dialogs/drawers/forms/tables, workflow/agent Studio shared-control adoption, and final whole-site acceptance.
- Naming consistency: CSS `--brand-*`, `--surface-*`, `--border-*`, `--shadow-*`, `--glass-*`, `--text-*`, `--status-*`, and `--density-*` roles map directly to Figma slash names.
- Placeholder scan: no incomplete implementation markers remain.
