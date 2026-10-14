import { marked } from 'marked'

const allowedTags = new Set(['P', 'BR', 'HR', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'BLOCKQUOTE', 'PRE', 'CODE', 'EM',
  'STRONG', 'DEL', 'UL', 'OL', 'LI', 'TABLE', 'THEAD', 'TBODY', 'TR', 'TH', 'TD', 'A', 'IMG'])
const discardTags = new Set(['SCRIPT', 'STYLE', 'IFRAME', 'OBJECT', 'EMBED', 'SVG', 'MATH', 'TEMPLATE'])

function safeUrl(value: string, image: boolean) {
  try {
    const url = new URL(value, window.location.origin)
    return ['http:', 'https:', ...(image ? [] : ['mailto:'])].includes(url.protocol)
  } catch { return false }
}

/** Render source/AI Markdown as a restricted document, never active HTML. */
export function renderSafeMarkdown(content: string | null | undefined): string {
  if (!content) return ''
  const template = document.createElement('template')
  template.innerHTML = marked.parse(content, { async: false }) as string
  function clean(node: ParentNode) {
    for (const child of Array.from(node.children)) {
      if (discardTags.has(child.tagName)) { child.remove(); continue }
      clean(child)
      if (!allowedTags.has(child.tagName)) { child.replaceWith(...Array.from(child.childNodes)); continue }
      for (const attribute of Array.from(child.attributes)) {
        const allowed = attribute.name === 'title' || (child.tagName === 'A' && attribute.name === 'href')
          || (child.tagName === 'IMG' && ['src', 'alt'].includes(attribute.name))
        if (!allowed) child.removeAttribute(attribute.name)
      }
      if (child.tagName === 'A') {
        const href = child.getAttribute('href')
        if (href && !safeUrl(href, false)) child.removeAttribute('href')
        child.setAttribute('rel', 'noopener noreferrer')
      }
      if (child.tagName === 'IMG') {
        const src = child.getAttribute('src')
        if (!src || !safeUrl(src, true)) child.remove()
      }
    }
  }
  clean(template.content)
  return template.innerHTML
}
