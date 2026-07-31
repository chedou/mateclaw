<template>
  <el-dialog
    :model-value="modelValue"
    title="注册候选 SOP"
    width="720px"
    destroy-on-close
    :teleported="false"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <el-alert type="info" :closable="false" class="register-note">
      <template #title>
        只接受单个 JSON 对象，并强制以 <code>candidate + verified=false</code> 注册。sopId 冲突会拒绝覆盖；同 selector 的新 source 仍须重新审核并创建新版本。
      </template>
    </el-alert>
    <div class="template-actions">
      <span>第一次接入场景 Playbook？</span>
      <el-button size="small" plain @click="$emit('loadTemplate')">
        载入部署拓扑示例
      </el-button>
    </div>
    <el-input
      :model-value="registerJson"
      type="textarea"
      :rows="20"
      resize="vertical"
      spellcheck="false"
      class="json-input"
      @update:model-value="$emit('update:registerJson', $event)"
    />
    <div class="validation" :class="{ valid: importValidation.sop }">
      <template v-if="importValidation.sop">
        <span class="validation-dot" />
        合同可提交：<code>{{ importValidation.sop.system }}:{{ importValidation.sop.errorCode }}</code>
        · {{ importValidation.sop.evidenceRequests.length }} 取证
        · {{ importValidation.sop.diagnosisRules.length }} 规则
      </template>
      <template v-else>{{ importValidation.error }}</template>
    </div>
    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button
        type="primary"
        :disabled="!importValidation.sop"
        :loading="registering"
        @click="$emit('register')"
      >注册为 candidate</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import type { SopEntry } from '@/api'

defineProps<{
  modelValue: boolean
  registerJson: string
  registering: boolean
  importValidation: { sop: SopEntry | null; error: string | null }
}>()

defineEmits<{
  'update:modelValue': [value: boolean]
  'update:registerJson': [value: string]
  register: []
  loadTemplate: []
}>()
</script>

<style scoped>
.register-note { margin-bottom: 12px; }
.template-actions {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  margin: 0 0 10px; color: var(--el-text-color-secondary); font-size: 11px;
}
.register-note code, .validation code { font-family: var(--mc-mono, monospace); }
.json-input :deep(textarea) { font-family: var(--mc-mono, monospace); font-size: 11px; line-height: 1.55; }
.validation { min-height: 20px; margin-top: 9px; color: var(--el-color-danger); font-size: 11px; line-height: 1.5; }
.validation.valid { color: var(--el-color-success); }
.validation-dot { display: inline-block; width: 6px; height: 6px; margin-right: 5px; border-radius: 50%; background: currentColor; }
</style>
