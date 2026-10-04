import { nextTick, onBeforeUnmount, onMounted, onUpdated, ref, watch, type ComponentPublicInstance, type Ref } from 'vue'
import type { MenuInstance } from 'element-plus'
import type { SidebarEntry } from './sidebarMenu'

/** AppSidebar's vertical menu only; Element Plus still owns pointer actions and routing. */
export function useSidebarKeyboardNavigation(
  menu: Ref<MenuInstance | undefined>,
  entries: Ref<SidebarEntry[]>,
  collapsed: Ref<boolean>,
  active: Ref<string>,
) {
  const nodes = new Map<string, HTMLElement>()
  const rovingIndex = ref('')
  const opened = ref<string[]>([])
  let lastFocus: HTMLElement | undefined
  let observer: MutationObserver | undefined
  let disposed = false

  const items = () => entries.value.flatMap(entry => entry.kind === 'group' ? [] : [
    { index: entry.index, parent: '', children: entry.children },
    ...(entry.children ?? []).map(child => ({ index: child.index, parent: entry.index, children: undefined })),
  ])
  const isMenuOpen = (index: string) => opened.value.includes(index)
  const usable = (index: string) => {
    const item = items().find(candidate => candidate.index === index)
    const node = nodes.get(index)
    return Boolean(item && node?.isConnected && !node.classList.contains('is-disabled')
      && node.getAttribute('aria-disabled') !== 'true' && (!item.parent || isMenuOpen(item.parent)))
  }
  const visible = () => items().filter(item => usable(item.index))
  const tabindex = (index: string) => index === rovingIndex.value && usable(index) ? 0 : -1

  function setMenuNode(index: string, value: Element | ComponentPublicInstance | null) {
    const element = value && ('$el' in value ? value.$el : value)
    if (element instanceof HTMLElement) nodes.set(index, element)
    else nodes.delete(index)
  }

  function keepVisible(element: HTMLElement) {
    // Do not scroll the document or the content pane: the sidebar/popup owns this focus.
    const owner = element.closest<HTMLElement>('.sidebar-keyboard-popper')
      ?? element.closest('.menu-scroll')?.querySelector<HTMLElement>('.el-scrollbar__wrap')
    if (!owner) return
    const viewport = owner.getBoundingClientRect()
    const target = (element.classList.contains('el-sub-menu')
      ? element.querySelector<HTMLElement>('.el-sub-menu__title') : element)?.getBoundingClientRect()
    if (!target || target.bottom <= target.top || viewport.bottom <= viewport.top) return
    const padding = 4
    if (target.top < viewport.top + padding) owner.scrollTop += target.top - viewport.top - padding
    else if (target.bottom > viewport.bottom - padding) owner.scrollTop += target.bottom - viewport.bottom + padding
  }

  function focusNode(index: string) {
    if (!usable(index)) return
    rovingIndex.value = index
    const node = nodes.get(index)!
    node.focus({ preventScroll: true })
    lastFocus = node
    keepVisible(node)
    if (collapsed.value) {
      const parent = items().find(item => item.index === index)?.parent
      opened.value.filter(group => group !== index && group !== parent).forEach(group => menu.value?.close(group))
    }
  }

  function syncMenu() {
    if (disposed) return
    const actualOpen = items().filter(item => item.children && nodes.get(item.index)?.classList.contains('is-opened')).map(item => item.index)
    if (actualOpen.join('|') !== opened.value.join('|')) opened.value = actualOpen
    if (!usable(rovingIndex.value)) {
      const oldParent = lastFocus?.dataset.sidebarParent
      const fallback = oldParent && usable(oldParent) ? oldParent : usable(active.value) ? active.value : visible()[0]?.index
      rovingIndex.value = fallback ?? ''
      // A removed/closed child must not retain focus. Never steal focus from another control.
      if (fallback && lastFocus && (document.activeElement === lastFocus
        || (!lastFocus.isConnected && document.activeElement === document.body))) focusNode(fallback)
    } else if (lastFocus && !lastFocus.isConnected && document.activeElement === document.body) {
      // Inline/popup mode changes replace Element Plus roots, even for the same index.
      focusNode(rovingIndex.value)
    }
    for (const [index, node] of nodes) {
      const value = String(tabindex(index))
      if (node.getAttribute('tabindex') !== value) node.setAttribute('tabindex', value)
    }
  }

  function onMenuFocus(event: FocusEvent) {
    const node = event.target as HTMLElement
    const index = node.dataset.sidebarIndex
    if (!index || nodes.get(index) !== node || !usable(index)) return
    rovingIndex.value = index
    lastFocus = node
    keepVisible(node)
    syncMenu()
  }

  async function openChildren(index: string) {
    menu.value?.open(index)
    await nextTick()
    syncMenu()
    const first = visible().find(item => item.parent === index)
    if (first) focusNode(first.index)
  }

  function closeGroup(index: string) {
    focusNode(index)
    menu.value?.close(index)
    void nextTick(syncMenu)
  }

  function onMenuKeydown(event: KeyboardEvent) {
    if (event.defaultPrevented || event.isComposing || event.altKey || event.ctrlKey || event.metaKey) return
    const node = event.target as HTMLElement
    const index = node.dataset.sidebarIndex
    // Handles only the real focused item, not an input or a bubbling nested menu event.
    if (!index || node !== event.currentTarget || nodes.get(index) !== node) return
    syncMenu()
    if (!usable(index)) return
    const item = items().find(candidate => candidate.index === index)!
    if (event.key === 'Tab') {
      if (collapsed.value && item.parent) closeGroup(item.parent)
      else if (collapsed.value && isMenuOpen(index)) closeGroup(index)
      return // Native Tab/Shift+Tab leaves this single roving entry; no focus trap.
    }
    if (!['ArrowDown', 'ArrowUp', 'Home', 'End', 'ArrowRight', 'ArrowLeft', 'Escape', 'Enter', ' '].includes(event.key)) return
    event.preventDefault()
    event.stopPropagation()
    if (event.repeat && (event.key === 'Enter' || event.key === ' ')) return
    const available = visible()
    const position = available.findIndex(candidate => candidate.index === index)
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      const step = event.key === 'ArrowDown' ? 1 : -1
      focusNode(available[(position + step + available.length) % available.length]!.index)
    } else if (event.key === 'Home' || event.key === 'End') {
      focusNode(available[event.key === 'Home' ? 0 : available.length - 1]!.index)
    } else if (event.key === 'ArrowRight' && item.children) {
      void openChildren(index)
    } else if (event.key === 'ArrowLeft' || event.key === 'Escape') {
      if (item.parent) closeGroup(item.parent)
      else if (item.children && isMenuOpen(index)) closeGroup(index)
    } else if (event.key === 'Enter' || event.key === ' ') {
      if (item.children) {
        if (isMenuOpen(index)) closeGroup(index)
        else void openChildren(index)
      } else node.click() // Exactly one original Element Plus click; no second router.push.
    }
  }

  onMounted(() => {
    syncMenu()
    observer = new MutationObserver(() => { void nextTick(syncMenu) })
    if (menu.value?.$el instanceof HTMLElement) observer.observe(menu.value.$el, { subtree: true, attributes: true, attributeFilter: ['class', 'aria-disabled'] })
  })
  onUpdated(syncMenu)
  watch([entries, collapsed, active], () => { void nextTick(syncMenu) }, { flush: 'post' })
  onBeforeUnmount(() => { disposed = true; observer?.disconnect(); nodes.clear() })
  const scheduleMenuSync = () => { void nextTick(syncMenu) }
  return { setMenuNode, tabindex, isMenuOpen, onMenuFocus, onMenuKeydown, scheduleMenuSync }
}
