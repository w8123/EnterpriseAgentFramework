import type { InvocationContext } from './core';

export interface BusinessIndexRequest { resourceType: string; resourceId: string; expectedSourceVersion: string }
const scopeProof = new WeakSet<BusinessIndexAccessScope>();
const resolutionProof = new WeakSet<BusinessIndexResolution>();
const proofKey = Symbol('authorized business index row');
function required(value: unknown, max: number, identifier = false): string {
  if (typeof value !== 'string' || !value.trim() || value.trim().length > max || (identifier && !/^[A-Za-z0-9._:-]+$/.test(value.trim()))) throw new Error('Invalid business index scope');
  return value.trim();
}
export class BusinessIndexAccessScope {
  private constructor(readonly tenantId: string, readonly userId: string, readonly projectCode: string, readonly resourceType: string, readonly resourceId: string, proof: symbol) {
    if (proof !== proofKey) throw new Error('Authorized row scope required');
    scopeProof.add(this); Object.freeze(this);
  }
  static authorizeRow(input: { invocationTenantId: string; authoritativeRowTenantId: string; currentUserId: string; projectCode: string; resourceType: string; requestedResourceId: string; authoritativeResourceId: string; visibleToCurrentUser: boolean }): BusinessIndexAccessScope {
    const tenant = required(input.invocationTenantId,96,true), actualTenant = required(input.authoritativeRowTenantId,96,true);
    const user = required(input.currentUserId,128), project = required(input.projectCode,96,true), kind = required(input.resourceType,96,true);
    const requested = required(input.requestedResourceId,256), actual = required(input.authoritativeResourceId,256);
    if (tenant !== actualTenant || requested !== actual || input.visibleToCurrentUser !== true) throw new Error('Business row authorization failed');
    return new BusinessIndexAccessScope(tenant,user,project,kind,actual,proofKey);
  }
}
export class BusinessIndexResolution {
  private constructor(readonly scope: BusinessIndexAccessScope, readonly sourceSystem: string, readonly sourceVersion: string, readonly data: Record<string, unknown>, readonly resolvedAt: string, proof: symbol) {
    if (proof !== proofKey) throw new Error('Authorized row scope required');
    resolutionProof.add(this); Object.freeze(this);
  }
  static createAuthorized(scope: BusinessIndexAccessScope, sourceSystem: string, sourceVersion: string, data: Record<string, unknown>): BusinessIndexResolution {
    if (!scopeProof.has(scope) || !data || typeof data !== 'object' || Array.isArray(data)) throw new Error('Authorized row scope required');
    return new BusinessIndexResolution(scope, required(sourceSystem,96,true), required(sourceVersion,128), structuredClone(data), new Date().toISOString(), proofKey);
  }
}
export function validateResolution(value: unknown, request: BusinessIndexRequest, identity: InvocationContext): Record<string, unknown> {
  if (!(value instanceof BusinessIndexResolution) || !resolutionProof.has(value) || !scopeProof.has(value.scope)) throw new Error('Authorized row scope required');
  const s = value.scope;
  if (s.tenantId !== required(identity.tenantId,96,true) || s.userId !== required(identity.externalUserId || identity.globalUserId,128)
      || s.projectCode !== identity.projectCode || s.resourceType !== required(request.resourceType,96,true) || s.resourceId !== required(request.resourceId,256)) throw new Error('Business index scope mismatch');
  required(request.expectedSourceVersion,128);
  return { schema:'reachai-business-index-resolution-v1', tenantId:s.tenantId, projectCode:s.projectCode,
    sourceSystem:value.sourceSystem, resourceType:s.resourceType, resourceId:s.resourceId,
    sourceVersion:value.sourceVersion, data:value.data, resolvedAt:value.resolvedAt };
}
