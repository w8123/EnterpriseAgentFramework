import { strict as assert } from 'node:assert'
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve, sep } from 'node:path'
import { test } from 'node:test'
import { browserBundleVersion, publishBrowserBundle, requireBrowserBundleVersion } from './embed-chat-browser-bundle.mjs'

test('publishes paired immutable bundles, preserves old versions and detects tampering', () => {
  const root = mkdtempSync(join(tmpdir(), 'reachai-sdk-bundle-'))
  const source = join(root, 'source')
  const target = join(root, 'dist')
  mkdirSync(join(source, 'assets'), { recursive: true })
  try {
    writeFileSync(join(source, 'reachai-chat-embed.umd.js'), 'script-one')
    writeFileSync(join(source, 'style.css'), 'style-one')
    writeFileSync(join(source, 'assets', 'image.webp'), 'image-one')
    const first = publishBrowserBundle(source, target)
    requireBrowserBundleVersion(source, first)
    assert.equal(readFileSync(join(target, 'versions', first, 'style.css'), 'utf8'), 'style-one')
    assert.equal(readFileSync(join(target, 'versions', first, 'reachai-chat-embed.umd.js'), 'utf8'), 'script-one')
    writeFileSync(join(source, 'style.css'), 'style-two')
    assert.throws(() => requireBrowserBundleVersion(source, first), /changed while the admin frontend was building/)
    const second = publishBrowserBundle(source, target)
    assert.notEqual(second, first)
    assert.equal(readFileSync(join(target, 'versions', first, 'style.css'), 'utf8'), 'style-one')
    assert.equal(readFileSync(join(target, 'versions', second, 'style.css'), 'utf8'), 'style-two')
    assert.equal(readFileSync(join(target, 'style.css'), 'utf8'), 'style-two')
    writeFileSync(join(source, 'assets', 'image.webp'), 'image-two')
    assert.notEqual(browserBundleVersion(source), second)
    writeFileSync(join(source, 'assets', 'image.webp'), 'image-one')
    writeFileSync(join(target, 'versions', second, 'style.css'), 'tampered')
    assert.throws(() => publishBrowserBundle(source, target), /does not match its version/)
  } finally {
    if (!resolve(root).startsWith(`${resolve(tmpdir())}${sep}`)) throw new Error('Refusing to delete outside temporary directory')
    rmSync(root, { recursive: true, force: true })
  }
})
