// 平台管理员篇的截图。
import { loginReachai, mark, openReachai, settle } from './helpers.mjs'

export const adminShots = [
  {
    name: 'admin-login',
    title: '管理台登录页',
    fresh: true,
    async run({ page, env }) {
      await page.goto(`${env.reachai}/login`)
      await settle(page, 1500)
    },
  },
  {
    name: 'admin-exploration-notice',
    title: '首次登录的探索站提示',
    fresh: true,
    async run({ page, env }) {
      await loginReachai(page, env, env.reachaiAdmin, { keepNotice: true })
    },
  },
  {
    name: 'admin-getting-started',
    title: '开始使用：接入路线和系统体检',
    fresh: true,
    async run({ page, env }) {
      await openReachai(page, env, '/getting-started')
    },
  },
]

/** 以下截图在一键体验初始化完成后拍。 */
async function asAdmin(context) {
  await context.addInitScript(() => {
    try {
      for (let id = 1; id <= 5; id++) localStorage.setItem(`reachai.sidebar.workspace.user.${id}`, 'admin')
    } catch { /* 隐私模式下忽略 */ }
  })
}

adminShots.push(
  {
    name: 'admin-getting-started-done',
    title: '开始使用：四步完成后的样子和系统体检',
    async run({ page, context, env }) {
      await asAdmin(context)
      await openReachai(page, env, '/getting-started')
    },
  },
  {
    name: 'admin-accounts',
    title: '账号与权限：账号管理',
    async run({ page, context, env }) {
      await asAdmin(context)
      await openReachai(page, env, '/settings/platform-users')
      await page.getByText(/^\s*账号管理/).first().click()
      await settle(page, 1500)
    },
  },
  {
    name: 'admin-roles',
    title: '账号与权限：角色与权限',
    async run({ page, context, env }) {
      await asAdmin(context)
      await openReachai(page, env, '/settings/platform-users')
      await page.getByText(/^\s*角色与权限/).first().click()
      await settle(page, 1500)
    },
  },
)
