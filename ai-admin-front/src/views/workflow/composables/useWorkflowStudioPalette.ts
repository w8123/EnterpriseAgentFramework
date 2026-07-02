import { computed } from 'vue'
import type { Ref } from 'vue'
import type { Component } from 'vue'
import { Briefcase, Coin, Collection, Connection, Document, Finished, Files, Link, MagicStick, Operation, SetUp, Switch, Tickets, Tools } from '@element-plus/icons-vue'
import LlmModelIcon from '@/components/icons/LlmModelIcon.vue'
import type { WorkflowGraphNodeTypeDescriptor as NodeTypeDescriptor } from '@/types/workflow'
import type { CanvasNodeKind } from '@/types/studio'
import { STUDIO_NODE_GROUPS, STUDIO_NODE_REGISTRY, enabledStudioNodeKinds, studioNodeCapabilityMap } from '@/utils/studioNodeRegistry'

export type WorkflowStudioPaletteItem = {
  kind: CanvasNodeKind
  label: string
  meta: string
  icon: Component
  hint: string
}

export type WorkflowStudioPaletteGroup = {
  title: string
  icon: Component
  items: WorkflowStudioPaletteItem[]
}

export interface UseWorkflowStudioPaletteDeps {
  nodeSearchKeyword: Ref<string>
  activePaletteGroup: Ref<string>
  paletteExpanded: Ref<boolean>
  nodeTypes: Ref<NodeTypeDescriptor[]>
  graphNodeTypeCapabilitiesLoaded: Ref<boolean>
}

const nodeIconMap: Record<CanvasNodeKind, Component> = {
  start: SetUp,
  end: Finished,
  userInput: SetUp,
  interaction: SetUp,
  pageAction: Link,
  llm: LlmModelIcon,
  skill: Briefcase,
  tool: Tools,
  knowledge: Coin,
  condition: Switch,
  variable: SetUp,
  template: Document,
  parameter: MagicStick,
  http: Link,
  answer: Finished,
  code: Document,
  classifier: SetUp,
  aggregate: SetUp,
  approval: Finished,
  loop: SetUp,
  knowledgeWrite: Collection,
  documentExtract: Files,
  mcp: Connection,
}

const groupIconMap: Record<string, Component> = {
  Cpu: LlmModelIcon,
  Operation,
  Connection,
  Collection,
}

const kindColorMap: Record<'start'|'end'|'answer'|'approval'|'llm'|'classifier'|'tool'|'skill'|'http'|'mcp'|'knowledge'|'knowledgeWrite'|'documentExtract'|'condition'|'variable'|'aggregate'|'loop', { border: string; bg: string }> = {
  start: { border: '#34d399', bg: 'rgba(52, 211, 153, 0.12)' },
  end: { border: '#34d399', bg: 'rgba(52, 211, 153, 0.12)' },
  answer: { border: '#34d399', bg: 'rgba(52, 211, 153, 0.12)' },
  approval: { border: '#34d399', bg: 'rgba(52, 211, 153, 0.12)' },
  llm: { border: '#7c6cff', bg: 'rgba(124, 108, 255, 0.12)' },
  classifier: { border: '#7c6cff', bg: 'rgba(124, 108, 255, 0.12)' },
  tool: { border: '#5b8def', bg: 'rgba(91, 141, 239, 0.12)' },
  skill: { border: '#5b8def', bg: 'rgba(91, 141, 239, 0.12)' },
  http: { border: '#5b8def', bg: 'rgba(91, 141, 239, 0.12)' },
  mcp: { border: '#5b8def', bg: 'rgba(91, 141, 239, 0.12)' },
  knowledge: { border: '#22c55e', bg: 'rgba(34, 197, 94, 0.12)' },
  knowledgeWrite: { border: '#22c55e', bg: 'rgba(34, 197, 94, 0.12)' },
  documentExtract: { border: '#22c55e', bg: 'rgba(34, 197, 94, 0.12)' },
  condition: { border: '#f59e0b', bg: 'rgba(245, 158, 11, 0.12)' },
  variable: { border: '#f59e0b', bg: 'rgba(245, 158, 11, 0.12)' },
  aggregate: { border: '#f59e0b', bg: 'rgba(245, 158, 11, 0.12)' },
  loop: { border: '#f59e0b', bg: 'rgba(245, 158, 11, 0.12)' },
}

export function useWorkflowStudioPalette(deps: UseWorkflowStudioPaletteDeps) {
  const { nodeSearchKeyword, activePaletteGroup, paletteExpanded, nodeTypes, graphNodeTypeCapabilitiesLoaded } = deps

  const graphNodeCompositionByKind = computed(() => studioNodeCapabilityMap(nodeTypes.value))
  const enabledPaletteKinds = computed(() =>
    enabledStudioNodeKinds(nodeTypes.value, graphNodeTypeCapabilitiesLoaded.value),
  )

  const paletteGroups = computed<WorkflowStudioPaletteGroup[]>(() =>
    STUDIO_NODE_GROUPS.map((group) => ({
      title: group.title,
      icon: groupIconMap[group.icon] || Operation,
      items: Object.values(STUDIO_NODE_REGISTRY)
        .filter((item) =>
          item.group === group.title
          && item.category !== 'system'
          && enabledPaletteKinds.value.has(item.kind),
        )
        .map((item) => {
          const capability = graphNodeCompositionByKind.value[item.kind]
          return {
            kind: item.kind,
            label: item.label,
            meta: capability?.type || item.meta,
            icon: nodeIconMap[item.kind],
            hint: item.hint,
          }
        }),
    })).filter((group) => group.items.length),
  )

  const filteredPaletteGroups = computed<WorkflowStudioPaletteGroup[]>(() => {
    const keyword = nodeSearchKeyword.value.trim().toLowerCase()
    const groups = paletteGroups.value
    if (!keyword) return groups
    return groups
      .map((group) => ({
        ...group,
        items: group.items.filter((item) =>
          [item.label, item.meta, item.hint, item.kind, group.title].some((value) =>
            String(value).toLowerCase().includes(keyword),
          ),
        ),
      }))
      .filter((group) => group.items.length > 0)
  })

  const flatFilteredPalette = computed(() => filteredPaletteGroups.value.flatMap((group) => group.items))

  function openPaletteGroup(title: string) {
    if (activePaletteGroup.value === title && paletteExpanded.value) {
      paletteExpanded.value = false
      return
    }
    activePaletteGroup.value = title
    paletteExpanded.value = true
  }

  function kindColor(kind: CanvasNodeKind) {
    return kindColorMap[kind as keyof typeof kindColorMap] || { border: '#6f63ff', bg: 'rgba(111, 99, 255, 0.12)' }
  }

  function nodeIcon(kind: CanvasNodeKind): Component {
    return nodeIconMap[kind]
  }

  return {
    paletteGroups,
    filteredPaletteGroups,
    flatFilteredPalette,
    openPaletteGroup,
    kindColor,
    nodeIcon,
  }
}
