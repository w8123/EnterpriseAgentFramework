import { createHash } from 'node:crypto'
import { copyFile, mkdir, readFile, stat, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const scriptDir = dirname(fileURLToPath(import.meta.url))
const repositoryRoot = resolve(scriptDir, '..')
const version = '1.0.0-SNAPSHOT'
const outputDir = resolve(
  repositoryRoot,
  'reachai-control-service',
  'src',
  'main',
  'resources',
  'ai-assist',
  'artifacts',
  'java-sdk',
)

const artifacts = [
  {
    artifactId: 'reachai-capability-sdk',
    sourceJar: resolve(
      repositoryRoot,
      'reachai-capability-sdk',
      'target',
      `reachai-capability-sdk-${version}.jar`,
    ),
  },
  {
    artifactId: 'reachai-spring-boot2-starter',
    sourceJar: resolve(
      repositoryRoot,
      'reachai-spring-boot2-starter',
      'target',
      `reachai-spring-boot2-starter-${version}.jar`,
    ),
  },
]

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex')
}

async function assertJar(path) {
  const details = await stat(path)
  if (!details.isFile() || details.size < 100) {
    throw new Error(`Java SDK JAR is missing or too small: ${path}`)
  }
  const bytes = await readFile(path)
  if (bytes[0] !== 0x50 || bytes[1] !== 0x4b) {
    throw new Error(`Java SDK artifact is not a ZIP/JAR: ${path}`)
  }
  return bytes
}

await mkdir(outputDir, { recursive: true })

for (const artifact of artifacts) {
  const jarFileName = `${artifact.artifactId}-${version}.jar`
  const pomFileName = `${artifact.artifactId}-${version}.pom`
  const outputJar = resolve(outputDir, jarFileName)
  const outputPom = resolve(outputDir, pomFileName)

  const sourceBytes = await assertJar(artifact.sourceJar)
  const pomBytes = await readFile(outputPom)
  await copyFile(artifact.sourceJar, outputJar)
  await writeFile(`${outputJar}.sha256`, `${sha256(sourceBytes)}\n`, 'utf8')
  await writeFile(`${outputPom}.sha256`, `${sha256(pomBytes)}\n`, 'utf8')

  console.log(
    `[pack-java-sdk-artifacts] ${artifact.artifactId}:${version} jarSha256=${sha256(sourceBytes)} pomSha256=${sha256(pomBytes)}`,
  )
}
