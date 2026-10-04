import { describe, expect, it } from 'vitest'
import type { ToolInfo } from '@/types/tool'
import { capabilityExecutionFacts, capabilityInputContract, capabilityReadiness, splitCapabilityParameters } from './capabilityDetail'

const tool: ToolInfo = { name: 'orders_read', title: '查询订单', qualifiedName: 'orders:read', description: '', parameters: [], source: 'sdk', enabled: true }
describe('capability inspection semantics', () => {
  it('distinguishes source failures, disabled definitions and missing evidence', () => {
    expect(capabilityReadiness({ ...tool, sourceAvailability: 'CONTRACT_DRIFT' }).label).toBe('契约已变化')
    expect(capabilityReadiness({ ...tool, sourceAvailability: 'SOURCE_MISSING' }).label).toBe('来源已移除')
    expect(capabilityReadiness({ ...tool, sourceAvailability: 'SOURCE_UNKNOWN' }).description).toContain('拦截')
    expect(capabilityReadiness({ ...tool, enabled: false, sourceAvailability: 'READY' }).label).toBe('已停用')
    expect(capabilityReadiness(tool).tone).not.toBe('success')
  })
  it('keeps outputs out of input contracts and preserves source locations, nested declarations and required fields', () => {
    const value = { ...tool, parameters: [
      { name: 'id', type: 'java.lang.Long', required: true, description: '订单标识', location: 'PATH' },
      { name: 'orders', type: 'java.util.List<Order>', required: true, description: '', location: 'BODY', children: [
        { name: 'amount', type: 'java.math.BigDecimal', required: true, description: '' },
      ] },
      { name: 'result', type: 'Result', required: false, description: '', location: 'OUTPUT' },
    ] }
    expect(splitCapabilityParameters(value.parameters).outputs).toHaveLength(1)
    const contract = JSON.parse(capabilityInputContract(value))
    expect(contract.inputs[0]).toMatchObject({ type: 'java.lang.Long', location: 'PATH', required: true })
    expect(contract.inputs[1].children[0]).toMatchObject({ name: 'amount', type: 'java.math.BigDecimal', required: true })
    expect(JSON.stringify(contract)).not.toContain('result')
  })
  it('preserves dotted names and does not invent default values or a shape for undeclared DTO fields', () => {
    const contract = JSON.parse(capabilityInputContract({ ...tool, parameters: [{ name: 'request.filter', type: 'OrderQuery', required: false, description: '' }] }))
    expect(contract.inputs[0]).toEqual({ name: 'request.filter', type: 'OrderQuery', location: 'body', required: false, description: '' })
  })
  it('does not show SDK sentinel values as zero-second timeouts or negative retries', () => {
    expect(capabilityExecutionFacts({ timeoutMs: 0, retryLimit: -1 }).map(f => f.value)).toEqual(['未单独声明', '未单独声明'])
    expect(capabilityExecutionFacts({ timeoutMs: 15000, retryLimit: 0 }).map(f => f.value)).toEqual(['15 秒', '0 次'])
  })
})
