<template>
  <WorkbenchPanel
    v-show="isNew || activeConfigSection === 'skills'"
    id="agent-config-skills"
    class="skills-panel"
    title="Skill"
    density="compact"
  >
    <template #actions>
      <el-button v-if="canBindSkills" type="primary" :icon="Plus" @click="openSkillPicker()">
        添加 Skill
      </el-button>
    </template>

    <div v-if="!skillBindings.length" class="capability-empty-state">
      <span class="capability-empty-state__icon is-skill"><el-icon><Guide /></el-icon></span>
      <strong>暂未添加 Skill</strong>
    </div>

    <el-table
      v-else
      :data="skillBindings"
      row-key="skillVersionId"
      class="skill-binding-table"
      size="small"
    >
      <el-table-column label="Skill" min-width="220">
        <template #default="{ row }">
          <div class="skill-binding-identity">
            <strong>{{ row.displayName || row.name }}</strong>
            <code>{{ row.publisher }}/{{ row.name }}</code>
            <el-tag :type="row.publisher === 'reachai' ? 'success' : 'warning'" size="small">
              {{ row.publisher === 'reachai' ? '内置可信' : '发布者未验证' }}
            </el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="使用版本" width="128">
        <template #default="{ row }">
          <div class="skill-binding-version">
            <code>{{ row.version }}</code>
            <el-tag v-if="row.hasScripts" type="warning" size="small">含脚本</el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="使用方式" min-width="168">
        <template #default="{ row }">
          <el-select v-model="row.activationMode" size="small" :disabled="!canBindSkills">
            <el-option label="需要时自动使用" value="MODEL_SELECTED" />
            <el-option label="每次都使用" value="ALWAYS" />
            <el-option label="仅明确要求时使用（暂未开放）" value="EXPLICIT" disabled />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="脚本状态" width="112">
        <template #default="{ row }">
          <el-tooltip
            :content="row.hasScripts ? '当前版本只加载说明和资源，脚本暂不执行。' : '该版本不含脚本。'"
            placement="top"
          >
            <el-tag :type="row.hasScripts ? 'warning' : 'info'" size="small">
              {{ row.hasScripts ? '脚本不执行' : '不含脚本' }}
            </el-tag>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="必须加载" width="84" align="center">
        <template #default="{ row }">
          <el-tooltip content="开启后，如果这个 Skill 无法加载，本次 Agent 运行会直接停止。" placement="top">
            <el-switch v-model="row.required" size="small" :disabled="!canBindSkills" @change="handleSkillRequiredChange(row)" />
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="可用" width="68" align="center">
        <template #default="{ row }">
          <el-switch v-model="row.enabled" size="small" :disabled="row.required || !canBindSkills" />
        </template>
      </el-table-column>
      <el-table-column v-if="canBindSkills" label="" width="176" align="right">
        <template #default="{ $index }">
          <div class="table-row-actions">
            <el-button link @click="openSkillPicker($index)">更换版本</el-button>
            <el-button link :icon="ArrowUp" :disabled="$index === 0" @click="moveSkillBinding($index, -1)" />
            <el-button
              link
              :icon="ArrowDown"
              :disabled="$index === skillBindings.length - 1"
              @click="moveSkillBinding($index, 1)"
            />
            <el-button link type="danger" :icon="Delete" @click="removeSkillBinding($index)" />
          </div>
        </template>
      </el-table-column>
    </el-table>
  </WorkbenchPanel>

  <AppDialog
    v-model="skillPickerVisible"
    :title="skillReplaceTargetIndex == null ? '添加 Skill' : '更换 Skill 版本'"
    description="只显示已经评审并发布的版本。保存后将固定使用所选版本，不会自动升级。"
    width="920px"
    append-to-body
    destroy-on-close
  >
    <div class="skill-picker-toolbar">
      <el-input
        v-model="skillPickerKeyword"
        clearable
        :prefix-icon="Search"
        placeholder="搜索名称、发布方或用途"
        @keyup.enter="loadSkillPicker"
        @clear="loadSkillPicker"
      />
      <el-button :icon="Search" :loading="skillPickerLoading" @click="loadSkillPicker">搜索</el-button>
    </div>
    <LiquidGlassNotice
      v-if="skillReplaceTargetIndex != null"
      type="info"
      title="更换时只能选择同一个 Skill 的其他版本；如需更换来源，请先移除原有 Skill。"
      source="backdrop"
    />
    <el-table
      v-loading="skillPickerLoading"
      :data="skillPickerRows"
      row-key="rowKey"
      class="skill-picker-table"
      max-height="430"
    >
      <el-table-column label="Skill" min-width="230">
        <template #default="{ row }">
          <div class="skill-binding-identity">
            <strong>{{ row.skill.displayName || row.skill.name }}</strong>
            <code>{{ row.skill.publisher }}/{{ row.skill.name }}</code>
            <el-tag :type="row.skill.publisher === 'reachai' ? 'success' : 'warning'" size="small">
              {{ row.skill.publisher === 'reachai' ? '内置可信' : '发布者未验证' }}
            </el-tag>
            <small>{{ row.skill.description || '未提供说明' }}</small>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="版本" width="130">
        <template #default="{ row }"><code>{{ row.version.version }}</code></template>
      </el-table-column>
      <el-table-column label="来源" width="110">
        <template #default="{ row }">{{ skillSourceLabel(row.version.sourceType) }}</template>
      </el-table-column>
      <el-table-column label="内容类型" min-width="168">
        <template #default="{ row }">
          <el-tag v-if="row.version.hasScripts" type="warning" size="small">包含脚本，当前不执行</el-tag>
          <el-tag v-else type="success" size="small">仅说明与资源</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="112" fixed="right" align="right">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            :disabled="!canChooseSkillRow(row)"
            @click="chooseSkillRow(row)"
          >
            {{ canChooseSkillRow(row) ? (skillReplaceTargetIndex == null ? '添加' : '选用此版本') : '已添加' }}
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="没有可添加的已发布 Skill" :image-size="72" />
      </template>
    </el-table>
    <template #footer>
      <LiquidGlassButton @click="skillPickerVisible = false">关闭</LiquidGlassButton>
      <LiquidGlassButton @click="router.push('/skills')">打开 Skill 管理</LiquidGlassButton>
    </template>
  </AppDialog>
</template>

<script setup lang="ts">
import { ArrowDown, ArrowUp, Delete, Guide, Plus, Search } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import { useAgentEditorContext } from './useAgentEditor'
import { LiquidGlassNotice, LiquidGlassButton } from '@/components/common/liquid-glass'

const {
  router,
  isNew,
  activeConfigSection,
  skillBindings,
  skillPickerVisible,
  skillPickerKeyword,
  skillPickerLoading,
  skillPickerRows,
  skillReplaceTargetIndex,
  canBindSkills,
  openSkillPicker,
  loadSkillPicker,
  canChooseSkillRow,
  chooseSkillRow,
  handleSkillRequiredChange,
  moveSkillBinding,
  removeSkillBinding,
  skillSourceLabel,
} = useAgentEditorContext()
</script>

<style scoped lang="scss">
.table-row-actions {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 2px;
}

.capability-empty-state {
  display: grid;
  min-height: 280px;
  place-items: center;
  align-content: center;
  gap: 12px;
  color: var(--text-primary);
}

.capability-empty-state__icon,
.config-asset-card__icon {
  display: grid;
  width: 44px;
  height: 44px;
  flex: 0 0 44px;
  place-items: center;
  border-radius: 12px;
  color: var(--status-info);
  background: color-mix(in srgb, var(--status-info) 10%, var(--surface-solid-control));
  font-size: 20px;
}

.capability-empty-state__icon.is-collaboration,
.config-asset-card__icon.is-collaboration {
  color: var(--brand-active);
  background: var(--brand-selected-bg);
}

.capability-empty-state__icon.is-skill {
  color: var(--status-info);
}

.capability-empty-state strong {
  font-size: 14px;
  line-height: 22px;
}

.config-asset-card__actions .el-button {
  padding: 6px;
}

.workbench-layout:not(.has-section-nav) .capability-empty-state {
  min-height: 140px;
}

.workflow-picker-searchbar .el-input {
  flex: 1;
  min-width: 0;
}

.skill-binding-identity,
.skill-binding-version {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.skill-binding-identity strong {
  overflow: hidden;
  color: var(--text-primary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-binding-identity code,
.skill-binding-version code {
  color: var(--brand-primary);
  font-size: 12px;
}

.skill-binding-identity .el-tag {
  justify-self: start;
}

.skill-binding-identity small {
  overflow: hidden;
  color: var(--text-muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-binding-version {
  justify-items: start;
}

.skill-picker-toolbar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  margin-bottom: 12px;
}

.skill-picker-table {
  margin-top: 12px;
}

@media (max-width: 960px) {
  .workflow-tool-zero-state .el-button {
      width: 100%;
    }
}
</style>
