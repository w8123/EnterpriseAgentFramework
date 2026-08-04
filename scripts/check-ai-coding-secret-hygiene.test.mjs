import assert from 'node:assert/strict'
import test from 'node:test'

import {
  findSecretFindings,
} from './check-ai-coding-secret-hygiene.mjs'

test('detects supported AI Coding secret literals without returning values', () => {
  const activationCode = 'rhc_' + 'A'.repeat(32)
  const bearerToken = 'Bearer ' + 'B'.repeat(48)
  const taskToken = '"taskToken": "eyJ' + 'C'.repeat(40) + '"'
  const findings = findSecretFindings(
    'fixture.txt',
    ['safe', activationCode, bearerToken, taskToken].join('\n'),
  )

  assert.deepEqual(
    findings,
    [
      { key: 'activation-code', relativePath: 'fixture.txt', line: 2 },
      { key: 'bearer-token', relativePath: 'fixture.txt', line: 3 },
      { key: 'task-token-literal', relativePath: 'fixture.txt', line: 4 },
    ],
  )
  assert.equal(JSON.stringify(findings).includes(activationCode), false)
  assert.equal(JSON.stringify(findings).includes(bearerToken), false)
  assert.equal(JSON.stringify(findings).includes(taskToken), false)
})

test('ignores dynamic bootstrap expressions and ordinary schema names', () => {
  const findings = findSecretFindings(
    'bootstrap.ps1',
    [
      'Authorization = "Bearer $($reachAiSession.taskToken)"',
      'schema = "reachai.ai-coding.activation.v1"',
      'taskToken = $reachAiSession.taskToken',
    ].join('\n'),
  )

  assert.deepEqual(findings, [])
})
