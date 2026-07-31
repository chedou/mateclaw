<template>
  <header class="topbar">
    <div class="heading">
      <button class="back-link" type="button" @click="$emit('back')">
        <el-icon><ArrowLeft /></el-icon>
        诊断工作台
      </button>
      <span class="divider">/</span>
      <div>
        <h1>{{ TROUBLESHOOTING_UI_LABELS.rules }}</h1>
        <p>已批准 Playbook 驱动确定性命中；三类候选共用独立、可审计的审核流程。</p>
      </div>
    </div>
    <div class="top-actions">
      <el-button
        :icon="Refresh"
        :loading="activeDesk === 'registry' ? listLoading : reviewLoading"
        @click="$emit('reload')"
      >刷新</el-button>
      <template v-if="activeDesk === 'registry'">
        <el-button plain @click="$emit('openSynthesis')">{{ TROUBLESHOOTING_UI_LABELS.noCodePreview }}</el-button>
        <el-button type="primary" :icon="Plus" @click="$emit('openRegister')">注册候选</el-button>
      </template>
    </div>
  </header>
</template>

<script setup lang="ts">
import { ArrowLeft, Plus, Refresh } from '@element-plus/icons-vue'
import { TROUBLESHOOTING_UI_LABELS } from './workbenchView'

defineProps<{
  activeDesk: 'registry' | 'review'
  listLoading: boolean
  reviewLoading: boolean
}>()

defineEmits<{
  back: []
  reload: []
  openRegister: []
  openSynthesis: []
}>()
</script>

<style scoped>
.topbar {
  min-height: 62px;
  padding: 11px 18px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.heading { display: flex; align-items: center; min-width: 0; gap: 10px; }
.heading h1 { margin: 0; font-size: 17px; line-height: 1.3; font-weight: 680; }
.heading p { margin: 2px 0 0; color: var(--el-text-color-secondary); font-size: 11.5px; }
.back-link {
  display: inline-flex; align-items: center; gap: 5px; padding: 6px 0; flex-shrink: 0;
  border: 0; background: transparent; color: var(--el-text-color-secondary); cursor: pointer;
  font: inherit; font-size: 12px;
}
.back-link:hover { color: var(--el-color-primary); }
.divider { color: var(--el-border-color); }
.top-actions { display: flex; gap: 8px; flex-shrink: 0; }
</style>
