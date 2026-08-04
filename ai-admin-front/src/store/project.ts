import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { getScanProjects } from '@/api/scanProject'
import type { ScanProject } from '@/types/scanProject'

export const useProjectStore = defineStore('project', () => {
  const projects = ref<ScanProject[]>([])
  const loading = ref(false)
  const currentProjectId = ref<number | null>(null)

  const currentProject = computed(() =>
    projects.value.find((p) => p.id === currentProjectId.value) || null,
  )

  const currentProjectCode = computed(() => currentProject.value?.projectCode || null)

  async function fetchProjects() {
    loading.value = true
    try {
      const { data } = await getScanProjects()
      projects.value = Array.isArray(data) ? data : []
      if (
        currentProjectId.value !== null
        && !projects.value.some((p) => p.id === currentProjectId.value)
      ) {
        clearCurrentProject()
      }
    } catch {
      projects.value = []
    } finally {
      loading.value = false
    }
  }

  function selectCurrentProject(projectId: number | null) {
    currentProjectId.value = projectId
  }

  function clearCurrentProject(projectId?: number) {
    if (projectId !== undefined && currentProjectId.value !== projectId) return
    currentProjectId.value = null
  }

  function projectLabel(project?: ScanProject | null) {
    if (!project) return '未选择项目'
    const code = project.projectCode ? ` / ${project.projectCode}` : ''
    const env = project.environment ? ` · ${project.environment}` : ''
    return `${project.name}${code}${env}`
  }

  return {
    projects,
    loading,
    currentProjectId,
    currentProject,
    currentProjectCode,
    fetchProjects,
    selectCurrentProject,
    clearCurrentProject,
    projectLabel,
  }
})
