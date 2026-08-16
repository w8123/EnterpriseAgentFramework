const JSON_OBJECT_TYPES = new Set(['object', 'map', 'record'])
const JSON_ARRAY_TYPES = new Set(['array', 'list', 'collection', 'set'])
const INTEGER_TYPES = new Set(['byte', 'short', 'int', 'integer'])
const LONG_TYPES = new Set(['long', 'biginteger'])
const NUMBER_TYPES = new Set(['number', 'float', 'double'])
const DECIMAL_TYPES = new Set(['decimal', 'bigdecimal'])

function normalizeType(type: string): string {
  return String(type || '')
    .trim()
    .toLowerCase()
    .replace(/\s+/g, '')
    .replace(/^java\.lang\./, '')
    .replace(/^java\.math\./, '')
    .replace(/^java\.util\./, '')
}

function baseType(type: string): string {
  const genericIndex = type.indexOf('<')
  return genericIndex >= 0 ? type.slice(0, genericIndex) : type
}

function parseJson(value: string, name: string): unknown {
  try {
    return JSON.parse(value)
  } catch {
    throw new Error(`${name} 需要填写有效的 JSON`)
  }
}

function parseFiniteNumber(value: string, name: string): number {
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) {
    throw new Error(`${name} 需要填写有限数字`)
  }
  return parsed
}

/**
 * Converts a Tool test-dialog string to the declared parameter type without
 * silently changing invalid booleans or precision-sensitive Java numbers.
 */
export function parseToolTestArgument(value: string, type: string, name: string): unknown {
  const normalizedType = normalizeType(type)
  const normalizedBaseType = baseType(normalizedType)

  if (normalizedType === 'json') {
    return parseJson(value, name)
  }

  if (
    JSON_ARRAY_TYPES.has(normalizedBaseType)
    || normalizedType.endsWith('[]')
  ) {
    const parsed = parseJson(value, name)
    if (!Array.isArray(parsed)) {
      throw new Error(`${name} 需要填写 JSON 数组`)
    }
    return parsed
  }

  if (JSON_OBJECT_TYPES.has(normalizedBaseType)) {
    const parsed = parseJson(value, name)
    if (parsed === null || Array.isArray(parsed) || typeof parsed !== 'object') {
      throw new Error(`${name} 需要填写 JSON 对象`)
    }
    return parsed
  }

  if (INTEGER_TYPES.has(normalizedBaseType)) {
    const parsed = parseFiniteNumber(value, name)
    if (!Number.isSafeInteger(parsed)) {
      throw new Error(`${name} 需要填写安全范围内的整数`)
    }
    return parsed
  }

  if (LONG_TYPES.has(normalizedBaseType)) {
    const trimmed = value.trim()
    if (!/^[+-]?\d+$/.test(trimmed)) {
      throw new Error(`${name} 需要填写整数`)
    }
    const parsed = Number(trimmed)
    return Number.isSafeInteger(parsed) ? parsed : trimmed
  }

  if (NUMBER_TYPES.has(normalizedBaseType)) {
    return parseFiniteNumber(value, name)
  }

  if (DECIMAL_TYPES.has(normalizedBaseType)) {
    const trimmed = value.trim()
    if (!/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(trimmed)) {
      throw new Error(`${name} 需要填写数字`)
    }
    return trimmed
  }

  if (normalizedBaseType === 'boolean' || normalizedBaseType === 'bool') {
    const normalizedValue = value.trim().toLowerCase()
    if (normalizedValue !== 'true' && normalizedValue !== 'false') {
      throw new Error(`${name} 只能填写 true 或 false`)
    }
    return normalizedValue === 'true'
  }

  return value
}
