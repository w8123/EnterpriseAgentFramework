import type { CatalogClassificationGroup, CatalogClassificationIndex, CatalogClassificationSubjectType } from '@/types/semanticDoc'

export type ClassificationSection<Row> = { key: string; title: string; group?: CatalogClassificationGroup;
  memberCount?: number; rows: Row[] }
const positive = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) > 0
const nonnegative = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) >= 0

export function validateClassificationIndex(value: unknown, projectId: number, subjectType: CatalogClassificationSubjectType): value is CatalogClassificationIndex {
  if (!value || typeof value !== 'object') return false
  const data = value as CatalogClassificationIndex
  if (data.projectId !== projectId || data.subjectType !== subjectType || !Array.isArray(data.groups)
    || !Array.isArray(data.memberships) || !nonnegative(data.ungroupedCount)) return false
  const groups = new Set<number>(), subjects = new Set<number>()
  for (const group of data.groups) {
    if (!group || !positive(group.id) || group.projectId !== projectId || groups.has(group.id)
      || typeof group.name !== 'string' || typeof group.displayName !== 'string'
      || !Array.isArray(group.sourceClasses) || !nonnegative(group.memberCount)) return false
    groups.add(group.id)
  }
  const counts = new Map<number, number>()
  let ungroupedCount = 0
  for (const member of data.memberships) {
    if (!member || !positive(member.subjectId) || subjects.has(member.subjectId) || !Array.isArray(member.groupIds)
      || new Set(member.groupIds).size !== member.groupIds.length || member.groupIds.some(id => !groups.has(id))) return false
    subjects.add(member.subjectId)
    if (!member.groupIds.length) ungroupedCount += 1
    for (const id of member.groupIds) counts.set(id, (counts.get(id) || 0) + 1)
  }
  // The index is complete. A page-shaped response must not advertise project-wide counts.
  if (data.ungroupedCount !== ungroupedCount
    || data.groups.some(group => group.memberCount !== (counts.get(group.id) || 0))) return false
  return true
}

const lookups = new WeakMap<CatalogClassificationIndex, {
  groups: Map<number, CatalogClassificationGroup>; memberships: Map<number, number[]>; ranks: Map<number, number>;
  sourceGroups: CatalogClassificationIndex['groups']; sourceMemberships: CatalogClassificationIndex['memberships']
}>()
function classificationLookup(index: CatalogClassificationIndex) {
  let lookup = lookups.get(index)
  if (!lookup || lookup.sourceGroups !== index.groups || lookup.sourceMemberships !== index.memberships) {
    lookup = { groups: new Map(index.groups.map(group => [group.id, group])),
      memberships: new Map(index.memberships.map(member => [member.subjectId, member.groupIds])),
      ranks: new Map(index.groups.map((group, rank) => [group.id, rank])), sourceGroups: index.groups, sourceMemberships: index.memberships }
    lookups.set(index, lookup)
  }
  return lookup
}
export function classificationMemberships(index: CatalogClassificationIndex | null, subjectId: number): CatalogClassificationGroup[] {
  if (!index) return []
  const lookup = classificationLookup(index)
  return (lookup.memberships.get(subjectId) || []).map(id => lookup.groups.get(id)).filter((group): group is CatalogClassificationGroup => !!group)
}

export function classificationNavigationGroups(index: CatalogClassificationIndex | null, selected: string,
  adjusting = false): CatalogClassificationGroup[] {
  return index?.groups.filter(group => adjusting || group.memberCount > 0 || String(group.id) === selected) ?? []
}

/** A page is only arranged here; the server alone owns category filtering, totals and pagination. */
export function buildClassificationSections<Row>(rows: Row[], index: CatalogClassificationIndex | null,
  selected: string, identity: (row: Row) => number): ClassificationSection<Row>[] {
  const sections = new Map<string, ClassificationSection<Row>>()
  const lookup = index ? classificationLookup(index) : null
  const groupMap = lookup?.groups || new Map<number, CatalogClassificationGroup>()
  const memberMap = lookup?.memberships || new Map<number, number[]>()
  for (const row of rows) {
    const memberships = memberMap.get(identity(row))
    const known = memberships !== undefined && memberships.every(id => groupMap.has(id))
    let key = 'pending', group: CatalogClassificationGroup | undefined
    if (known) {
      if (selected === 'unclassified') key = memberships.length ? 'pending' : 'unclassified'
      else if (selected) { group = groupMap.get(Number(selected)); key = group && memberships.includes(group.id) ? String(group.id) : 'pending' }
      else if (memberships.length) {
        const firstId = memberships.reduce((first, id) => (lookup?.ranks.get(id) ?? Infinity) < (lookup?.ranks.get(first) ?? Infinity) ? id : first)
        group = groupMap.get(firstId); key = String(group?.id ?? 'pending')
      }
      else key = 'unclassified'
    }
    if (!sections.has(key)) sections.set(key, { key, group, title: group?.displayName || group?.name
      || (key === 'unclassified' ? '未分类' : '分类待更新'), memberCount: group?.memberCount
      ?? (key === 'unclassified' ? index?.ungroupedCount : undefined), rows: [] })
    sections.get(key)!.rows.push(row)
  }
  const selectedGroup = selected ? groupMap.get(Number(selected)) : undefined
  if (selectedGroup && !sections.has(selected)) sections.set(selected, { key: selected, title: selectedGroup.displayName || selectedGroup.name,
    group: selectedGroup, memberCount: selectedGroup.memberCount, rows: [] })
  const rank = (key: string) => key === 'pending' ? Number.MAX_SAFE_INTEGER : key === 'unclassified' ? Number.MAX_SAFE_INTEGER - 1
    : lookup?.ranks.get(Number(key)) ?? 0
  return [...sections.values()].sort((a, b) => rank(a.key) - rank(b.key))
}
