<template>
  <section class="filterbar" aria-label="Playbook 工作区筛选">
    <div class="desk-switch" role="tablist" aria-label="知识治理工作区">
      <button
        type="button"
        role="tab"
        :aria-selected="activeDesk === 'registry'"
        :class="{ active: activeDesk === 'registry' }"
        @click="$emit('update:activeDesk', 'registry')"
      >生效路由 <b>{{ rowCount }}</b></button>
      <button
        type="button"
        role="tab"
        :aria-selected="activeDesk === 'review'"
        :class="{ active: activeDesk === 'review' }"
        @click="$emit('update:activeDesk', 'review')"
      >知识候选 <b>{{ knowledgeRowCount }}</b></button>
    </div>

    <template v-if="activeDesk === 'registry'">
      <span class="filter-separator" />
      <el-select
        :model-value="statusFilter"
        clearable
        placeholder="全部状态"
        style="width: 152px"
        @update:model-value="$emit('update:statusFilter', $event)"
        @change="$emit('applyFilters')"
      >
        <el-option
          v-for="status in SOP_STATUSES"
          :key="status"
          :label="STATUS_LABEL[status]"
          :value="status"
        />
      </el-select>
      <el-input
        :model-value="systemFilter"
        clearable
        placeholder="按 system 精确筛选"
        style="width: 220px"
        @update:model-value="$emit('update:systemFilter', $event)"
        @clear="$emit('applyFilters')"
        @keyup.enter="$emit('applyFilters')"
      />
      <el-button @click="$emit('applyFilters')">查询</el-button>
      <button
        v-if="statusFilter || systemFilter"
        class="clear-filter"
        type="button"
        @click="$emit('clearFilters')"
      >清除筛选</button>
      <span class="registry-count">{{ rowCount }} 条路由</span>
      <div class="lifecycle" aria-label="SOP 生命周期">
        <span>candidate</span><i>→</i><span>qualification gate</span><i>→</i><span>approved version</span><i>→</i><span>deprecated</span>
      </div>
    </template>

    <template v-else>
      <span class="filter-separator" />
      <el-select
        :model-value="originFilter"
        clearable
        placeholder="全部来源"
        style="width: 180px"
        @update:model-value="$emit('update:originFilter', $event)"
      >
        <el-option label="证据生成" value="EVIDENCE_DERIVED" />
        <el-option label="关闭结果沉淀" value="OUTCOME_BACKED" />
        <el-option label="人工注册" value="MANUAL" />
      </el-select>
      <el-input
        :model-value="reviewQuery"
        clearable
        placeholder="搜索 selector、标题或来源"
        style="width: 260px"
        @update:model-value="$emit('update:reviewQuery', $event)"
      />
      <span class="registry-count">{{ knowledgeRowCount }} 条候选</span>
      <div class="lifecycle review-lifecycle" aria-label="知识审核生命周期">
        <span>candidate</span><i>→</i><span>in review</span><i>→</i><span>approved / rejected</span><i>→</i><span>deprecated</span>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
import type { KnowledgeOrigin, SopStatus } from '@/api'

const SOP_STATUSES: SopStatus[] = ['candidate', 'approved', 'deprecated']
const STATUS_LABEL: Record<SopStatus, string> = {
  candidate: '待审核',
  approved: '已生效',
  deprecated: '已过期',
}

defineProps<{
  activeDesk: 'registry' | 'review'
  rowCount: number
  knowledgeRowCount: number
  statusFilter: SopStatus | ''
  systemFilter: string
  originFilter: '' | KnowledgeOrigin
  reviewQuery: string
}>()

defineEmits<{
  'update:activeDesk': [value: 'registry' | 'review']
  'update:statusFilter': [value: SopStatus | '']
  'update:systemFilter': [value: string]
  'update:originFilter': [value: '' | KnowledgeOrigin]
  'update:reviewQuery': [value: string]
  applyFilters: []
  clearFilters: []
}>()
</script>

<style scoped>
.filterbar {
  min-height: 48px;
  padding: 8px 14px;
  display: flex;
  align-items: center;
  gap: 8px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  background: var(--el-fill-color-blank);
}
.desk-switch {
  display: inline-flex; flex-shrink: 0; padding: 3px; gap: 2px;
  border: 1px solid var(--el-border-color-lighter); border-radius: 7px;
  background: var(--el-fill-color-light);
}
.desk-switch button {
  min-height: 28px; padding: 4px 10px; border: 0; border-radius: 5px;
  background: transparent; color: var(--el-text-color-secondary); cursor: pointer;
  font: 600 11px/1.2 inherit;
}
.desk-switch button:hover { color: var(--el-text-color-primary); }
.desk-switch button.active {
  background: var(--el-bg-color); color: var(--el-color-primary);
  box-shadow: 0 1px 3px color-mix(in srgb, var(--el-text-color-primary) 10%, transparent);
}
.desk-switch b {
  display: inline-block; min-width: 17px; margin-left: 4px; padding: 1px 4px;
  border-radius: 8px; background: var(--el-fill-color-dark); color: inherit;
  font: 600 9.5px var(--mc-mono, monospace);
}
.filter-separator { width: 1px; height: 24px; margin: 0 3px; background: var(--el-border-color-lighter); }
.clear-filter {
  border: 0; background: transparent; color: var(--el-text-color-secondary); cursor: pointer;
  font: inherit; font-size: 12px;
}
.clear-filter:hover { color: var(--el-color-primary); }
.registry-count { margin-left: 4px; font-size: 11.5px; color: var(--el-text-color-secondary); }
.lifecycle {
  margin-left: auto; display: flex; align-items: center; gap: 7px;
  color: var(--el-text-color-secondary); font: 10.5px var(--mc-mono, monospace);
}
.lifecycle span { padding: 3px 7px; border: 1px solid var(--el-border-color-lighter); border-radius: 4px; }
.lifecycle i { color: var(--el-text-color-placeholder); font-style: normal; }
</style>
