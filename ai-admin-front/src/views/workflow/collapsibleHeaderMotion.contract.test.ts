import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const collapsibleRegionSource = readFileSync(
  resolve(__dirname, '../../components/common/CollapsibleHeaderRegion.vue'),
  'utf8',
)
const pageHeaderSource = readFileSync(
  resolve(__dirname, '../../components/common/PageHeader.vue'),
  'utf8',
)
const collapsibleHeaderComposableSource = readFileSync(
  resolve(__dirname, '../../composables/useCollapsiblePageHeader.ts'),
  'utf8',
)
const workflowListSource = readFileSync(resolve(__dirname, './WorkflowList.vue'), 'utf8')

describe('collapsible list header motion contract', () => {
  it('animates the title, spacing, and summary instead of removing them in one frame', () => {
    expect(pageHeaderSource).toContain(
      'height var(--motion-duration-normal) var(--motion-easing-standard)',
    )
    expect(pageHeaderSource).toContain(
      'min-height var(--motion-duration-normal) var(--motion-easing-standard)',
    )
    expect(pageHeaderSource).toContain(
      'padding var(--motion-duration-normal) var(--motion-easing-standard)',
    )
    expect(pageHeaderSource).toContain('interpolate-size: allow-keywords;')
    expect(pageHeaderSource).not.toContain('props.description && !props.collapsed')
    expect(pageHeaderSource).not.toContain('$slots.tags && !props.collapsed')
    expect(pageHeaderSource).toContain(
      'max-height var(--motion-duration-normal) var(--motion-easing-standard)',
    )

    expect(collapsibleRegionSource).toContain(
      'transition: gap var(--motion-duration-normal) var(--motion-easing-standard)',
    )
    expect(collapsibleRegionSource).toContain(
      'grid-template-rows var(--motion-duration-normal) var(--motion-easing-standard)',
    )
    expect(collapsibleRegionSource).toContain(
      'opacity var(--motion-duration-fast) ease',
    )
    expect(collapsibleRegionSource).toContain('transform: translateY(-8px)')
  })

  it('keeps the reduced-motion path instantaneous', () => {
    expect(collapsibleRegionSource).toMatch(
      /@media \(prefers-reduced-motion: reduce\) \{[\s\S]*?\.collapsible-header-region,[\s\S]*?\.collapsible-header-region__summary \{[\s\S]*?transition: none;/,
    )
    expect(pageHeaderSource).toMatch(
      /@media \(prefers-reduced-motion: reduce\) \{[\s\S]*?\.app-page-header,[\s\S]*?\.app-page-header__meta \{[\s\S]*?transition: none;/,
    )
    expect(collapsibleHeaderComposableSource).toContain(
      "window.matchMedia('(prefers-reduced-motion: reduce)').matches",
    )
    expect(collapsibleHeaderComposableSource).toContain(
      'notifyLayoutChange(reduceMotion ? 0 : LAYOUT_TRANSITION_DURATION)',
    )
    expect(workflowListSource).toMatch(
      /@media \(prefers-reduced-motion: reduce\) \{[\s\S]*?\.workflow-table,[\s\S]*?\.workflow-table :deep\(\.el-scrollbar__wrap\) \{[\s\S]*?transition: none;/,
    )
  })

  it('smooths the table viewport resize after the header motion settles', () => {
    expect(collapsibleHeaderComposableSource).toContain('const LAYOUT_TRANSITION_DURATION = 240')
    expect(collapsibleHeaderComposableSource).toContain('const INTERACTION_SETTLE_DELAY = 280')
    expect(workflowListSource).toMatch(
      /\.workflow-table \{[\s\S]*?transition: max-height var\(--motion-duration-fast\) var\(--motion-easing-standard\);[\s\S]*?:deep\(\.el-scrollbar__wrap\) \{[\s\S]*?transition: max-height var\(--motion-duration-fast\) var\(--motion-easing-standard\);/,
    )
  })

  it('does not repaint a second full-size backdrop blur while the sticky region resizes', () => {
    expect(collapsibleRegionSource).not.toContain('backdrop-filter')
  })
})
