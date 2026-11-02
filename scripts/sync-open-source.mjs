import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { git, policy, checkTree, checkHistory } from './check-open-source.mjs'

function parseArgs(args) {
  const options = { apply: false }
  for (let index = 0; index < args.length; index++) {
    const arg = args[index]
    if (arg === '--apply') options.apply = true
    else if (['--source', '--ref'].includes(arg) && args[index + 1] && !args[index + 1].startsWith('--')) options[arg.slice(2)] = args[++index]
    else throw new Error(`未知或不完整的参数：${arg}`)
  }
  if (!options.source || !options.ref) throw new Error('用法：node scripts/sync-open-source.mjs --source <开发库目录> --ref <已提交修订> [--apply]')
  return options
}

function repositoryUrl(value) {
  return value.trim().replace(/^git@github\.com:/, 'https://github.com/').replace(/^ssh:\/\/git@github\.com\//, 'https://github.com/').replace(/\/$/, '').replace(/\.git$/, '')
}

function main() {
  const options = parseArgs(process.argv.slice(2))
  const target = fs.realpathSync(git(process.cwd(), ['rev-parse', '--show-toplevel']).trim())
  const source = fs.realpathSync(git(path.resolve(options.source), ['rev-parse', '--show-toplevel']).trim())
  if (source === target) throw new Error('源库与开源目标库必须是不同检出。')
  if (repositoryUrl(git(target, ['remote', 'get-url', 'origin'])) !== `https://github.com/${policy.publicRepository}`) {
    throw new Error('只允许在配置的开源仓库检出中运行；不会修改开发库。')
  }
  if (git(target, ['status', '--porcelain=v1', '-z'])) throw new Error('开源目标库有未提交文件；先保留并处理这些改动后再同步。')
  checkHistory(target, ['HEAD'])
  const sourceRef = git(source, ['rev-parse', '--verify', '--end-of-options', `${options.ref}^{commit}`]).trim()
  for (const name of policy.requiredFiles) {
    try { git(source, ['cat-file', '-e', `${sourceRef}:${name}`], { stdio: ['pipe', 'pipe', 'pipe'] }) }
    catch { throw new Error(`源修订缺少同步规则 ${name}；先在开发库提交规则与相关代码。`) }
  }
  const sourcePolicy = JSON.parse(git(source, ['show', `${sourceRef}:scripts/open-source-policy.json`]))
  if (JSON.stringify(sourcePolicy) !== JSON.stringify(policy)) throw new Error('两库的开源策略不同；先人工核对策略，避免同步覆盖排除规则。')
  if (git(source, ['status', '--porcelain=v1', '-z'])) console.log(`开发库存在未提交改动；本次只读取 ${options.ref}（${sourceRef.slice(0, 12)}）的已提交快照。`)

  // Import objects from a local checkout without importing branches or source ancestry into a public commit.
  git(target, ['fetch', '--quiet', '--no-tags', '--no-write-fetch-head', '--no-recurse-submodules', source, sourceRef])
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-open-source-'))
  try {
    const env = { ...process.env, GIT_INDEX_FILE: path.join(temporary, 'index') }
    git(target, ['read-tree', 'HEAD'], { env })
    const excluded = [...policy.excludedPaths, '.gitignore'].map(name => `:(top,exclude,icase,literal)${name}`)
    const patch = git(target, ['diff', '--binary', '--full-index', '--no-renames', 'HEAD', sourceRef, '--', '.', ...excluded], { encoding: null })
    if (patch.length) git(target, ['apply', '--cached', '--whitespace=nowarn'], { env, input: patch })
    const ignore = git(source, ['show', `${sourceRef}:.gitignore`]).trimEnd()
      + '\n\n# Open-source export exclusions (managed by sync-open-source.mjs).\n'
      + policy.excludedPaths.map(name => `/${name}/`).join('\n') + '\n'
    const blob = git(target, ['hash-object', '-w', '--stdin'], { input: ignore }).trim()
    git(target, ['update-index', '--add', '--cacheinfo', '100644', blob, '.gitignore'], { env })
    git(target, ['update-index', '--chmod=+x', '--', '.githooks/pre-push'], { env })
    const tree = git(target, ['write-tree'], { env }).trim()
    checkTree(target, tree)
    const result = git(target, ['diff', '--binary', '--full-index', '--no-renames', 'HEAD', tree], { encoding: null })
    console.log(git(target, ['diff', '--stat', 'HEAD', tree]).trim() || '没有需要同步的改动。')
    if (options.apply && result.length) git(target, ['apply', '--index', '--whitespace=nowarn'], { input: result })
    console.log(options.apply ? '同步结果已写入工作区与暂存区；请检查并测试后单独提交。未提交、未推送。' : '以上为预览；添加 --apply 才会写入工作区与暂存区。')
  } finally {
    const relative = path.relative(os.tmpdir(), temporary)
    if (!relative || relative.startsWith('..') || path.isAbsolute(relative) || !path.basename(temporary).startsWith('reachai-open-source-')) throw new Error('临时目录越界，停止清理。')
    fs.rmSync(temporary, { recursive: true, force: true })
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { main() } catch (error) {
    console.error(error.message)
    process.exitCode = 1
  }
}
