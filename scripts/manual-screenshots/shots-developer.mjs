// 开发者篇的截图。用平台管理员账号登录，切到「开发人员」身份。
// 依赖一键体验初始化完成：芋道已接入、ERP 助手和 4 个查询 Workflow 已发布、MCP 服务 yudao-erp-employee 已发布。
import { loginReachai, mark, settle } from './helpers.mjs'

async function openAsDeveloper(page, context, env, route, workspace = 'developer') {
  await context.addInitScript(value => {
    try {
      for (let id = 1; id <= 5; id++) localStorage.setItem(`reachai.sidebar.workspace.user.${id}`, value)
    } catch { /* 隐私模式下忽略 */ }
  }, workspace)
  await loginReachai(page, env)
  await page.goto(`${env.reachai}${route}`)
  await settle(page, 2200)
}

function devPage(name, title, route, prepare, workspace) {
  return {
    name, title,
    async run({ page, context, env }) {
      await openAsDeveloper(page, context, env, route, workspace)
      if (prepare) return prepare(page)
    },
  }
}

async function openAgent(page, tabText) {
  await page.getByText('编辑', { exact: true }).first().click()
  await settle(page, 2000)
  if (tabText) {
    await page.getByText(tabText, { exact: true }).first().click()
    await settle(page, 1500)
  }
}

export const developerShots = [
  devPage('dev-projects', '项目管理', '/registry/projects', async page => {
    await mark(page.getByRole('button', { name: '接入项目' }), '接入新的业务系统')
  }),
  devPage('dev-new-project', '接入项目：生成接入令牌', '/registry/projects', async page => {
    await page.getByRole('button', { name: '接入项目' }).click()
    await settle(page, 1000)
    await page.getByPlaceholder('如：智能客服平台').fill('订单系统')
    await page.getByPlaceholder('如：customer-service').fill('order-service')
    await page.getByPlaceholder('http://localhost:8080').fill('http://192.168.1.20:8080')
  }),
  devPage('dev-project-detail', '项目详情', '/registry/projects/yudao-erp'),
  devPage('dev-sdk-access', '项目接入工作台', '/registry/projects/yudao-erp/sdk-access'),
  devPage('dev-capabilities', '业务能力目录', '/business-capabilities'),
  devPage('dev-capability-detail', '业务方法详情', '/business-capabilities', async page => {
    await page.locator('tr', { hasText: '查询产品库存' }).getByText('查看').first().click()
    await settle(page, 2000)
  }),
  devPage('dev-model-new', '接入模型：选择厂商', '/model/instances', async page => {
    await page.getByRole('button', { name: '接入模型' }).click()
    await settle(page, 1200)
  }),
  devPage('dev-workflows', 'Workflow 列表', '/workflows'),
  devPage('dev-workflow-new', '新建 Workflow：选择起点', '/workflows', async page => {
    await page.getByRole('button', { name: '新建 Workflow' }).click()
    await settle(page, 1200)
  }),
  devPage('dev-workflow-studio', 'Workflow Studio', '/workflows', async page => {
    await page.getByText('查询产品库存').first().click()
    await settle(page, 2500)
  }),
  devPage('dev-agent-overview', 'Agent 概览', '/agent', page => openAgent(page)),
  devPage('dev-agent-embed', 'Agent 的嵌入设置', '/agent', page => openAgent(page, '嵌入')),
  // 芋道的业务方法要求真实员工身份，管理台调试用的是平台账号，调用会被拒绝，所以这里只拍调试页本身。
  devPage('dev-agent-debug', 'Agent 调试', '/agent', async page => {
    await page.getByText('调试', { exact: true }).first().click()
    await settle(page, 2000)
  }),
  devPage('dev-handoff', '交给我的 AI', '/workflows', async page => {
    await page.getByRole('button', { name: '交给我的 AI' }).click()
    await settle(page, 1200)
  }),
  devPage('dev-ai-connections', '我的 AI 工具接入（平台账号）', '/me/ai-connections', null, 'my'),
  devPage('dev-page-assistant', '业务页面工作台', '/registry/projects/yudao-erp/page-assistant'),
  devPage('dev-retrofit', '用 AI 改造业务能力', '/registry/projects/yudao-erp/capability-retrofit'),
  devPage('dev-mcp-new', '新建 MCP 服务', '/mcp-hub/publications', async page => {
    await page.getByRole('button', { name: '新建 MCP 服务' }).click()
    await settle(page, 1000)
  }),
  devPage('dev-mcp-tools', 'MCP 服务开放的工具', '/mcp-hub/publications', async page => {
    await page.getByRole('button', { name: '管理', exact: true }).first().click()
    await settle(page, 2200)
  }),
  devPage('dev-kb-new', '新建知识库', '/knowledge', async page => {
    await page.getByRole('button', { name: '新建知识库' }).first().click()
    await settle(page, 1200)
  }),
]
