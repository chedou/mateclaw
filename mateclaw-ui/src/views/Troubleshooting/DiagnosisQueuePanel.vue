<template>
  <aside class="queue-panel">
    <header class="queue-head">
      <div><span class="eyebrow">MateClaw</span><h2>排障队列</h2></div>
      <div class="queue-head-actions">
        <el-tag size="small" type="info" round>{{ rows.length }}</el-tag>
        <WorkbenchViewSwitch mode="QUEUE" compact @change="$emit('switch-view')" />
      </div>
    </header>

    <div class="queue-tools">
      <el-select
        v-model="statusFilter"
        size="small"
        clearable
        placeholder="全部状态"
        @change="$emit('refresh')"
      >
        <el-option
          v-for="status in WORKBENCH_DIAGNOSIS_STATUSES"
          :key="status"
          :label="diagnosisStatusLabel(status)"
          :value="status"
        />
      </el-select>
      <el-select
        v-model="investigationModeFilter"
        size="small"
        clearable
        placeholder="全部调查模式"
        @change="$emit('refresh')"
      >
        <el-option
          v-for="mode in WORKBENCH_INVESTIGATION_MODES"
          :key="mode"
          :label="investigationModeLabel(mode)"
          :value="mode"
        />
      </el-select>
      <div class="queue-action-row">
        <el-button
          v-if="canOperate || canManage"
          size="small"
          type="primary"
          plain
          :icon="Plus"
          @click="$emit('launch')"
        >{{ TROUBLESHOOTING_UI_LABELS.launch }}</el-button>
      </div>
    </div>

    <div v-loading="loading" class="queue-list">
      <button
        v-for="row in rows"
        :key="row.diagnosisId"
        type="button"
        class="queue-item"
        :class="{ active: row.diagnosisId === selectedId }"
        @click="$emit('select-diagnosis', row.diagnosisId)"
      >
        <div class="queue-item-top">
          <code>{{ row.system }}:{{ row.errorCode || 'NO-CODE' }}</code>
          <span v-if="row.rehearsal" class="rehearsal">演练</span>
        </div>
        <span class="queue-ticket-id" :title="row.diagnosisId">
          排障单号 · <code>{{ row.diagnosisId }}</code>
        </span>
        <strong>{{ row.service }}</strong>
        <div class="queue-item-bottom">
          <span :class="diagnosisStatusTone(row.status)">{{ diagnosisStatusLabel(row.status) }}</span>
          <time>{{ formatWorkbenchTime(row.updateTime) }}</time>
        </div>
      </button>
      <div v-if="!loading && !rows.length" class="queue-empty">
        <b>还没有诊断记录</b>
        <p>从正式入口选择排障场景；通用事件会进入 Diagnosis 主链，专项场景遵守各自能力边界。</p>
        <el-button
          v-if="canOperate || canManage"
          size="small"
          type="primary"
          plain
          @click="$emit('launch')"
        >{{ TROUBLESHOOTING_UI_LABELS.launch }}</el-button>
        <code v-else>需要 operate:troubleshooting 权限</code>
      </div>
    </div>

    <footer class="queue-foot"><span>正式入口 · 真实 API</span></footer>
  </aside>
</template>

<script setup lang="ts">
import { Plus } from '@element-plus/icons-vue'
import { vLoading } from 'element-plus/es/components/loading/index'
import type { DiagnosisStatus, DiagnosisSummary, InvestigationMode } from '@/api'
import { investigationModeLabel } from './formalProjection'
import WorkbenchViewSwitch from './WorkbenchViewSwitch.vue'
import {
  TROUBLESHOOTING_UI_LABELS,
  WORKBENCH_DIAGNOSIS_STATUSES,
  WORKBENCH_INVESTIGATION_MODES,
  diagnosisStatusLabel,
  diagnosisStatusTone,
  formatWorkbenchTime,
} from './workbenchView'

defineProps<{
  rows: DiagnosisSummary[]
  selectedId: string | null
  loading: boolean
  canOperate: boolean
  canManage: boolean
}>()

const statusFilter = defineModel<DiagnosisStatus | ''>('statusFilter', { required: true })
const investigationModeFilter = defineModel<InvestigationMode | ''>(
  'investigationModeFilter',
  { required: true },
)

defineEmits<{
  refresh: []
  launch: []
  'select-diagnosis': [diagnosisId: string]
  'switch-view': []
}>()
</script>

<style scoped>
.queue-panel { display:flex; flex-direction:column; min-width:0; overflow:hidden; background:var(--mc-bg-elevated); border-right:1px solid var(--line); }
.queue-head { display:flex; align-items:center; justify-content:space-between; padding:18px 16px 14px; border-bottom:1px solid var(--line); }
.queue-head-actions { display:flex; align-items:flex-end; flex-direction:column; }
.eyebrow { display:block; color:var(--blue); font-size:var(--mc-text-xs); font-weight:750; letter-spacing:.12em; text-transform:uppercase; }
.queue-head h2 { margin:4px 0 0; font-size:var(--mc-text-base); letter-spacing:-.02em; }
.queue-tools { display:flex; flex-direction:column; gap:8px; padding:10px 12px; border-bottom:1px solid var(--line); }
.queue-tools .el-select { flex:1; min-width:0; }
.queue-action-row { display:flex; flex-wrap:wrap; align-items:center; gap:5px; }
.queue-action-row>.el-button { flex:1 1 92px; margin-left:0; }
.queue-list { flex:1; min-height:0; overflow-y:auto; }
.queue-item { width:100%; padding:13px 14px 12px; border:0; border-bottom:1px solid var(--mc-border-light); border-left:3px solid transparent; background:var(--mc-bg-elevated); color:inherit; font:inherit; text-align:left; cursor:pointer; }
.queue-item:hover { background:var(--mc-bg-elevated); }
.queue-item.active { border-left-color:var(--blue); background:var(--mc-sidebar-active); }
.queue-item-top,.queue-item-bottom { display:flex; align-items:center; gap:8px; }
.queue-item-top code { color:var(--mc-text-secondary); font-size:var(--mc-text-xs); font-weight:700; }
.queue-ticket-id { display:flex; min-width:0; align-items:center; gap:5px; margin-top:7px; color:var(--mc-text-tertiary); font-size:var(--mc-text-xs); text-align:left; }
.queue-ticket-id code { min-width:0; overflow:hidden; color:var(--mc-text-secondary); text-overflow:ellipsis; white-space:nowrap; }
.queue-item strong { display:block; margin-top:5px; font-size:var(--mc-text-sm); }
.queue-item-bottom { margin-top:7px; color:var(--muted); font-size:var(--mc-text-xs); }
.queue-item-bottom time { margin-left:auto; font-family:var(--mc-mono,monospace); }
.rehearsal { padding:1px 6px; border-radius:var(--mc-radius-sm); color:var(--mc-status-purple-text); background:var(--mc-status-info-bg); font-size:var(--mc-text-xs); }
.active { color:var(--blue)!important; }
.success { color:var(--green)!important; }
.warning { color:var(--amber)!important; }
.muted { color:var(--mc-text-tertiary)!important; }
.queue-empty { padding:26px 17px; color:var(--muted); font-size:var(--mc-text-sm); line-height:1.65; }
.queue-empty b { color:var(--ink); }
.queue-empty p { margin:5px 0 10px; }
.queue-empty code { color:var(--blue); font-size:var(--mc-text-xs); }
.queue-empty .el-button { width:100%; }
.queue-foot { display:flex; align-items:center; justify-content:space-between; padding:10px 13px; border-top:1px solid var(--line); color:var(--mc-text-tertiary); font-size:var(--mc-text-xs); }

@media(max-width:760px){.queue-panel{max-height:320px;border-right:0;border-bottom:1px solid var(--line)}}
</style>
