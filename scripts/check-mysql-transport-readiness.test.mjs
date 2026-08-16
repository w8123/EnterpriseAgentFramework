import assert from 'node:assert/strict'
import test from 'node:test'

import {
  buildSslRequest,
  inspectMysqlJdbcUrl,
  inspectPeerCertificate,
  parseServerCapabilities,
} from './check-mysql-transport-readiness.mjs'

test('requires explicit verify identity without exposing URL credentials', () => {
  const config = inspectMysqlJdbcUrl(
    'jdbc:mysql://db.example.test:3307/reach_ai?sslMode=VERIFY_IDENTITY&serverTimezone=Asia%2FShanghai',
  )
  assert.deepEqual(config, {
    hostname: 'db.example.test',
    port: 3307,
    databasePresent: true,
    targetClass: 'NON_LOOPBACK',
    sslMode: 'VERIFY_IDENTITY',
    embeddedCredentials: false,
    insecureOptions: [],
  })
})

test('detects insecure JDBC options and embedded credentials', () => {
  const config = inspectMysqlJdbcUrl(
    'jdbc:mysql://user:secret@localhost/reach_ai?useSSL=false&trustServerCertificate=true',
  )
  assert.equal(config.targetClass, 'LOOPBACK')
  assert.equal(config.sslMode, 'UNSPECIFIED')
  assert.equal(config.embeddedCredentials, true)
  assert.deepEqual(config.insecureOptions, ['useSSL=false', 'trustServerCertificate=true'])
})

test('parses server TLS capability and builds a pre-authentication SSL request', () => {
  const payload = Buffer.alloc(64)
  payload[0] = 10
  Buffer.from('8.0.42\0').copy(payload, 1)
  const versionEnd = payload.indexOf(0, 1)
  const lowerOffset = versionEnd + 1 + 4 + 8 + 1
  const capabilities = 0x00000001 | 0x00000200 | 0x00000800 | 0x00008000 | 0x00080000
  payload.writeUInt16LE(capabilities & 0xffff, lowerOffset)
  payload.writeUInt16LE((capabilities >>> 16) & 0xffff, lowerOffset + 5)

  const parsed = parseServerCapabilities(payload)
  const request = buildSslRequest(parsed)

  assert.equal(parsed >>> 0, capabilities >>> 0)
  assert.equal(request.length, 36)
  assert.equal(request.readUIntLE(0, 3), 32)
  assert.equal(request[3], 1)
  assert.notEqual(request.readUInt32LE(4) & 0x00000800, 0)
})

test('fails closed when the server omits TLS capability', () => {
  assert.throws(() => buildSslRequest(0x00000200), /TLS_UNSUPPORTED/)
})

test('reports certificate SAN and hostname status without exposing certificate identity', () => {
  const matching = inspectPeerCertificate('db.example.test', {
    getPeerCertificate: () => ({
      raw: Buffer.from('certificate'),
      subjectaltname: 'DNS:db.example.test',
      subject: { CN: 'db.example.test' },
      valid_from: 'Jan  1 00:00:00 2020 GMT',
      valid_to: 'Jan  1 00:00:00 2100 GMT',
    }),
  })
  assert.equal(matching.certificatePresented, true)
  assert.equal(matching.certificateDnsSanPresent, true)
  assert.equal(matching.hostIdentityMatch, true)
  assert.equal(matching.certificateValidNow, true)

  const mismatching = inspectPeerCertificate('db.example.test', {
    getPeerCertificate: () => ({
      raw: Buffer.from('certificate'),
      subjectaltname: '',
      subject: { CN: 'different.example.test' },
      valid_from: 'Jan  1 00:00:00 2020 GMT',
      valid_to: 'Jan  1 00:00:00 2100 GMT',
    }),
  })
  assert.equal(mismatching.certificateDnsSanPresent, false)
  assert.equal(mismatching.hostIdentityMatch, false)
  assert.equal(mismatching.hostIdentityFailureCode, 'ERR_TLS_CERT_ALTNAME_INVALID')
})
