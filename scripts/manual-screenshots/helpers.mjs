// 截图脚本共用的登录和标注方法。

export async function settle(page, ms = 1200) {
  await page.waitForLoadState('networkidle').catch(() => {})
  await page.waitForTimeout(ms)
}

/** 登录 ReachAI 管理台，并关掉首次登录的「探索站」提示。 */
export async function loginReachai(page, env, account = env.reachaiAdmin, { keepNotice = false } = {}) {
  await page.goto(`${env.reachai}/login`)
  await page.fill('input[placeholder="请输入用户名"]', account[0])
  await page.fill('input[placeholder="请输入密码"]', account[1])
  await page.keyboard.press('Enter')
  await page.waitForURL(url => !url.pathname.startsWith('/login'), { timeout: 20000 })
  await settle(page)
  if (keepNotice) return
  const ack = page.getByText('我已了解上述说明，并愿意继续体验')
  if (await ack.count()) {
    await ack.click()
    await page.getByText('我已了解，进入平台').click()
    await settle(page, 600)
  }
}

export async function openReachai(page, env, route, account) {
  await loginReachai(page, env, account)
  await page.goto(`${env.reachai}${route}`)
  await settle(page, 1800)
}

/** 登录芋道 ERP（一键体验里租户和验证码都已关闭或固定）。 */
export async function loginYudao(page, env, account = env.yudaoAdmin) {
  await page.goto(`${env.yudao}/login`)
  await settle(page, 1500)
  const user = page.locator('input[placeholder*="用户名"], input[placeholder*="账号"]').first()
  const pass = page.locator('input[type="password"]').first()
  await user.fill(account[0])
  await pass.fill(account[1])
  await page.keyboard.press('Enter')
  await page.waitForURL(url => !url.pathname.startsWith('/login'), { timeout: 30000 })
  await settle(page, 2500)
}

/** 用醒目的框标出读者要点的位置；不改变页面布局。 */
export async function mark(locator, label) {
  await locator.first().scrollIntoViewIfNeeded().catch(() => {})
  await locator.first().evaluate((element, text) => {
    const rect = element.getBoundingClientRect()
    const box = document.createElement('div')
    box.setAttribute('data-manual-mark', '')
    Object.assign(box.style, {
      position: 'fixed', left: `${rect.left - 6}px`, top: `${rect.top - 6}px`,
      width: `${rect.width + 12}px`, height: `${rect.height + 12}px`,
      border: '3px solid #ef4444', borderRadius: '10px', zIndex: 2147483647, pointerEvents: 'none',
      boxShadow: '0 0 0 4px rgba(239,68,68,.18)',
    })
    document.body.appendChild(box)
    if (text) {
      const tag = document.createElement('div')
      tag.setAttribute('data-manual-mark', '')
      tag.textContent = text
      const below = rect.top < 60
      Object.assign(tag.style, {
        position: 'fixed', left: `${Math.max(8, rect.left - 6)}px`,
        top: below ? `${rect.bottom + 12}px` : `${rect.top - 40}px`,
        background: '#ef4444', color: '#fff', font: '600 14px/1 sans-serif', padding: '7px 10px',
        borderRadius: '6px', zIndex: 2147483647, pointerEvents: 'none', whiteSpace: 'nowrap',
      })
      document.body.appendChild(tag)
      // 靠近右边缘时整体左移，标签不被裁掉
      const overflow = tag.getBoundingClientRect().right - (window.innerWidth - 8)
      if (overflow > 0) tag.style.left = `${Math.max(8, parseFloat(tag.style.left) - overflow)}px`
    }
  }, label || '')
}
