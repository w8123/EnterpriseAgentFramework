import fs from 'node:fs'
import path from 'node:path'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

export const policy = JSON.parse(fs.readFileSync(new URL('./open-source-policy.json', import.meta.url), 'utf8'))
export const excludedPathspecs = policy.excludedPaths.map(name => `:(top,icase,literal)${name}`)
export function git(root, args, options = {}) {
  return execFileSync('git', args, { cwd: root, encoding: 'utf8', maxBuffer: 128 * 1024 * 1024, ...options })
}

export function checkPaths(names, context) {
  const blocked = names.filter(name => policy.excludedPaths.some(excluded => {
    const value = name.toLowerCase()
    return value === excluded.toLowerCase() || value.startsWith(`${excluded.toLowerCase()}/`)
  }))
  if (blocked.length) throw new Error(`${context}包含禁止开源的路径：\n${blocked.slice(0, 10).join('\n')}`)
}

export function checkTree(root, ref) {
  checkPaths(git(root, ['ls-tree', '-r', '--name-only', '-z', ref]).split('\0').filter(Boolean), `文件树 ${ref} `)
}

export function checkHistory(root, refs) {
  if (git(root, ['rev-parse', '--is-shallow-repository']).trim() === 'true') {
    throw new Error('需要完整提交历史；先执行 git fetch --unshallow，CI checkout 使用 fetch-depth: 0。')
  }
  for (const ref of new Set(refs)) {
    const commit = git(root, ['rev-parse', '--verify', '--end-of-options', `${ref}^{commit}`]).trim()
    checkTree(root, commit)
    const history = git(root, ['log', '--full-history', '--format=%h %s', commit, '--', ...excludedPathspecs]).trim()
    if (history) throw new Error(`提交历史包含禁止开源的目录；必须从待发布历史移除，追加删除提交无效：\n${history.split('\n').slice(0, 10).join('\n')}`)
  }
}

function main() {
  const root = git(process.cwd(), ['rev-parse', '--show-toplevel']).trim()
  const args = process.argv.slice(2)
  if (args[0] === '--pre-push') {
    if (args.length !== 3) throw new Error('pre-push 需要 Git 提供远程名称与地址。')
    const refs = fs.readFileSync(0, 'utf8').split(/\r?\n/).filter(Boolean).flatMap(line => {
      const fields = line.trim().split(/\s+/)
      if (fields.length !== 4) throw new Error('无法读取 pre-push 引用，拒绝推送。')
      return /^0+$/.test(fields[1]) ? [] : [fields[1]]
    })
    checkHistory(root, refs)
  } else {
    if (args.length && (args.length !== 2 || args[0] !== '--ref')) throw new Error('用法：node scripts/check-open-source.mjs [--ref HEAD]')
    checkPaths(git(root, ['ls-files', '-z']).split('\0').filter(Boolean), '暂存区 ')
    checkHistory(root, [args[1] || 'HEAD'])
  }
  console.log('开源检查通过：文件树与提交历史均不包含排除目录。')
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { main() } catch (error) {
    console.error(error.message)
    process.exitCode = 1
  }
}
