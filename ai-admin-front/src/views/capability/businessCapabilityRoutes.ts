import type { LocationQuery, LocationQueryRaw } from 'vue-router'

export const BUSINESS_CAPABILITY_ROOT = '/business-capabilities'
export const BUSINESS_METHOD_CATALOG_PATH = `${BUSINESS_CAPABILITY_ROOT}/java-methods`
export const HTTP_API_CATALOG_PATH = `${BUSINESS_CAPABILITY_ROOT}/http-apis`

export function httpApiDetailLocation(id: number, query: LocationQueryRaw = {}) {
  return { path: `${HTTP_API_CATALOG_PATH}/${id}`, query }
}

/** Preserve bookmarked filters while handing their ownership to the selected tab. */
export function legacyCatalogQuery(query: LocationQuery, namespace: 'method' | 'api') {
  const next: LocationQueryRaw = { ...query }
  const fields = namespace === 'method' ? ['keyword', 'enabled', 'page', 'size']
    : ['keyword', 'environment', 'method', 'sourceStatus', 'page', 'size']
  for (const field of fields) {
    if (query[field] !== undefined) next[`${namespace}${field.charAt(0).toUpperCase()}${field.slice(1)}`] = query[field]
    delete next[field]
  }
  return next
}
