<template>
  <section class="audit-panel">
    <div class="audit-toolbar">
      <div>
        <el-input v-model="keyword" clearable :prefix-icon="Search" placeholder="搜索操作人、对象或事件" />
        <el-select v-model="eventFilter" aria-label="事件类型">
          <el-option label="全部事件" value="ALL" />
          <el-option v-for="eventType in eventTypes" :key="eventType" :label="platformAuditEventLabel(eventType)" :value="eventType" />
        </el-select>
        <el-select v-model="targetFilter" aria-label="对象类型">
          <el-option label="全部对象" value="ALL" />
          <el-option label="平台账号" value="PLATFORM_USER" />
          <el-option label="平台角色" value="PLATFORM_ROLE" />
          <el-option label="认证源" value="AUTH_PROVIDER" />
        </el-select>
      </div>
      <span>展示最近 {{ events.length }} 条身份治理事件</span>
    </div>

    <div class="audit-table-shell">
      <el-table :data="filteredEvents" v-loading="loading" row-key="id">
        <el-table-column label="时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="210">
          <template #default="{ row }">
            <div class="event-cell">
              <span class="event-icon"><el-icon><DocumentChecked /></el-icon></span>
              <div>
                <strong>{{ platformAuditEventLabel(row.eventType) }}</strong>
                <code>{{ row.eventType }}</code>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作人" min-width="150">
          <template #default="{ row }">
            <strong>{{ row.actorUsername || '系统' }}</strong>
            <span v-if="row.actorUserId" class="subline">ID {{ row.actorUserId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="对象" min-width="180">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ targetLabel(row.targetType) }}</el-tag>
            <span class="target-id">{{ row.targetId || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="变更摘要" min-width="310">
          <template #default="{ row }">
            <div v-if="detailEntries(row).length" class="detail-list">
              <el-tag
                v-for="entry in detailEntries(row).slice(0, 4)"
                :key="entry[0]"
                size="small"
                type="info"
                effect="plain"
              >{{ detailLabel(entry[0]) }}: {{ entry[1] }}</el-tag>
              <el-popover v-if="detailEntries(row).length > 4" trigger="click" width="360">
                <template #reference>
                  <el-button link type="primary">另 {{ detailEntries(row).length - 4 }} 项</el-button>
                </template>
                <dl class="detail-popover">
                  <template v-for="entry in detailEntries(row)" :key="entry[0]">
                    <dt>{{ detailLabel(entry[0]) }}</dt><dd>{{ entry[1] }}</dd>
                  </template>
                </dl>
              </el-popover>
            </div>
            <span v-else class="muted">无可展示详情</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有符合条件的授权审计事件" />
        </template>
      </el-table>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="审计页面只展示经过脱敏的结构化摘要；密码、令牌、密钥和认证配置不会进入界面。"
    />
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { DocumentChecked, Search } from '@element-plus/icons-vue'
import type { PlatformAuthAuditEventView } from '@/api/platformAuth'
import { platformAuditEventLabel, safeAuditDetailEntries } from '@/auth/platformAccountManagement'

const props = defineProps<{
  events: PlatformAuthAuditEventView[]
  loading: boolean
}>()

const keyword = ref('')
const eventFilter = ref('ALL')
const targetFilter = ref('ALL')

const eventTypes = computed(() => [...new Set(props.events.map((event) => event.eventType))].sort())
const filteredEvents = computed(() => {
  const term = keyword.value.trim().toLowerCase()
  return props.events.filter((event) => {
    if (eventFilter.value !== 'ALL' && event.eventType !== eventFilter.value) return false
    if (targetFilter.value !== 'ALL' && event.targetType !== targetFilter.value) return false
    if (!term) return true
    return [event.eventType, platformAuditEventLabel(event.eventType), event.actorUsername, event.targetType, event.targetId]
      .some((value) => value?.toLowerCase().includes(term))
  })
})

function detailEntries(event: PlatformAuthAuditEventView) {
  return safeAuditDetailEntries(event)
}

const TARGET_LABELS: Record<string, string> = {
  PLATFORM_USER: '平台账号',
  PLATFORM_ROLE: '平台角色',
  AUTH_PROVIDER: '认证源',
}

const DETAIL_LABELS: Record<string, string> = {
  username: '用户名',
  roleCode: '角色编码',
  sourceProvider: '身份来源',
  status: '当前状态',
  previousStatus: '原状态',
  grantCount: '授权数',
  permissionCount: '权限数',
  revokedSessions: '撤销会话',
}

function targetLabel(targetType: string) {
  return TARGET_LABELS[targetType] || targetType
}

function detailLabel(key: string) {
  return DETAIL_LABELS[key] || key
}

function formatTime(value?: string) {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}
</script>

<style scoped lang="scss">
.audit-panel {
  display: grid;
  gap: 16px;
}

.audit-toolbar,
.audit-toolbar > div,
.event-cell,
.detail-list {
  display: flex;
  align-items: center;
  gap: 10px;
}

.audit-toolbar {
  justify-content: space-between;

  > div .el-input {
    width: 320px;
  }

  > div .el-select {
    width: 150px;
  }

  > span {
    color: var(--text-secondary);
    font-size: 12px;
  }
}

.audit-table-shell {
  overflow: hidden;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-lg);
  background: var(--surface-glass-panel);
}

.event-icon {
  display: grid;
  width: 32px;
  height: 32px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 9px;
  color: var(--brand-active);
  background: rgb(var(--brand-selected-rgb) / 0.56);
}

.event-cell > div {
  display: grid;
  gap: 2px;
}

.event-cell code,
.subline,
.target-id,
.muted {
  color: var(--text-secondary);
  font-size: 11px;
}

.subline {
  display: block;
}

.target-id {
  margin-left: 7px;
}

.detail-list {
  flex-wrap: wrap;
}

.detail-popover {
  display: grid;
  grid-template-columns: minmax(90px, auto) 1fr;
  gap: 8px 12px;
  margin: 0;

  dt {
    color: var(--text-secondary);
  }

  dd {
    margin: 0;
    word-break: break-all;
  }
}

@media (max-width: 900px) {
  .audit-toolbar,
  .audit-toolbar > div {
    align-items: stretch;
    flex-direction: column;
  }

  .audit-toolbar > div .el-input,
  .audit-toolbar > div .el-select {
    width: 100%;
  }
}
</style>
