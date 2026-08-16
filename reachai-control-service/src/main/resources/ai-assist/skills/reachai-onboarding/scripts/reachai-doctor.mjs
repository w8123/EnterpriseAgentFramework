#!/usr/bin/env node
import { existsSync } from 'node:fs'
import { readFile, readdir } from 'node:fs/promises'
import { basename, extname, resolve } from 'node:path'
import { createHash } from 'node:crypto'
import http from 'node:http'
import https from 'node:https'

const MAX_HTTP_RESPONSE_BYTES = 2 * 1024 * 1024

function arg(name, fallback = undefined) {
  const index = process.argv.indexOf(name)
  if (index < 0) return fallback
  const value = process.argv[index + 1]
  if (!value || value.startsWith('--')) throw new Error(`${name} requires a value`)
  return value
}

function nodeMajor() {
  return Number(process.versions.node.split('.')[0])
}

function status(key, label, state, message, evidence = undefined) {
  return { key, label, status: state, message, ...(evidence ? { evidence } : {}) }
}

async function readJson(path) {
  return JSON.parse(await readFile(path, 'utf8'))
}

async function sha256(path) {
  return createHash('sha256').update(await readFile(path)).digest('hex')
}

async function findFiles(root, predicate, maximum = 200) {
  const result = []
  async function visit(dir) {
    if (result.length >= maximum) return
    let entries
    try { entries = await readdir(dir, { withFileTypes: true }) } catch { return }
    for (const entry of entries) {
      if (result.length >= maximum) return
      if ([
        '.claude', '.codex', '.cursor', '.git', '.gradle', '.idea', '.next', '.trae',
        'build', 'coverage', 'node_modules', 'target', 'dist', 'output',
        'reachai-onboarding', 'vendor',
      ].includes(entry.name)) continue
      const path = resolve(dir, entry.name)
      if (entry.isDirectory()) await visit(path)
      else if (predicate(entry.name)) result.push(path)
    }
  }
  await visit(root)
  return result
}

async function discoverFrontendPackage(businessRoot) {
  const candidates = await findFiles(businessRoot, name => name === 'package.json', 50)
  const scored = []
  for (const file of candidates) {
    try {
      const packageJson = await readJson(file)
      const dependencies = { ...(packageJson.dependencies || {}), ...(packageJson.devDependencies || {}) }
      const frameworkScore = dependencies['@reachai/embed-chat'] ? 100
        : dependencies['@angular/core'] || dependencies.vue || dependencies.react ? 50 : 0
      const buildScore = packageJson.scripts?.build ? 10 : 0
      const depthPenalty = file.split(/[\\/]/).length
      scored.push({ file, score: frameworkScore + buildScore - depthPenalty })
    } catch {}
  }
  scored.sort((left, right) => right.score - left.score || left.file.length - right.file.length)
  return scored[0]?.file
}

function requestJson(rawUrl, method = 'GET', headers = {}, payload = undefined) {
  return new Promise((resolveRequest, reject) => {
    const target = new URL(rawUrl)
    if (target.protocol !== 'http:' && target.protocol !== 'https:') {
      reject(new Error(`Unsupported URL protocol: ${target.protocol}`))
      return
    }
    const client = target.protocol === 'https:' ? https : http
    const requestBody = payload === undefined
      ? (method === 'POST' ? '{}' : undefined)
      : JSON.stringify(payload)
    const request = client.request(target, {
      method,
      headers: {
        ...headers,
        ...(requestBody === undefined ? {} : {
          'Content-Type': 'application/json',
          'Content-Length': String(Buffer.byteLength(requestBody)),
        }),
      },
      timeout: 15000,
    }, response => {
      const chunks = []
      let receivedBytes = 0
      response.on('data', chunk => {
        receivedBytes += chunk.length
        if (receivedBytes > MAX_HTTP_RESPONSE_BYTES) {
          response.destroy(new Error(`Response exceeds ${MAX_HTTP_RESPONSE_BYTES} bytes`))
          return
        }
        chunks.push(chunk)
      })
      response.on('error', reject)
      response.on('end', () => {
        const body = Buffer.concat(chunks).toString('utf8')
        if (response.statusCode < 200 || response.statusCode >= 300) {
          reject(new Error(`HTTP ${response.statusCode}: ${body.slice(0, 400)}`))
          return
        }
        try { resolveRequest(JSON.parse(body)) } catch { reject(new Error('Response is not JSON')) }
      })
    })
    request.on('timeout', () => request.destroy(new Error('request timed out')))
    request.on('error', reject)
    if (requestBody !== undefined) request.write(requestBody)
    request.end()
  })
}

function resolveSameOriginEndpoint(manifestUrl, rawEndpoint) {
  const manifest = new URL(manifestUrl)
  const endpoint = new URL(rawEndpoint, manifest)
  if (endpoint.origin !== manifest.origin) {
    throw new Error(`Manifest endpoint must use the same origin (${manifest.origin})`)
  }
  return endpoint.href
}

function sdkAccessCheckStatus(access) {
  const reported = String(access?.overallStatus || '').trim().toUpperCase()
  const readiness = Array.isArray(access?.readiness) ? access.readiness : []
  const readinessStates = readiness.map(item => String(item?.status || '').trim().toUpperCase())
  if (reported === 'FAIL' || readinessStates.includes('FAIL')) return 'FAIL'
  if (reported === 'WARN' || reported === 'PENDING'
    || readinessStates.some(state => state === 'WARN' || state === 'PENDING')) return 'WARN'
  if (reported === 'PASS' && readiness.length && readinessStates.every(state => state === 'PASS')) return 'PASS'
  return 'FAIL'
}

async function staticChecks(businessRoot, frontendRoot) {
  const checks = [status(
    'NODE_RUNTIME',
    'Node.js 运行时',
    nodeMajor() >= 14 ? 'PASS' : 'FAIL',
    `检测到 Node ${process.versions.node}；安装器和 doctor 至少需要 Node 14。`,
  )]
  let frontendPackage = resolve(frontendRoot, 'package.json')
  let frontendAutoDiscovered = false
  if (!existsSync(frontendPackage)) {
    const discovered = await discoverFrontendPackage(businessRoot)
    if (discovered) {
      frontendPackage = discovered
      frontendAutoDiscovered = true
    }
  }
  if (!existsSync(frontendPackage)) {
    checks.push(status('FRONTEND_PACKAGE', '前端 package.json', 'WARN', '未找到前端 package.json；跳过前端 SDK 与 Angular 兼容性检查。', frontendRoot))
  } else {
    const packageJson = await readJson(frontendPackage)
    const dependencies = { ...(packageJson.dependencies || {}), ...(packageJson.devDependencies || {}) }
    const sdkSpec = dependencies['@reachai/embed-chat']
    checks.push(status(
      'EMBED_SDK_DEPENDENCY',
      'Embed SDK 依赖',
      sdkSpec ? 'PASS' : 'WARN',
      sdkSpec
        ? `已声明 @reachai/embed-chat: ${sdkSpec}${frontendAutoDiscovered ? '（已自动定位前端目录）' : ''}`
        : `尚未在 package.json 声明 @reachai/embed-chat${frontendAutoDiscovered ? '；doctor 已自动定位前端目录' : ''}。`,
      frontendPackage,
    ))
    if (sdkSpec && String(sdkSpec).startsWith('file:')) {
      const frontendPackageRoot = resolve(frontendPackage, '..')
      const vendoredTarball = resolve(frontendPackageRoot, String(sdkSpec).slice('file:'.length))
      const installedPackageRoot = resolve(frontendPackageRoot, 'node_modules', '@reachai', 'embed-chat')
      const vendoredManifest = `${vendoredTarball}.sha256`
      let installedState = 'WARN'
      let installedMessage = '无法验证已安装 Embed SDK 是否与 vendored tgz 一致。请重新运行 Skill 中的 install-embed-chat.mjs。'
      let installedEvidence = { vendoredTarball, installedPackageRoot }
      try {
        const expectedArtifactSha = existsSync(vendoredManifest)
          ? (await readFile(vendoredManifest, 'utf8')).trim().toLowerCase()
          : await sha256(vendoredTarball)
        const installedMarkerPath = resolve(installedPackageRoot, '.reachai-artifact-sha256')
        const installedArtifactSha = (await readFile(installedMarkerPath, 'utf8')).trim().toLowerCase()
        installedEvidence = {
          ...installedEvidence,
          expectedArtifactSha,
          installedArtifactSha,
          installedMarkerPath,
        }
        installedState = expectedArtifactSha === installedArtifactSha ? 'PASS' : 'FAIL'
        installedMessage = installedState === 'PASS'
          ? 'node_modules 中的 Embed SDK 与当前 vendored tgz 校验和一致。'
          : '检测到同版本 SDK 内容漂移：package.json 版本未变，但 node_modules 仍是旧制品。重新运行 Skill 中的 install-embed-chat.mjs；仅执行 npm install --force 不能证明已刷新。'
      } catch (error) {
        installedMessage = existsSync(installedPackageRoot)
          ? `已安装 SDK 缺少制品校验标记（${error.message}）。重新运行新版 install-embed-chat.mjs 后再验收。`
          : 'node_modules 中尚未安装 @reachai/embed-chat。运行 Skill 中的 install-embed-chat.mjs。'
      }
      checks.push(status(
        'EMBED_SDK_ARTIFACT_MATCH',
        'Embed SDK 制品一致性',
        installedState,
        installedMessage,
        installedEvidence,
      ))
    }
    const angular = dependencies['@angular/core']
    if (angular) {
      const major = Number(String(angular).match(/\d+/)?.[0])
      const legacyRisk = Number.isFinite(major) && major <= 12 && nodeMajor() >= 17
      checks.push(status(
        'ANGULAR_NODE_COMPATIBILITY',
        'Angular 与 Node 兼容性',
        legacyRisk ? 'WARN' : 'PASS',
        legacyRisk
          ? `检测到 Angular ${angular} 与 Node ${process.versions.node}。构建若报 OpenSSL provider 错误，请由业务项目明确选择兼容 Node 版本或临时 NODE_OPTIONS=--openssl-legacy-provider；doctor 不会自动写入该设置。`
          : `检测到 Angular ${angular}，未命中 Angular 12 + Node 17+ 的已知 OpenSSL 风险。`,
      ))
    }
  }

  const pomFiles = await findFiles(businessRoot, name => name === 'pom.xml', 100)
  const capabilitySdkPoms = []
  const starterPoms = []
  for (const file of pomFiles) {
    const source = await readFile(file, 'utf8')
    if (source.includes('<artifactId>reachai-capability-sdk</artifactId>')) capabilitySdkPoms.push(file)
    if (source.includes('<artifactId>reachai-spring-boot2-starter</artifactId>')) starterPoms.push(file)
  }
  checks.push(status(
    'CAPABILITY_SDK_DEPENDENCY',
    'Java Capability SDK 依赖',
    capabilitySdkPoms.length ? 'PASS' : 'WARN',
    capabilitySdkPoms.length
      ? `发现 ${capabilitySdkPoms.length} 个 Maven 模块声明 reachai-capability-sdk。`
      : '未在 pom.xml 中发现 reachai-capability-sdk；若该项目不声明 Java 能力可忽略。',
    capabilitySdkPoms,
  ))
  checks.push(status(
    'SPRING_STARTER_DEPENDENCY',
    'Spring Boot Starter 依赖',
    starterPoms.length ? 'PASS' : 'WARN',
    starterPoms.length
      ? `发现 ${starterPoms.length} 个 Maven 模块声明 reachai-spring-boot2-starter。`
      : '未在 pom.xml 中发现 reachai-spring-boot2-starter；请确认 runnable Spring Boot 模块。',
    starterPoms,
  ))

  const configFiles = await findFiles(businessRoot, name => /^application(?:[-.].+)?\.(?:ya?ml|properties)$/i.test(name))
  const reachAiConfigs = []
  const reachAiConfigSources = []
  for (const file of configFiles) {
    if (file.split('\\').join('/').includes('/ai-assist/skills/reachai-onboarding/templates/')) continue
    const source = await readFile(file, 'utf8')
    if (source.match(/(^|\n)\s*reachai\s*[:.]/i)) {
      reachAiConfigs.push(file)
      reachAiConfigSources.push(source)
    }
  }
  checks.push(status(
    'REACHAI_CONFIGURATION',
    'ReachAI 配置',
    reachAiConfigs.length ? 'PASS' : 'WARN',
    reachAiConfigs.length ? `发现 ${reachAiConfigs.length} 个包含 reachai 配置的 application 文件。` : '未在 application 配置中发现 reachai 配置；请确认配置中心或 profile 位置。',
    reachAiConfigs,
  ))

  const combinedConfig = reachAiConfigSources.join('\n').toLowerCase()
  const requiredConfigTokens = [
    ['registry URL', /registry[\s\S]{0,500}(?:url|base-url)|reachai\.registry\.url/],
    ['registry app key', /app[-.]?key|app_key/],
    ['registry app secret', /app[-.]?secret|app_secret/],
    ['project code', /project[\s\S]{0,500}code|reachai\.project\.code/],
    ['project base URL', /base[-.]?url|base_url/],
  ]
  const missingConfigTokens = requiredConfigTokens
    .filter(([, pattern]) => !pattern.test(combinedConfig))
    .map(([label]) => label)
  checks.push(status(
    'REACHAI_CONFIGURATION_FIELDS',
    'ReachAI 必要配置字段',
    !reachAiConfigs.length ? 'WARN' : (missingConfigTokens.length ? 'WARN' : 'PASS'),
    !reachAiConfigs.length
      ? '没有可检查的本地 ReachAI 配置文件。'
      : (missingConfigTokens.length
          ? `本地配置未发现：${missingConfigTokens.join(', ')}；若由配置中心注入，请人工确认。`
          : '本地配置已发现 registry URL/app key/app secret 与 project code/base URL。'),
    reachAiConfigs,
  ))

  const gatewayFiles = await findFiles(businessRoot, name => /gateway|route|security/i.test(basename(name)) && ['.java', '.yml', '.yaml', '.properties', '.conf'].includes(extname(name).toLowerCase()))
  const gatewayMatches = []
  const embedRouteMatches = []
  const registryRouteMatches = []
  const securityBoundaryMatches = []
  const authorizationRemovalRisks = []
  for (const file of gatewayFiles) {
    const source = await readFile(file, 'utf8')
    const hasEmbedRoute = source.includes('/api/reachai/embed')
    const hasRegistryRoute = source.includes('/reachai/registry')
    if (hasEmbedRoute || hasRegistryRoute) gatewayMatches.push(file)
    if (hasEmbedRoute) embedRouteMatches.push(file)
    if (hasRegistryRoute) registryRouteMatches.push(file)
    if (hasEmbedRoute && (
      (source.includes('SecurityWebFilterChain') && source.includes('securityMatcher'))
      || source.includes('proxy_set_header Authorization $http_authorization')
      || (source.includes('reachai-embed') && !/\b(?:jwt|openid-connect|oauth2)\b/i.test(source))
    )) securityBoundaryMatches.push(file)
    if (hasEmbedRoute && (
      source.includes('RemoveRequestHeader=Authorization')
      || source.includes('RemoveJwtFilter')
      || source.includes('IgnoreUrlsRemoveJwtFilter')
      || /header\s*\(\s*["']Authorization["']\s*,\s*["']{0,2}\s*\)/.test(source)
    )) authorizationRemovalRisks.push(file)
  }
  const gatewayReady = embedRouteMatches.length
    && registryRouteMatches.length
    && securityBoundaryMatches.length
    && !authorizationRemovalRisks.length
  checks.push(status(
    'GATEWAY_BOUNDARY',
    '网关与认证边界',
    gatewayReady ? 'PASS' : 'WARN',
    gatewayReady
      ? '已发现 Embed 代理、registry callback、独立安全边界，且未命中 Authorization 清除风险。'
      : '网关静态检查未形成完整证据；请核对 Embed 代理、registry callback、独立安全链与 Authorization 保留。',
    gatewayMatches,
  ))
  checks.push(status(
    'EMBED_PROXY_ROUTE',
    'Embed 代理路由',
    embedRouteMatches.length ? 'PASS' : 'WARN',
    embedRouteMatches.length ? '已发现 /api/reachai/embed 路由。' : '未发现 /api/reachai/embed 路由。',
    embedRouteMatches,
  ))
  checks.push(status(
    'REGISTRY_CALLBACK_ROUTE',
    'Registry callback 路由',
    registryRouteMatches.length ? 'PASS' : 'WARN',
    registryRouteMatches.length ? '已发现 /reachai/registry 路由。' : '未发现 /reachai/registry 路由。',
    registryRouteMatches,
  ))
  checks.push(status(
    'EMBED_SECURITY_BOUNDARY',
    'Embed 独立认证边界',
    securityBoundaryMatches.length ? 'PASS' : 'WARN',
    securityBoundaryMatches.length
      ? '已发现专用 WebFlux chain 或显式保留 Authorization 的代理配置。'
      : '未发现足够证据证明 Embed Bearer 不经过业务 OAuth2/JWT 解析。',
    securityBoundaryMatches,
  ))
  checks.push(status(
    'AUTHORIZATION_REMOVAL_RISK',
    'Authorization 清除风险',
    authorizationRemovalRisks.length ? 'WARN' : 'PASS',
    authorizationRemovalRisks.length
      ? 'Embed 路由所在文件出现 JWT/Authorization 清除逻辑；必须确认该路径被显式排除。'
      : '未在 Embed 路由文件中命中常见 Authorization 清除模式。',
    authorizationRemovalRisks,
  ))
  return checks
}

async function runtimeChecks(manifestUrl, aiCodingKey, aiCodingKeyEnvironment) {
  if (!manifestUrl) return [status('MANIFEST', 'Onboarding Manifest', 'FAIL', 'runtime 模式必须提供 --manifest-url。')]
  const headers = aiCodingKey ? { 'X-ReachAI-AiCoding-Key': aiCodingKey } : {}
  try {
    const manifest = await requestJson(manifestUrl, 'GET', headers)
    const checks = [status(
      'MANIFEST',
      'Onboarding Manifest',
      manifest.schema ? 'PASS' : 'FAIL',
      manifest.schema ? `已读取 ${manifest.schema}` : 'Manifest 缺少 schema。',
    )]
    checks.push(status(
      'MANIFEST_ARTIFACTS',
      'SDK 制品声明',
      Array.isArray(manifest.sdkArtifacts) && manifest.sdkArtifacts.length ? 'PASS' : 'FAIL',
      Array.isArray(manifest.sdkArtifacts) && manifest.sdkArtifacts.length ? `声明 ${manifest.sdkArtifacts.length} 个 SDK 制品。` : 'Manifest 未声明 sdkArtifacts。',
    ))
    if (!manifest.endpoints?.sdkAccessCheckUrl) {
      checks.push(status('SDK_ACCESS_CHECK', 'SDK Access Check', 'FAIL', 'Manifest 缺少 endpoints.sdkAccessCheckUrl。'))
      return checks
    }
    let accessCheckUrl
    try {
      accessCheckUrl = resolveSameOriginEndpoint(manifestUrl, manifest.endpoints.sdkAccessCheckUrl)
    } catch (error) {
      checks.push(status('SDK_ACCESS_CHECK', 'SDK Access Check', 'FAIL', `Manifest 自检地址无效：${error.message}`))
      return checks
    }
    try {
      const access = await requestJson(accessCheckUrl, 'POST', headers)
      const readiness = Array.isArray(access?.readiness) ? access.readiness : []
      checks.push(status(
        'SDK_ACCESS_CHECK',
        'SDK Access Check',
        sdkAccessCheckStatus(access),
        `overallStatus=${access?.overallStatus || 'UNKNOWN'}；readiness=${readiness.map(item => `${item.key}:${item.status}`).join(', ') || 'none'}`,
        readiness,
      ))
      for (const item of readiness) {
        const itemState = String(item?.status || '').trim().toUpperCase()
        checks.push(status(
          String(item?.key || 'UNKNOWN_READINESS'),
          String(item?.label || item?.key || '平台 readiness'),
          ['PASS', 'WARN', 'FAIL', 'PENDING'].includes(itemState) ? itemState : 'FAIL',
          String(item?.message || `status=${itemState || 'UNKNOWN'}`),
          item?.evidence,
        ))
      }
    } catch (error) {
      checks.push(status('SDK_ACCESS_CHECK', 'SDK Access Check', 'WARN', `平台自检暂不可用：${error.message}`))
    }
    return checks
  } catch (error) {
    const authenticationHint = aiCodingKey
      ? ''
      : `；如使用 /api/ai-coding/projects/**，请先在当前进程设置 ${aiCodingKeyEnvironment}`
    return [status('MANIFEST', 'Onboarding Manifest', 'FAIL', `无法读取 Manifest：${error.message}${authenticationHint}`)]
  }
}

function responseData(payload) {
  return payload?.data && typeof payload.data === 'object' ? payload.data : payload
}

function appendPath(base, path) {
  return `${String(base).replace(/\/+$/, '')}/${String(path).replace(/^\/+/, '')}`
}

function redactSecrets(message, secrets) {
  let result = String(message || '')
  for (const secret of secrets.filter(Boolean)) {
    result = result.split(String(secret)).join('[REDACTED]')
    result = result.split(String(secret).replace(/^Bearer\s+/i, '')).join('[REDACTED]')
  }
  return result
}

function pageWorkbenchEvidencePendingChecks() {
  return [
    status(
      'WORKFLOW_CAPABILITY_E2E',
      'Workflow 能力调用 E2E',
      'PENDING',
      'doctor 不会猜测业务能力参数或调用任意业务 API。请在页面接入工作台查看已发布 Workflow 的精确 Trace，并用真实业务意图验证声明的 CAPABILITY / TOOL 节点。',
    ),
    status(
      'PAGE_ACTION_BROWSER_E2E',
      '页面动作浏览器 E2E',
      'PENDING',
      'doctor 只验证授权 Embed 协议，Session 会以空 bridgeActions 创建，不会伪造页面回调成功。请在已登录业务页面执行真实 Page Action，并在页面接入工作台确认版本化浏览器证据。',
    ),
  ]
}

async function e2eChecks(options) {
  const missing = []
  if (!options.brokerUrl) missing.push('--broker-url')
  if (!options.embedApiBase) missing.push('--embed-api-base')
  if (!options.agentId) missing.push('--agent-id')
  if (!options.pageKey) missing.push('--page-key')
  if (missing.length) {
    return [status(
      'EMBED_CONVERSATION_E2E',
      '授权 Embed 协议 E2E',
      'PENDING',
      `未提供 ${missing.join(', ')}。可继续由用户在真实页面验收，或提供业务测试授权后运行 doctor。`,
    ), ...pageWorkbenchEvidencePendingChecks()]
  }

  const businessAuthorization = process.env[options.authorizationEnvironment]
  const businessCookie = process.env[options.cookieEnvironment]
  if (!businessAuthorization && !businessCookie) {
    return [status(
      'EMBED_CONVERSATION_E2E',
      '授权 Embed 协议 E2E',
      'PENDING',
      `请在当前进程环境变量 ${options.authorizationEnvironment} 或 ${options.cookieEnvironment} 中提供业务方专用测试授权；禁止把值放到命令行。`,
    ), ...pageWorkbenchEvidencePendingChecks()]
  }

  const pageInstanceId = `reachai-doctor-${Date.now()}-${Math.random().toString(16).slice(2)}`
  const route = options.route || '/'
  const origin = options.origin || new URL(options.brokerUrl).origin
  const businessHeaders = {
    ...(businessAuthorization ? { Authorization: businessAuthorization } : {}),
    ...(businessCookie ? { Cookie: businessCookie } : {}),
  }
  let embedToken
  try {
    const brokerPayload = await requestJson(
      options.brokerUrl,
      'POST',
      businessHeaders,
      {
        agentId: options.agentId,
        pageKey: options.pageKey,
        pageInstanceId,
        route,
        origin,
      },
    )
    const brokerData = responseData(brokerPayload)
    embedToken = brokerData?.token
    if (!embedToken) throw new Error('Token Broker response does not contain data.token or token')

    const embedHeaders = { Authorization: `Bearer ${embedToken}` }
    const sessionPayload = await requestJson(
      appendPath(options.embedApiBase, 'chat/sessions'),
      'POST',
      embedHeaders,
      {
        pageKey: options.pageKey,
        pageInstanceId,
        route,
        bridgeActions: [],
        sdkVersion: options.sdkVersion,
      },
    )
    const sessionData = responseData(sessionPayload)
    const sessionId = sessionData?.sessionId
    if (!sessionId) throw new Error('Embed session response does not contain data.sessionId or sessionId')

    const messagePayload = await requestJson(
      appendPath(options.embedApiBase, `chat/sessions/${encodeURIComponent(sessionId)}/messages`),
      'POST',
      embedHeaders,
      { message: options.message },
    )
    const messageData = responseData(messagePayload)
    const answer = messageData?.answer
    if (typeof answer !== 'string' || !answer.trim()) {
      throw new Error('Embed message response does not contain a non-empty data.answer or answer')
    }
    return [status(
      'EMBED_CONVERSATION_E2E',
      '授权 Embed 协议 E2E',
      'PASS',
      '业务测试授权、Token Broker、Embed 代理、Session、用户消息和助手回复链路已通过；该结果不代表 Workflow 能力调用或页面动作已完成。',
      {
        sessionId,
        pageKey: options.pageKey,
        route,
        sdkVersion: options.sdkVersion,
        answerCharacters: Array.from(answer).length,
        businessAuthorizationSource: businessAuthorization
          ? options.authorizationEnvironment
          : options.cookieEnvironment,
      },
    ), ...pageWorkbenchEvidencePendingChecks()]
  } catch (error) {
    return [status(
      'EMBED_CONVERSATION_E2E',
      '授权 Embed 协议 E2E',
      'FAIL',
      `授权协议链路失败：${redactSecrets(error.message, [businessAuthorization, businessCookie, embedToken])}`,
    ), ...pageWorkbenchEvidencePendingChecks()]
  }
}

const mode = arg('--mode', 'static')
const businessRoot = resolve(arg('--business-root', process.cwd()))
const frontendRoot = resolve(arg('--frontend-dir', businessRoot))
const aiCodingKeyEnvironment = arg('--ai-coding-key-env', 'REACHAI_AI_CODING_KEY')
let checks
if (mode === 'static') checks = await staticChecks(businessRoot, frontendRoot)
else if (mode === 'runtime') checks = await runtimeChecks(
  arg('--manifest-url'),
  process.env[aiCodingKeyEnvironment],
  aiCodingKeyEnvironment,
)
else if (mode === 'e2e') checks = await e2eChecks({
  brokerUrl: arg('--broker-url'),
  embedApiBase: arg('--embed-api-base'),
  agentId: arg('--agent-id'),
  pageKey: arg('--page-key'),
  route: arg('--route', '/'),
  origin: arg('--origin'),
  message: arg('--message', 'Reply with REACHAI_E2E_OK without calling write tools.'),
  sdkVersion: arg('--sdk-version', 'reachai-doctor/0.5.0'),
  authorizationEnvironment: arg('--authorization-env', 'REACHAI_E2E_AUTHORIZATION'),
  cookieEnvironment: arg('--cookie-env', 'REACHAI_E2E_COOKIE'),
})
else throw new Error(`Unsupported --mode: ${mode}. Use static, runtime, or e2e.`)

const overallStatus = checks.some(check => check.status === 'FAIL')
  ? 'FAIL'
  : checks.some(check => check.status === 'WARN' || check.status === 'PENDING') ? 'WARN' : 'PASS'
console.log(JSON.stringify({ schema: 'reachai.doctor.v1', mode, businessRoot, overallStatus, checks }, null, 2))
process.exitCode = overallStatus === 'FAIL' ? 1 : 0
