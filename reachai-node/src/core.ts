import { AsyncLocalStorage } from 'node:async_hooks';
import { createHash, randomUUID } from 'node:crypto';
import { hostname } from 'node:os';
import { inspect } from 'node:util';
import Ajv, { type ValidateFunction } from 'ajv';
import addFormats from 'ajv-formats';
import { bodySignature, registrySignature, secureEqual, verifyInvocation, type Claims } from './protocol';
import { validateResolution, type BusinessIndexRequest } from './business-index';
import type { Credentials, CredentialStore } from './credentials';
import type { Request } from 'express';

export interface ReachAIOptions {
  registryUrl: string; projectCode: string; baseUrl: string; projectName?: string; contextPath?: string;
  environment?: string; instanceId?: string; enrollmentToken?: string; appKey?: string; appSecret?: string;
  heartbeatSeconds?: number; allowedOrigins?: string[];
}
export interface InvocationContext { readonly projectCode: string; readonly capabilityName: string; readonly externalUserId?: string; readonly globalUserId?: string; readonly tenantId?: string; readonly roles: readonly string[]; readonly traceId?: string; readonly claims: Claims }
export interface EmbedPrincipal { externalUserId: string; globalUserId?: string; userName?: string; roles?: string[]; attributes?: Record<string, unknown>; businessSessionId?: string; businessSessionExpiresAt?: number }
export interface CallbackNonceStore { claim(projectCode: string, nonce: string, expiresAtMs: number): Promise<boolean> }
export interface ReachAIDependencies {
  credentialStore?: CredentialStore;
  principalResolver?: (request: Request, agentId: string) => Promise<EmbedPrincipal | null> | EmbedPrincipal | null;
  securityBridge?: (identity: InvocationContext, next: () => Promise<unknown>) => Promise<unknown>;
  nonceStore?: CallbackNonceStore;
  fetch?: typeof fetch;
}
export type JsonSchema = Record<string, unknown>;
export interface CapabilityDefinition {
  name: string; title?: string; description?: string; sideEffect?: 'READ' | 'WRITE' | 'IDEMPOTENT_WRITE' | 'IRREVERSIBLE';
  businessIdentityRequired?: boolean; requiredRoles?: string[]; inputSchema: JsonSchema; outputSchema: JsonSchema;
  handler: (input: Record<string, unknown>) => unknown | Promise<unknown>; businessIndexResolver?: boolean;
}
interface Registered { definition: CapabilityDefinition; validate: ValidateFunction; validateOutput: ValidateFunction; registration: Record<string, unknown> }
const storage = new AsyncLocalStorage<InvocationContext>();
export function currentInvocation(): InvocationContext | undefined { return storage.getStore(); }
class LocalNonceStore implements CallbackNonceStore {
  private nonces = new Map<string,number>();
  async claim(projectCode: string, nonce: string, expiresAtMs: number): Promise<boolean> {
    const now = Date.now(); for (const [key, expiry] of this.nonces) if (expiry < now) this.nonces.delete(key);
    const key = projectCode + '\0' + nonce; if (this.nonces.has(key)) return false;
    this.nonces.set(key, expiresAtMs); return true;
  }
}
function origin(value: string): string {
  const u = new URL(value);
  if (!['http:', 'https:'].includes(u.protocol) || u.username || u.password || (u.pathname !== '/' && u.pathname !== '') || u.search || u.hash) throw new Error('Invalid origin');
  return u.origin;
}
function object(value: unknown): value is Record<string, unknown> { return !!value && typeof value === 'object' && !Array.isArray(value); }
function frozen(value: unknown): unknown { if (value && typeof value === 'object') { Object.values(value).forEach(frozen); Object.freeze(value); } return value; }
function text(value: unknown): string | undefined { return typeof value === 'string' && value.trim() ? value : undefined; }
function kind(schema: JsonSchema): string { return Array.isArray(schema.type) ? schema.type.find((v: unknown) => v !== 'null') as string : schema.type as string; }
function schemaCheck(schema: JsonSchema, depth = 0): void {
  if (!object(schema) || depth > 20) throw new Error('Invalid schema');
  const k = kind(schema); if (!['string','integer','number','boolean','array','object','null'].includes(k)) throw new Error('Unsupported schema type');
  if (Array.isArray(schema.type) && (schema.type.length !== 2 || !schema.type.includes('null'))) throw new Error('Unsupported schema union');
  if (k === 'array') schemaCheck(schema.items as JsonSchema, depth+1);
  if (k === 'object') {
    if (!object(schema.properties) || (schema.additionalProperties !== undefined && schema.additionalProperties !== false)) throw new Error('Object must declare fixed properties');
    for (const [key,value] of Object.entries(schema.properties)) { if (!/^[A-Za-z0-9_.-]{1,192}$/.test(key)) throw new Error('Invalid property name'); schemaCheck(value as JsonSchema,depth+1); }
    if (schema.required !== undefined && (!Array.isArray(schema.required) || schema.required.some(v => typeof v !== 'string' || !Object.hasOwn(schema.properties as object, v)))) throw new Error('Invalid required properties');
  }
  for (const key of ['$ref','anyOf','oneOf','allOf','patternProperties','if','then','else']) if (key in schema) throw new Error('Unsupported schema construct');
}
function closeObjects(schema: JsonSchema): void {
  if (kind(schema) === 'array') closeObjects(schema.items as JsonSchema);
  if (kind(schema) === 'object') {
    schema.additionalProperties = false;
    for (const child of Object.values(schema.properties as Record<string,JsonSchema>)) closeObjects(child);
  }
}
function metadata(schema: JsonSchema): Record<string,unknown> {
  const result: Record<string,unknown> = {nullable: Array.isArray(schema.type)};
  if (schema.enum) result.enum = schema.enum; if (schema.format) result.format = schema.format;
  if (schema.items) { result.itemsType = kind(schema.items as JsonSchema); result.itemsMetadata = metadata(schema.items as JsonSchema); }
  return result;
}
function children(schema: JsonSchema): unknown[] {
  if (kind(schema) === 'array') schema = schema.items as JsonSchema;
  const required = (schema.required || []) as string[];
  return Object.entries((schema.properties || {}) as Record<string,JsonSchema>).map(([name, child]) => ({name,type:kind(child),required:required.includes(name),location:'BODY',children:children(child),metadata:metadata(child)}));
}
export class ReachAI {
  readonly options: Readonly<ReachAIOptions>;
  readonly instanceId: string;
  #dependencies: ReachAIDependencies;
  #credentials?: Credentials;
  private readonly capabilities = new Map<string,Registered>();
  private configured = false; private mounted = false; private timer?: NodeJS.Timeout; private registered = false;
  #starting = false; #registering = false; #heartbeatInFlight?: Promise<void>;
  private readonly nonceStore: CallbackNonceStore; private readonly ajv = new Ajv({allErrors:true,strict:false,strictSchema:true,ownProperties:true});
  private readonly allowedOrigins: Set<string>; private readonly fetcher: typeof fetch;
  #enrollmentToken: string;
  constructor(options: ReachAIOptions, dependencies: ReachAIDependencies = {}) {
    addFormats(this.ajv);
    if (!/^[a-z0-9_]+(?:-[a-z0-9_]+)*$/.test(options.projectCode)) throw new Error('Invalid project code');
    for (const value of [options.registryUrl,options.baseUrl]) { const u = new URL(value); if (!['http:','https:'].includes(u.protocol) || u.username || u.password || u.search || u.hash) throw new Error('Invalid service URL'); }
    if (!!options.appKey !== !!options.appSecret || (options.enrollmentToken && (options.appKey || options.appSecret))) throw new Error('Invalid credential configuration');
    const { appSecret, enrollmentToken, ...safe } = options;
    this.options = Object.freeze(safe); this.#enrollmentToken = enrollmentToken || '';
    if (options.appKey && appSecret) this.#credentials = {appKey:options.appKey, appSecret};
    this.instanceId = options.instanceId?.trim() || 'node-' + randomUUID(); this.#dependencies = dependencies;
    this.nonceStore = dependencies.nonceStore || new LocalNonceStore(); this.fetcher = dependencies.fetch || fetch;
    this.allowedOrigins = new Set((options.allowedOrigins || []).map(origin));
  }
  toJSON(): Record<string,unknown> { return {projectCode:this.options.projectCode,baseUrl:this.options.baseUrl,instanceId:this.instanceId,registered:this.registered}; }
  /** Never expand host-provided stores or other dependencies into application logs. */
  [inspect.custom](): Record<string,unknown> { return this.toJSON(); }
  capability(definition: CapabilityDefinition): void {
    if (this.mounted) throw new Error('Capabilities must be declared before router mounting');
    if (!/^[A-Za-z0-9_.-]{1,192}$/.test(definition.name) || this.capabilities.has(definition.name.toLowerCase())) throw new Error('Invalid or duplicate capability');
    const sideEffect = definition.sideEffect || 'WRITE';
    if (!['READ','WRITE','IDEMPOTENT_WRITE','IRREVERSIBLE'].includes(sideEffect)) throw new Error('Invalid side effect');
    const roles = definition.requiredRoles || [];
    if (roles.some(x => typeof x !== 'string' || !x.trim()) || (roles.length && !definition.businessIdentityRequired)) throw new Error('Roles require business identity');
    if (definition.businessIndexResolver && !definition.businessIdentityRequired) throw new Error('Business index resolver requires identity');
    const input = structuredClone(definition.inputSchema), output = structuredClone(definition.outputSchema);
    schemaCheck(input); schemaCheck(output);
    if (input.type !== 'object') throw new Error('Capability input must be an object schema');
    if (definition.businessIndexResolver) {
      const properties = input.properties as Record<string,JsonSchema>;
      const request = properties.request;
      const requestProps = request?.properties as Record<string,JsonSchema> | undefined;
      const expected = ['resourceType','resourceId','expectedSourceVersion'];
      if (Object.keys(properties).length !== 1 || !Array.isArray(input.required) || input.required.length !== 1 || input.required[0] !== 'request'
          || !request || request.type !== 'object' || !requestProps || Object.keys(requestProps).length !== 3
          || !Array.isArray(request.required) || request.required.length !== 3
          || expected.some(key => !(request.required as unknown[]).includes(key) || requestProps[key]?.type !== 'string')) throw new Error('Business index resolver requires a typed request object');
    }
    if (typeof definition.handler !== 'function') throw new Error('Capability handler required');
    // Unspecified fields must never become implicit business inputs.
    closeObjects(input);
    const parameters = children(input);
    const meta: Record<string,unknown> = {assetType:'BUSINESS_METHOD',declared:true,source:'ReachCapability',requiredRoles:[...roles],businessIdentityRequired:!!definition.businessIdentityRequired,className:'node',methodName:definition.name,invokeProtocol:'REACHAI_CAPABILITY_HTTP',timeoutMs:0,retryLimit:-1,resultSchema:output};
    if (definition.businessIndexResolver) { meta.tags = ['business-index-resolver']; delete meta.resultSchema; }
    const registration = {name:definition.name,title:definition.title || '',description:definition.description || '',httpMethod:'POST',baseUrl:this.options.baseUrl,contextPath:this.options.contextPath || '',endpointPath:`/reachai/capabilities/${definition.name}/invoke`,requestBodyType:'object',responseType:kind(output),sideEffect,enabled:true,parameters,metadata:meta};
    this.capabilities.set(definition.name.toLowerCase(), {definition:{...definition,inputSchema:input,outputSchema:output,requiredRoles:[...roles]},validate:this.ajv.compile(input),validateOutput:this.ajv.compile(output),registration}); this.configured = true;
  }
  markEmptyInventoryComplete(): void { if (this.mounted) throw new Error('Already mounted'); this.configured = true; }
  mount(): void {
    if (this.mounted) throw new Error('Already mounted');
    if (!this.configured) throw new Error('Capability inventory not configured');
    if ([...this.capabilities.values()].some(x => x.definition.businessIdentityRequired) && !this.#dependencies.securityBridge) throw new Error('Business identity bridge required');
    this.mounted = true;
  }
  private async post(path: string, body: unknown, enrollment = false, bodySigned = false, projectOnly = false): Promise<Record<string,unknown>> {
    const bytes = Buffer.from(JSON.stringify(body), 'utf8');
    const headers: Record<string,string> = {'Content-Type':'application/json'};
    if (enrollment) headers['X-ReachAI-Registry-Enrollment-Token'] = this.#enrollmentToken;
    else {
      const c = this.#credentials; if (!c) throw new Error('Credentials unavailable');
      const ts=String(Date.now()), nonce=randomUUID();
      Object.assign(headers, {'X-ReachAI-App-Key':c.appKey,'X-ReachAI-Timestamp':ts,'X-ReachAI-Nonce':nonce,'X-ReachAI-Signature':registrySignature(this.options.projectCode,ts,nonce,c.appSecret)});
      if (bodySigned) { headers['X-ReachAI-Body-SHA256'] = createHash('sha256').update(bytes).digest('hex'); headers[projectOnly ? 'X-ReachAI-Signature' : 'X-ReachAI-Body-Signature'] = bodySignature('POST',path,this.options.projectCode,c.appKey,ts,nonce,bytes,c.appSecret); }
    }
    const response = await this.fetcher(this.options.registryUrl.replace(/\/+$/,'')+path,{method:'POST',headers,body:bytes,redirect:'manual',signal:AbortSignal.timeout(30000)});
    if (!response.ok) throw new Error(`ReachAI HTTP ${response.status}`);
    const reader = response.body?.getReader(); const chunks: Uint8Array[] = []; let size=0;
    if (reader) { while (true) { const part=await reader.read(); if(part.done) break; size+=part.value.length; if(size>8*1024*1024) { await reader.cancel(); throw new Error('ReachAI response too large'); } chunks.push(part.value); } }
    const result: unknown = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
    if (!object(result) || ('code' in result && String(result.code) !== '200')) throw new Error('ReachAI request rejected');
    return result;
  }
  private projectPath(suffix: string): string { return `/api/registry/projects/${encodeURIComponent(this.options.projectCode)}/${suffix}`; }
  async register(): Promise<void> {
    if (this.#registering) throw new Error('Registration already in progress');
    this.#registering = true;
    try {
    const store=this.#dependencies.credentialStore;
    if (!this.#credentials && store) this.#credentials = (await store.load()) || undefined;
    const enrollment=!this.#credentials;
    if (enrollment) { if (!store || !this.#enrollmentToken) throw new Error('Enrollment requires token and durable credential store'); if (await store.enrollmentUncertain()) throw new Error('Enrollment outcome uncertain'); await store.markEnrollmentStarted(); }
    const result=await this.post('/api/registry/projects/register',{projectCode:this.options.projectCode,name:this.options.projectName || this.options.projectCode,environment:this.options.environment || 'default',visibility:'PRIVATE',baseUrl:this.options.baseUrl,contextPath:this.options.contextPath || '',appKey:this.#credentials?.appKey || null},enrollment);
    const payload=object(result.data) ? result.data : result;
    if (payload.projectCode !== this.options.projectCode) throw new Error('Registration project mismatch');
    if (enrollment) {
      if (typeof payload.appKey !== 'string' || !payload.appKey.trim() || typeof payload.appSecret !== 'string' || !payload.appSecret.trim()) throw new Error('Registration credentials missing');
      const c={appKey:payload.appKey,appSecret:payload.appSecret}; await store!.save(c); this.#credentials=c; await store!.clearEnrollmentStarted(); this.#enrollmentToken='';
    }
    this.registered=true;
    } finally { this.#registering = false; }
  }
  async heartbeat(): Promise<void> {
    if (this.#heartbeatInFlight) return this.#heartbeatInFlight;
    const work=(async()=> {
      const result=await this.post(this.projectPath('instances/heartbeat'),{instanceId:this.instanceId,baseUrl:this.options.baseUrl,host:hostname(),sdkVersion:'1.0.0',metadata:{runtimePlacement:'CAPABILITY_HOST',runtimeTypes:['EXPRESS_CAPABILITY_HOST'],supportsTools:true,sdkLanguage:'node'}});
      const payload=object(result.data)?result.data:result;
      if (!object(payload.instance) || payload.instance.instanceId !== this.instanceId) throw new Error('Incomplete heartbeat response');
    })();
    this.#heartbeatInFlight=work;
    try { await work; } finally { this.#heartbeatInFlight=undefined; }
  }
  async sync(source = 'SDK'): Promise<Record<string,unknown>> {
    if (!this.configured) throw new Error('Capability inventory not configured');
    const syncId=randomUUID(), capabilities=[...this.capabilities.values()].map(x=>x.registration);
    const result=await this.post(this.projectPath('capabilities/sync'),{syncId,source,apply:false,capabilities});
    const payload=object(result.data)?result.data:result;
    // Capability may deduplicate identical content and return the original stored
    // snapshot's syncId; this value is not a receipt for the current request id.
    if(typeof payload.syncId!=='string' || !payload.syncId.trim() || payload.syncId.length>64 ||
       payload.projectCode!==this.options.projectCode || payload.received!==capabilities.length) throw new Error('Incomplete sync response');
    return result;
  }
  async start(): Promise<void> {
    if (!this.mounted || this.timer || this.#starting) throw new Error('Mount router before starting; do not start twice');
    this.#starting = true;
    try {
      await this.register(); await this.heartbeat();
      this.timer=setInterval(() => { void this.heartbeat().catch(() => { /* next heartbeat may retry; never replay business writes */ }); },Math.max(5,this.options.heartbeatSeconds || 30)*1000);
      this.timer.unref();
    } finally { this.#starting = false; }
  }
  async stop(): Promise<void> {
    if (this.#starting) throw new Error('Wait for startup before shutdown');
    if (this.timer) clearInterval(this.timer); this.timer=undefined;
    if (this.#heartbeatInFlight) { try { await this.#heartbeatInFlight; } catch { /* still send offline */ } }
    if(this.registered) { await this.post(this.projectPath('instances/offline'),{instanceId:this.instanceId}); this.registered=false; }
  }
  async verifyCallback(key: string, ts: string, nonce: string, signature: string): Promise<boolean> {
    try { const c=this.#credentials, sent=Number(ts); if (!c || key!==c.appKey || !/^\d+$/.test(ts) || !Number.isSafeInteger(sent) || Math.abs(Date.now()-sent)>300000 || !nonce || nonce.length>256) return false;
      if (!secureEqual(registrySignature(this.options.projectCode,ts,nonce,c.appSecret),signature.toLowerCase())) return false;
      return await this.nonceStore.claim(this.options.projectCode,nonce,sent+300000);
    } catch { return false; }
  }
  async invoke(name: string, token: string, input: unknown): Promise<{status: number; body: unknown}> {
    const entry=this.capabilities.get(name.toLowerCase()); if (!entry || entry.definition.name!==name) return {status:404,body:{error:'NOT_FOUND'}};
    let ctx: InvocationContext;
    try {
      if (!this.#credentials) throw new Error();
      const claims=verifyInvocation(token,this.#credentials.appKey,this.#credentials.appSecret,this.options.projectCode,name);
      if (claims.roles !== undefined && (!Array.isArray(claims.roles) || claims.roles.some(x=>typeof x!=='string'))) throw new Error();
      ctx=Object.freeze({projectCode:this.options.projectCode,capabilityName:name,externalUserId:text(claims.externalUserId),globalUserId:text(claims.globalUserId),tenantId:text(claims.tenantId),roles:Object.freeze([...(claims.roles as string[] || [])]),traceId:text(claims.traceId),claims:frozen(claims) as Claims});
      if (entry.definition.businessIdentityRequired && !ctx.externalUserId && !ctx.globalUserId) throw new Error();
    } catch { return {status:401,body:{error:'UNAUTHORIZED'}}; }
    if (entry.definition.requiredRoles?.length && !entry.definition.requiredRoles.some(r=>ctx.roles.includes(r))) return {status:403,body:{error:'FORBIDDEN'}};
    const bound = entry.definition.businessIndexResolver && object(input) && !Object.hasOwn(input,'request') ? {request:input} : input;
    if (!entry.validate(bound)) return {status:400,body:{error:'INVALID_ARGUMENTS'}};
    return storage.run(ctx,async()=> {
      let invoked=false;
      const next=async()=> { if (invoked) throw new Error('Capability may only execute once'); invoked=true; return await entry.definition.handler(bound as Record<string,unknown>); };
      try {
        let result=this.#dependencies.securityBridge ? await this.#dependencies.securityBridge(ctx,next) : await next();
        if (!invoked) throw new Error('Business bridge did not invoke capability');
        if (entry.definition.businessIndexResolver) result=validateResolution(result,(bound as Record<string,unknown>).request as BusinessIndexRequest,ctx);
        else if (!entry.validateOutput(result)) throw new Error('Capability result violates schema');
        return {status:200,body:result};
      } catch { return {status:500,body:{success:false,code:'CAPABILITY_EXECUTION_FAILED',message:'业务方法执行失败'}}; }
    });
  }
  async embed(request: Request, body: unknown): Promise<{status:number;body:unknown}> {
    let o: string;
    try { o=origin(request.get('Origin') || ''); } catch { return {status:403,body:{error:'FORBIDDEN'}}; }
    if (!this.allowedOrigins.has(o)) return {status:403,body:{error:'FORBIDDEN'}};
    try {
      if (!object(body) || Object.keys(body).some(k=>!['agentId','pageKey','pageInstanceId','route','origin'].includes(k)) || !text(body.agentId) || !text(body.pageInstanceId) || (body.agentId as string).length > 192 || (body.pageInstanceId as string).length > 192) return {status:400,body:{error:'INVALID_ARGUMENTS'}};
      if (body.origin !== undefined) { try { if (typeof body.origin!=='string' || origin(body.origin)!==o) return {status:403,body:{error:'FORBIDDEN'}}; } catch { return {status:403,body:{error:'FORBIDDEN'}}; } }
      if (!this.#dependencies.principalResolver) return {status:503,body:{error:'UNAVAILABLE'}};
      const principal=await this.#dependencies.principalResolver(request,body.agentId as string);
      if (!principal || !text(principal.externalUserId)) return {status:401,body:{error:'UNAUTHORIZED'}};
      const result=await this.post('/api/embed/token/exchange',{projectCode:this.options.projectCode,agentId:body.agentId,pageKey:body.pageKey ?? null,pageInstanceId:body.pageInstanceId,route:body.route ?? null,origin:o,principal:{externalUserId:principal.externalUserId,globalUserId:principal.globalUserId ?? null,userName:principal.userName ?? null,roles:principal.roles || [],attributes:principal.attributes || {}},businessSessionId:principal.businessSessionId ?? null,businessSessionExpiresAt:principal.businessSessionExpiresAt ?? null},false,true);
      const payload=object(result.data)?result.data:result;
      if (!text(payload.token) || typeof payload.expiresIn!=='number' || payload.expiresIn<=0 || (principal.businessSessionId && payload.businessSessionBound!==true) || (principal.businessSessionExpiresAt !== undefined && payload.businessSessionExpiryApplied!==true)) throw new Error('Incomplete embed response');
      return {status:200,body:payload};
    } catch { return {status:502,body:{error:'EXCHANGE_FAILED'}}; }
  }
  async revokeBusinessSession(sessionId: string): Promise<void> { if (!text(sessionId)) throw new Error('Business session required'); const r=await this.post('/api/embed/token/business-session/revoke',{projectCode:this.options.projectCode,businessSessionId:sessionId},false,true); if (String(r.code)!=='200') throw new Error('Revocation not confirmed'); }
  private indexPath(indexCode: string, operation: string): string { if(!/^[A-Za-z][A-Za-z0-9_]{1,62}$/.test(indexCode)) throw new Error('Invalid business index'); return `/api/knowledge-ingress/projects/${this.options.projectCode}/biz-index/${indexCode}/${operation}`; }
  async indexUpsert(indexCode: string, record: Record<string,unknown>): Promise<Record<string,unknown>> { return this.post(this.indexPath(indexCode,'upsert'),record,false,true,true); }
  async indexBatch(indexCode: string, items: Record<string,unknown>[]): Promise<Record<string,unknown>> { return this.post(this.indexPath(indexCode,'batch'),{items},false,true,true); }
  async indexDelete(indexCode: string, bizId: string): Promise<Record<string,unknown>> { return this.post(this.indexPath(indexCode,'delete'),{bizId},false,true,true); }
}
