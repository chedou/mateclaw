<template>
  <section class="traditional-list-page">
    <header class="list-page-head">
      <div>
        <h1>排障队列</h1>
        <p>粘贴告警，或点一条记录进入详情。</p>
      </div>
      <div class="list-page-head-actions">
        <WorkbenchViewSwitch mode="LIST" @change="emit('switch-view')" />
        <el-button
          v-if="canOperate || canManage"
          plain
          @click="emit('guide')"
        >{{ TROUBLESHOOTING_UI_LABELS.firstUse }}</el-button>
        <el-button
          v-if="canOperate || canManage"
          type="primary"
          :icon="Plus"
          @click="emit('launch')"
        >{{ TROUBLESHOOTING_UI_LABELS.launch }}</el-button>
      </div>
    </header>

    <section v-if="pilotPlanError" class="pilot-workbench-strip pilot-load-error" aria-live="polite">
      <div class="pilot-copy">
        <span>团队试点</span>
        <h2>暂时没有读到试点状态</h2>
        <p>{{ pilotPlanError }} 排障列表仍可正常使用。</p>
      </div>
      <el-button plain :loading="pilotPlanLoading" @click="emit('pilot-refresh')">重新读取</el-button>
    </section>

    <section
      v-else-if="!pilotPlanLoading"
      class="pilot-workbench-strip"
      :class="`step-${pilotPrompt.step}`"
      aria-labelledby="pilot-workbench-title"
    >
      <div class="pilot-copy">
        <span>团队试点 · 第 {{ pilotPrompt.step }} 步，共 3 步</span>
        <h2 id="pilot-workbench-title">{{ pilotPrompt.title }}</h2>
        <p>{{ pilotPrompt.detail }}</p>
        <code v-if="pilotPrompt.scope">
          {{ pilotPrompt.scope.system }} / {{ pilotPrompt.scope.service }}
        </code>
      </div>
      <ol class="pilot-progress" aria-label="团队试点进度">
        <li v-for="step in PILOT_STEPS" :key="step.index" :class="{
          done: step.index < pilotPrompt.step,
          current: step.index === pilotPrompt.step,
        }">
          <i>{{ step.index < pilotPrompt.step ? '✓' : step.index }}</i>
          <span>{{ step.label }}</span>
        </li>
      </ol>
      <div class="pilot-owner-action">
        <span>现在轮到</span>
        <b>{{ pilotPrompt.ownerLabel }}</b>
        <el-button
          v-if="pilotPrompt.kind === 'SETUP' && canManage"
          type="primary"
          @click="runPilotAction"
        >{{ pilotActionLabel }}</el-button>
        <el-button
          v-else-if="pilotPrompt.kind === 'CREATE_FORMAL' && canOperate"
          type="primary"
          @click="runPilotAction"
        >{{ pilotActionLabel }}</el-button>
        <el-button
          v-else-if="pilotPrompt.kind === 'CONTINUE_DIAGNOSIS'"
          type="primary"
          @click="runPilotAction"
        >{{ pilotActionLabel }}</el-button>
        <el-button
          v-else-if="pilotPrompt.kind === 'HANDOFF_EVALUATION' && canManage"
          type="primary"
          @click="runPilotAction"
        >{{ pilotActionLabel }}</el-button>
        <el-button
          v-else-if="pilotPrompt.kind === 'HANDOFF_EVALUATION' && !canManage"
          plain
          @click="runPilotAction"
        >{{ pilotActionLabel }}</el-button>
        <small v-else>请联系具备相应权限的负责人继续。</small>
      </div>
    </section>

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
      <el-select
        v-model="store.investigationModeFilter"
        clearable
        placeholder="全部调查模式"
        aria-label="按调查模式筛选"
        @change="emit('refresh')"
      >
        <el-option
          v-for="mode in WORKBENCH_INVESTIGATION_MODES"
          :key="mode"
          :label="investigationModeLabel(mode)"
          :value="mode"
        />
      </el-select>
      <el-select
        v-model="store.systemFilter"
        clearable
        placeholder="全部系统"
        aria-label="按系统筛选"
        size="default"
        style="width: 140px;"
      >
        <el-option
          v-for="sys in systemOptions"
          :key="sys"
          :label="sys"
          :value="sys"
        />
      </el-select>
      <el-input
        v-model="store.searchKeyword"
        placeholder="搜索排障单号、服务、错误码、Case..."
        clearable
        :prefix-icon="Search"
        style="max-width: 280px;"
      />
      <span>共 {{ store.filteredRows.length }} / {{ rows.length }} 条诊断</span>
      <el-button :icon="Refresh" text :loading="loading" @click="emit('refresh')">刷新</el-button>
    </div>

    <div v-if="store.selectedIds.size > 0" class="batch-action-bar">
      <span>已选择 {{ store.selectedIds.size }} 条</span>
      <el-button size="small" type="primary" :loading="store.batchOperating" :disabled="!store.canBatchConfirm" @click="store.batchConfirm()">批量确认</el-button>
      <el-button size="small" type="danger" :loading="store.batchOperating" :disabled="!store.canBatchClose" @click="store.batchClose()">批量关闭</el-button>
      <el-button size="small" @click="store.clearSelection()">取消选择</el-button>
    </div>

    <div v-loading="loading" class="traditional-table-card">
      <el-table
        v-if="store.filteredRows.length"
        :data="store.filteredRows"
        row-key="diagnosisId"
        stripe
        class="diagnosis-table"
        @sort-change="onSortChange"
        @selection-change="onSelectionChange"
      >
        <el-table-column type="selection" width="40" />
        <el-table-column label="更新时间" width="170" sortable="custom">
          <template #default="{ row }"><time>{{ formatWorkbenchTime(row.updateTime) }}</time></template>
        </el-table-column>
        <el-table-column label="排障单号" min-width="220">
          <template #default="{ row }">
            <button
              class="diagnosis-ticket-link"
              type="button"
              :title="row.diagnosisId"
              :aria-label="`打开排障单 ${row.diagnosisId}`"
              @click.stop="openDiagnosis(row)"
            >{{ row.diagnosisId }}</button>
          </template>
        </el-table-column>
        <el-table-column label="系统" width="100">
          <template #default="{ row }"><code>{{ row.system }}</code></template>
        </el-table-column>
        <el-table-column label="系统 / 错误码" min-width="190">
          <template #default="{ row }"><code>{{ row.system }}:{{ row.errorCode || 'NO-CODE' }}</code></template>
        </el-table-column>
        <el-table-column prop="service" label="服务" min-width="180" sortable="custom" show-overflow-tooltip />
        <el-table-column label="调查路径" min-width="210" show-overflow-tooltip>
          <template #default="{ row }">
            <span
              class="route-semantics"
              :class="{ legacy: row.routeSemanticsProvenance === 'LEGACY_DERIVED' }"
            >{{ diagnosisSummaryRouteLabel(
              row.investigationMode,
              row.routeAuthority,
              row.routeSemanticsProvenance,
            ) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="150" sortable="custom">
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
            <el-button type="primary" link @click.stop="openDiagnosis(row)">查看详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-else class="traditional-list-empty">
        <div class="empty-mark">MC</div>
        <h2>{{ rows.length ? '没有匹配的记录' : '还没有诊断记录' }}</h2>
        <p v-if="rows.length">尝试调整搜索关键词或筛选条件。</p>
        <p v-else>点「发起排障」，把告警贴进去。</p>
        <el-button v-if="!rows.length && (canOperate || canManage)" type="primary" plain @click="emit('launch')">
          {{ TROUBLESHOOTING_UI_LABELS.launch }}
        </el-button>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { vLoading } from 'element-plus/es/components/loading/index'
import type {
  DiagnosisStatus,
  DiagnosisSummary,
  TroubleshootingPilotModuleScope,
  TroubleshootingPilotPlan,
} from '@/api'
import { useTroubleshootingStore } from '@/stores/useTroubleshootingStore'
import WorkbenchViewSwitch from './WorkbenchViewSwitch.vue'
import {
  diagnosisSummaryRouteLabel,
  investigationModeLabel,
} from './formalProjection'
import {
  TROUBLESHOOTING_UI_LABELS,
  WORKBENCH_DIAGNOSIS_STATUSES,
  WORKBENCH_INVESTIGATION_MODES,
  diagnosisStatusLabel,
  diagnosisStatusTone,
  formatWorkbenchTime,
} from './workbenchView'
import { buildPilotWorkbenchPrompt } from './evaluationPilot'

const PILOT_STEPS = [
  { index: 1, label: '固定范围与人' },
  { index: 2, label: '完成正式排障' },
  { index: 3, label: '补齐效果证据' },
] as const

const props = defineProps<{
  rows: DiagnosisSummary[]
  statusFilter: DiagnosisStatus | ''
  loading: boolean
  canOperate: boolean
  canManage: boolean
  pilotPlan: TroubleshootingPilotPlan | null
  pilotPlanLoading: boolean
  pilotPlanError: string
}>()

const emit = defineEmits<{
  'update:statusFilter': [value: DiagnosisStatus | '']
  refresh: []
  guide: []
  launch: []
  'open-diagnosis': [row: DiagnosisSummary]
  'switch-view': []
  'pilot-refresh': []
  'pilot-setup': []
  'pilot-launch-formal': [scope: TroubleshootingPilotModuleScope]
  'pilot-open-diagnosis': [diagnosisId: string]
  'pilot-open-evaluation': [diagnosisId: string]
}>()

const store = useTroubleshootingStore()

const filterModel = computed({
  get: () => props.statusFilter,
  set: (value: DiagnosisStatus | '') => emit('update:statusFilter', value),
})

const systemOptions = computed(() =>
  [...new Set(props.rows.map(r => r.system).filter(Boolean))].sort()
)
const pilotPrompt = computed(() => buildPilotWorkbenchPrompt(props.rows, props.pilotPlan))
const pilotActionLabel = computed(() => pilotPrompt.value.kind === 'HANDOFF_EVALUATION'
  && !props.canManage
  ? '查看已闭环排障单'
  : pilotPrompt.value.actionLabel)

function runPilotAction() {
  const prompt = pilotPrompt.value
  if (prompt.kind === 'SETUP') {
    if (props.canManage) emit('pilot-setup')
    return
  }
  if (prompt.kind === 'CREATE_FORMAL') {
    if (props.canOperate && prompt.scope) emit('pilot-launch-formal', prompt.scope)
    return
  }
  if (!prompt.diagnosisId) return
  if (prompt.kind === 'HANDOFF_EVALUATION' && props.canManage) {
    emit('pilot-open-evaluation', prompt.diagnosisId)
    return
  }
  emit('pilot-open-diagnosis', prompt.diagnosisId)
}

/**
 * Element Plus emits `{ column, prop, order }` with both `prop` and `order`
 * nullable — clearing a sort sends nulls. Declaring them non-null made the
 * handler unassignable to the emitted type, which is why `vue-tsc` failed.
 */
function onSortChange({ prop, order }: { prop: string | null; order: string | null }) {
  if (prop && order) {
    store.sortField = prop as 'updateTime' | 'service' | 'status'
    store.sortOrder = order === 'ascending' ? 'asc' : 'desc'
  } else {
    store.sortField = 'updateTime'
    store.sortOrder = 'desc'
  }
}

/**
 * `el-table` scoped slots are typed as `DefaultRow`, not as the element type of
 * `:data`. The table is bound to `store.filteredRows`, so the row really is a
 * `DiagnosisSummary`; narrowing happens here, at one boundary, rather than with
 * a cast scattered through the template.
 */
function openDiagnosis(row: DiagnosisSummary | Record<string, unknown>) {
  emit('open-diagnosis', row as DiagnosisSummary)
}

function onSelectionChange(rows: DiagnosisSummary[]) {
  store.selectedIds = new Set(rows.map(r => r.diagnosisId))
}
</script>

<style scoped>
.traditional-list-page { width:100%; min-width:0; min-height:100%; padding:30px clamp(20px,4vw,56px) 22px; background:var(--mc-bg-elevated); }
.list-page-head,.list-page-toolbar,.traditional-table-card,.list-page-foot { width:100%; margin-right:0; margin-left:0; }
.list-page-head { display:flex; align-items:flex-start; justify-content:space-between; gap:28px; }
.eyebrow { display:block; color:var(--mc-primary); font-size:10px; font-weight:750; letter-spacing:.12em; text-transform:uppercase; }
.list-page-head h1 { margin:6px 0 4px; font-size:28px; letter-spacing:-.035em; }
.list-page-head p { margin:0; color:var(--mc-text-secondary); font-size:var(--mc-text-sm); }
.list-page-head-actions { display:flex; align-items:center; justify-content:flex-end; gap:9px; flex-wrap:wrap; }
.pilot-workbench-strip { display:grid; grid-template-columns:minmax(0,1.4fr) minmax(220px,.55fr); align-items:center; gap:20px; margin-top:16px; padding:12px 0; border-top:1px solid var(--mc-border); border-bottom:1px solid var(--mc-border); }
.pilot-copy>span,.pilot-owner-action>span { color:var(--mc-primary); font-size:10px; font-weight:750; letter-spacing:.08em; }
.pilot-copy h2 { margin:4px 0 0; color:var(--mc-text-primary); font-size:16px; letter-spacing:-.02em; }
.pilot-copy p { display:none; }
.pilot-copy code { display:inline-block; margin-top:6px; color:var(--mc-primary); font-size:10px; }
.pilot-progress { display:none; }
.pilot-progress li { position:relative; display:grid; justify-items:center; gap:6px; color:var(--mc-text-tertiary); font-size:10px; text-align:center; }
.pilot-progress li::before { position:absolute; top:10px; right:50%; left:-50%; height:1px; background:var(--mc-border); content:''; }
.pilot-progress li:first-child::before { display:none; }
.pilot-progress i { position:relative; z-index:1; display:grid; place-items:center; width:21px; height:21px; border:1px solid var(--mc-border); border-radius:50%; background:var(--mc-bg-elevated); font-style:normal; font-weight:750; }
.pilot-progress li.done,.pilot-progress li.current { color:var(--mc-text-primary); }
.pilot-progress li.done i { color:var(--mc-status-success-text); border-color:var(--mc-success); background:var(--mc-status-success-bg); }
.pilot-progress li.current i { color:var(--mc-text-inverse); border-color:var(--mc-primary); background:var(--mc-primary); }
.pilot-owner-action { display:flex; flex-direction:column; align-items:flex-start; gap:5px; padding-left:20px; border-left:1px solid var(--mc-border); }
.pilot-owner-action>b { font-size:13px; }
.pilot-owner-action>.el-button { width:100%; margin-top:5px; }
.pilot-owner-action>small { color:var(--mc-text-tertiary); font-size:10px; line-height:1.5; }
.pilot-load-error { grid-template-columns:minmax(0,1fr) auto; background:var(--mc-status-warning-bg); }
.list-page-toolbar { display:flex; align-items:center; gap:12px; margin-top:24px; margin-bottom:12px; flex-wrap:wrap; }
.list-page-toolbar .el-select { width:190px; }
.list-page-toolbar>span { color:var(--mc-text-secondary); font-size:var(--mc-text-xs); }
.list-page-toolbar>.el-button { margin-left:auto; }
.batch-action-bar { display:flex; align-items:center; gap:12px; width:100%; padding:10px 14px; margin-right:0; margin-bottom:12px; margin-left:0; background:var(--mc-status-info-bg); border:1px solid var(--mc-border); border-radius:var(--mc-radius-sm); font-size:var(--mc-text-sm); }
.traditional-table-card { min-height:320px; overflow:hidden; border:1px solid var(--mc-border); border-radius:var(--mc-radius-md); background:var(--mc-bg-elevated); box-shadow:var(--mc-shadow-soft); }
.diagnosis-table { width:100%; }
.diagnosis-table :deep(.el-table__header th) { height:48px; color:var(--mc-text-tertiary); background:var(--mc-bg-elevated); font-size:var(--mc-text-xs); font-weight:700; }
.diagnosis-table :deep(.el-table__row) { height:58px; }
.diagnosis-table :deep(.el-table__row:hover>td) { background:var(--mc-bg-muted)!important; }
.diagnosis-table code { color:var(--mc-text-secondary); font-size:var(--mc-text-xs); font-weight:700; }
.diagnosis-table time { color:var(--mc-text-tertiary); font:var(--mc-text-xs) var(--mc-mono,monospace); }
.diagnosis-ticket-link { display:block; max-width:100%; overflow:hidden; padding:0; border:0; color:var(--mc-primary); background:none; font:700 var(--mc-text-xs) var(--mc-mono,monospace); text-align:left; text-overflow:ellipsis; white-space:nowrap; cursor:pointer; }
.diagnosis-ticket-link:hover { text-decoration:underline; text-underline-offset:3px; }
.diagnosis-ticket-link:focus-visible { border-radius:2px; outline:2px solid var(--mc-primary); outline-offset:3px; }
.route-semantics { color:var(--mc-text-secondary); font-size:var(--mc-text-xs); }
.route-semantics.legacy { color:var(--mc-warning); }
.table-status { display:inline-flex; align-items:center; padding:3px 8px; border-radius:12px; background:var(--mc-bg-muted); font-size:10px; font-weight:700; }
.table-status.active { color:var(--mc-primary); background:var(--mc-status-info-bg); }.table-status.success { color:var(--mc-success); background:var(--mc-status-success-bg); }.table-status.warning { color:var(--mc-warning); background:var(--mc-status-warning-bg); }.table-status.muted { color:var(--mc-text-tertiary); }
.rehearsal { padding:1px 6px; border-radius:var(--mc-radius-sm); color:var(--mc-status-purple-text); background:var(--mc-status-info-bg); font-size:9px; }
.production-record { padding:1px 6px; border-radius:var(--mc-radius-sm); color:var(--mc-status-success-text); background:var(--mc-status-success-bg); font-size:9px; }
.case-code { color:var(--mc-text-tertiary)!important; font-weight:500!important; }
.traditional-list-empty { display:grid; place-items:center; align-content:center; min-height:420px; padding:36px; color:var(--mc-text-secondary); text-align:center; }
.empty-mark { display:grid; place-items:center; width:52px; height:52px; border:1px solid var(--mc-border); border-radius:var(--mc-radius-md); color:var(--mc-primary); background:var(--mc-bg-elevated); font-weight:800; box-shadow:var(--mc-shadow-soft); }
.traditional-list-empty h2 { margin:16px 0 5px; color:var(--mc-text-primary); font-size:19px; }
.traditional-list-empty p { max-width:520px; margin:0 0 16px; font-size:12px; line-height:1.65; }
.list-page-foot { display:flex; align-items:center; justify-content:space-between; padding:13px 2px 0; color:var(--mc-text-tertiary); font-size:10px; }
.list-page-foot button { border:0; background:none; color:var(--mc-primary); font:inherit; cursor:pointer; }
@media(max-width:980px){.pilot-workbench-strip{grid-template-columns:1fr}.pilot-owner-action{padding-top:14px;padding-left:0;border-top:1px solid var(--mc-border);border-left:0}.pilot-owner-action>.el-button{width:auto}}
@media(max-width:760px){.traditional-list-page{padding:20px 12px}.list-page-head{flex-direction:column}.list-page-head-actions{justify-content:flex-start}.pilot-workbench-strip{gap:18px;padding:18px 0}.pilot-progress span{font-size:9px}.list-page-toolbar{align-items:stretch;flex-wrap:wrap}.list-page-toolbar .el-select{width:100%}.list-page-toolbar>.el-button{margin-left:auto}.traditional-table-card{overflow-x:auto}.diagnosis-table{min-width:920px}}
</style>
