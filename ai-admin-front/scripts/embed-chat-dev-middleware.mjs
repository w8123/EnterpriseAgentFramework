import { readFile } from 'node:fs'
import { extname, join } from 'node:path'

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
    let relative
    try {
      relative = decodeURIComponent(rawPath.slice(PREFIX.length))
    } catch {
      res.statusCode = 400
      return res.end('Invalid SDK asset path')
    }
    const segments = relative.split('/')
    const safePath = segments.every((part) => part && part !== '.' && part !== '..' && /^[a-zA-Z0-9._-]+$/.test(part))
    const allowed = relative === 'reachai-chat-embed.umd.js' || relative === 'style.css'
      || ((segments.length === 1 || segments[0] === 'assets')
          && STATIC_EXTENSIONS.has(extname(relative).toLowerCase()))
    if (!safePath || !allowed) {
      res.statusCode = 404
      return res.end('SDK asset not found')
    }
    if (req.method !== 'GET' && req.method !== 'HEAD') {
      res.statusCode = 405
      return res.end('Method not allowed')
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
