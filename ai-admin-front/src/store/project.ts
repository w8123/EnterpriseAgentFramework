import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { getScanProjects } from '@/api/scanProject'
import type { ScanProject } from '@/types/scanProject'

const STORAGE_KEY = 'eaf.currentProjectId'

export const useProjectStore = defineStore('project', () => {
  const projects = ref<ScanProject[]>([])
  const loading = ref(false)
  const currentProjectId = ref<number | null>(readStoredProjectId())

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
        setCurrentProject(null)
      }
    } catch {
      projects.value = []
    } finally {
      loading.value = false
    }
  }

  function setCurrentProject(projectId: number | null) {
    currentProjectId.value = projectId
    if (projectId === null) {
      localStorage.removeItem(STORAGE_KEY)
    } else {
      localStorage.setItem(STORAGE_KEY, String(projectId))
    }
  }

  function projectLabel(project?: ScanProject | null) {
    if (!project) return '全部项目'
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
    setCurrentProject,
    projectLabel,
  }
})

function readStoredProjectId() {
  const raw = localStorage.getItem(STORAGE_KEY)
  if (!raw) return null
  const id = Number(raw)
  return Number.isFinite(id) ? id : null
}
