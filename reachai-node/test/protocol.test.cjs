const { test } = require('node:test')
const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const {registrySignature,bodySignature,verifyInvocation} = require('../dist')
const vectors = JSON.parse(readFileSync(path.join(__dirname,'../../ai-runtime-contract/src/main/resources/contracts/reachai-sdk-protocol-v1.vectors.json'),'utf8'))

test('shared language-neutral signatures and JWT vectors', () => {
  for (const v of vectors.registry) assert.equal(registrySignature(v.projectCode,v.timestamp,v.nonce,v.appSecret),v.signature)
  for (const v of vectors.projectRequest) {
    const body=Buffer.from(v.body,'utf8')
    assert.equal(crypto.createHash('sha256').update(body).digest('hex'),v.bodySha256)
    assert.equal(bodySignature(v.method,v.path,v.projectCode,v.appKey,v.timestamp,v.nonce,body,v.appSecret),v.signature)
    assert.notEqual(bodySignature(v.method,v.path,v.projectCode,v.appKey,v.timestamp,v.nonce,Buffer.concat([body,Buffer.from('!')]),v.appSecret),v.signature)
  }
  for (const v of vectors.invocation) {
    assert.deepEqual(verifyInvocation(v.token,v.claims.appKey,v.appSecret,v.claims.projectCode,v.claims.capabilityName,v.claims.iat),v.claims)
    for (const args of [[v.claims.appKey,'wrong',v.claims.projectCode,v.claims.capabilityName,v.claims.iat],[v.claims.appKey,v.appSecret,'wrong',v.claims.capabilityName,v.claims.iat],[v.claims.appKey,v.appSecret,v.claims.projectCode,'wrong',v.claims.iat],[v.claims.appKey,v.appSecret,v.claims.projectCode,v.claims.capabilityName,v.claims.exp]]) assert.throws(() => verifyInvocation(v.token,...args))
  }
  assert.throws(()=>bodySignature('POST','/api/../bad','p','k','1','n',Buffer.alloc(0),'secret'))
})
