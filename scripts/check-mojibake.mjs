import { readFileSync } from 'node:fs'
import { extname, join } from 'node:path'
import { execFileSync } from 'node:child_process'

const root = process.cwd()
const scanRoots = [
  'ai-admin-front',
  'reachai-control-service',
  'reachai-runtime-service',
  'reachai-capability-service',
  'reachai-knowledge-service',
  'reachai-model-service',
  'ai-runtime-contract',
  'reachai-capability-sdk',
  'reachai-spring-boot2-starter',
  'sql',
  'docs',
]

const textExtensions = new Set([
  '.java',
  '.js',
  '.mjs',
  '.ts',
  '.tsx',
  '.vue',
  '.xml',
  '.yml',
  '.yaml',
  '.properties',
  '.sql',
  '.md',
  '.json',
])

const mojibakePattern = /[鏁鎺绠椤鍚鐢鍙涓鏆浣璇寮瀵杩缂鑳妯宸鏈閾鐩浜淇搴浼鍏鍒澶瀹綰]|鈥|銆|€�|閻|闁|濡|婵|鈹/

const fileList = execFileSync('rg', ['--files', ...scanRoots], {
  cwd: root,
  encoding: 'utf8',
})
  .split(/\r?\n/)
  .filter(Boolean)
  .filter((file) => textExtensions.has(extname(file)))

const failures = []

for (const file of fileList) {
  const content = readFileSync(join(root, file), 'utf8')
  const lines = content.split(/\r?\n/)
  lines.forEach((line, index) => {
    if (mojibakePattern.test(line)) {
      failures.push(`${file}:${index + 1}: ${line.trim()}`)
    }
  })
}

if (failures.length) {
  console.error(`Detected likely mojibake in ${failures.length} line(s):`)
  console.error(failures.join('\n'))
  process.exit(1)
}

console.log('No likely mojibake patterns detected.')
