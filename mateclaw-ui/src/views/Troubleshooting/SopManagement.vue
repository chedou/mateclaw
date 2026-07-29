<template>
  <div class="sop-page">
    <header class="topbar">
      <div class="heading">
        <button class="back-link" type="button" @click="router.push('/troubleshooting')">
          <el-icon><ArrowLeft /></el-icon>
          诊断工作台
        </button>
        <span class="divider">/</span>
        <div>
          <h1>SOP 管理</h1>
          <p>确定性命中路的权威知识；新条目必须先以 candidate 注册。</p>
        </div>
      </div>
      <div class="top-actions">
        <el-button :icon="Refresh" :loading="listLoading" @click="reload">刷新</el-button>
        <el-button plain @click="synthesisOpen = true">无错误码证据预览</el-button>
        <el-button type="primary" :icon="Plus" @click="openRegister">注册 SOP</el-button>
      </div>
    </header>

    <section class="filterbar" aria-label="SOP 筛选">
      <el-select
        v-model="statusFilter"
        clearable
        placeholder="全部状态"
        style="width: 152px"
        @change="loadList()"
      >
        <el-option
          v-for="status in SOP_STATUSES"
          :key="status"
          :label="STATUS_LABEL[status]"
          :value="status"
        />
      </el-select>
      <el-input
        v-model="systemFilter"
        clearable
        placeholder="按 system 精确筛选"
        style="width: 220px"
        @clear="loadList()"
        @keyup.enter="loadList()"
      />
      <el-button @click="loadList()">查询</el-button>
      <button v-if="statusFilter || systemFilter" class="clear-filter" type="button" @click="clearFilters">
        清除筛选
      </button>
      <span class="registry-count">{{ rows.length }} 条路由</span>
      <div class="lifecycle" aria-label="SOP 生命周期">
        <span>candidate</span><i>→</i><span>approved</span><i>→</i><span>deprecated</span>
      </div>
    </section>

    <div class="workspace">
      <section class="registry" aria-label="SOP 注册表">
        <el-table
          :data="rows"
          :aria-busy="listLoading"
          row-key="routeKey"
          height="100%"
          :row-class-name="rowClassName"
          @row-click="selectSop"
        >
          <el-table-column label="路由键" min-width="170">
            <template #default="{ row }">
              <div class="route-cell">
                <strong>{{ row.system }}:{{ row.errorCode }}</strong>
                <span>{{ row.sopId }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="service" label="服务" min-width="130" />
          <el-table-column label="状态" width="126">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" size="small" effect="plain">
                {{ statusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="命中路" width="104">
            <template #default="{ row }">
              <span class="operational" :class="{ live: row.operational }">
                <i />{{ row.operational ? '已生效' : '未生效' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="166">
            <template #default="{ row }">
              <span class="mono muted">{{ formatTime(row.updateTime) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="!listLoading && !rows.length" class="empty-state">
          <strong>没有匹配的 SOP</strong>
          <p>新知识只能以 candidate 注册，再由管理员显式审核。</p>
          <el-button type="primary" plain @click="openRegister">注册第一条 SOP</el-button>
        </div>
      </section>

      <aside
        class="inspector"
        :class="{ 'is-loading': detailLoading }"
        :aria-busy="detailLoading"
        aria-label="SOP 详情检查器"
      >
        <div v-if="!selectedSop" class="inspector-empty">
          <span class="empty-mark">{ }</span>
          <strong>选择一条路由查看完整契约</strong>
          <p>列表只读索引列；判据、规则和建议动作按需加载。</p>
        </div>

        <Transition v-else name="inspector" mode="out-in">
          <div :key="selectedSop.system + ':' + selectedSop.errorCode" class="inspector-body">
            <div class="inspector-head">
              <div>
                <span class="eyebrow">{{ selectedSop.contractVersion }}</span>
                <h2>{{ selectedSop.title }}</h2>
                <div class="route-line">{{ selectedSop.system }}:{{ selectedSop.errorCode }}</div>
              </div>
              <el-tag :type="statusTagType(selectedSop.status)" effect="plain">
                {{ STATUS_LABEL[selectedSop.status] }}
              </el-tag>
            </div>

            <el-alert
              v-if="selectedSop.status === 'candidate'"
              type="warning"
              :closable="false"
              title="候选 SOP 不参与确定性诊断；审核通过后才进入命中路。"
            />
            <el-alert
              v-else-if="selectedSop.status === 'deprecated'"
              type="info"
              :closable="false"
              title="该版本已退出命中路；当前注册表按 routeKey 唯一，替代版本需等待版本化模型落地。"
            />

            <dl class="metadata">
              <div><dt>service</dt><dd>{{ selectedSop.service }}</dd></div>
              <div><dt>owner</dt><dd>{{ selectedSop.ownerTeam || '未指定' }}</dd></div>
              <div><dt>category</dt><dd>{{ selectedSop.category || '未分类' }}</dd></div>
              <div><dt>verified</dt><dd class="mono">{{ selectedSop.verified }}</dd></div>
            </dl>

            <section class="contract-health">
              <div class="section-title">
                <span>契约组成</span>
                <span v-if="contractWarnings.length" class="warning-count">
                  {{ contractWarnings.length }} 个审核提示
                </span>
              </div>
              <div class="counts">
                <div><b>{{ selectedSop.evidenceRequests.length }}</b><span>取证请求</span></div>
                <div><b>{{ selectedSop.anomalyCriteria.length }}</b><span>异常判据</span></div>
                <div><b>{{ selectedSop.diagnosisRules.length }}</b><span>诊断规则</span></div>
                <div><b>{{ selectedSop.actions.length }}</b><span>建议动作</span></div>
              </div>
              <ul v-if="contractWarnings.length" class="review-warnings">
                <li v-for="warning in contractWarnings" :key="warning">{{ warning }}</li>
              </ul>
            </section>

            <section v-if="containsManualWrite" class="redline-note">
              <strong>生产写红线</strong>
              <p>此 SOP 含 MANUAL_WRITE 建议，但平台只允许转派、批准状态推进和外部结果登记，绝不执行写操作。</p>
            </section>

            <section class="json-section">
              <div class="section-title">
                <span>完整 SOP JSON</span>
                <el-button size="small" text @click="copyContract">复制</el-button>
              </div>
              <pre>{{ prettyContract }}</pre>
            </section>

            <div class="review-action">
              <template v-if="nextStatus">
                <div>
                  <strong>{{ nextStatus === 'approved' ? '审核后进入命中路' : '将当前版本退出命中路' }}</strong>
                  <p v-if="nextStatus === 'approved'">
                    状态只能前进；错误批准只能先标记过期，当前 API 尚不能为同一路由直接注册替代版。
                  </p>
                  <p v-else>
                    标记后该路由将退出命中路；当前 API 尚不能为同一路由直接注册替代版。
                  </p>
                </div>
                <el-button
                  :type="nextStatus === 'approved' ? 'primary' : 'danger'"
                  :plain="nextStatus === 'deprecated'"
                  :loading="statusUpdating"
                  @click="advanceStatus"
                >{{ nextStatus === 'approved' ? '审核通过' : '标记过期' }}</el-button>
              </template>
              <span v-else>生命周期已结束；该版本只保留审计记录。</span>
            </div>
          </div>
        </Transition>
      </aside>
    </div>

    <el-dialog
      v-model="registerOpen"
      title="注册候选 SOP"
      width="720px"
      destroy-on-close
      :teleported="false"
    >
      <el-alert type="info" :closable="false" class="register-note">
        <template #title>
          只接受单个 JSON 对象，并强制以 <code>candidate + verified=false</code> 注册。路由键冲突会拒绝覆盖。
        </template>
      </el-alert>
      <el-input
        v-model="registerJson"
        type="textarea"
        :rows="20"
        resize="vertical"
        spellcheck="false"
        class="json-input"
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
        <el-button @click="registerOpen = false">取消</el-button>
        <el-button
          type="primary"
          :disabled="!importValidation.sop"
          :loading="registering"
          @click="registerSop"
        >注册为 candidate</el-button>
      </template>
    </el-dialog>

    <SynthesisPreviewDialog v-model="synthesisOpen" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { copyToClipboard } from '@/utils/clipboard'
import {
  troubleshootingApi,
  type SopEntry,
  type SopStatus,
  type SopSummary,
} from '@/api'
import { nextSopStatus, parseCandidateSopJson } from './sopRegistry'
import SynthesisPreviewDialog from './SynthesisPreviewDialog.vue'

const SOP_STATUSES: SopStatus[] = ['candidate', 'approved', 'deprecated']
const STATUS_LABEL: Record<SopStatus, string> = {
  candidate: '待审核',
  approved: '已生效',
  deprecated: '已过期',
}
const EMPTY_TEMPLATE = JSON.stringify({
  sopId: '',
  contractVersion: 'sop.v1',
  system: '',
  errorCode: '',
  service: '',
  title: '',
  cause: '',
  category: '',
  ownerTeam: '',
  status: 'candidate',
  verified: false,
  evidenceRequests: [],
  anomalyCriteria: [],
  diagnosisRules: [],
  actions: [],
}, null, 2)

const router = useRouter()
const rows = ref<SopSummary[]>([])
const selectedSop = ref<SopEntry | null>(null)
const selectedRouteKey = ref<string | null>(null)
const statusFilter = ref<SopStatus | ''>('')
const systemFilter = ref('')
const listLoading = ref(false)
const detailLoading = ref(false)
const statusUpdating = ref(false)
const registerOpen = ref(false)
const synthesisOpen = ref(false)
const registering = ref(false)
const registerJson = ref(EMPTY_TEMPLATE)
let detailRequest = 0

const nextStatus = computed(() => selectedSop.value
  ? nextSopStatus(selectedSop.value.status)
  : null)

const prettyContract = computed(() => selectedSop.value
  ? JSON.stringify(selectedSop.value, null, 2)
  : '')

const containsManualWrite = computed(() => selectedSop.value?.actions.some((action) =>
  action.actionType === 'MANUAL_WRITE') ?? false)

const contractWarnings = computed(() => {
  const sop = selectedSop.value
  if (!sop) return []
  const warnings: string[] = []
  if (!sop.evidenceRequests.length) warnings.push('没有取证请求；审核前确认该路由是否真的无需证据。')
  if (!sop.anomalyCriteria.length) warnings.push('没有异常判据；当前合同无法形成可解释的信号。')
  if (!sop.diagnosisRules.length) warnings.push('没有诊断规则；当前合同不能产出确定性根因。')
  if (sop.status === 'approved' && !sop.verified) warnings.push('状态与 verified 不一致，应停止使用并检查数据。')
  return warnings
})

const importValidation = computed<{ sop: SopEntry | null; error: string | null }>(() => {
  try {
    return { sop: parseCandidateSopJson(registerJson.value), error: null }
  } catch (error) {
    return { sop: null, error: error instanceof Error ? error.message : 'SOP JSON 无效' }
  }
})

async function loadList() {
  listLoading.value = true
  try {
    const { data } = await troubleshootingApi.listSops({
      status: statusFilter.value || undefined,
      system: systemFilter.value.trim() || undefined,
      limit: 500,
    })
    rows.value = data ?? []
    const selected = rows.value.find((row) => row.routeKey === selectedRouteKey.value)
    if (selected) return
    if (rows.value.length) {
      await selectSop(rows.value[0])
    } else {
      selectedRouteKey.value = null
      selectedSop.value = null
    }
  } finally {
    listLoading.value = false
  }
}

async function selectSop(row: SopSummary) {
  selectedRouteKey.value = row.routeKey
  const request = ++detailRequest
  detailLoading.value = true
  try {
    const { data } = await troubleshootingApi.getSop(row.system, row.errorCode)
    if (request === detailRequest) selectedSop.value = data
  } finally {
    if (request === detailRequest) detailLoading.value = false
  }
}

async function reload() {
  await loadList()
  const row = rows.value.find((item) => item.routeKey === selectedRouteKey.value)
  if (row) await selectSop(row)
}

function clearFilters() {
  statusFilter.value = ''
  systemFilter.value = ''
  loadList()
}

function openRegister() {
  registerJson.value = EMPTY_TEMPLATE
  registerOpen.value = true
}

async function registerSop() {
  const sop = importValidation.value.sop
  if (!sop) return
  registering.value = true
  try {
    const { data } = await troubleshootingApi.registerSop(sop)
    registerOpen.value = false
    statusFilter.value = ''
    systemFilter.value = ''
    selectedRouteKey.value = `${data.system.toLowerCase()}:${data.errorCode}`
    selectedSop.value = data
    await loadList()
    ElMessage.success(`已注册候选 SOP ${data.system}:${data.errorCode}`)
  } finally {
    registering.value = false
  }
}

async function advanceStatus() {
  const sop = selectedSop.value
  const target = nextStatus.value
  if (!sop || !target) return
  const approving = target === 'approved'
  const title = approving
    ? `审核通过 ${sop.system}:${sop.errorCode}？`
    : `标记 ${sop.system}:${sop.errorCode} 为过期？`
  const message = approving
    ? '通过后，该 SOP 会开始驱动未来故障的确定性结论。请确认取证、判据、规则和动作均已审核。'
    : '过期后，该版本立即退出命中路且不能恢复；当前 API 尚不能为同一路由直接注册替代版。'
  try {
    await ElMessageBox.confirm(message, title, {
      confirmButtonText: approving ? '审核通过' : '标记过期',
      cancelButtonText: '取消',
      type: approving ? 'warning' : 'error',
      customClass: 'sop-status-confirm',
    })
  } catch {
    return
  }

  statusUpdating.value = true
  try {
    const { data } = await troubleshootingApi.updateSopStatus(
      sop.system, sop.errorCode, target)
    selectedSop.value = data
    statusFilter.value = ''
    await loadList()
    ElMessage.success(approving ? 'SOP 已审核生效' : 'SOP 已标记过期')
  } finally {
    statusUpdating.value = false
  }
}

async function copyContract() {
  await copyToClipboard(prettyContract.value)
  ElMessage.success('SOP JSON 已复制')
}

function rowClassName({ row }: { row: SopSummary }) {
  return row.routeKey === selectedRouteKey.value ? 'selected-row' : ''
}

function statusTagType(status: SopStatus): 'warning' | 'success' | 'info' {
  if (status === 'candidate') return 'warning'
  if (status === 'approved') return 'success'
  return 'info'
}

function statusLabel(status: SopStatus) {
  return STATUS_LABEL[status]
}

function formatTime(value?: string | null) {
  if (!value) return '—'
  return value.replace('T', ' ').replace(/\.\d+Z?$/, '').slice(0, 19)
}

onMounted(loadList)
</script>

<style scoped>
.sop-page {
  --ts-signal: #2f5cf5;
  --el-color-primary: var(--ts-signal);
  --el-color-primary-light-3: color-mix(in srgb, var(--ts-signal) 70%, var(--el-bg-color));
  --el-color-primary-light-5: color-mix(in srgb, var(--ts-signal) 50%, var(--el-bg-color));
  --el-color-primary-light-7: color-mix(in srgb, var(--ts-signal) 30%, var(--el-bg-color));
  --el-color-primary-light-8: color-mix(in srgb, var(--ts-signal) 20%, var(--el-bg-color));
  --el-color-primary-light-9: color-mix(in srgb, var(--ts-signal) 10%, var(--el-bg-color));
  --el-color-primary-dark-2: color-mix(in srgb, var(--ts-signal) 80%, black);
  --el-color-warning-light-8: color-mix(in srgb, var(--el-color-warning) 20%, var(--el-bg-color));
  --el-color-warning-light-9: color-mix(in srgb, var(--el-color-warning) 10%, var(--el-bg-color));
  --el-color-danger-light-8: color-mix(in srgb, var(--el-color-danger) 20%, var(--el-bg-color));
  --el-color-danger-light-9: color-mix(in srgb, var(--el-color-danger) 10%, var(--el-bg-color));
  --el-color-success-light-8: color-mix(in srgb, var(--el-color-success) 20%, var(--el-bg-color));
  --el-color-success-light-9: color-mix(in srgb, var(--el-color-success) 10%, var(--el-bg-color));
  --el-color-info-light-8: color-mix(in srgb, var(--el-color-info) 20%, var(--el-bg-color));
  --el-color-info-light-9: color-mix(in srgb, var(--el-color-info) 10%, var(--el-bg-color));
  display: flex;
  flex-direction: column;
  height: 100%;
  min-width: 0;
  overflow: hidden;
  background: var(--el-bg-color);
  color: var(--el-text-color-primary);
}

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

.filterbar {
  min-height: 48px;
  padding: 8px 14px;
  display: flex;
  align-items: center;
  gap: 8px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  background: var(--el-fill-color-blank);
}
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

.workspace { flex: 1; min-height: 0; display: grid; grid-template-columns: minmax(560px, 1fr) 430px; }
.registry { min-width: 0; min-height: 0; position: relative; border-right: 1px solid var(--el-border-color-lighter); }
.route-cell { display: flex; flex-direction: column; gap: 2px; }
.route-cell strong { font: 600 12px var(--mc-mono, monospace); color: var(--el-text-color-primary); }
.route-cell span { font: 10px var(--mc-mono, monospace); color: var(--el-text-color-placeholder); }
.mono { font-family: var(--mc-mono, monospace); }
.muted { color: var(--el-text-color-secondary); font-size: 10.5px; }
.operational { display: inline-flex; align-items: center; gap: 6px; color: var(--el-text-color-secondary); font-size: 11px; }
.operational i { width: 6px; height: 6px; border-radius: 50%; background: var(--el-text-color-placeholder); }
.operational.live { color: var(--el-color-success); }
.operational.live i {
  background: var(--el-color-success);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--el-color-success) 16%, transparent);
}
.empty-state {
  position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center;
  justify-content: center; text-align: center; pointer-events: none;
}
.empty-state p { margin: 6px 0 14px; font-size: 12px; color: var(--el-text-color-secondary); }
.empty-state .el-button { pointer-events: auto; }

.inspector {
  min-width: 0; overflow-y: auto;
  background: color-mix(in srgb, var(--el-bg-color) 96%, var(--el-text-color-primary) 4%);
  transition: opacity 120ms ease;
}
.inspector.is-loading { opacity: .62; pointer-events: none; }
.inspector-empty {
  min-height: 65%; display: flex; flex-direction: column; align-items: center; justify-content: center;
  padding: 28px; text-align: center; color: var(--el-text-color-secondary);
}
.inspector-empty strong { color: var(--el-text-color-primary); font-size: 13px; }
.inspector-empty p { max-width: 280px; margin: 7px 0 0; font-size: 11.5px; line-height: 1.6; }
.empty-mark { margin-bottom: 14px; font: 24px var(--mc-mono, monospace); color: var(--el-text-color-placeholder); }
.inspector-body { padding: 18px 18px 28px; }
.inspector-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin-bottom: 14px; }
.eyebrow { color: var(--el-text-color-secondary); font: 10px var(--mc-mono, monospace); }
.inspector-head h2 { margin: 5px 0 4px; font-size: 17px; line-height: 1.45; }
.route-line { color: var(--ts-signal); font: 600 11.5px var(--mc-mono, monospace); }
.metadata { margin: 16px 0 0; display: grid; grid-template-columns: 1fr 1fr; border-top: 1px solid var(--el-border-color-lighter); }
.metadata div { padding: 9px 0; border-bottom: 1px solid var(--el-border-color-lighter); }
.metadata div:nth-child(odd) { padding-right: 12px; }
.metadata dt { color: var(--el-text-color-secondary); font: 10px var(--mc-mono, monospace); }
.metadata dd { margin: 4px 0 0; font-size: 11.5px; color: var(--el-text-color-primary); }
.contract-health, .json-section { margin-top: 18px; }
.section-title { display: flex; align-items: center; justify-content: space-between; margin-bottom: 9px; font-size: 12px; font-weight: 650; }
.warning-count { color: var(--el-color-warning); font-size: 10.5px; font-weight: 500; }
.counts { display: grid; grid-template-columns: repeat(4, 1fr); border: 1px solid var(--el-border-color-lighter); border-radius: 6px; overflow: hidden; }
.counts div { padding: 9px 6px; text-align: center; border-right: 1px solid var(--el-border-color-lighter); }
.counts div:last-child { border-right: 0; }
.counts b { display: block; font: 650 15px var(--mc-mono, monospace); }
.counts span { display: block; margin-top: 2px; color: var(--el-text-color-secondary); font-size: 9.5px; }
.review-warnings {
  margin: 8px 0 0; padding: 9px 11px 9px 27px; border-radius: 5px;
  background: color-mix(in srgb, var(--el-color-warning) 10%, var(--el-bg-color));
  color: var(--el-text-color-regular); font-size: 10.5px; line-height: 1.65;
}
.redline-note {
  margin-top: 14px; padding: 10px 11px; border-left: 2px solid var(--el-color-danger);
  background: color-mix(in srgb, var(--el-color-danger) 10%, var(--el-bg-color));
}
.redline-note strong { font-size: 11.5px; color: var(--el-color-danger); }
.redline-note p { margin: 4px 0 0; font-size: 10.5px; line-height: 1.6; color: var(--el-text-color-regular); }
.json-section pre {
  max-height: 330px; margin: 0; padding: 11px; overflow: auto; border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px; background: var(--el-bg-color); color: var(--el-text-color-regular);
  font: 10px/1.6 var(--mc-mono, monospace); white-space: pre-wrap; word-break: break-word;
}
.review-action {
  margin-top: 18px; padding-top: 14px; border-top: 1px solid var(--el-border-color-lighter);
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
}
.review-action strong { font-size: 12px; }
.review-action p { margin: 3px 0 0; color: var(--el-text-color-secondary); font-size: 10.5px; line-height: 1.5; }
.review-action > span { color: var(--el-text-color-secondary); font-size: 11px; }
.register-note { margin-bottom: 12px; }
.register-note code, .validation code { font-family: var(--mc-mono, monospace); }
.json-input :deep(textarea) { font-family: var(--mc-mono, monospace); font-size: 11px; line-height: 1.55; }
.validation { min-height: 20px; margin-top: 9px; color: var(--el-color-danger); font-size: 11px; line-height: 1.5; }
.validation.valid { color: var(--el-color-success); }
.validation-dot { display: inline-block; width: 6px; height: 6px; margin-right: 5px; border-radius: 50%; background: currentColor; }

:deep(.el-table) {
  --el-table-row-hover-bg-color: color-mix(in srgb, var(--ts-signal) 7%, var(--el-bg-color));
}
:deep(.el-table__row) { cursor: pointer; transition: background-color 140ms ease; }
:deep(.selected-row > td.el-table__cell) {
  background: color-mix(in srgb, var(--ts-signal) 12%, var(--el-bg-color)) !important;
}
:deep(.selected-row > td:first-child) { box-shadow: inset 3px 0 0 var(--ts-signal); }
.inspector-enter-active, .inspector-leave-active { transition: opacity 150ms ease, transform 150ms ease; }
.inspector-enter-from { opacity: 0; transform: translateX(6px); }
.inspector-leave-to { opacity: 0; transform: translateX(-4px); }

:global(.sop-status-confirm) {
  --el-color-primary: #2f5cf5;
  --el-color-primary-light-3: color-mix(in srgb, #2f5cf5 70%, var(--el-bg-color));
  --el-color-primary-dark-2: color-mix(in srgb, #2f5cf5 80%, black);
}

@media (max-width: 1280px) {
  .sop-page { height: auto; min-height: 100%; overflow: auto; }
  .workspace {
    grid-template-columns: 1fr;
    grid-template-rows: 520px auto;
    align-content: start;
    overflow: visible;
  }
  .registry { height: 520px; border-right: 0; border-bottom: 1px solid var(--el-border-color-lighter); }
  .inspector { overflow: visible; }
  .lifecycle { display: none; }
}

@media (max-width: 720px) {
  .topbar { align-items: flex-start; flex-direction: column; }
  .top-actions { width: 100%; }
  .top-actions .el-button { flex: 1; }
  .filterbar { align-items: stretch; flex-wrap: wrap; }
  .filterbar .el-select, .filterbar .el-input { width: calc(50% - 4px) !important; }
  .registry-count { margin-left: auto; align-self: center; }
  :deep(.el-dialog) { max-width: calc(100vw - 32px); }
}

@media (prefers-reduced-motion: reduce) {
  :deep(.el-table__row), .inspector, .inspector-enter-active, .inspector-leave-active { transition: none; }
}
</style>
