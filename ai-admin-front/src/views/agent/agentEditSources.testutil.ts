import { readdirSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

/** AgentEdit.vue 拆成了 edit/ 下的分区组件；源码守卫测试按整页拼接后检查。 */
const files = [
  resolve(__dirname, './AgentEdit.vue'),
  ...readdirSync(resolve(__dirname, './edit'))
    .filter((name) => /\.(vue|ts)$/.test(name))
    .sort()
    .map((name) => resolve(__dirname, './edit', name)),
]
const sources = files.map((file) => ({ file, text: readFileSync(file, 'utf8') }))

export const agentEditTemplate = sources
  .filter(({ file }) => file.endsWith('.vue'))
  .map(({ text }) => text.split('<script setup')[0])
  .join('\n')

export const agentEditScript = sources
  .map(({ file, text }) => (file.endsWith('.vue') ? text.split('<script setup')[1] || '' : text))
  .join('\n')

export const agentEditSource = sources.map(({ text }) => text).join('\n')
