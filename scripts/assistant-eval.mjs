#!/usr/bin/env node
// 内嵌助手自动评测：在业务系统页面里向助手提问，检查回答并记录耗时。
//
//   node scripts/assistant-eval.mjs                        # 跑 docs/evals/assistant-walkthrough-v1.json 的全部任务
//   node scripts/assistant-eval.mjs --only sale-order,stock # 只跑 id 里包含 sale-order 或 stock 的任务（逗号分隔）
//   node scripts/assistant-eval.mjs --suite path/to.json --out report-dir
//
// 环境变量：
//   EVAL_BASE_URL   业务系统地址，默认 http://localhost:48080（一键体验里的芋道）
//   EVAL_USERNAME / EVAL_PASSWORD   登录账号；不填则直接点登录页的「登录」（演示环境已预填）
//   EVAL_TIMEOUT    单个问题最长等待秒数，默认 180
//
// 判定规则（任务里可写）：
//   expectAny  回答里至少出现其中一个词
//   expectAll  回答里必须全部出现
//   forbid     回答里不能出现（不写则用套件的 forbidDefault）
//   maxSeconds 超过算「慢」，但不算失败
// 结果写到 <out>/report.json 和 <out>/report.md，每个任务一张截图。全部通过退出码 0，否则 1。
//
// 需要 Node.js 和 Playwright（require('playwright') 可用）。每个任务会真实调用模型，注意 token 消耗。

import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

function option(name, fallback) {
  const index = process.argv.indexOf(name)
  return index > 0 && process.argv[index + 1] ? process.argv[index + 1] : fallback
}

/** 纯函数，便于单测：按任务规则判定一条回答。 */
export function judge(task, answer, seconds, forbidDefault = []) {
  const text = String(answer || '')
  const reasons = []
  if (!text.trim()) reasons.push('没有回答')
  const any = task.expectAny || []
  if (any.length && !any.some((word) => text.includes(word))) reasons.push(`没有出现任何一个预期词：${any.join(' / ')}`)
  for (const word of task.expectAll || []) if (!text.includes(word)) reasons.push(`缺少预期词：${word}`)
  for (const word of task.forbid || forbidDefault) if (text.includes(word)) reasons.push(`出现了不应出现的词：${word}`)
  return { pass: reasons.length === 0, slow: !!task.maxSeconds && seconds > task.maxSeconds, reasons }
}

/** 从助手面板全文里取最后一个问题之后的内容，作为这次的回答。 */
export function answerAfter(panelText, question) {
  const text = String(panelText || '')
  const at = text.lastIndexOf(question)
  const tail = at >= 0 ? text.slice(at + question.length) : text
  return tail.replace(/Ctrl\s*\+\s*Enter\s*发送\s*发送\s*$/u, '').trim()
}

export function renderMarkdown(report) {
  const lines = [`# 助手评测报告：${report.suite}`, '',
    `- 时间：${report.startedAt}`, `- 地址：${report.baseUrl}`,
    `- 通过：${report.passed} / ${report.total}（${report.total ? Math.round(report.passed * 100 / report.total) : 0}%）`,
    `- 平均耗时：${report.avgSeconds} 秒，超过预期时间的有 ${report.slow} 个`, '',
    '| 任务 | 结果 | 耗时（秒） | 说明 |', '|---|---|---|---|']
  for (const row of report.results) {
    const note = row.error || row.reasons.join('；') || (row.slow ? `超过预期的 ${row.maxSeconds} 秒` : '')
    lines.push(`| ${row.id} | ${row.pass ? '通过' : '失败'}${row.slow && row.pass ? '（慢）' : ''} | ${row.seconds} | ${note.replace(/\|/g, '\\|')} |`)
  }
  return lines.join('\n') + '\n'
}

async function login(page, baseUrl) {
  await page.goto(baseUrl + '/login', { waitUntil: 'networkidle', timeout: 60000 })
  if (process.env.EVAL_USERNAME) {
    await page.getByPlaceholder(/用户名|账号/).first().fill(process.env.EVAL_USERNAME)
    await page.getByPlaceholder(/密码/).first().fill(process.env.EVAL_PASSWORD || '')
  }
  await page.click('button:has-text("登录")')
  await page.waitForURL((url) => !url.toString().includes('/login'), { timeout: 30000 })
}

async function ask(page, question, timeoutSeconds) {
  const box = page.getByPlaceholder('输入消息')
  await box.fill(question)
  const started = Date.now()
  await page.click('button:has-text("发送")')
  const panelText = () => page.evaluate(() => {
    let node = document.querySelector('textarea[placeholder="输入消息"]')
    for (let depth = 0; node && depth < 12 && node.parentElement; depth++) {
      node = node.parentElement
      if (node.innerText && node.innerText.includes('ReachAI')) break
    }
    return node ? node.innerText : ''
  })
  let last = ''
  let lastChange = Date.now()
  while (Date.now() - started < timeoutSeconds * 1000) {
    await page.waitForTimeout(1000)
    const text = await panelText()
    if (text !== last) { last = text; lastChange = Date.now() }
    else if (Date.now() - lastChange > 8000 && Date.now() - started > 10000
      && !text.includes('正在处理') && !text.includes('思考中…')) break
  }
  return { answer: answerAfter(last, question), seconds: Math.round((lastChange - started) / 1000) }
}

async function main() {
  const require = createRequire(import.meta.url)
  const { chromium } = require('playwright')
  const suitePath = path.resolve(option('--suite', path.join(repoRoot, 'docs/evals/assistant-walkthrough-v1.json')))
  const suite = JSON.parse(fs.readFileSync(suitePath, 'utf8'))
  const outDir = path.resolve(option('--out', path.join(repoRoot, 'eval-reports', new Date().toISOString().replace(/[:.]/g, '-'))))
  const baseUrl = (process.env.EVAL_BASE_URL || 'http://localhost:48080').replace(/\/+$/, '')
  const timeoutSeconds = Number(process.env.EVAL_TIMEOUT || 180)
  const stamp = new Date().toISOString().slice(5, 16).replace(/[-T:]/g, '')
  const only = option('--only', '')
  const wanted = only ? only.split(',').map((item) => item.trim()).filter(Boolean) : []
  const tasks = suite.tasks.filter((task) => !wanted.length || wanted.some((item) => task.id.includes(item)))
  fs.mkdirSync(outDir, { recursive: true })

  const browser = await chromium.launch()
  const results = []
  try {
    for (const task of tasks) {
      const question = task.question.replaceAll('{{stamp}}', stamp)
      // 每个任务用新的浏览器上下文，等于一次新会话，任务之间互不影响
      const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
      const page = await context.newPage()
      const row = { id: task.id, question, maxSeconds: task.maxSeconds || null, seconds: 0, pass: false, slow: false, reasons: [], answer: '' }
      try {
        await login(page, baseUrl)
        await page.goto(baseUrl + task.page, { waitUntil: 'networkidle', timeout: 60000 })
        await page.waitForTimeout(2500)
        const { answer, seconds } = await ask(page, question, timeoutSeconds)
        Object.assign(row, { answer, seconds }, judge(task, answer, seconds, suite.forbidDefault || []))
        await page.screenshot({ path: path.join(outDir, `${task.id}.png`) })
      } catch (error) {
        row.error = error instanceof Error ? error.message.split('\n')[0] : String(error)
        row.reasons = ['执行出错']
      } finally {
        await context.close()
      }
      results.push(row)
      // 每跑完一个任务就落盘，进程中途被停也能保留已完成的结果
      fs.writeFileSync(path.join(outDir, 'partial.jsonl'), results.map((item) => JSON.stringify(item)).join('\n') + '\n')
      console.log(`${row.pass ? 'PASS' : 'FAIL'} ${row.id} ${row.seconds}s ${row.error || row.reasons.join('；')}`)
    }
  } finally {
    await browser.close()
  }
  const passed = results.filter((row) => row.pass).length
  const report = {
    suite: suite.name, startedAt: new Date().toISOString(), baseUrl, total: results.length, passed,
    slow: results.filter((row) => row.slow).length,
    avgSeconds: results.length ? Math.round(results.reduce((sum, row) => sum + row.seconds, 0) / results.length) : 0,
    results,
  }
  fs.writeFileSync(path.join(outDir, 'report.json'), JSON.stringify(report, null, 2))
  fs.writeFileSync(path.join(outDir, 'report.md'), renderMarkdown(report))
  console.log(`\n通过 ${passed} / ${results.length}，平均 ${report.avgSeconds} 秒。报告：${path.join(outDir, 'report.md')}`)
  process.exit(passed === results.length ? 0 : 1)
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => { console.error(error); process.exit(2) })
}
