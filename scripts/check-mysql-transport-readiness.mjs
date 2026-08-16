#!/usr/bin/env node

import { once } from 'node:events'
import { readFile } from 'node:fs/promises'
import net from 'node:net'
import tls from 'node:tls'
import { fileURLToPath } from 'node:url'

const CLIENT_LONG_PASSWORD = 0x00000001
const CLIENT_PROTOCOL_41 = 0x00000200
const CLIENT_SSL = 0x00000800
const CLIENT_SECURE_CONNECTION = 0x00008000
const CLIENT_PLUGIN_AUTH = 0x00080000

function argument(name) {
  const prefix = `--${name}=`
  const value = process.argv.slice(2).find(item => item.startsWith(prefix))
  return value ? value.slice(prefix.length) : null
}

function isLoopback(hostname) {
  const value = String(hostname || '').toLowerCase().replace(/^\[|\]$/g, '')
  return value === 'localhost' || value === '127.0.0.1' || value === '::1'
}

export function inspectMysqlJdbcUrl(rawValue) {
  if (typeof rawValue !== 'string' || !rawValue.startsWith('jdbc:mysql://')) {
    throw new Error('AI_MYSQL_URL must be a jdbc:mysql:// URL')
  }
  const parsed = new URL(rawValue.slice('jdbc:'.length))
  const sslMode = String(parsed.searchParams.get('sslMode') || '').toUpperCase()
  const insecureOptions = []
  if (String(parsed.searchParams.get('useSSL') || '').toLowerCase() === 'false') insecureOptions.push('useSSL=false')
  if (String(parsed.searchParams.get('trustServerCertificate') || '').toLowerCase() === 'true') {
    insecureOptions.push('trustServerCertificate=true')
  }
  if (String(parsed.searchParams.get('verifyServerCertificate') || '').toLowerCase() === 'false') {
    insecureOptions.push('verifyServerCertificate=false')
  }
  return {
    hostname: parsed.hostname,
    port: Number(parsed.port || 3306),
    databasePresent: parsed.pathname.replace(/^\//, '').trim() !== '',
    targetClass: isLoopback(parsed.hostname) ? 'LOOPBACK' : 'NON_LOOPBACK',
    sslMode: sslMode || 'UNSPECIFIED',
    embeddedCredentials: parsed.username !== '' || parsed.password !== '',
    insecureOptions,
  }
}

function readPacket(socket, timeoutMs) {
  return new Promise((resolve, reject) => {
    let buffer = Buffer.alloc(0)
    const timeout = setTimeout(() => finish(new Error('MYSQL_HANDSHAKE_TIMEOUT')), timeoutMs)
    function cleanup() {
      clearTimeout(timeout)
      socket.off('data', onData)
      socket.off('error', finish)
      socket.off('end', onEnd)
    }
    function finish(error, value) {
      cleanup()
      if (error) reject(error)
      else resolve(value)
    }
    function onEnd() {
      finish(new Error('MYSQL_HANDSHAKE_ENDED'))
    }
    function onData(chunk) {
      buffer = Buffer.concat([buffer, chunk])
      if (buffer.length < 4) return
      const payloadLength = buffer.readUIntLE(0, 3)
      if (buffer.length < payloadLength + 4) return
      socket.pause()
      finish(null, buffer.subarray(4, payloadLength + 4))
    }
    socket.on('data', onData)
    socket.once('error', finish)
    socket.once('end', onEnd)
    socket.resume()
  })
}

export function parseServerCapabilities(payload) {
  if (!Buffer.isBuffer(payload) || payload.length < 32 || payload[0] !== 10) {
    throw new Error('INVALID_MYSQL_HANDSHAKE')
  }
  const versionEnd = payload.indexOf(0, 1)
  if (versionEnd < 0) throw new Error('INVALID_MYSQL_SERVER_VERSION')
  const lowerOffset = versionEnd + 1 + 4 + 8 + 1
  if (lowerOffset + 7 > payload.length) throw new Error('TRUNCATED_MYSQL_CAPABILITIES')
  return payload.readUInt16LE(lowerOffset) | (payload.readUInt16LE(lowerOffset + 5) << 16)
}

export function buildSslRequest(serverCapabilities) {
  const requested = CLIENT_LONG_PASSWORD | CLIENT_PROTOCOL_41 | CLIENT_SSL
    | CLIENT_SECURE_CONNECTION | CLIENT_PLUGIN_AUTH
  const flags = requested & serverCapabilities
  if ((flags & CLIENT_SSL) === 0) throw new Error('MYSQL_SERVER_TLS_UNSUPPORTED')
  const payload = Buffer.alloc(32)
  payload.writeUInt32LE(flags >>> 0, 0)
  payload.writeUInt32LE(16 * 1024 * 1024, 4)
  payload[8] = 45
  const packet = Buffer.alloc(4 + payload.length)
  packet.writeUIntLE(payload.length, 0, 3)
  packet[3] = 1
  payload.copy(packet, 4)
  return packet
}

function safeFailure(error) {
  const code = typeof error?.code === 'string' && /^[A-Z0-9_]+$/.test(error.code)
    ? error.code
    : 'TLS_PROBE_FAILED'
  return { code, type: error?.constructor?.name || 'Error' }
}

export function inspectPeerCertificate(hostname, secureSocket) {
  let certificate
  try {
    certificate = secureSocket?.getPeerCertificate?.(true)
  } catch {
    certificate = null
  }
  const certificatePresented = Boolean(certificate?.raw)
  if (!certificatePresented) {
    return {
      certificatePresented: false,
      certificateValidNow: null,
      certificateDnsSanPresent: null,
      hostIdentityMatch: null,
      hostIdentityFailureCode: null,
    }
  }
  let identityFailure = null
  try {
    identityFailure = tls.checkServerIdentity(hostname, certificate)
  } catch (error) {
    identityFailure = error
  }
  const validFrom = Date.parse(certificate.valid_from)
  const validTo = Date.parse(certificate.valid_to)
  const now = Date.now()
  return {
    certificatePresented: true,
    certificateValidNow: Number.isFinite(validFrom) && Number.isFinite(validTo)
      ? validFrom <= now && now <= validTo
      : null,
    certificateDnsSanPresent: /(?:^|,\s*)DNS:/i.test(String(certificate.subjectaltname || '')),
    hostIdentityMatch: !identityFailure,
    hostIdentityFailureCode: typeof identityFailure?.code === 'string'
      && /^[A-Z0-9_]+$/.test(identityFailure.code)
      ? identityFailure.code
      : identityFailure ? 'TLS_HOST_IDENTITY_MISMATCH' : null,
  }
}

export async function probeMysqlTlsIdentity({ hostname, port, ca, timeoutMs = 5000 }) {
  let socket
  let secureSocket
  let connectTimeout
  let tlsTimeout
  let serverTlsCapable = null
  try {
    socket = net.createConnection({ host: hostname, port })
    connectTimeout = setTimeout(() => socket.destroy(new Error('MYSQL_CONNECT_TIMEOUT')), timeoutMs)
    await once(socket, 'connect')
    clearTimeout(connectTimeout)
    connectTimeout = null
    const handshake = await readPacket(socket, timeoutMs)
    const capabilities = parseServerCapabilities(handshake)
    serverTlsCapable = (capabilities & CLIENT_SSL) !== 0
    socket.write(buildSslRequest(capabilities))
    secureSocket = tls.connect({
      socket,
      servername: net.isIP(hostname) ? undefined : hostname,
      rejectUnauthorized: true,
      ca,
      checkServerIdentity: (_servername, certificate) => tls.checkServerIdentity(hostname, certificate),
    })
    tlsTimeout = setTimeout(() => secureSocket.destroy(new Error('MYSQL_TLS_TIMEOUT')), timeoutMs)
    await once(secureSocket, 'secureConnect')
    clearTimeout(tlsTimeout)
    tlsTimeout = null
    const protocol = secureSocket.getProtocol()
    const authorized = secureSocket.authorized === true
    const certificate = inspectPeerCertificate(hostname, secureSocket)
    return {
      serverTlsCapable,
      tlsIdentityVerified: authorized,
      tlsProtocol: protocol || null,
      credentialsUsed: false,
      ...certificate,
      failure: null,
    }
  } catch (error) {
    const certificate = inspectPeerCertificate(hostname, secureSocket)
    return {
      serverTlsCapable,
      tlsIdentityVerified: false,
      tlsProtocol: null,
      credentialsUsed: false,
      ...certificate,
      failure: safeFailure(error),
    }
  } finally {
    if (connectTimeout) clearTimeout(connectTimeout)
    if (tlsTimeout) clearTimeout(tlsTimeout)
    secureSocket?.destroy()
    socket?.destroy()
  }
}

export async function probeMysqlCertificateMetadata({ hostname, port, timeoutMs = 5000 }) {
  let socket
  let secureSocket
  let connectTimeout
  let tlsTimeout
  try {
    socket = net.createConnection({ host: hostname, port })
    connectTimeout = setTimeout(() => socket.destroy(new Error('MYSQL_CONNECT_TIMEOUT')), timeoutMs)
    await once(socket, 'connect')
    clearTimeout(connectTimeout)
    connectTimeout = null
    const handshake = await readPacket(socket, timeoutMs)
    socket.write(buildSslRequest(parseServerCapabilities(handshake)))
    secureSocket = tls.connect({
      socket,
      servername: net.isIP(hostname) ? undefined : hostname,
      // Metadata-only fallback after the strict handshake has already failed. This
      // connection closes before authentication and can never make readiness pass.
      rejectUnauthorized: false,
    })
    tlsTimeout = setTimeout(() => secureSocket.destroy(new Error('MYSQL_TLS_TIMEOUT')), timeoutMs)
    await once(secureSocket, 'secureConnect')
    clearTimeout(tlsTimeout)
    tlsTimeout = null
    return {
      ...inspectPeerCertificate(hostname, secureSocket),
      observedTlsProtocol: secureSocket.getProtocol() || null,
      credentialsUsed: false,
      failure: null,
    }
  } catch (error) {
    return {
      ...inspectPeerCertificate(hostname, secureSocket),
      observedTlsProtocol: null,
      credentialsUsed: false,
      failure: safeFailure(error),
    }
  } finally {
    if (connectTimeout) clearTimeout(connectTimeout)
    if (tlsTimeout) clearTimeout(tlsTimeout)
    secureSocket?.destroy()
    socket?.destroy()
  }
}

export async function evaluateMysqlTransport({ jdbcUrl, caFile, timeoutMs = 5000 }) {
  const config = inspectMysqlJdbcUrl(jdbcUrl)
  const issues = []
  if (config.embeddedCredentials) issues.push('JDBC_URL_EMBEDDED_CREDENTIALS')
  if (!config.databasePresent) issues.push('JDBC_DATABASE_MISSING')
  if (config.sslMode !== 'VERIFY_IDENTITY') issues.push('JDBC_SSL_MODE_NOT_VERIFY_IDENTITY')
  if (config.insecureOptions.length > 0) issues.push('JDBC_INSECURE_TLS_OPTION')
  let ca
  if (caFile) ca = await readFile(caFile, 'utf8')
  const probe = await probeMysqlTlsIdentity({
    hostname: config.hostname,
    port: config.port,
    ca,
    timeoutMs,
  })
  const metadataProbe = !probe.certificatePresented && probe.serverTlsCapable === true
    ? await probeMysqlCertificateMetadata({
      hostname: config.hostname,
      port: config.port,
      timeoutMs,
    })
    : null
  const certificate = metadataProbe || probe
  if (probe.serverTlsCapable === false) issues.push('MYSQL_SERVER_TLS_UNSUPPORTED')
  if (probe.serverTlsCapable == null) issues.push('MYSQL_TLS_CAPABILITY_UNKNOWN')
  if (certificate.certificateValidNow === false) issues.push('MYSQL_CERTIFICATE_NOT_CURRENTLY_VALID')
  if (certificate.certificateDnsSanPresent === false) issues.push('MYSQL_CERTIFICATE_DNS_SAN_MISSING')
  if (certificate.hostIdentityMatch === false) issues.push('MYSQL_CERTIFICATE_HOST_MISMATCH')
  if (!probe.tlsIdentityVerified) issues.push('MYSQL_TLS_IDENTITY_NOT_VERIFIED')
  return {
    schema: 'reachai-mysql-transport-readiness-v1',
    ready: issues.length === 0,
    targetClass: config.targetClass,
    jdbcSslMode: config.sslMode,
    customCaProvided: Boolean(caFile),
    serverTlsCapable: probe.serverTlsCapable,
    tlsIdentityVerified: probe.tlsIdentityVerified,
    tlsProtocol: probe.tlsProtocol,
    observedTlsProtocol: probe.tlsProtocol || metadataProbe?.observedTlsProtocol || null,
    credentialsUsed: false,
    metadataOnlyDiagnosticUsed: Boolean(metadataProbe),
    certificatePresented: certificate.certificatePresented,
    certificateValidNow: certificate.certificateValidNow,
    certificateDnsSanPresent: certificate.certificateDnsSanPresent,
    hostIdentityMatch: certificate.hostIdentityMatch,
    hostIdentityFailureCode: certificate.hostIdentityFailureCode,
    issues,
    failure: probe.failure,
    diagnosticFailure: metadataProbe?.failure || null,
  }
}

async function main() {
  const jdbcUrl = process.env.AI_MYSQL_URL
  const caFile = argument('ca') || process.env.REACHAI_MYSQL_CA_FILE || null
  const timeoutMs = Number(argument('timeout-ms') || 5000)
  if (!Number.isInteger(timeoutMs) || timeoutMs < 1000 || timeoutMs > 30000) {
    throw new Error('--timeout-ms must be an integer between 1000 and 30000')
  }
  const result = await evaluateMysqlTransport({ jdbcUrl, caFile, timeoutMs })
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`)
  if (!result.ready) process.exitCode = 1
}

if (process.argv[1]
    && fileURLToPath(import.meta.url) === fileURLToPath(new URL(`file:///${process.argv[1].replaceAll('\\', '/')}`))) {
  main().catch(error => {
    process.stderr.write(`${error.message}\n`)
    process.exitCode = 2
  })
}
