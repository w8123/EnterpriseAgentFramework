#!/usr/bin/env node

import { readFileSync, readdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const sqlDirectory = join(root, 'sql')
const targetName = 'upgrade-20260830-platform-consolidated.sql'
const targetPath = join(sqlDirectory, targetName)
const initPath = join(sqlDirectory, 'initV2.sql')

const expectedSections = [
  ['01', 'sql/upgrade-20260823-a2a-hub.sql', '5bb0c0bdbd16a01681a5002404ed534c69a6b9829c1972a1cd634b0d8d3a772a'],
  ['02', 'sql/upgrade-20260823-agent-skill-catalog.sql', 'dd392e23a29f2276ccfb1c4950438abde3683caf22dd6417ba0e8a0a58d0fe38'],
  ['03', 'sql/upgrade-20260823-api-market.sql', 'af1838c83cf394d502b9f2268dcd2684ae657ba7328d5a1e00856ad98048973d'],
  ['04', 'sql/upgrade-20260824-agent-skill-market.sql', '17951b197dbf0bc33ad09e0ff7dd10295be4f4a02b17838862ad8da6ed4557ea'],
  ['05', 'sql/upgrade-20260824-eval-target-snapshot.sql', '1d3291eca95555982f069fd559b3fe2fe184f13110ad600a3b95e6c51492ee6d'],
  ['06', 'sql/upgrade-20260824-evalops-experiments.sql', '6f3bd6768eb967f3780377e3179a017c2b8eb776bcdaa76cd71accc421a5a2dc'],
  ['07', 'sql/upgrade-20260824-managed-executor.sql', '95ce423066737a792c4023780ecebe7fa87b0cfdce83a48a24c0d285e55a6ebb'],
  ['08', 'sql/upgrade-20260824-runtime-automation.sql', '31a67254328223a4d3c60bec8da87afaf1a5904e9164ce16055af8c9a586606d'],
  ['09', 'sql/upgrade-20260824-runtime-checkpoint-v1.sql', '28ef690bf28de2223184f1e55e0428056e951a2ba1a77b822c4080e19175bf3f'],
  ['10', 'sql/upgrade-20260825-model-catalog-sync.sql', '35b0e63d3fc0901132aa3201b89cf117f3a11361b6be14ebc1a7e697c702e4d7'],
  ['11', 'sql/upgrade-20260827-platform-authorization-foundation.sql', '1ee853b31f5a08f560cc5e63a5551dd345e8aeae8fc1d490fa4ef413c0e7c429'],
  ['12', 'sql/upgrade-20260827-runtime-management-authorization.sql', 'f6e27024828fa694278703a74eb514540dbef7705359300be4653c3108646071'],
  ['13', 'sql/upgrade-20260827-enterprise-account-role-management.sql', 'a54de1eed4af7d328794587895556343ff09572175d7d0807f7e34d9af885f08'],
  ['14', 'sql/upgrade-20260828-mcp-hub.sql', 'dcfa19343b2a8d531115d564370869e88fb3d2094e28b116b0b037941853d463'],
]

const init = readFileSync(initPath, 'utf8')
const upgrade = readFileSync(targetPath, 'utf8')
const issues = []

function fail(message) {
  issues.push(message)
  console.error(`[consolidated-upgrade-sql] FAIL: ${message}`)
}

const upgradeFiles = readdirSync(sqlDirectory)
  .filter(name => /^upgrade-.*\.sql$/i.test(name))
  .sort()
if (upgradeFiles.length !== 1 || upgradeFiles[0] !== targetName) {
  fail(`sql/ must contain only ${targetName}; found ${upgradeFiles.join(', ')}`)
}

let previous = -1
for (const [number, source, sha256] of expectedSections) {
  const marker = `-- Section ${number}/14:`
  const index = upgrade.indexOf(marker)
  if (index < 0) fail(`missing ${marker}`)
  if (index <= previous) fail(`${marker} is out of order`)
  previous = index
  if (!upgrade.includes(`-- Consolidated from: ${source}`)) {
    fail(`missing source manifest for ${source}`)
  }
  if (!upgrade.includes(`-- Source SHA-256: ${sha256}`)) {
    fail(`missing source SHA-256 for ${source}`)
  }
}

function normalize(sql) {
  return sql.replace(/\s+/g, ' ').trim()
}

function tableDefinition(sql, table) {
  const pattern = new RegExp(
    'CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+`' + table
      + '`\\s*\\([\\s\\S]*?\\)\\s*ENGINE=InnoDB[^;]+;',
    'i',
  )
  return sql.match(pattern)?.[0] ?? ''
}

const createdTables = [...upgrade.matchAll(
  /CREATE\s+TABLE\s+IF\s+NOT\s+EXISTS\s+`([A-Za-z0-9_]+)`/gi,
)].map(match => match[1])
const uniqueTables = [...new Set(createdTables)].sort()
if (uniqueTables.length !== 68) {
  fail(`expected 68 unique CREATE TABLE contracts, found ${uniqueTables.length}`)
}
for (const table of uniqueTables) {
  const migration = tableDefinition(upgrade, table)
  const baseline = tableDefinition(init, table)
  if (!baseline) fail(`${table} is missing from initV2.sql`)
  else if (normalize(migration) !== normalize(baseline)) {
    fail(`${table} differs between initV2.sql and the consolidated upgrade`)
  }
}

for (const token of [
  '`checkpoint_schema_version`',
  '`execution_engine_version`',
  '`checkpoint_digest`',
  '`checkpoint_size_bytes`',
  '`role_kind`',
  "'workspace:build:access'",
  "'agent:read'",
  "'workflow:read'",
  "'runops:read'",
]) {
  if (!init.includes(token)) fail(`initV2.sql is missing ${token}`)
  if (!upgrade.includes(token)) fail(`consolidated upgrade is missing ${token}`)
}

if (!issues.length) {
  console.log('[consolidated-upgrade-sql] PASS: one upgrade, 14 ordered sections, 68 baseline-matched tables')
} else {
  process.exitCode = 1
}
