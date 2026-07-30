<template>
  <section class="traditional-list-page">
    <header class="list-page-head">
      <div>
        <span class="eyebrow">MateClaw · Troubleshooting</span>
        <h1>排障队列</h1>
        <p>集中查看全部诊断记录，选择一条进入处置工作台。</p>
      </div>
      <div class="list-page-head-actions">
        <WorkbenchViewSwitch mode="LIST" @change="emit('switch-view')" />
        <el-button
          v-if="canOperate"
          type="primary"
          :icon="Plus"
          @click="emit('report')"
        >上报事件</el-button>
        <WorkbenchCapabilityMenu
          v-if="canManage"
          @command="emit('capability-command', $event)"
        />
      </div>
    </header>

    <div class="list-page-toolbar">
      <el-select
        v-model="filterModel"
        clearable
        placeholder="全部状态"
        aria-label="按状态筛选"
        @change="emit('refresh')"
      >
        <el-option
          v-for="status in WORKBENCH_DIAGNOSIS_STATUSES"
          :key="status"
          :label="diagnosisStatusLabel(status)"
          :value="status"
        />
      </el-select>
      <span>共 {{ rows.length }} 条诊断</span>
      <el-button :icon="Refresh" text :loading="loading" @click="emit('refresh')">刷新</el-button>
    </div>

    <div v-loading="loading" class="traditional-table-card">
      <el-table
        v-if="rows.length"
        :data="rows"
        row-key="diagnosisId"
        stripe
        class="diagnosis-table"
        @row-click="emit('open-diagnosis', $event)"
      >
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }"><time>{{ formatWorkbenchTime(row.updateTime) }}</time></template>
        </el-table-column>
        <el-table-column label="系统 / 错误码" min-width="190">
          <template #default="{ row }"><code>{{ row.system }}:{{ row.errorCode || 'NO-CODE' }}</code></template>
        </el-table-column>
        <el-table-column prop="service" label="服务" min-width="180" show-overflow-tooltip />
        <el-table-column label="状态" width="150">
          <template #default="{ row }">
            <span class="table-status" :class="diagnosisStatusTone(row.status)">
              {{ diagnosisStatusLabel(row.status) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <span v-if="row.rehearsal" class="rehearsal">演练</span>
            <span v-else class="production-record">正式</span>
          </template>
        </el-table-column>
        <el-table-column label="Case" min-width="170" show-overflow-tooltip>
          <template #default="{ row }"><code class="case-code">{{ row.caseId }}</code></template>
        </el-table-column>
        <el-table-column label="操作" width="110" align="right">
          <template #default="{ row }">
            <el-button type="primary" link @click.stop="emit('open-diagnosis', row)">查看详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-else class="traditional-list-empty">
        <div class="empty-mark">MC</div>
        <h2>还没有诊断记录</h2>
        <p>从正式入口上报事件后，会通过既有 Incident API 进入同一条 Diagnosis 主链。</p>
        <el-button v-if="canOperate" type="primary" plain @click="emit('report')">
          上报第一个事件
        </el-button>
      </div>
    </div>

    <footer class="list-page-foot">
      <span>正式入口 · 真实 API</span>
      <button type="button" @click="emit('open-legacy')">旧版处置台</button>
    </footer>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { vLoading } from 'element-plus/es/components/loading/index'
import type { DiagnosisStatus, DiagnosisSummary } from '@/api'
import WorkbenchCapabilityMenu from './WorkbenchCapabilityMenu.vue'
import WorkbenchViewSwitch from './WorkbenchViewSwitch.vue'
import {
  WORKBENCH_DIAGNOSIS_STATUSES,
  diagnosisStatusLabel,
  diagnosisStatusTone,
  formatWorkbenchTime,
  type WorkbenchCapabilityCommand,
} from './workbenchView'

const props = defineProps<{
  rows: DiagnosisSummary[]
  statusFilter: DiagnosisStatus | ''
  loading: boolean
  canOperate: boolean
  canManage: boolean
}>()

const emit = defineEmits<{
  'update:statusFilter': [value: DiagnosisStatus | '']
  refresh: []
  report: []
  'capability-command': [command: WorkbenchCapabilityCommand]
  'open-diagnosis': [row: DiagnosisSummary]
  'switch-view': []
  'open-legacy': []
}>()

const filterModel = computed({
  get: () => props.statusFilter,
  set: (value: DiagnosisStatus | '') => emit('update:statusFilter', value),
})
</script>

<style scoped>
.traditional-list-page { min-height:100%; padding:30px clamp(20px,4vw,56px) 22px; background:#f4f6fa; }
.list-page-head,.list-page-toolbar,.traditional-table-card,.list-page-foot { width:min(1440px,100%); margin-right:auto; margin-left:auto; }
.list-page-head { display:flex; align-items:flex-start; justify-content:space-between; gap:28px; }
.eyebrow { display:block; color:var(--blue); font-size:10px; font-weight:750; letter-spacing:.12em; text-transform:uppercase; }
.list-page-head h1 { margin:6px 0 4px; font-size:28px; letter-spacing:-.035em; }
.list-page-head p { margin:0; color:var(--muted); font-size:13px; }
.list-page-head-actions { display:flex; align-items:center; justify-content:flex-end; gap:9px; flex-wrap:wrap; }
.list-page-toolbar { display:flex; align-items:center; gap:12px; margin-top:24px; margin-bottom:12px; }
.list-page-toolbar .el-select { width:190px; }
.list-page-toolbar>span { color:var(--muted); font-size:11px; }
.list-page-toolbar>.el-button { margin-left:auto; }
.traditional-table-card { min-height:320px; overflow:hidden; border:1px solid var(--line); border-radius:13px; background:#fff; box-shadow:0 9px 30px rgba(21,37,68,.045); }
.diagnosis-table { width:100%; }
.diagnosis-table :deep(.el-table__header th) { height:48px; color:#667085; background:#f8f9fc; font-size:11px; font-weight:700; }
.diagnosis-table :deep(.el-table__row) { height:58px; cursor:pointer; }
.diagnosis-table :deep(.el-table__row:hover>td) { background:#f3f6ff!important; }
.diagnosis-table code { color:#344054; font-size:11px; font-weight:700; }
.diagnosis-table time { color:#667085; font:11px var(--mc-mono,monospace); }
.table-status { display:inline-flex; align-items:center; padding:3px 8px; border-radius:12px; background:#f2f4f7; font-size:10px; font-weight:700; }
.table-status.active { color:var(--blue); background:#eff4ff; }.table-status.success { color:var(--green); background:#ecfdf3; }.table-status.warning { color:var(--amber); background:#fffaeb; }.table-status.muted { color:#98a2b3; }
.rehearsal { padding:1px 6px; border-radius:10px; color:#6941c6; background:#f4f0ff; font-size:9px; }
.production-record { padding:1px 6px; border-radius:10px; color:#067647; background:#ecfdf3; font-size:9px; }
.case-code { color:#667085!important; font-weight:500!important; }
.traditional-list-empty { display:grid; place-items:center; align-content:center; min-height:420px; padding:36px; color:var(--muted); text-align:center; }
.empty-mark { display:grid; place-items:center; width:52px; height:52px; border:1px solid #cdd6f8; border-radius:15px; color:var(--blue); background:#fff; font-weight:800; box-shadow:0 10px 30px rgba(47,92,245,.08); }
.traditional-list-empty h2 { margin:16px 0 5px; color:var(--ink); font-size:19px; }
.traditional-list-empty p { max-width:520px; margin:0 0 16px; font-size:12px; line-height:1.65; }
.list-page-foot { display:flex; align-items:center; justify-content:space-between; padding:13px 2px 0; color:#98a2b3; font-size:10px; }
.list-page-foot button { border:0; background:none; color:var(--blue); font:inherit; cursor:pointer; }
@media(max-width:760px){.traditional-list-page{padding:20px 12px}.list-page-head{flex-direction:column}.list-page-head-actions{justify-content:flex-start}.list-page-toolbar{align-items:stretch;flex-wrap:wrap}.list-page-toolbar .el-select{width:100%}.list-page-toolbar>.el-button{margin-left:auto}.traditional-table-card{overflow-x:auto}.diagnosis-table{min-width:920px}}
</style>
