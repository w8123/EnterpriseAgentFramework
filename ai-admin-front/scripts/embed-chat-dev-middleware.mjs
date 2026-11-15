import { readFile } from 'node:fs'
import { extname, join } from 'node:path'
import { browserBundleVersion } from './embed-chat-browser-bundle.mjs'

const PREFIX = '/sdk/embed-chat/'
const TYPES = {
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.webp': 'image/webp',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.svg': 'image/svg+xml',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
}
const STATIC_EXTENSIONS = new Set(['.webp', '.png', '.jpg', '.jpeg', '.gif', '.svg', '.woff', '.woff2'])

/** Serve only browser SDK artifacts; never fall back to the admin SPA for a missing script. */
export function embedChatDevMiddleware(sdkDir) {
  return (req, res, next) => {
    const rawPath = (req.url || '').split('?')[0]
    if (!rawPath.startsWith(PREFIX)) return next()
    res.setHeader('Access-Control-Allow-Origin', '*')
    if (req.method !== 'GET' && req.method !== 'HEAD') {
      res.statusCode = 405
      return res.end('Method not allowed')
    }
    if (rawPath === `${PREFIX}manifest.json`) {
      try {
        const body = JSON.stringify({ version: browserBundleVersion(sdkDir) })
        res.setHeader('Content-Type', 'application/json; charset=utf-8')
        res.setHeader('Cache-Control', 'no-cache')
        return res.end(req.method === 'HEAD' ? undefined : body)
      } catch {
        res.statusCode = 404
        return res.end('SDK asset not built; run npm run build:sdk')
      }
    }
    let relative
    try {
      relative = decodeURIComponent(rawPath.slice(PREFIX.length))
    } catch {
      res.statusCode = 400
      return res.end('Invalid SDK asset path')
    }
    let segments = relative.split('/')
    const safePath = segments.every((part) => part && part !== '.' && part !== '..' && /^[a-zA-Z0-9._-]+$/.test(part))
    if (segments[0] === 'versions') {
      let expected = ''
      try { expected = browserBundleVersion(sdkDir) } catch { /* SDK has not been built */ }
      if (!expected || segments[1] !== expected) {
        res.statusCode = 404
        return res.end('SDK version not found')
      }
      segments = segments.slice(2)
    }
    const assetPath = segments.join('/')
    const allowed = assetPath === 'reachai-chat-embed.umd.js' || assetPath === 'style.css'
      || ((segments.length === 1 || segments[0] === 'assets')
          && STATIC_EXTENSIONS.has(extname(assetPath).toLowerCase()))
    if (!safePath || !allowed) {
      res.statusCode = 404
      return res.end('SDK asset not found')
    }
    readFile(join(sdkDir, ...segments), (error, content) => {
      if (error) {
        res.statusCode = 404
        return res.end('SDK asset not built; run npm run build:sdk')
      }
      res.setHeader('Content-Type', TYPES[extname(relative).toLowerCase()])
      res.setHeader('Cache-Control', 'no-cache')
      res.end(req.method === 'HEAD' ? undefined : content)
    })
  }
}
