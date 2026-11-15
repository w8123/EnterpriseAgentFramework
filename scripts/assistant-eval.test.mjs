import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { answerAfter, judge, renderMarkdown } from './assistant-eval.mjs'

test('judge applies expectAny, expectAll, forbid and maxSeconds', () => {
  assert.deepEqual(judge({ expectAny: ['审批'] }, '已提交审批，审批单 #4', 20), { pass: true, slow: false, reasons: [] })
  assert.equal(judge({ expectAny: ['审批'] }, '客户已创建', 20).pass, false)
  assert.equal(judge({ expectAll: ['2', '合计'] }, '共 2 张', 20).pass, false)
  assert.equal(judge({ expectAny: ['订单'] }, '没有权限查询订单', 20, ['没有权限']).pass, false)
  assert.equal(judge({ expectAny: ['不能'], forbid: [] }, '我不能订机票，没有权限', 20, ['没有权限']).pass, true)
  assert.equal(judge({ expectAny: ['订单'], maxSeconds: 30 }, '订单 2 张', 45).slow, true)
  assert.equal(judge({}, '', 5).pass, false)
})

test('answerAfter keeps only the text after the last question', () => {
  const panel = 'ReachAI\n查订单\n旧回答\n查订单\n共 2 张订单\n\nCtrl\n+\nEnter\n发送\n发送'
  assert.equal(answerAfter(panel, '查订单'), '共 2 张订单')
})

test('report and bundled suite are well formed', () => {
  const markdown = renderMarkdown({ suite: 's', startedAt: 't', baseUrl: 'u', total: 1, passed: 0, slow: 0, avgSeconds: 3,
    results: [{ id: 'a', pass: false, slow: false, seconds: 3, reasons: ['缺少预期词：x|y'], maxSeconds: 10 }] })
  assert.match(markdown, /\| a \| 失败 \| 3 \| 缺少预期词：x\\\|y \|/)
  const suite = JSON.parse(fs.readFileSync(new URL('../docs/evals/assistant-walkthrough-v1.json', import.meta.url), 'utf8'))
  assert.ok(suite.tasks.length >= 10)
  assert.equal(new Set(suite.tasks.map((task) => task.id)).size, suite.tasks.length)
  for (const task of suite.tasks) assert.ok(task.id && task.page.startsWith('/') && task.question)
})
