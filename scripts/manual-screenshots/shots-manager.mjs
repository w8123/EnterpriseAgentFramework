// 管理人员篇的截图。用平台管理员账号登录，切到「管理人员」身份，菜单和真实的管理人员一样。
// 依赖演示数据：员工已经通过 AI 工具查过库存、提交过新建客户的审批（#1 已通过，#2 待审批），
// 平台管理员已把调用记录沉淀成 Skill「在芋道 ERP 中查询产品库存」并发布。
import { loginReachai, mark, settle } from './helpers.mjs'

/** 切换身份只是浏览器本地的菜单偏好（每个账号一份），登录前写好即可。 */
async function asWorkspace(context, workspace) {
  await context.addInitScript(value => {
    try {
      for (let id = 1; id <= 5; id++) localStorage.setItem(`reachai.sidebar.workspace.user.${id}`, value)
    } catch { /* 隐私模式下忽略 */ }
  }, workspace)
}

async function openAs(page, context, env, route, workspace = 'manager') {
  await asWorkspace(context, workspace)
  await loginReachai(page, env)
  await page.goto(`${env.reachai}${route}`)
  await settle(page, 2200)
}

function managerPage(name, title, route, prepare) {
  return {
    name, title,
    async run({ page, context, env }) {
      await openAs(page, context, env, route)
      if (prepare) return prepare(page)
    },
  }
}

async function selectStockCalls(page) {
  const rows = page.locator('tbody tr')
  const count = await rows.count()
  let picked = 0
  for (let i = 0; i < count && picked < 2; i++) {
    const text = await rows.nth(i).innerText()
    if (/erpStockPage|erpProductPage/.test(text) && /成功/.test(text)) {
      await rows.nth(i).locator('.el-checkbox').click()
      picked++
    }
  }
  await settle(page, 600)
}

export const managerShots = [
  managerPage('manager-switch-role', '切换角色', '/dashboard', async page => {
    await page.getByRole('button', { name: /切换角色/ }).click()
    await settle(page, 1000)
  }),
  managerPage('manager-dashboard', '管理人员的概览', '/dashboard'),
  managerPage('manager-usage', 'AI 使用情况', '/usage'),
  managerPage('manager-usage-detail', '某位员工的使用详情', '/usage', async page => {
    await page.getByText('查看详情').first().click()
    await settle(page, 1800)
  }),
  managerPage('manager-employee-use', 'MCP 服务的「员工使用」设置', '/mcp-hub/publications', async page => {
    await page.getByRole('button', { name: '管理', exact: true }).first().click()
    await settle(page, 2000)
    await page.getByText('接入与使用', { exact: true }).click()
    await settle(page, 1500)
    await page.mouse.wheel(0, 420)
    await settle(page, 800)
  }),
  managerPage('manager-connections', '员工连接', '/mcp-hub/employee-connections'),
  managerPage('manager-approvals', '写操作审批列表', '/mcp-hub/write-approvals', async page => {
    await mark(page.getByRole('button', { name: '审批', exact: true }), '点这里审批')
  }),
  managerPage('manager-approval-dialog', '审批详情：看清楚要做什么', '/mcp-hub/write-approvals', async page => {
    await page.getByRole('button', { name: '审批', exact: true }).first().click()
    await settle(page, 1200)
  }),
  managerPage('manager-approval-done', '通过后的执行结果', '/mcp-hub/write-approvals?state=SUCCEEDED', async page => {
    await page.getByRole('button', { name: '查看', exact: true }).first().click()
    await settle(page, 1200)
  }),
  managerPage('manager-call-logs', '调用记录', '/mcp-hub/call-logs'),
  managerPage('manager-skill-capture', '从调用记录沉淀 Skill', '/mcp-hub/call-logs', async page => {
    await selectStockCalls(page)
    await page.getByRole('button', { name: '沉淀成 Skill' }).click()
    await settle(page, 2500)
  }),
  {
    name: 'manager-skill-publish',
    title: '平台管理员评审并发布 Skill',
    async run({ page, context, env }) {
      await openAs(page, context, env, '/skills', 'admin')
      await page.getByText('在芋道 ERP 中查询产品库存').first().click()
      await settle(page, 2000)
      await mark(page.getByText('治理', { exact: true }).last(), '评审通过 → 发布')
    },
  },
  managerPage('manager-run-center', '运行中心：每一次对话', '/run-center'),
  managerPage('manager-run-detail', '一次运行的详细过程', '/run-center', async page => {
    await page.getByText('查看运行').first().click()
    await settle(page, 2500)
  }),
  managerPage('manager-automations', '自动化中心', '/automations'),
  managerPage('manager-automation-new', '新建自动化', '/automations', async page => {
    await page.getByRole('button', { name: '新建自动化' }).click()
    await settle(page, 1500)
  }),
  managerPage('manager-tool-acl', '调用权限', '/settings/tool-acl'),
]
