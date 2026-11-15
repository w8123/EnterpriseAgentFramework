import { createHash } from 'node:crypto'
import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync } from 'node:fs'
import { dirname, join } from 'node:path'

const required = ['reachai-chat-embed.umd.js', 'style.css']
const image = /\.(webp|png|jpe?g|gif|svg)$/i

/** The complete browser bundle must be fingerprinted together, including CSS assets. */
export function browserBundleFiles(root) {
  for (const name of required) {
    if (!existsSync(join(root, name))) throw new Error(`Missing Embed SDK browser artifact: ${name}`)
  }
  const files = []
  function visit(relative) {
    for (const entry of readdirSync(join(root, relative), { withFileTypes: true })) {
      const name = relative ? `${relative}/${entry.name}` : entry.name
      if (entry.isDirectory() && (relative || entry.name === 'assets')) visit(name)
      else if (entry.isFile() && (relative || required.includes(name) || image.test(name))) files.push(name)
    }
  }
  visit('')
  return files.sort()
}

export function browserBundleVersion(root) {
  const hash = createHash('sha256')
  for (const name of browserBundleFiles(root)) {
    const bytes = readFileSync(join(root, ...name.split('/')))
    hash.update(`${Buffer.byteLength(name)}:${name}:${bytes.length}:`)
    hash.update(bytes)
  }
  return hash.digest('hex')
}

export function requireBrowserBundleVersion(root, expected) {
  if (browserBundleVersion(root) !== expected) {
    throw new Error('Embed SDK changed while the admin frontend was building; rebuild the frontend and SDK together')
  }
}

/** Preserve older version directories so already-integrated sites keep working. */
export function publishBrowserBundle(source, target) {
  const files = browserBundleFiles(source)
  const version = browserBundleVersion(source)
  const versionTarget = join(target, 'versions', version)
  if (existsSync(versionTarget) && browserBundleVersion(versionTarget) !== version) {
    throw new Error(`Existing immutable Embed SDK bundle does not match its version: ${version}`)
  }
  for (const file of files) {
    for (const root of [target, versionTarget]) {
      const dest = join(root, ...file.split('/'))
      mkdirSync(dirname(dest), { recursive: true })
      cpSync(join(source, ...file.split('/')), dest)
    }
  }
  return version
}
