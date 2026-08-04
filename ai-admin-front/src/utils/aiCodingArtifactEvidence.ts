export interface AiCodingReportedCheck {
  name: string
  status: string
  command?: string
  evidence?: string
}

export interface AiCodingBrowserEvidence {
  passed: boolean
  browser: string
  url: string
  scenarios: string[]
  screenshots: string[]
  evidence: string
}

export interface AiCodingArtifactEvidence {
  summary?: string
  files: string[]
  checks: AiCodingReportedCheck[]
  browser: AiCodingBrowserEvidence | null
}

const MATERIAL_KEYS = [
  'codingReport',
  'implementation',
  'browserAcceptance',
  'preRelease',
] as const

function record(value: unknown): Record<string, unknown> | null {
  return value !== null
    && typeof value === 'object'
    && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim()
    ? value.trim()
    : undefined
}

function strings(value: unknown): string[] {
  if (!Array.isArray(value)) return []
  return value
    .map(text)
    .filter((item): item is string => Boolean(item))
}

function checks(value: unknown): AiCodingReportedCheck[] {
  if (!Array.isArray(value)) return []
  return value.flatMap((item) => {
    const source = record(item)
    const name = text(source?.name)
    const status = text(source?.status)
    if (!name || !status) return []
    return [{
      name,
      status,
      command: text(source?.command),
      evidence: text(source?.evidence),
    }]
  })
}

function browser(value: unknown): AiCodingBrowserEvidence | null {
  const source = record(value)
  const scenarios = strings(source?.scenarios)
  const screenshots = strings(source?.screenshots)
  if (!source
    || typeof source.passed !== 'boolean'
    || !text(source.browser)
    || !text(source.url)
    || !text(source.evidence)
    || scenarios.length === 0
    || screenshots.length === 0) {
    return null
  }
  return {
    passed: source.passed,
    browser: text(source.browser)!,
    url: text(source.url)!,
    scenarios,
    screenshots,
    evidence: text(source.evidence)!,
  }
}

function materialRoot(value: unknown): Record<string, unknown> | null {
  const root = record(value)
  if (!root) return null
  for (const key of MATERIAL_KEYS) {
    const material = record(root[key])
    if (material) return material
  }
  return root
}

function stepFiles(value: unknown): string[] {
  if (!Array.isArray(value)) return []
  return value.flatMap((item) => strings(record(item)?.files))
}

export function aiCodingArtifactEvidence(
  applicationResult: unknown,
): AiCodingArtifactEvidence | null {
  const source = materialRoot(applicationResult)
  if (!source) return null

  const files = Array.from(new Set([
    ...strings(source.changedFiles),
    ...stepFiles(source.steps),
  ]))
  const reportedChecks = checks(source.tests ?? source.checks)
  const browserEvidence = browser(source.browserVerification)
  const summary = text(source.summary)

  if (!summary
    && files.length === 0
    && reportedChecks.length === 0
    && !browserEvidence) {
    return null
  }
  return {
    summary,
    files,
    checks: reportedChecks,
    browser: browserEvidence,
  }
}
