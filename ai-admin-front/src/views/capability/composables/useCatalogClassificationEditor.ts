import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { platformSessionId, platformSessionUser } from '@/auth/platformSession'
import { hasPlatformResourcePermission, PLATFORM_PERMISSION_WRITE } from '@/auth/platformAccess'
import { getClassificationAssignments, replaceClassifications, type ClassificationAssignment, editSubjectSemanticDoc, generateSubjectSemanticDoc, getCapabilityEnrichment,
  createScanModule, renameScanModule, listScanModules } from '@/api/semanticDoc'
import { getModelInstances } from '@/api/model'
import { normalizeActiveModelInstances } from '@/utils/modelSelection'
import type { ModelInstance } from '@/types/model'
import type { CapabilityEnrichment, ScanModule, SemanticDoc } from '@/types/semanticDoc'

export interface ClassificationSubject { subjectType: 'BUSINESS_METHOD' | 'HTTP_API'; subjectId: number; title?: string }
export interface ClassificationEditorProps {
  visible: boolean
  projectId: number
  projectCode?: string
  groups: ScanModule[]
  group: ScanModule | null
  mode: 'description' | 'rename' | 'assign' | 'create'
  subject?: ClassificationSubject
  subjects?: ClassificationSubject[]
}

export function useCatalogClassificationEditor(props: ClassificationEditorProps,
  emit: (event: 'update:visible' | 'updated' | 'busy-change', value?: boolean) => void) {
  const enrichment = ref<CapabilityEnrichment | null>(null)
  const loading = ref(false)
  const error = ref('')
  const mutationError = ref('')
  const status = ref('')
  const busy = ref(false)
  const editing = ref(false)
  const draft = ref('')
  const expectedUpdatedAt = ref<string | null>(null)
  const conflict = ref(false)
  const renameValue = ref('')
  const originalName = ref('')
  const createName = ref('')
  const createValid = computed(() => !!createName.value.trim() && createName.value.trim().length <= 128
    && !/[\x00-\x1f\x7f]/.test(createName.value))
  const assignGroups = ref<(number | string)[]>([])
  const assignmentGroups = ref<ScanModule[] | null>(null)
  const assignments = ref<ClassificationAssignment[]>([])
  const originalAssignment = ref('[]')
  const assignmentReady = ref(false)
  const mixedAssignment = ref(false)
  const assignmentTouched = ref(false)
  watch(assignGroups, () => { if (assignmentReady.value) assignmentTouched.value = true }, { deep: true, flush: 'sync' })
  function clearAssignments() { assignGroups.value = []; assignmentTouched.value = true }
  const selectedSubjects = computed(() => props.subjects?.length ? props.subjects : props.subject ? [props.subject] : [])
  const assignmentKey = computed(() => JSON.stringify(selectedSubjects.value.map(item => [item.subjectType, item.subjectId])))
  const assignmentValid = computed(() => assignmentReady.value && !loading.value && !conflict.value && !error.value
    && (!mixedAssignment.value || assignmentTouched.value)
    && selectedSubjects.value.length > 0 && assignGroups.value.length <= 30 && assignGroups.value.every(value => typeof value === 'number'
      ? groups.value.some(group => group.id === value) : !!value.trim() && value.trim().length <= 128 && !/[\x00-\x1f\x7f]/.test(value)))
  const generationOpen = ref(false)
  const models = ref<ModelInstance[]>([])
  const modelId = ref('')
  const modelsLoading = ref(false)
  const modelsError = ref('')
  const leaveOpen = ref(false)
  let leaveResolve: ((value: boolean) => void) | null = null
  let sequence = 0
  let initialized = false
  const canWrite = computed(() => Number.isSafeInteger(props.projectId) && props.projectId > 0 && !!props.projectCode && hasPlatformResourcePermission(
    platformSessionUser.value?.permissionGrants, PLATFORM_PERMISSION_WRITE, 'PROJECT', null, props.projectCode))
  const groups = computed(() => Array.from(new Map((assignmentGroups.value || props.groups).filter(group =>
    group.projectId === props.projectId && Number.isSafeInteger(group.id) && group.id > 0).map(group => [group.id, group])).values()))
  const validGroup = computed(() => !!props.group && props.group.projectId === props.projectId
    && groups.value.some(group => group.id === props.group?.id))
  const dirty = computed(() => props.visible && (props.mode === 'description'
    ? editing.value && draft.value !== (enrichment.value?.semanticDoc?.contentMd || '')
    : props.mode === 'create' ? !!createName.value
    : props.mode === 'rename' ? renameValue.value !== originalName.value
      : (mixedAssignment.value && assignmentTouched.value) || JSON.stringify(assignGroups.value) !== originalAssignment.value))
  const title = computed(() => ({ description: '业务分类说明', rename: '重命名业务分类', create: '新增分类', assign: selectedSubjects.value.length > 1 ? `批量归类（${selectedSubjects.value.length} 项）` : '调整业务分类' })[props.mode])

  function reset() {
    sequence += 1; enrichment.value = null; loading.value = false; busy.value = false
    error.value = ''; mutationError.value = ''; status.value = ''; editing.value = false; draft.value = ''; expectedUpdatedAt.value = null; conflict.value = false
    originalName.value = props.group?.displayName || props.group?.name || ''; renameValue.value = originalName.value; createName.value = ''
    assignGroups.value = []; assignmentGroups.value = null; assignments.value = []; originalAssignment.value = '[]'; assignmentReady.value = false; mixedAssignment.value = false; assignmentTouched.value = false
    generationOpen.value = false; models.value = []; modelId.value = ''; modelsLoading.value = false; modelsError.value = ''
    leaveOpen.value = false; leaveResolve?.(false); leaveResolve = null
  }
  const documentMatches = (doc: SemanticDoc) => doc.projectId === props.projectId && doc.subjectType === 'GROUP' && doc.subjectId === props.group?.id
  function validEnrichment(data: CapabilityEnrichment, type: 'GROUP' | 'BUSINESS_METHOD' | 'HTTP_API', id: number) {
    return data.projectId === props.projectId && data.subjectType === type && data.subjectId === id
      && (!data.semanticDoc || data.semanticDoc.projectId === props.projectId && data.semanticDoc.subjectType === type && data.semanticDoc.subjectId === id)
  }
  async function read(current: number) {
    if (!validGroup.value || !props.group) throw new Error('Invalid classification owner')
    const { data } = await getCapabilityEnrichment(props.projectId, 'GROUP', props.group.id)
    if (current !== sequence || !props.visible) return false
    if (!validEnrichment(data, 'GROUP', props.group.id)) throw new Error('Invalid classification document owner')
    enrichment.value = data; error.value = ''
    return true
  }
  async function load() {
    if (!props.visible || busy.value || loading.value || props.mode !== 'description') return
    const current = sequence; loading.value = true; error.value = ''
    try {
      if (await read(current) && editing.value) {
        expectedUpdatedAt.value = enrichment.value?.semanticDoc?.updatedAt || null; conflict.value = false
        status.value = '已重读最新说明；你的输入已保留，请核对最新正文后保存。'
      }
    } catch { if (current === sequence) { enrichment.value = null; error.value = '分类说明读取失败，请重新加载核实当前分类。' } }
    finally { if (current === sequence) loading.value = false }
  }
  function startEdit() {
    if (!canWrite.value || busy.value || loading.value || !enrichment.value?.semanticDoc || error.value) return
    draft.value = enrichment.value.semanticDoc.contentMd || ''; expectedUpdatedAt.value = enrichment.value.semanticDoc.updatedAt || null
    editing.value = true; generationOpen.value = false; mutationError.value = ''; status.value = ''; conflict.value = false
  }
  async function saveDescription() {
    if (!props.visible || !canWrite.value || !validGroup.value || !editing.value || busy.value || loading.value || conflict.value || !expectedUpdatedAt.value) return
    const current = sequence; busy.value = true; mutationError.value = ''; status.value = ''
    try {
      const { data } = await editSubjectSemanticDoc(props.projectId, 'GROUP', props.group!.id,
        { contentMd: draft.value, expectedUpdatedAt: expectedUpdatedAt.value })
      if (current !== sequence) return
      if (!documentMatches(data)) throw new Error('Invalid saved document owner')
      if (await read(current)) { editing.value = false; draft.value = ''; status.value = '分类说明已保存并重读。'; emit('updated') }
    } catch (cause) {
      if (current !== sequence) return
      conflict.value = (cause as { response?: { status?: number } })?.response?.status === 409
      mutationError.value = conflict.value ? '说明已被更新；输入已保留，请重读最新说明并核对。' : '说明保存或回读失败；输入已保留，请重读最新说明确认结果。'
    } finally { if (current === sequence) busy.value = false }
  }
  async function prepareGeneration() {
    if (!props.visible || !canWrite.value || !validGroup.value || busy.value || loading.value || editing.value || modelsLoading.value || error.value) return
    generationOpen.value = true; modelsLoading.value = true; modelsError.value = ''; models.value = []
    const current = sequence
    try { const { data } = await getModelInstances({ modelType: 'LLM' }); if (current !== sequence) return
      models.value = normalizeActiveModelInstances(data, 'LLM'); if (!models.value.some(model => model.id === modelId.value)) modelId.value = ''
    } catch { if (current === sequence) modelsError.value = '模型列表读取失败，请重试。' }
    finally { if (current === sequence) modelsLoading.value = false }
  }
  async function generate() {
    if (!props.visible || !canWrite.value || !validGroup.value || busy.value || loading.value || modelsLoading.value || editing.value || !models.value.some(model => model.id === modelId.value)) return
    const current = sequence; busy.value = true; mutationError.value = ''; status.value = ''
    try { const { data } = await generateSubjectSemanticDoc(props.projectId, 'GROUP', props.group!.id, true, { modelInstanceId: modelId.value })
      if (current !== sequence) return
      if (!documentMatches(data)) throw new Error('Invalid generated document owner')
      if (await read(current)) { generationOpen.value = false; status.value = '分类说明已生成并重读。'; emit('updated') }
    } catch { if (current === sequence) mutationError.value = '生成或回读失败，请重读说明确认当前结果。' }
    finally { if (current === sequence) busy.value = false }
  }
  function complete() { if (!props.visible) return; ElMessage.success('业务分类已保存'); emit('updated'); reset(); emit('update:visible', false) }
  async function createGroup() {
    if (!props.visible || props.mode !== 'create' || !canWrite.value || busy.value || !createValid.value) return
    const current = sequence; busy.value = true; mutationError.value = ''
    try {
      const { data } = await createScanModule(props.projectId, createName.value.trim())
      if (current !== sequence) return
      if (data.projectId !== props.projectId || !Number.isSafeInteger(data.id) || data.id < 1) throw new Error('Invalid created classification owner')
      ElMessage.success(groups.value.some(group => group.id === data.id) ? '分类已存在，可继续归类' : '分类已保存')
      emit('updated'); reset(); emit('update:visible', false)
    } catch (cause) {
      if (current !== sequence) return
      mutationError.value = (cause as { response?: { status?: number } })?.response?.status === 409
        ? '该分类名称已存在，请修改名称。' : '新增分类未确认；输入已保留，请刷新分类确认结果后重试。'
    } finally { if (current === sequence) busy.value = false }
  }
  async function saveName() {
    if (!props.visible || !canWrite.value || !validGroup.value || busy.value || !renameValue.value.trim()) return
    const current = sequence; busy.value = true; mutationError.value = ''
    try { const { data } = await renameScanModule(props.projectId, props.group!.id, renameValue.value.trim())
      if (current !== sequence) return
      if (data.projectId !== props.projectId || data.id !== props.group!.id) throw new Error('Invalid renamed classification owner')
      complete()
    } catch { if (current === sequence) mutationError.value = '分类重命名失败；输入已保留，请重试。' }
    finally { if (current === sequence) busy.value = false }
  }
  async function loadAssignments(preserveDraft = false) {
    if (!props.visible || busy.value || loading.value || props.mode !== 'assign') return
    const current = sequence; loading.value = true; error.value = ''; assignmentReady.value = false
    try {
      const subjects = selectedSubjects.value
      if (!subjects.length || subjects.length > 100 || new Set(subjects.map(item => item.subjectId)).size !== subjects.length
        || subjects.some(item => item.subjectType !== subjects[0].subjectType || !Number.isSafeInteger(item.subjectId) || item.subjectId < 1)) throw new Error('Invalid owners')
      const [{ data: latestGroups }, { data }] = await Promise.all([
        listScanModules(props.projectId),
        getClassificationAssignments(props.projectId, subjects[0].subjectType, subjects.map(subject => subject.subjectId)),
      ])
      if (current !== sequence || !props.visible) return
      if (!Array.isArray(latestGroups) || latestGroups.some(group => group.projectId !== props.projectId
        || !Number.isSafeInteger(group.id) || group.id <= 0)) throw new Error('Invalid classification project')
      assignmentGroups.value = latestGroups
      const result: ClassificationAssignment[] = []
      const owners = new Set(subjects.map(subject => subject.subjectId)), returned = new Set<number>()
      if (!Array.isArray(data) || data.length !== subjects.length) throw new Error('Incomplete classifications')
      for (const assignment of data) {
        if (assignment.projectId !== props.projectId || assignment.subjectType !== subjects[0].subjectType
          || !owners.has(assignment.subjectId) || returned.has(assignment.subjectId)
          || !Number.isSafeInteger(assignment.revision) || assignment.revision < 0 || !Array.isArray(assignment.groupIds)
          || assignment.groupIds.some(id => !groups.value.some(group => group.id === id))) throw new Error('Invalid classification owner')
        returned.add(assignment.subjectId); result.push(assignment)
      }
      assignments.value = result
      const first = [...result[0].groupIds].sort((a, b) => a - b)
      mixedAssignment.value = result.some(item => JSON.stringify([...item.groupIds].sort((a, b) => a - b)) !== JSON.stringify(first))
      if (!preserveDraft) {
        assignGroups.value = mixedAssignment.value ? [] : first
        originalAssignment.value = JSON.stringify(assignGroups.value)
      } else status.value = '已重读当前归属；你的选择已保留，请核对后保存。'
      assignmentReady.value = true; conflict.value = false; mutationError.value = ''
    } catch { if (current === sequence) error.value = '分类归属读取失败，请重试后核对当前归属。' }
    finally { if (current === sequence) loading.value = false }
  }
  async function assign() {
    if (!props.visible || !canWrite.value || busy.value || !assignmentValid.value) return
    const current = sequence; busy.value = true; mutationError.value = ''
    try {
      const groupIds = assignGroups.value.filter((value): value is number => typeof value === 'number')
      const newNames = assignGroups.value.filter((value): value is string => typeof value === 'string').map(value => value.trim())
      const { data } = await replaceClassifications(props.projectId, selectedSubjects.value[0].subjectType, {
        subjects: assignments.value.map(item => ({ subjectId: item.subjectId, expectedRevision: item.revision })), groupIds, newNames })
      if (current !== sequence) return
      if (!Array.isArray(data) || data.length !== assignments.value.length || assignments.value.some(expected => !data.some(item =>
        item.projectId === expected.projectId && item.subjectType === expected.subjectType && item.subjectId === expected.subjectId
        && Number.isSafeInteger(item.revision) && item.revision >= expected.revision))) throw new Error('Invalid saved classification')
      complete()
    } catch (cause) {
      if (current !== sequence) return
      conflict.value = true; assignmentReady.value = false
      mutationError.value = (cause as { response?: { status?: number } })?.response?.status === 409
        ? '部分能力的分类已变化；选择已保留，请重读归属并核对后保存。'
        : '归类保存未确认；选择已保留，请重读归属确认结果后再保存。'
    } finally { if (current === sequence) busy.value = false }
  }
  async function requestClose(): Promise<boolean> {
    if (!props.visible) return true
    if (busy.value) return false
    if (!dirty.value) { reset(); emit('update:visible', false); return true }
    return await requestLeave()
  }
  function requestLeave() {
    if (!props.visible) return true
    if (busy.value) return false
    if (!dirty.value) return true
    if (leaveResolve) return false
    leaveOpen.value = true
    return new Promise<boolean>(resolve => { leaveResolve = resolve })
  }
  function cancelLeave() { leaveOpen.value = false; leaveResolve?.(false); leaveResolve = null }
  function discardLeave() {
    const resolve = leaveResolve
    leaveResolve = null; reset(); emit('update:visible', false); resolve?.(true)
  }
  function beforeUnload(event: BeforeUnloadEvent) { if (busy.value || dirty.value) { event.preventDefault(); event.returnValue = '' } }
  window.addEventListener('beforeunload', beforeUnload)
  watch(busy, value => emit('busy-change', value), { flush: 'sync' })
  watch([() => props.projectId, () => props.projectCode, platformSessionId, () => platformSessionUser.value?.userId], () => {
    const changed = initialized; initialized = true; reset()
    if (changed && props.visible) emit('update:visible', false)
  }, { immediate: true, flush: 'sync' })
  watch(canWrite, value => {
    if (value || !props.visible) return
    // Permission changes within this identity must disable writes without discarding input.
    sequence += 1; busy.value = false; loading.value = false; modelsLoading.value = false; assignmentReady.value = false
    status.value = '当前项目写权限已变化；输入已保留，请恢复权限后重读当前内容再保存。'
  }, { flush: 'sync' })
  watch([() => props.visible, () => props.group?.id, () => props.group?.projectId, () => props.mode, assignmentKey], () => {
    reset(); if (props.visible && props.mode === 'description') void load()
    if (props.visible && props.mode === 'assign') void loadAssignments()
  }, { immediate: true, flush: 'sync' })
  onBeforeUnmount(() => { sequence += 1; leaveResolve?.(false); window.removeEventListener('beforeunload', beforeUnload); emit('busy-change', false) })
  return { enrichment, loading, error, mutationError, status, busy, editing, draft, expectedUpdatedAt, conflict, renameValue, createName, createValid, createGroup,
    assignGroups, assignmentValid, assignmentReady, mixedAssignment, selectedSubjects, loadAssignments, clearAssignments, generationOpen, models, modelId, modelsLoading, modelsError, leaveOpen, canWrite, groups, validGroup,
    dirty, title, load, startEdit, saveDescription, prepareGeneration, generate, saveName, assign, requestClose, requestLeave, cancelLeave, discardLeave }
}
