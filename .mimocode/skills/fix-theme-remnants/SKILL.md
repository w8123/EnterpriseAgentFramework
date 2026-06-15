---
name: fix-theme-remnants
description: "Systematically find and fix hardcoded dark/light mode color remnants in Vue components. Use when user reports dark backgrounds in light mode, light backgrounds in dark mode, or inconsistent theme appearance across pages."
---

# Fix Theme Remnants

Systematically find and fix hardcoded dark/light mode color remnants in the Vue 3 + Element Plus frontend (`ai-admin-front/`).

## When to use

- User reports dark backgrounds appearing in light/day mode
- User reports light backgrounds appearing in dark/night mode
- Specific pages, buttons, tables, or components look wrong after theme toggle
- After adding a new theme or changing the theme system

## Prerequisites

- Read `AGENTS.md` first (project rules).
- Read `docs/ai-memory/KNOWN-PITFALLS.md` § Theme Hard-Coding.
- The theme system lives in `ai-admin-front/src/styles/theme.scss`.

## Procedure

### Step 1 — Understand the issue

Ask or infer:
- Which mode is broken? (light mode shows dark remnants, or dark mode shows light remnants)
- Which pages/components are affected? (list pages, buttons, tables, specific views)

### Step 2 — Audit the theme system

1. Read `ai-admin-front/src/styles/theme.scss` — understand the `[data-theme="light"]` and `:root` variable blocks.
2. Read `ai-admin-front/src/styles/index.scss` — check for global overrides.
3. Read `ai-admin-front/src/App.vue` — check how the `dark` class and `data-theme` attribute are managed.

Common root causes:
- `App.vue` permanently adding `dark` class to `<html>`, keeping Element Plus dark CSS active in light mode.
- Element Plus dark CSS variables (`html.dark { --el-bg-color: ... }`) overriding light mode variables due to specificity.
- Component-level hardcoded colors (`background: #1a1a28`, `color: #e2e8f0`, etc.) that don't respect theme toggle.

### Step 3 — Find hardcoded colors

Search for hardcoded dark/light colors in Vue files:

```
Grep pattern: background.*#[0-9a-fA-F]{3,8}|#[0-9a-fA-F]{6}.*background
Grep pattern: color:\s*#[0-9a-fA-F]{3,8}
Grep glob: **/*.vue
Path: ai-admin-front/src/views
```

Also search for known dark palette strings:
```
Grep pattern: #2b2d3a|#1d2129|#2a2e36|#0a0a0f|#12121a|#1a1a28
```

### Step 4 — Fix in theme.scss first

Add explicit Element Plus component overrides in the `[data-theme="light"]` block of `theme.scss`. Priority targets:

- **Tables**: `el-table`, `el-table__header`, `el-table__row` — headers and odd rows must have light backgrounds.
- **Buttons**: `el-button--default`, `el-button--primary` — background and border must be visible in light mode.
- **Cards/Panels**: `el-card`, `el-collapse` — background must be light.
- **Dropdowns/Popovers**: `el-dropdown`, `el-popover` — background must be light.
- **Calendars**: `el-calendar-table td` — cell backgrounds.
- **Empty states**: `el-empty`.

Use CSS variables (`var(--el-bg-color)`, `var(--bg-primary)`) instead of hardcoded hex values.

### Step 5 — Fix component-level hardcoding

For hardcoded colors inside individual `.vue` files:
- Replace dark hex values with theme CSS variables.
- Use `[data-theme="light"]` scoped selectors when component-specific overrides are needed.
- Prefer `var(--bg-primary)`, `var(--bg-secondary)`, `var(--text-primary)`, `var(--text-secondary)`.

### Step 6 — Verify

1. Run type check: `npx vue-tsc --noEmit` in `ai-admin-front/`.
2. If type check passes, run build: `npm run build` in `ai-admin-front/`.
3. Report what was fixed and which files were changed.

## Key files

| File | Role |
|------|------|
| `ai-admin-front/src/styles/theme.scss` | Central theme variables and Element Plus overrides |
| `ai-admin-front/src/styles/index.scss` | Global style imports |
| `ai-admin-front/src/App.vue` | Theme class/data-attribute management |
| `ai-admin-front/src/views/layout/MainLayout.vue` | Layout-level theme overrides |
| `ai-admin-front/src/composables/useTheme.ts` | Theme toggle composable |

## Anti-patterns

- Don't add hardcoded hex colors to individual `.vue` files — use CSS variables.
- Don't override Element Plus internals in component `<style scoped>` when a global override in `theme.scss` would suffice.
- Don't change Element Plus component behavior (switches, selects, etc.) when only color/background is broken.
- Don't modify the dark mode `:root` block when fixing light mode issues.
