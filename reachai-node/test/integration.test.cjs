const { test } = require('node:test')
const assert = require('node:assert/strict')
const { createHmac, randomBytes } = require('node:crypto')
const { mkdtemp, readFile, rm } = require('node:fs/promises')
const { tmpdir } = require('node:os')
const path = require('node:path')
const util = require('node:util')
const express = require('express')
const { ReachAI, EncryptedFileCredentialStore, createReachRouter, currentInvocation, registrySignature, bodySignature, BusinessIndexAccessScope, BusinessIndexResolution } = require('../dist')

const key='fixture-key', secret='fixture-secret'
function sign(claims) { const b=x=>Buffer.from(JSON.stringify(x)).toString('base64url'); const input=b({alg:'HS256',typ:'JWT'})+'.'+b(claims); return input+'.'+createHmac('sha256',secret).update(input).digest('base64url') }
function token(user='alice', tenant='t1', roles=['Customer'], capabilityName='order.query', extra={}) { return sign({projectCode:'demo',appKey:key,capabilityName,exp:Math.floor(Date.now()/1000)+120,externalUserId:user,tenantId:tenant,roles,...extra}) }
const inputSchema={type:'object',properties:{orderId:{type:'integer'},filter:{type:'object',properties:{status:{type:'string'}},required:['status']}},required:['orderId','filter']}
const outputSchema={type:'object',properties:{user:{type:'string'},tenant:{type:'string'},orderId:{type:'integer'}},required:['user','tenant','orderId']}
function fixtures() {
  const calls=[]
  const fetcher=async (url, options) => {
    const body=JSON.parse(Buffer.from(options.body).toString('utf8')); calls.push({url:String(url),headers:options.headers,body,bytes:Buffer.from(options.body)})
    let payload={code:200}
    if (String(url).endsWith('/register')) payload={projectCode:'demo'}
    if (String(url).endsWith('/heartbeat')) payload={instance:{instanceId:body.instanceId}}
    if (String(url).endsWith('/capabilities/sync')) payload={syncId:body.syncId,projectCode:'demo',received:body.capabilities.length}
    if (String(url).endsWith('/exchange')) payload={code:200,data:{token:'fixture-token',expiresIn:60,businessSessionBound:true,businessSessionExpiryApplied:true}}
    return new Response(JSON.stringify(payload),{status:200,headers:{'content-type':'application/json'}})
  }
  const reach=new ReachAI({registryUrl:'http://registry.test',projectCode:'demo',baseUrl:'http://host.test',appKey:key,appSecret:secret,allowedOrigins:['http://host.test']},{fetch:fetcher,securityBridge:async (identity,next)=>next(),principalResolver:async(req)=>req.get('X-Test-Auth')==='yes'?{externalUserId:'alice',roles:['Customer'],attributes:{tenantId:'t1'},businessSessionId:'session-private',businessSessionExpiresAt:1999999999}:null})
  reach.capability({name:'order.query',sideEffect:'READ',businessIdentityRequired:true,requiredRoles:['Customer'],inputSchema,outputSchema,handler:async ({orderId})=>{ const before=currentInvocation(); await new Promise(resolve=>setTimeout(resolve,2)); assert.equal(currentInvocation(),before); return {user:before.externalUserId,tenant:before.tenantId,orderId} }})
  return {reach,calls}
}
async function serve(reach) { const app=express(); app.use(createReachRouter(reach)); const server=app.listen(0,'127.0.0.1'); await new Promise(resolve=>server.once('listening',resolve)); return {server,base:`http://127.0.0.1:${server.address().port}`} }
async function post(base,path,body,headers={}) { const r=await fetch(base+path,{method:'POST',headers:{'content-type':'application/json',...headers},body:JSON.stringify(body)}); return {status:r.status,body:await r.json()} }

test('actual Express routes enforce identity, concurrent scope, schema and callback replay',async()=>{
  const {reach,calls}=fixtures(); const {server,base}=await serve(reach)
  try {
    await reach.start()
    const results=await Promise.all(Array.from({length:32},async (_,i)=>{
      const user=i%2?'alice':'bob', tenant=i%2?'t1':'t2'
      return post(base,'/reachai/capabilities/order.query/invoke',{orderId:i,filter:{status:'open'}},{'X-ReachAI-Invocation-Token':token(user,tenant)})
    }))
    for (let i=0;i<results.length;i++) assert.deepEqual(results[i],{status:200,body:{user:i%2?'alice':'bob',tenant:i%2?'t1':'t2',orderId:i}})
    assert.equal(currentInvocation(),undefined)
    assert.equal((await post(base,'/reachai/capabilities/order.query/invoke',{orderId:1,filter:{status:'open',extra:1}},{'X-ReachAI-Invocation-Token':token()})).status,400)
    assert.equal((await post(base,'/reachai/capabilities/order.query/invoke',{orderId:1,filter:{status:'open'}},{'X-ReachAI-Invocation-Token':token('alice','t1',['Admin'])})).status,403)
    assert.equal((await post(base,'/reachai/capabilities/order.query/invoke',{orderId:1,filter:{status:'open'}},{'X-ReachAI-Invocation-Token':'forged'})).status,401)
    assert.equal((await post(base,'/reachai/capabilities/order.query/invoke',{orderId:1,filter:{status:'open'}},{'X-ReachAI-Invocation-Token':token('','t1')})).status,401)
    assert.equal((await post(base,'/reachai/capabilities/order.query/invoke',{orderId:1,filter:{status:'open'}},{'X-ReachAI-Invocation-Token':token('alice','t1',['Customer'],'order.query',{exp:Math.floor(Date.now()/1000)-1})})).status,401)
    assert.equal((await post(base,'/reachai/capabilities/order.query/invoke',{orderId:1,filter:{status:'open'}},{'X-ReachAI-Invocation-Token':token('alice','t1','Admin')})).status,401)
    assert.equal((await post(base,'/reachai/capabilities/order.query/invoke',{orderId:1,filter:{status:'open'}},{'X-ReachAI-Invocation-Token':token('alice','t1',['Customer'],'order.query',{nbf:Math.floor(Date.now()/1000)+60})})).status,401)
    const ts=String(Date.now()), nonce='nonce1', headers={'X-ReachAI-App-Key':key,'X-ReachAI-Timestamp':ts,'X-ReachAI-Nonce':nonce,'X-ReachAI-Signature':registrySignature('demo',ts,nonce,secret)}
    const first=await post(base,'/reachai/registry/capabilities/sync',{},headers); assert.equal(first.status,200); assert.equal(first.body.capabilityCount,1)
    assert.equal((await post(base,'/reachai/registry/capabilities/sync',{},headers)).status,401)
    const sync=calls.find(c=>c.url.endsWith('/capabilities/sync')); assert.equal(sync.body.capabilities[0].metadata.assetType,'BUSINESS_METHOD'); assert.equal('httpApis' in sync.body,false)
  } finally { await reach.stop(); server.close() }
})

test('repeated callbacks accept the original snapshot id for identical inventory',async()=>{
  const sent=[]; let snapshotId
  const fetcher=async(url,options)=>{
    const body=JSON.parse(Buffer.from(options.body).toString('utf8'))
    let payload={code:200}
    if(String(url).endsWith('/register')) payload={projectCode:'demo'}
    if(String(url).endsWith('/heartbeat')) payload={instance:{instanceId:body.instanceId}}
    if(String(url).endsWith('/capabilities/sync')) {
      sent.push(body.syncId)
      snapshotId ||= body.syncId
      payload={syncId:snapshotId,projectCode:'demo',received:body.capabilities.length}
    }
    return new Response(JSON.stringify(payload),{status:200})
  }
  const reach=new ReachAI({registryUrl:'http://registry.test',projectCode:'demo',baseUrl:'http://host.test',appKey:key,appSecret:secret},{fetch:fetcher})
  reach.capability({name:'ping',sideEffect:'READ',inputSchema:{type:'object',properties:{}},outputSchema:{type:'string'},handler:()=> 'ok'})
  const {server,base}=await serve(reach)
  try {
    await reach.start()
    const headersFor=nonce=>{const ts=String(Date.now());return {'X-ReachAI-App-Key':key,'X-ReachAI-Timestamp':ts,'X-ReachAI-Nonce':nonce,'X-ReachAI-Signature':registrySignature('demo',ts,nonce,secret)}}
    const first=await post(base,'/reachai/registry/capabilities/sync',{},headersFor('first-callback'))
    const second=await post(base,'/reachai/registry/capabilities/sync',{},headersFor('second-callback'))
    assert.equal(first.status,200); assert.equal(second.status,200)
    assert.equal(sent.length,2); assert.notEqual(sent[0],sent[1])
    assert.equal(JSON.parse(first.body.registryResponse).syncId,sent[0])
    // The stored snapshot id is not an acknowledgement of the second request id.
    assert.equal(JSON.parse(second.body.registryResponse).syncId,sent[0])
    assert.notEqual(JSON.parse(second.body.registryResponse).syncId,sent[1])
  } finally { await reach.stop(); server.close() }
})

test('sync rejects malformed, wrong-project and incomplete snapshot responses',async()=>{
  let response={syncId:'snapshot-existing',projectCode:'demo',received:1}
  const fetcher=async(url,options)=>{
    const body=JSON.parse(Buffer.from(options.body).toString('utf8'))
    let result={code:200}
    if(String(url).endsWith('/register')) result={projectCode:'demo'}
    if(String(url).endsWith('/heartbeat')) result={instance:{instanceId:body.instanceId}}
    if(String(url).endsWith('/capabilities/sync')) result=response
    return new Response(JSON.stringify(result),{status:200})
  }
  const reach=new ReachAI({registryUrl:'http://registry.test',projectCode:'demo',baseUrl:'http://host.test',appKey:key,appSecret:secret},{fetch:fetcher})
  reach.capability({name:'ping',sideEffect:'READ',inputSchema:{type:'object',properties:{}},outputSchema:{type:'string'},handler:()=> 'ok'})
  const {server}=await serve(reach)
  try {
    await reach.start()
    // Legacy snapshot identifiers are opaque and need not be UUIDs.
    assert.equal((await reach.sync()).syncId,'snapshot-existing')
    for(const invalid of [
      {projectCode:'demo',received:1}, {syncId:'   ',projectCode:'demo',received:1},
      {syncId:42,projectCode:'demo',received:1}, {syncId:'x'.repeat(65),projectCode:'demo',received:1},
      {syncId:'snapshot-existing',projectCode:'other',received:1},
      {syncId:'snapshot-existing',projectCode:'demo',received:0}, {code:500,message:'failed'},
    ]) { response=invalid; await assert.rejects(()=>reach.sync()) }
  } finally { await reach.stop();server.close() }
})

test('embed broker only trusts host login and allowed origin, binds and revokes session',async()=>{
  const {reach,calls}=fixtures(); const {server,base}=await serve(reach)
  try {
    await reach.start(); const data={agentId:'agent',pageInstanceId:'page',origin:'http://host.test'}
    assert.equal((await post(base,'/reachai/embed/token',data)).status,403)
    assert.equal((await post(base,'/reachai/embed/token',data,{Origin:'null','X-Test-Auth':'yes'})).status,403)
    assert.equal((await post(base,'/reachai/embed/token',data,{Origin:'http://evil.test','X-Test-Auth':'yes'})).status,403)
    assert.equal((await post(base,'/reachai/embed/token',data,{Origin:'http://host.test'})).status,401)
    assert.equal((await post(base,'/reachai/embed/token',{...data,externalUserId:'admin'},{Origin:'http://host.test','X-Test-Auth':'yes'})).status,400)
    assert.equal((await post(base,'/reachai/embed/token',data,{Origin:'http://host.test','X-Test-Auth':'yes'})).status,200)
    await reach.revokeBusinessSession('session-private')
    for (const call of calls.filter(c=>c.url.includes('/api/embed/token/'))) {
      const h=call.headers,p=new URL(call.url).pathname
      assert.equal(h['X-ReachAI-Body-Signature'],bodySignature('POST',p,'demo',key,h['X-ReachAI-Timestamp'],h['X-ReachAI-Nonce'],call.bytes,secret))
      assert.equal(call.body.projectCode,'demo')
    }
  } finally { await reach.stop(); server.close() }
})

test('uncertain enrollment cannot replay; encrypted credentials and secrets remain private',async()=>{
  const dir=await mkdtemp(path.join(tmpdir(),'reach-node-test-')); const key64=randomBytes(32).toString('base64'); const store=new EncryptedFileCredentialStore(path.join(dir,'credentials'),key64)
  try {
    let sends=0
    const reach=new ReachAI({registryUrl:'http://registry.test',projectCode:'demo',baseUrl:'http://host.test',enrollmentToken:'never-print-token'},{credentialStore:store,fetch:async()=>{sends++;throw new Error('network uncertain')}})
    assert.ok(!util.inspect(reach).includes('never-print-token')); assert.ok(!JSON.stringify(reach).includes('never-print-token')); assert.ok(!util.inspect(store).includes(key64)); assert.ok(!JSON.stringify(store).includes(key64))
    await assert.rejects(()=>reach.register()); await assert.rejects(()=>reach.register(),/uncertain/); assert.equal(sends,1)
    const secretReach=new ReachAI({registryUrl:'http://registry.test',projectCode:'demo',baseUrl:'http://host.test',appKey:key,appSecret:'private-super-secret'})
    assert.ok(!util.inspect(secretReach).includes('private-super-secret')); assert.ok(!JSON.stringify(secretReach).includes('private-super-secret'))
    const customStoreReach=new ReachAI({registryUrl:'http://registry.test',projectCode:'demo',baseUrl:'http://host.test',appKey:key,appSecret:secret},{nonceStore:{password:'private-nonce-store-secret',claim:async()=>true}})
    assert.ok(!util.inspect(customStoreReach).includes('private-nonce-store-secret')); assert.ok(!JSON.stringify(customStoreReach).includes('private-nonce-store-secret'))
    await store.save({appKey:key,appSecret:'private-super-secret'}); assert.ok(!(await readFile(path.join(dir,'credentials'))).includes(Buffer.from('private-super-secret')))
    assert.deepEqual(await store.load(),{appKey:key,appSecret:'private-super-secret'}); await assert.rejects(()=>store.save({appKey:key,appSecret:secret}))
  } finally { await rm(dir,{recursive:true,force:true}) }
})

test('business-index resolver binds tenant, row, user, and accepts flat and nested protocol inputs',async()=>{
  assert.throws(()=>new BusinessIndexAccessScope('t1','alice','demo','order','42',Symbol()),/Authorized/)
  assert.throws(()=>new BusinessIndexResolution({},'orders','v1',{},'now',Symbol()),/Authorized/)
  const {reach}=fixtures()
  const reqSchema={type:'object',properties:{resourceType:{type:'string'},resourceId:{type:'string'},expectedSourceVersion:{type:'string'}},required:['resourceType','resourceId','expectedSourceVersion']}
  const args={type:'object',properties:{request:reqSchema},required:['request']}
  reach.capability({name:'index.resolve',sideEffect:'READ',businessIdentityRequired:true,businessIndexResolver:true,inputSchema:args,outputSchema:{type:'object',properties:{},required:[]},handler:async({request})=>{
    const ctx=currentInvocation(); const scope=BusinessIndexAccessScope.authorizeRow({invocationTenantId:ctx.tenantId,authoritativeRowTenantId:'t1',currentUserId:ctx.externalUserId,projectCode:ctx.projectCode,resourceType:request.resourceType,requestedResourceId:request.resourceId,authoritativeResourceId:'42',visibleToCurrentUser:ctx.externalUserId==='alice'})
    return BusinessIndexResolution.createAuthorized(scope,'orders','v1',{name:'demo'})
  }})
  const {server,base}=await serve(reach)
  try {
    await reach.start(); const req={resourceType:'order',resourceId:'42',expectedSourceVersion:'v1'}, hdr={'X-ReachAI-Invocation-Token':token('alice','t1',[],'index.resolve')}
    for(const body of [req,{request:req}]) { const r=await post(base,'/reachai/capabilities/index.resolve/invoke',body,hdr);assert.equal(r.status,200);assert.equal(r.body.resourceId,'42');assert.equal(r.body.tenantId,'t1') }
    assert.equal((await post(base,'/reachai/capabilities/index.resolve/invoke',{...req,extra:'bad'},hdr)).status,400)
    assert.equal((await post(base,'/reachai/capabilities/index.resolve/invoke',req,{'X-ReachAI-Invocation-Token':token('bob','t1',[],'index.resolve')})).status,500)
    assert.equal((await post(base,'/reachai/capabilities/index.resolve/invoke',req,{'X-ReachAI-Invocation-Token':token('alice','other',[],'index.resolve')})).status,500)
  } finally { await reach.stop();server.close() }
})

test('business-index writes sign the exact bytes and reject invalid index names',async()=>{
  const {reach,calls}=fixtures(); const {server}=await serve(reach)
  try {
    await reach.start(); await reach.indexUpsert('orders_idx',{bizId:'42',title:'中文订单'}); await reach.indexBatch('orders_idx',[{bizId:'42'}]); await reach.indexDelete('orders_idx','42')
    await assert.rejects(()=>reach.indexDelete('../bad','42'))
    for (const call of calls.filter(c=>c.url.includes('/knowledge-ingress/'))) {
      const h=call.headers,p=new URL(call.url).pathname
      assert.equal(h['X-ReachAI-Signature'],bodySignature('POST',p,'demo',key,h['X-ReachAI-Timestamp'],h['X-ReachAI-Nonce'],call.bytes,secret))
      assert.equal(h['X-ReachAI-Body-SHA256'],require('node:crypto').createHash('sha256').update(call.bytes).digest('hex'))
    }
  } finally { await reach.stop();server.close() }
})

test('roles require identity; unknown inventory and unsupported schemas fail closed',()=>{
  const opts={registryUrl:'http://registry.test',projectCode:'demo',baseUrl:'http://host.test',appKey:key,appSecret:secret}
  assert.throws(()=>createReachRouter(new ReachAI(opts)),/inventory/)
  const reach=new ReachAI(opts)
  assert.throws(()=>reach.capability({name:'x',businessIdentityRequired:false,requiredRoles:['Admin'],inputSchema,outputSchema,handler:()=>{}}),/identity/)
  assert.throws(()=>reach.capability({name:'x',inputSchema:{type:'object',properties:{payload:{type:'object',additionalProperties:true}},required:['payload']},outputSchema,handler:()=>{}}),/Object/)
  assert.throws(()=>reach.capability({name:'x',inputSchema:{type:'object',properties:{},required:['toString']},outputSchema,handler:()=>{}}),/Invalid required/)
  assert.throws(()=>reach.capability({name:'x',businessIndexResolver:true,businessIdentityRequired:true,inputSchema,outputSchema,handler:()=>{}}),/typed request/)
  assert.throws(()=>reach.capability({name:'x',inputSchema:{type:'object',properties:{value:{type:'string',format:'made-up-format'}},required:['value']},outputSchema,handler:()=>{}}),/unknown format/)
})

test('declared standard JSON Schema formats are enforced',async()=>{
  const {reach}=fixtures()
  reach.capability({name:'date.check',sideEffect:'READ',inputSchema:{type:'object',properties:{when:{type:'string',format:'date-time'}},required:['when']},outputSchema:{type:'string'},handler:({when})=>when})
  const {server,base}=await serve(reach)
  try {
    await reach.start(); const hdr={'X-ReachAI-Invocation-Token':token('alice','t1',[],'date.check')}
    assert.equal((await post(base,'/reachai/capabilities/date.check/invoke',{when:'2026-11-06T14:00:00Z'},hdr)).status,200)
    assert.equal((await post(base,'/reachai/capabilities/date.check/invoke',{when:'not-a-date'},hdr)).status,400)
  } finally { await reach.stop(); server.close() }
})

test('shutdown waits for an in-flight heartbeat before marking instance offline',async()=>{
  const order=[]; let release, heartbeatCount=0
  const upstream=async (url,options)=>{
    const body=JSON.parse(Buffer.from(options.body).toString('utf8'))
    if (String(url).endsWith('/register')) return new Response(JSON.stringify({projectCode:'demo'}))
    if (String(url).endsWith('/heartbeat')) {
      heartbeatCount++; order.push('heartbeat-start')
      if (heartbeatCount===2) await new Promise(resolve=>{release=resolve})
      order.push('heartbeat-end'); return new Response(JSON.stringify({instance:{instanceId:body.instanceId}}))
    }
    if (String(url).endsWith('/offline')) { order.push('offline'); return new Response('{"code":200}') }
    throw new Error('unexpected route')
  }
  const reach=new ReachAI({registryUrl:'http://registry.test',projectCode:'demo',baseUrl:'http://host.test',appKey:key,appSecret:secret},{fetch:upstream}); reach.markEmptyInventoryComplete(); const {server}=await serve(reach)
  try {
    await reach.start(); const inFlight=reach.heartbeat(); await new Promise(resolve=>setImmediate(resolve));
    const stop=reach.stop(); await new Promise(resolve=>setImmediate(resolve)); assert.ok(!order.includes('offline'))
    release(); await Promise.all([inFlight,stop]); assert.deepEqual(order,['heartbeat-start','heartbeat-end','heartbeat-start','heartbeat-end','offline'])
  } finally { server.close() }
})

test('successful one-time registration persists credentials and subsequent startup uses signed registration',async()=>{
  const dir=await mkdtemp(path.join(tmpdir(),'reach-node-enroll-')); const store=new EncryptedFileCredentialStore(path.join(dir,'credentials'),randomBytes(32).toString('base64')); const calls=[]
  const upstream=async(url,options)=>{
    calls.push(options.headers); return new Response(JSON.stringify({projectCode:'demo',appKey:key,appSecret:secret}),{status:200})
  }
  try {
    const options={registryUrl:'http://registry.test',projectCode:'demo',baseUrl:'http://host.test',enrollmentToken:'one-time'}
    await new ReachAI(options,{credentialStore:store,fetch:upstream}).register()
    assert.equal(await store.enrollmentUncertain(),false); assert.deepEqual(await store.load(),{appKey:key,appSecret:secret}); assert.equal(calls[0]['X-ReachAI-Registry-Enrollment-Token'],'one-time')
    const fresh=new ReachAI({registryUrl:options.registryUrl,projectCode:options.projectCode,baseUrl:options.baseUrl},{credentialStore:store,fetch:upstream}); await fresh.register()
    assert.equal(calls[1]['X-ReachAI-App-Key'],key); assert.equal(calls[1]['X-ReachAI-Registry-Enrollment-Token'],undefined)
  } finally { await rm(dir,{recursive:true,force:true}) }
})
