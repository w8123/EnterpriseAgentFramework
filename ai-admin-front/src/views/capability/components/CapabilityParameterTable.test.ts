import { describe, expect, it } from 'vitest'
import type { ToolParameter } from '@/types/tool'
import { parameterMetadataItems } from './capabilityParameterMetadata'

describe('CapabilityParameterTable', () => {
  it('renders declared source metadata for nested parameters without inventing absent values', () => {
    const parameters: ToolParameter[] = [{
      name: 'order', type: 'object', description: '订单查询条件', required: true,
      metadata: { sourceHint: '由订单上下文提供', dictType: 'order_status' },
      children: [{
        name: 'pageSize', type: 'integer', description: '每页条数', required: false,
        metadata: { example: 20, default: 10, minimum: 1, maximum: 100, enum: [10, 20, 50] },
      }, {
        name: 'credential', type: 'string', description: '访问凭据', required: true,
        metadata: { sensitive: true, example: 'secret-example', default: 'secret-default', pattern: '^[A-Z]+$' },
      }, {
        name: 'opaque', type: 'string', description: '未附加声明', required: false,
      }],
    }]

    const text = parameters.flatMap(parameter => [parameter, ...(parameter.children || [])])
      .flatMap(parameter => parameterMetadataItems(parameter.metadata))
      .map(item => `${item.label}：${item.value}`)
      .join('\n')
    expect(text).toContain('来源提示：由订单上下文提供')
    expect(text).toContain('字典：order_status')
    expect(text).toContain('示例：20')
    expect(text).toContain('默认值：10')
    expect(text).toContain('可选值：10、20、50')
    expect(text).toContain('约束：最小值 1')
    expect(text).toContain('约束：最大值 100')
    expect(text).toContain('敏感字段：示例与默认值不展示')
    expect(text).toContain('约束：格式规则 ^[A-Z]+$')
    expect(parameterMetadataItems(parameters[0].children![2].metadata)).toEqual([])
    expect(text).not.toContain('secret-example')
    expect(text).not.toContain('secret-default')
  })
})
