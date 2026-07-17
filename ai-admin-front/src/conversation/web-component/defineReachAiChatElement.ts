import { defineCustomElement } from 'vue'
import ReachAiChatElement from './ReachAiChatElement.ce.vue'

let registered = false

export function defineReachAiChatElement(tagName = 'reachai-chat') {
  if (registered || typeof customElements === 'undefined') return
  if (customElements.get(tagName)) {
    registered = true
    return
  }
  customElements.define(tagName, defineCustomElement(ReachAiChatElement))
  registered = true
}

export { ReachAiChatElement }
