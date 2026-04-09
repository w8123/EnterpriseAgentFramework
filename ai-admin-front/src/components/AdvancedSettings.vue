<template>
  <el-collapse>
    <el-collapse-item title="高级参数" name="advanced">
      <el-form
        :model="params"
        label-width="100px"
        label-position="left"
        class="form-section"
      >
        <el-form-item label="OCR 识别">
          <el-switch v-model="params.enableOcr" active-text="开启" inactive-text="关闭" />
          <span class="form-hint">开启后将对图片内容进行 OCR 提取（预留功能）</span>
        </el-form-item>

        <el-form-item label="标签">
          <el-select
            v-model="params.tags"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="输入标签后回车添加"
            style="width: 100%"
          />
        </el-form-item>

        <el-form-item label="部门 ID">
          <el-input
            v-model="params.deptId"
            placeholder="请输入部门ID（可选）"
            clearable
          />
        </el-form-item>

        <el-form-item label="覆盖入库">
          <el-switch v-model="params.overwrite" active-text="覆盖" inactive-text="追加" />
          <span class="form-hint">开启后将覆盖同名文件的已有数据</span>
        </el-form-item>
      </el-form>
    </el-collapse-item>
  </el-collapse>
</template>

<script setup lang="ts">
import type { ExtraParams } from '@/types/import'

const params = defineModel<ExtraParams>('params', { required: true })
</script>

<style scoped>
.form-hint {
  margin-left: 12px;
  font-size: 12px;
  color: #909399;
}
</style>
