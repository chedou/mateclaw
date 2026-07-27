<template>
  <div class="ts-page">
    <!-- duty queue -->
    <aside class="queue">
      <header class="qhead">
        <b>值班队列</b>
        <el-tag size="small" type="info" round>{{ rows.length }}</el-tag>
        <el-button
          v-if="canManageSops"
          class="sop-link"
          size="small"
          text
          @click="router.push('/troubleshooting/sops')"
        >SOP 管理</el-button>
      </header>
      <div class="qfilter">
        <el-select v-model="statusFilter" size="small" clearable placeholder="全部状态" @change="loadList">
          <el-option v-for="s in STATUSES" :key="s" :label="STATUS_LABEL[s]" :value="s" />
        </el-select>
      </div>

      <div v-loading="listLoading" class="qlist">
        <button
          v-for="row in rows"
          :key="row.diagnosisId"
          type="button"
          class="qitem"
          :class="{ active: row.diagnosisId === selectedId }"
          @click="select(row.diagnosisId)"
        >
          <div class="qi-top">
            <span class="qi-code">{{ row.system }}:{{ row.errorCode ?? '—' }}</span>
            <el-tag v-if="row.rehearsal" size="small" type="info">演练</el-tag>
          </div>
          <div class="qi-svc">{{ row.service }}</div>
          <div class="qi-bot">
            <span class="qi-status" :class="statusClass(row.status)">{{ STATUS_LABEL[row.status] }}</span>
            <span class="qi-time">{{ shortTime(row.updateTime) }}</span>
          </div>
        </button>
        <p v-if="!listLoading && !rows.length" class="qempty">
          还没有诊断。用 <code>POST /api/v1/troubleshooting/incidents</code> 报一次故障即可出现在这里。
        </p>
      </div>
    </aside>

    <!-- detail -->
    <main class="detail" v-loading="detailLoading">
      <div v-if="!current" class="placeholder">
        <p>从左侧选一条诊断查看根因判定链。</p>
      </div>

      <template v-else>
        <!-- 第一层 · 结论：给服务经理和业务看的，默认且唯一展开的一层 -->
        <section class="verdict">
          <div class="vhead">
            <span class="chip cls">{{ classLabel }}</span>
            <span class="chip" :class="'conf-' + current.diagnosis.confidence">
              置信度 {{ current.diagnosis.confidence }}
            </span>
            <span class="chip st" :class="statusClass(current.diagnosis.status)">
              {{ STATUS_LABEL[current.diagnosis.status] }}
            </span>
            <span class="spacer" />
            <el-button size="small" :icon="Refresh" text @click="reload">刷新</el-button>
          </div>

          <h1 class="vtitle">
            {{ current.diagnosis.rootCause || current.diagnosis.summary || '未产出根因' }}
          </h1>
          <p class="vsub">{{ subline }}</p>

          <div class="trio">
            <div class="cell">
              <div class="clab">问题描述</div>
              <p>{{ current.diagnosis.summary || current.diagnosis.incident.title || '—' }}</p>
            </div>

            <div class="cell">
              <div class="clab">影响面</div>
              <p>{{ impactView.functionScope }}</p>
              <div v-if="impactView.hasCounts" class="metric">
                <span class="big">{{ impactView.customers }}</span><span>个客户</span>
                <span class="big gap">{{ impactView.users }}</span><span>名用户</span>
              </div>
              <span v-if="impactView.radius" class="radius" :class="impactView.radius">
                {{ RADIUS_LABEL[impactView.radius] }}
              </span>
            </div>

            <div class="cell">
              <div class="clab">{{ nextStep.title }}</div>
              <p>{{ nextStep.text }}</p>
              <div v-if="nextStep.locate" class="locate">{{ nextStep.locate }}</div>
              <p v-if="nextStep.boundary" class="bound">{{ nextStep.boundary }}</p>
            </div>
          </div>

          <div class="ops">
            <el-button
              v-if="current.diagnosis.status === 'READY_FOR_HUMAN'"
              type="primary"
              @click="confirm"
            >确认结论</el-button>
            <el-button v-if="canTransfer" @click="transferOpen = true">转派</el-button>
            <el-button v-if="canClose" @click="closeOpen = true">关闭并沉淀知识</el-button>
            <span v-if="current.diagnosis.status === 'NEEDS_INVESTIGATION'" class="hint">
              弃权的诊断需要补充证据后重新诊断才能确认。
            </span>
            <span v-else-if="current.diagnosis.status === 'CLOSED'" class="hint">已关闭。</span>
          </div>
        </section>

        <!-- 第二层 · 判定链：给开发看的，默认折叠 -->
        <details class="fold">
          <summary>
            <span class="caret" />
            <b>为什么是这个结论</b>
            <span class="srole">判定链 · 面向开发</span>
            <span class="shint">{{ chainHint }}</span>
          </summary>
          <div class="fbody">
            <DerivationChain :diagnosis="current.diagnosis" />

            <template v-if="current.diagnosis.recommendedActions.length">
              <div class="snum">第四步 · 可执行的动作（平台不执行）</div>
              <article
                v-for="act in current.diagnosis.recommendedActions"
                :key="act.actionId"
                class="act"
                :class="{ write: act.actionType === 'MANUAL_WRITE' }"
              >
                <div class="a-top">
                  <span class="atype" :class="act.actionType">{{ act.actionType }}</span>
                  <span class="atitle">{{ act.title }}</span>
                  <span class="astate">{{ act.approvalStatus }} · {{ act.executionStatus }}</span>
                </div>
                <p v-if="act.description" class="adesc">{{ act.description }}</p>
                <div v-if="canApprove(act) || canRecordOutcome(act)" class="arow">
                  <el-button v-if="canApprove(act)" size="small" type="warning" plain @click="openApprove(act)">
                    批准（只推进状态，系统不执行）
                  </el-button>
                  <el-button v-if="canRecordOutcome(act)" size="small" plain @click="openOutcome(act)">
                    登记外部处置结果
                  </el-button>
                </div>
              </article>
            </template>
          </div>
        </details>

        <!-- 第三层 · 运行细节：给审计和排障系统自己看的，默认折叠 -->
        <details class="fold">
          <summary>
            <span class="caret" />
            <b>运行细节</b>
            <span class="srole">面向审计</span>
            <span class="shint">
              {{ current.diagnosis.routeMode }}{{ current.diagnosis.fixtureMode ? ' · fixtureMode' : '' }}
            </span>
          </summary>
          <div class="fbody">
            <dl class="kv">
              <template v-for="[k, v] in auditRows" :key="k">
                <dt>{{ k }}</dt><dd>{{ v }}</dd>
              </template>
            </dl>

            <div class="tl">
              <div
                v-for="(t, i) in current.diagnosis.timeline"
                :key="i"
                class="tli"
                :class="{ cur: t.status === 'current' }"
              >
                {{ t.event }}
                <span class="tm">{{ shortTime(t.timestamp) }} · {{ t.actor }}</span>
              </div>
            </div>

            <div v-if="current.diagnosis.warnings.length" class="warns">
              <b>契约自曝的能力边界</b>
              <ul>
                <li v-for="(w, i) in current.diagnosis.warnings" :key="i">{{ w }}</li>
              </ul>
            </div>

            <p class="redline">
              平台没有生产写执行器：批准只把动作推进到 <code>APPROVED_NOT_EXECUTED</code>，
              真正的变更由有权限的人在 MateClaw 之外执行，回来登记结果。
            </p>
          </div>
        </details>
      </template>
    </main>

    <!-- transfer -->
    <el-dialog v-model="transferOpen" title="结构化转派" width="440px">
      <el-form label-position="top">
        <el-form-item label="目标团队">
          <el-input v-model="transferForm.targetTeam" placeholder="如 DBA 组" />
        </el-form-item>
        <el-form-item label="转派说明（接收方靠它免于重新排查）">
          <el-input v-model="transferForm.note" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="transferOpen = false">取消</el-button>
        <el-button type="primary" :disabled="!transferForm.targetTeam || !transferForm.note" @click="transfer">
          转派
        </el-button>
      </template>
    </el-dialog>

    <!-- approve -->
    <el-dialog v-model="approveOpen" title="批准生产写操作" width="460px">
      <el-alert type="warning" :closable="false" class="dlg-alert">
        批准只推进状态机，系统不执行任何操作。变更需由有权限的人在平台外完成。
      </el-alert>
      <el-form label-position="top">
        <el-form-item label="批准理由（审计依据，必填）">
          <el-input v-model="approveForm.reason" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="approveOpen = false">取消</el-button>
        <el-button type="warning" :disabled="!approveForm.reason" @click="approve">
          批准（不执行）
        </el-button>
      </template>
    </el-dialog>

    <!-- record outcome -->
    <el-dialog v-model="outcomeOpen" title="登记外部处置结果" width="460px">
      <el-form label-position="top">
        <el-form-item label="处置结果">
          <el-select v-model="outcomeForm.outcome" style="width: 100%">
            <el-option label="SUCCEEDED · 成功" value="SUCCEEDED" />
            <el-option label="FAILED · 失败" value="FAILED" />
            <el-option label="SKIPPED · 未执行" value="SKIPPED" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="outcomeForm.notes" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item>
          <el-checkbox
            v-model="outcomeForm.recoveryVerified"
            :disabled="outcomeForm.outcome !== 'SUCCEEDED'"
          >已验证故障恢复</el-checkbox>
          <div class="sub-hint">动作成功不等于故障恢复；只有已验证恢复才能支撑 RECOVERED 关闭。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="outcomeOpen = false">取消</el-button>
        <el-button type="primary" :disabled="!outcomeForm.notes" @click="recordOutcome">登记</el-button>
      </template>
    </el-dialog>

    <!-- close -->
    <el-dialog v-model="closeOpen" title="关闭归档" width="460px">
      <el-form label-position="top">
        <el-form-item label="关闭结论">
          <el-select v-model="closeForm.outcome" style="width: 100%">
            <el-option label="RECOVERED · 已恢复" value="RECOVERED" />
            <el-option label="FALSE_POSITIVE · 误报" value="FALSE_POSITIVE" />
            <el-option label="TRANSFERRED_OUT · 转出处置" value="TRANSFERRED_OUT" />
            <el-option label="UNRESOLVED · 未解决" value="UNRESOLVED" />
          </el-select>
        </el-form-item>
        <el-form-item label="关闭摘要">
          <el-input v-model="closeForm.summary" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item v-if="closeForm.outcome === 'RECOVERED'">
          <el-checkbox v-model="closeForm.recoveryVerified">已验证恢复（RECOVERED 必填）</el-checkbox>
        </el-form-item>
        <el-form-item label="对 SOP 的反馈（可选）">
          <el-input v-model="closeForm.sopFeedback" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="closeForm.createKnowledgeCandidate">生成知识候选</el-checkbox>
          <div class="sub-hint">候选只进审核队列，永不直接覆盖已审核 SOP。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeOpen = false">取消</el-button>
        <el-button type="primary" :disabled="!closeForm.summary" @click="close">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import DerivationChain from './DerivationChain.vue'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import {
  troubleshootingApi,
  type ActionOutcomeStatus,
  type ClosureOutcome,
  type DiagnosisStatus,
  type DiagnosisSummary,
  type RecommendedAction,
  type StoredDiagnosis,
} from '@/api'

const STATUSES: DiagnosisStatus[] = [
  'READY_FOR_HUMAN', 'NEEDS_INVESTIGATION', 'CONFIRMED', 'TRANSFERRED', 'CLOSED',
]

const router = useRouter()
const workspaceStore = useWorkspaceStore()
const canManageSops = computed(() => workspaceStore.can('manage:troubleshooting'))
const STATUS_LABEL: Record<DiagnosisStatus, string> = {
  READY_FOR_HUMAN: '待确认',
  NEEDS_INVESTIGATION: '转人工深查',
  CONFIRMED: '已确认',
  TRANSFERRED: '已转派',
  CLOSED: '已关闭',
}

const rows = ref<DiagnosisSummary[]>([])
const listLoading = ref(false)
const detailLoading = ref(false)
const selectedId = ref<string | null>(null)
const current = ref<StoredDiagnosis | null>(null)
const statusFilter = ref<DiagnosisStatus | ''>('')

const transferOpen = ref(false)
const approveOpen = ref(false)
const outcomeOpen = ref(false)
const closeOpen = ref(false)
const targetAction = ref<RecommendedAction | null>(null)

const transferForm = reactive({ targetTeam: '', note: '' })
const approveForm = reactive({ reason: '' })
const outcomeForm = reactive({
  outcome: 'SUCCEEDED' as ActionOutcomeStatus, notes: '', recoveryVerified: false,
})
const closeForm = reactive({
  outcome: 'RECOVERED' as ClosureOutcome, summary: '',
  recoveryVerified: false, sopFeedback: '', createKnowledgeCandidate: true,
})

const canTransfer = computed(() =>
  current.value?.diagnosis.status === 'CONFIRMED' || current.value?.diagnosis.status === 'TRANSFERRED')
const canClose = computed(() => canTransfer.value)

/**
 * `faultClass` and the structured `IncidentImpact` land with contract v1.4 (TODO T13/T14).
 * The page renders them when present and degrades to today's fields when absent, so the
 * three-layer rework does not have to wait on the contract change.
 */
type FaultClass =
  | 'CODE_BUG' | 'DATA_FIX' | 'BUSINESS_OPERATION' | 'EXTERNAL_CLIENT' | 'INFRASTRUCTURE'
type BlastRadius = 'SINGLE_CUSTOMER' | 'MULTI_CUSTOMER' | 'SYSTEM_WIDE' | 'UNKNOWN'

const CLASS_LABEL: Record<FaultClass, string> = {
  CODE_BUG: '代码缺陷类 · 只定位不给方案',
  DATA_FIX: '数据类 · 可给方案',
  BUSINESS_OPERATION: '业务操作类 · 可给方案',
  EXTERNAL_CLIENT: '外部客户端类 · 只排除不定位',
  INFRASTRUCTURE: '基础设施类 · 可定位到组件',
}
const RADIUS_LABEL: Record<BlastRadius, string> = {
  SINGLE_CUSTOMER: '仅单个客户',
  MULTI_CUSTOMER: '多客户同时报错',
  SYSTEM_WIDE: '系统级影响',
  UNKNOWN: '影响面未知',
}

const faultClass = computed<FaultClass | null>(() => {
  const value = (current.value?.diagnosis as { faultClass?: FaultClass } | undefined)?.faultClass
  return value && value in CLASS_LABEL ? value : null
})

const classLabel = computed(() =>
  faultClass.value ? CLASS_LABEL[faultClass.value] : '故障类别待补充（契约 v1.4）')

const subline = computed(() => {
  const i = current.value?.diagnosis.incident
  if (!i) return ''
  const code = i.errorCode ? `error_code ${i.errorCode}` : '无 error_code'
  const sop = current.value?.diagnosis.sopKey
  return [code, i.service, sop ? `SOP ${sop}` : '未命中已注册 SOP'].join(' · ')
})

const impactView = computed(() => {
  const incident = current.value?.diagnosis.incident
  const raw: unknown = incident?.impact
  if (raw && typeof raw === 'object') {
    const s = raw as {
      functionScope?: string; affectedCustomers?: number
      affectedUsers?: number; radius?: BlastRadius
    }
    return {
      functionScope: s.functionScope || '待确认',
      customers: s.affectedCustomers ?? 0,
      users: s.affectedUsers ?? 0,
      hasCounts: s.affectedCustomers != null || s.affectedUsers != null,
      radius: s.radius && s.radius in RADIUS_LABEL ? s.radius : null,
    }
  }
  return {
    functionScope: (raw as string) || '待确认',
    customers: 0, users: 0, hasCounts: false, radius: null as BlastRadius | null,
  }
})

/**
 * The third cell changes shape with the fault class, because the system's promise
 * changes with it: a code bug can only be located, a client-side issue can only be
 * ruled out. Saying "solution" for either would overstate what we can deliver.
 */
const nextStep = computed(() => {
  const d = current.value?.diagnosis
  if (!d) return { title: '下一步', text: '—', locate: '', boundary: '' }
  const first = d.recommendedActions[0]
  if (faultClass.value === 'CODE_BUG') {
    return {
      title: '定位结果',
      text: first?.description || first?.title
        || '系统只能定位到代码位置，不给解决方案，也无法自动恢复——这类问题必须改代码。',
      locate: d.routeToTeam ? `建议交由 ${d.routeToTeam}` : '',
      boundary: '代码缺陷类的能力边界：AI 定位到可疑代码区域，修复方案由开发判断。',
    }
  }
  if (faultClass.value === 'EXTERNAL_CLIENT') {
    return {
      title: '排除结论',
      text: first?.description || first?.title || '系统侧功能未见异常。',
      locate: '',
      boundary: '这是「排除」不是「定位」：我们能证明系统侧没问题，但无法定位客户端根因。',
    }
  }
  if (d.abstained || !first) {
    return {
      title: '下一步',
      text: d.routeMode === 'LLM_FALLBACK'
        ? '只读 Agent 路径不产出恢复建议，只能给出待人工确认的假设。'
        : '本次弃权，契约保证不输出任何恢复建议——需要补充证据后重新诊断。',
      locate: '', boundary: '',
    }
  }
  return {
    title: '解决方案',
    text: first.description || first.title,
    locate: '',
    boundary: '平台不执行任何生产变更；批准只推进状态，变更由有权限的人在平台外完成。',
  }
})

const chainHint = computed(() => {
  const d = current.value?.diagnosis
  if (!d) return ''
  return `${d.evidence.length} 条证据 · ${d.triggeredSignals.length} 条判据成立`
})

const auditRows = computed<[string, string][]>(() => {
  const d = current.value?.diagnosis
  if (!d) return []
  const i = d.incident
  return [
    ['diagnosisId', d.diagnosisId],
    ['routeMode', d.routeMode],
    ['sopKey', d.sopKey ?? 'null'],
    ['contractVersion', d.contractVersion],
    ['fixtureMode', String(d.fixtureMode)],
    ['rehearsal', String(d.rehearsal)],
    ['writeExecutionEnabled', String(d.writeExecutionEnabled)],
    ['system / service', `${i.system} / ${i.service}`],
    ['errorCode', i.errorCode ?? 'null'],
    ['severity / completeness', `${i.severity} / ${i.completeness}`],
    ['intakeSource', i.intakeSource],
    ['traceId', i.traceId ?? 'null'],
    ['slaRemaining', i.slaRemaining ?? 'null'],
  ]
})

function statusClass(status: DiagnosisStatus) {
  if (status === 'NEEDS_INVESTIGATION') return 'warn'
  if (status === 'CLOSED') return 'muted'
  return 'ready'
}

function shortTime(value?: string | null) {
  if (!value) return '—'
  return value.replace('T', ' ').replace(/\.\d+Z?$/, '').slice(0, 19)
}

/** A pending manual write is the only thing a human may authorize. */
function canApprove(act: RecommendedAction) {
  return act.actionType === 'MANUAL_WRITE'
    && act.approvalStatus === 'PENDING'
    && canTransfer.value
}

/** An outcome can only be reported for a write that was already authorized. */
function canRecordOutcome(act: RecommendedAction) {
  return act.actionType === 'MANUAL_WRITE'
    && act.approvalStatus === 'APPROVED_NOT_EXECUTED'
    && canTransfer.value
}

async function loadList() {
  listLoading.value = true
  try {
    const { data } = await troubleshootingApi.list({
      status: statusFilter.value || undefined, limit: 100,
    })
    rows.value = data ?? []
  } finally {
    listLoading.value = false
  }
}

async function select(diagnosisId: string) {
  selectedId.value = diagnosisId
  detailLoading.value = true
  try {
    const { data } = await troubleshootingApi.get(diagnosisId)
    current.value = data
  } finally {
    detailLoading.value = false
  }
}

function reload() {
  if (selectedId.value) select(selectedId.value)
  loadList()
}

/** Applies a lifecycle result: the response is the new aggregate, so trust it. */
function applied(stored: StoredDiagnosis, message: string) {
  current.value = stored
  ElMessage.success(message)
  loadList()
}

async function confirm() {
  const { data } = await troubleshootingApi.confirm(selectedId.value!)
  applied(data, '已确认诊断结论')
}

async function transfer() {
  const { data } = await troubleshootingApi.transfer(selectedId.value!, { ...transferForm })
  transferOpen.value = false
  transferForm.targetTeam = ''
  transferForm.note = ''
  applied(data, '已转派')
}

function openApprove(act: RecommendedAction) {
  targetAction.value = act
  approveForm.reason = ''
  approveOpen.value = true
}

async function approve() {
  const { data } = await troubleshootingApi.approveAction(
    selectedId.value!, targetAction.value!.actionId, { reason: approveForm.reason })
  approveOpen.value = false
  applied(data, '已批准（系统未执行）')
}

function openOutcome(act: RecommendedAction) {
  targetAction.value = act
  outcomeForm.outcome = 'SUCCEEDED'
  outcomeForm.notes = ''
  outcomeForm.recoveryVerified = false
  outcomeOpen.value = true
}

async function recordOutcome() {
  const { data } = await troubleshootingApi.recordOutcome(
    selectedId.value!, targetAction.value!.actionId, {
      outcome: outcomeForm.outcome,
      notes: outcomeForm.notes,
      recoveryVerified: outcomeForm.outcome === 'SUCCEEDED' && outcomeForm.recoveryVerified,
    })
  outcomeOpen.value = false
  applied(data, '已登记外部处置结果')
}

async function close() {
  const { data } = await troubleshootingApi.close(selectedId.value!, {
    outcome: closeForm.outcome,
    summary: closeForm.summary,
    recoveryVerified: closeForm.outcome === 'RECOVERED' && closeForm.recoveryVerified,
    sopFeedback: closeForm.sopFeedback || null,
    createKnowledgeCandidate: closeForm.createKnowledgeCandidate,
  })
  closeOpen.value = false
  applied(data, '已关闭归档')
}

onMounted(loadList)
</script>

<style scoped>
.ts-page { display: grid; grid-template-columns: 236px minmax(0, 1fr); height: 100%; overflow: hidden; }

.queue {
  border-right: 1px solid var(--el-border-color-lighter); display: flex; flex-direction: column;
  background: var(--el-bg-color); overflow: hidden;
}
.qhead { padding: 12px 14px; display: flex; align-items: center; gap: 8px;
  border-bottom: 1px solid var(--el-border-color-lighter); }
.qhead b { font-size: 14px; color: var(--el-text-color-primary); }
.sop-link { margin-left: auto; }
.qfilter { padding: 9px 12px; border-bottom: 1px solid var(--el-border-color-lighter); }
.qlist { flex: 1; overflow-y: auto; }
.qitem {
  width: 100%; text-align: left; border: none; background: transparent; cursor: pointer;
  padding: 10px 13px; border-bottom: 1px solid var(--el-border-color-lighter);
  border-left: 3px solid transparent; font-family: inherit;
}
.qitem:hover { background: var(--el-fill-color-lighter); }
.qitem.active { background: var(--el-color-primary-light-9); border-left-color: var(--el-color-primary); }
.qi-top { display: flex; align-items: center; gap: 6px; }
.qi-code { font-family: var(--mc-mono, monospace); font-size: 12px; font-weight: 600;
  color: var(--el-text-color-primary); }
.qi-svc { font-size: 11.5px; color: var(--el-text-color-secondary); margin-top: 2px; }
.qi-bot { display: flex; align-items: center; gap: 8px; margin-top: 6px; font-size: 10.5px; }
.qi-status { font-weight: 600; }
.qi-status.ready { color: var(--el-color-primary); }
.qi-status.warn { color: var(--el-color-warning); }
.qi-status.muted { color: var(--el-text-color-placeholder); }
.qi-time { margin-left: auto; font-family: var(--mc-mono, monospace);
  color: var(--el-text-color-placeholder); }
.qempty { padding: 18px 14px; font-size: 11.5px; color: var(--el-text-color-secondary); line-height: 1.6; }

.detail { overflow-y: auto; padding: 16px 20px 40px; }
.placeholder { display: flex; align-items: center; justify-content: center; height: 60vh;
  color: var(--el-text-color-secondary); font-size: 13px; }

/* ── 第一层 · 结论（唯一默认展开的一层） ───────────────────── */
.verdict {
  background: var(--el-bg-color); border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px; padding: 18px 20px;
}
.vhead { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.vhead .spacer { flex: 1; }
.chip {
  font-size: 11.5px; padding: 2.5px 9px; border-radius: 20px;
  border: 1px solid var(--el-border-color-lighter); background: var(--el-fill-color-lighter);
  color: var(--el-text-color-secondary); white-space: nowrap;
}
.chip.cls {
  border-color: var(--el-color-primary-light-7); background: var(--el-color-primary-light-9);
  color: var(--el-color-primary); font-weight: 600;
}
.chip.conf-HIGH {
  border-color: var(--el-color-success-light-7); background: var(--el-color-success-light-9);
  color: var(--el-color-success);
}
.chip.conf-MEDIUM {
  border-color: var(--el-color-warning-light-7); background: var(--el-color-warning-light-9);
  color: var(--el-color-warning);
}
.chip.conf-LOW {
  border-color: var(--el-color-danger-light-7); background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
}
.chip.st.ready { color: var(--el-color-primary); }
.chip.st.warn { color: var(--el-color-warning); }
.chip.st.muted { color: var(--el-text-color-placeholder); }

.vtitle {
  font-size: 19px; line-height: 1.45; margin: 0 0 5px; letter-spacing: -0.2px;
  color: var(--el-text-color-primary); font-weight: 650;
}
.vsub { margin: 0; font-size: 12.5px; color: var(--el-text-color-secondary); }

.trio {
  display: grid; grid-template-columns: repeat(3, 1fr); gap: 1px; margin-top: 16px;
  background: var(--el-border-color-lighter); border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px; overflow: hidden;
}
.cell { background: var(--el-bg-color); padding: 13px 14px; min-height: 104px; }
.cell p { margin: 0; font-size: 13px; line-height: 1.6; color: var(--el-text-color-regular); }
.metric { display: flex; align-items: baseline; gap: 5px; margin-top: 7px;
  font-size: 12px; color: var(--el-text-color-secondary); }
.big { font-family: var(--mc-mono, monospace); font-size: 20px; font-weight: 600;
  color: var(--el-text-color-primary); letter-spacing: -0.4px; }
.big.gap { margin-left: 8px; }
.radius { display: inline-block; margin-top: 7px; font-size: 11.5px; padding: 2px 8px;
  border-radius: 6px; border: 1px solid var(--el-border-color-lighter);
  background: var(--el-fill-color-lighter); color: var(--el-text-color-secondary); }
.radius.MULTI_CUSTOMER, .radius.SYSTEM_WIDE {
  border-color: var(--el-color-danger-light-7); background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
}
.locate { margin-top: 8px; font-family: var(--mc-mono, monospace); font-size: 11.5px;
  background: var(--el-fill-color-light); border: 1px solid var(--el-border-color-lighter);
  border-radius: 7px; padding: 7px 9px; color: var(--el-text-color-regular);
  white-space: pre-wrap; overflow-x: auto; }
/* The capability boundary sits next to the conclusion, not buried in warnings. */
.bound { margin: 9px 0 0 !important; font-size: 11.5px !important;
  color: var(--el-color-warning) !important; line-height: 1.55 !important; }

.ops { display: flex; align-items: center; gap: 9px; margin-top: 18px; flex-wrap: wrap; }

/* ── 第二 / 三层 · 折叠 ─────────────────────────────────── */
.fold {
  margin-top: 12px; background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter); border-radius: 12px; overflow: hidden;
}
.fold > summary {
  list-style: none; cursor: pointer; padding: 12px 18px; display: flex;
  align-items: center; gap: 10px; font-size: 13px; color: var(--el-text-color-regular);
  user-select: none;
}
.fold > summary::-webkit-details-marker { display: none; }
.fold > summary b { font-weight: 650; color: var(--el-text-color-primary); }
.caret { width: 0; height: 0; flex: none; transition: transform 0.16s;
  border-left: 5px solid var(--el-text-color-placeholder);
  border-top: 4px solid transparent; border-bottom: 4px solid transparent; }
.fold[open] > summary .caret { transform: rotate(90deg); }
.srole { font-size: 12px; color: var(--el-text-color-secondary); }
.shint { margin-left: auto; font-family: var(--mc-mono, monospace); font-size: 11.5px;
  color: var(--el-text-color-placeholder); }
.fbody { padding: 2px 18px 18px; border-top: 1px solid var(--el-border-color-lighter); }

.snum { font-size: 11px; letter-spacing: 0.09em; text-transform: uppercase;
  color: var(--el-text-color-placeholder); margin: 16px 0 10px; }
.act { border: 1px solid var(--el-border-color-lighter); border-radius: 8px;
  background: var(--el-bg-color); padding: 11px 13px; }
.act + .act { margin-top: 8px; }
.act.write { border-color: var(--el-color-danger-light-5); }
.a-top { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.atype { font-size: 9.5px; font-weight: 700; font-family: var(--mc-mono, monospace);
  padding: 2px 7px; border-radius: 4px; background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary); }
.atype.AUTO_READONLY { background: var(--el-color-success-light-9); color: var(--el-color-success); }
.atype.HUMAN_CONTACT { background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
.atype.MANUAL_WRITE { background: var(--el-color-danger-light-9); color: var(--el-color-danger); }
.atitle { font-size: 12.5px; font-weight: 650; color: var(--el-text-color-primary); }
.adesc { margin: 5px 0 0; font-size: 11.5px; color: var(--el-text-color-secondary); line-height: 1.55; }
.astate { margin-left: auto; font-family: var(--mc-mono, monospace); font-size: 10.5px;
  color: var(--el-text-color-placeholder); }
.arow { display: flex; gap: 8px; margin-top: 9px; }

/* ── 第三层 · 运行细节 ─────────────────────────────────── */
.kv { display: grid; grid-template-columns: 168px minmax(0, 1fr); gap: 2px 14px;
  font-size: 12px; margin: 14px 0 0; }
.kv dt { color: var(--el-text-color-placeholder); }
.kv dd { margin: 0; font-family: var(--mc-mono, monospace);
  color: var(--el-text-color-regular); word-break: break-all; }

.tl { margin-top: 16px; border-left: 1.5px solid var(--el-border-color-lighter); padding-left: 14px; }
.tli { position: relative; padding: 4px 0; font-size: 12.5px; color: var(--el-text-color-regular); }
.tli::before { content: ''; position: absolute; left: -19.5px; top: 10px; width: 7px; height: 7px;
  border-radius: 50%; background: var(--el-text-color-placeholder);
  border: 2px solid var(--el-bg-color); }
.tli.cur::before { background: var(--el-color-primary); }
.tm { font-family: var(--mc-mono, monospace); font-size: 11px;
  color: var(--el-text-color-placeholder); margin-left: 8px; }

.warns { margin-top: 14px; padding: 10px 13px; border-radius: 8px; font-size: 12.5px;
  line-height: 1.6; color: var(--el-text-color-regular);
  background: var(--el-color-warning-light-9); border: 1px solid var(--el-color-warning-light-7); }
.warns b { color: var(--el-color-warning); }
.warns ul { margin: 6px 0 0; padding-left: 17px; }

.hint { font-size: 11.5px; color: var(--el-text-color-secondary); line-height: 1.55; }
.redline { margin: 14px 0 0; font-size: 11.5px; line-height: 1.65;
  color: var(--el-text-color-secondary); }
.redline code, .qempty code {
  font-family: var(--mc-mono, monospace); font-size: 10.5px;
  background: var(--el-fill-color-light); border-radius: 3px; padding: 1px 4px;
}
.dlg-alert { margin-bottom: 12px; }
.sub-hint { font-size: 11px; color: var(--el-text-color-secondary); line-height: 1.5; margin-top: 4px; }

@media (max-width: 1100px) {
  .trio { grid-template-columns: 1fr; }
  .kv { grid-template-columns: 1fr; }
}
</style>
