import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import assert from 'node:assert/strict'

const root = resolve(import.meta.dirname, '..')

function read(relativePath) {
  return readFileSync(resolve(root, relativePath), 'utf8')
}

const detailView = read('src/views/registry/RegistryProjectDetail.vue')
const detailActions = read('src/views/registry/composables/useRegistryProjectDetailActions.ts')
const globalStyles = read('src/styles/index.scss')

assert.match(
  detailView,
  /<el-form-item\s+label="App Key"\s+required>/,
  'edit project dialog should mark App Key as required',
)
assert.match(
  detailView,
  /<el-input\s+v-model="editCredentialForm\.appKey"[^>]*placeholder="请输入 App Key"/,
  'edit project dialog should ask for App Key instead of allowing a blank credential update',
)
assert.match(
  detailView,
  /<el-form-item\s+label="App Secret"\s+required>/,
  'edit project dialog should mark App Secret as required',
)
assert.match(
  detailView,
  /<el-input\s+v-model="editCredentialForm\.appSecret"[^>]*show-password[^>]*placeholder="请输入 App Secret"/,
  'edit project dialog should ask for App Secret instead of allowing a blank credential update',
)

assert.match(
  detailActions,
  /deps\.isEditingSdkProject\.value\s*&&\s*\(!credentialAppKey\s*\|\|\s*!credentialAppSecret\)/,
  'SDK edit save should require both App Key and App Secret every time',
)
assert.doesNotMatch(
  detailActions,
  /更新接入凭据时请同时填写 App Key 和 App Secret/,
  'SDK edit save warning should no longer describe credentials as optional updates',
)

assert.match(
  globalStyles,
  /body\.el-popup-parent--hidden[\s\S]*\.sidebar-aside[\s\S]*filter:\s*blur\(/,
  'global modal/drawer state should blur the main sidebar too',
)
assert.match(
  globalStyles,
  /body\.el-popup-parent--hidden[\s\S]*\.app-sidebar[\s\S]*backdrop-filter:\s*blur\(/,
  'global modal/drawer state should soften the sidebar glass surface itself',
)
