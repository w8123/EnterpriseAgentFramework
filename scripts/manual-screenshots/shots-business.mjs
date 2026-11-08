// 业务人员篇的截图（在芋道 ERP 里使用 AI 助手和员工个人页）。
import { loginYudao, mark, settle } from './helpers.mjs'

const panel = page => page.locator('section.eaf-chat__panel')

/** 只拍对话框和它左边一点业务页面，读者能看清对话内容又知道它在哪。 */
async function panelClip(page, extraLeft = 360) {
  const box = await panel(page).boundingBox()
  const x = Math.max(0, box.x - extraLeft)
  const y = Math.max(0, box.y - 70)
  return { clip: { x, y, width: box.x + box.width + 16 - x, height: Math.min(900, box.y + box.height + 16) - y } }
}

async function ask(page, text, wait = 15000) {
  await page.getByPlaceholder('输入消息').fill(text)
  await page.keyboard.press('Control+Enter')
  // 等到助手回答完成：发送按钮重新可用，且最后一条消息不再是「正在处理」。
  await page.waitForFunction(() => !document.body.innerText.includes('正在处理') && !document.body.innerText.includes('正在生成回答'), null, { timeout: wait }).catch(() => {})
  await settle(page, 1500)
}

/** 截图里不出现真实的个人连接密钥。 */
async function maskKeys(page) {
  await page.evaluate(() => {
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT)
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      if (/rai_[A-Za-z0-9_-]{30,}/.test(node.nodeValue)) node.nodeValue = node.nodeValue.replace(/rai_[A-Za-z0-9_-]{30,}/g, 'rai_••••••••（你的个人密钥）')
    }
  })
}

async function openToolsPanel(page) {
  await page.getByRole('button', { name: '在我的 AI 工具中使用' }).click()
  await settle(page, 1500)
}

/** 从芋道助手打开员工个人页，返回个人页。 */
async function openPortal(page, context, env, tab, height = 900) {
  await loginYudao(page, env)
  await openToolsPanel(page)
  const [portal] = await Promise.all([context.waitForEvent('page'), page.getByText('在浏览器里管理').locator('visible=true').first().click()])
  await portal.setViewportSize({ width: 1440, height })
  await settle(portal, 2500)
  if (tab) {
    await portal.getByText(new RegExp('^\\s*' + tab)).locator('visible=true').first().click()
    await settle(portal, 2000)
  }
  return portal
}

function portalShot(name, title, tab, prepare, height = 900) {
  return {
    name, title,
    async run({ page, context, env }) {
      const portal = await openPortal(page, context, env, tab, height)
      if (prepare) await prepare(portal)
      await maskKeys(portal)
      // 后续由主脚本对 portal 截图：把 portal 换成当前页。
      return { __page: portal }
    },
  }
}

export const businessShots = [
  {
    name: 'business-assistant-panel',
    title: '芋道 ERP 右下角的 AI 助手',
    async run({ page, env }) {
      await loginYudao(page, env)
      await mark(panel(page), 'AI 助手')
    },
  },
  {
    name: 'business-assistant-stock',
    title: '在助手里问库存',
    async run({ page, env }) {
      await loginYudao(page, env)
      await ask(page, '罗技无线鼠标还有多少库存？')
      return panelClip(page)
    },
  },
  {
    name: 'business-assistant-customers',
    title: '在助手里查客户',
    async run({ page, env }) {
      await loginYudao(page, env)
      await ask(page, '帮我查一下上海的客户')
      return panelClip(page)
    },
  },
  {
    name: 'business-tools-entry',
    title: '「在我的 AI 工具中使用」按钮',
    async run({ page, env }) {
      await loginYudao(page, env)
      await mark(page.getByRole('button', { name: '在我的 AI 工具中使用' }), '在我的 AI 工具中使用')
      return panelClip(page)
    },
  },
  {
    name: 'business-tools-choose',
    title: '选择系统和 AI 工具',
    async run({ page, env }) {
      await loginYudao(page, env)
      await openToolsPanel(page)
      return panelClip(page)
    },
  },
  {
    name: 'business-tools-generated',
    title: '生成连接后复制的命令',
    async run({ page, env }) {
      await loginYudao(page, env)
      await openToolsPanel(page)
      await page.getByRole('button', { name: /生成连接/ }).click()
      await settle(page, 2500)
      await maskKeys(page)
      return panelClip(page)
    },
  },
  portalShot('business-portal-setup', '个人页：接入与体验', null),
  portalShot('business-portal-prompts', '个人页：提示词库', '提示词库'),
  portalShot('business-portal-capabilities', '个人页：我能办的事', '我能办的事', null, 760),
  portalShot('business-portal-credentials', '个人页：访问凭据', '访问凭据', null, 520),
  portalShot('business-portal-records', '个人页：任务记录', '任务记录'),
  portalShot('business-portal-approvals', '个人页：我的审批', '我的审批', null, 460),
  portalShot('business-portal-assistant', '个人页：我的助手', '我的助手', async portal => {
    await portal.getByPlaceholder(/说说要办的事/).fill('罗技机械键盘还有多少库存？')
    await portal.keyboard.press('Control+Enter')
    await portal.waitForTimeout(12000)
  }),
  portalShot('business-portal-memory', '个人页：我的记忆', '我的记忆', null, 640),
]
