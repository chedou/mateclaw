<template>
  <div class="ts-page">
    <!-- duty queue -->
    <aside class="queue">
      <header class="qhead">
        <b>值班队列</b>
        <el-tag size="small" type="info" round>{{ rows.length }}</el-tag>
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
        <header class="dhead">
          <div class="dh-left">
            <span class="dcode">{{ current.diagnosis.diagnosisId }}</span>
            <span class="pill" :class="statusClass(current.diagnosis.status)">
              {{ current.diagnosis.status }} · {{ STATUS_LABEL[current.diagnosis.status] }}
            </span>
            <span class="pill route">{{ current.diagnosis.routeMode }}</span>
            <span v-if="current.diagnosis.fixtureMode" class="pill mode">fixtureMode</span>
            <span v-if="current.diagnosis.rehearsal" class="pill mode">rehearsal</span>
            <span class="pill mode">writeExecutionEnabled=false</span>
          </div>
          <el-button size="small" :icon="Refresh" text @click="reload">刷新</el-button>
        </header>

        <el-alert
          v-if="current.diagnosis.warnings.length"
          type="warning"
          :closable="false"
          class="warns"
        >
          <template #title>能力边界 · 契约自曝的 warnings</template>
          <ul class="wlist">
            <li v-for="(w, i) in current.diagnosis.warnings" :key="i">{{ w }}</li>
          </ul>
        </el-alert>

        <section class="grid">
          <div class="col-chain">
            <DerivationChain :diagnosis="current.diagnosis" />

            <h3 class="sec">建议动作</h3>
            <div v-if="current.diagnosis.recommendedActions.length" class="acts">
              <article
                v-for="act in current.diagnosis.recommendedActions"
                :key="act.actionId"
                class="act"
                :class="{ write: act.actionType === 'MANUAL_WRITE' }"
              >
                <div class="a-top">
                  <span class="atype" :class="act.actionType">{{ act.actionType }}</span>
                  <span class="atitle">{{ act.title }}</span>
                </div>
                <p v-if="act.description" class="adesc">{{ act.description }}</p>
                <div class="astate">
                  <span class="kv">approvalStatus <b>{{ act.approvalStatus }}</b></span>
                  <span class="kv" :class="{ block: act.executionStatus === 'BLOCKED' }">
                    executionStatus <b>{{ act.executionStatus }}</b>
                  </span>
                </div>
                <div v-if="canApprove(act)" class="arow">
                  <el-button size="small" type="warning" plain @click="openApprove(act)">
                    批准（推进状态，系统不执行）
                  </el-button>
                </div>
                <div v-if="canRecordOutcome(act)" class="arow">
                  <el-button size="small" plain @click="openOutcome(act)">
                    登记外部处置结果
                  </el-button>
                </div>
              </article>
            </div>
            <p v-else class="noact">
              弃权时 <code>recommendedActions</code> 为空数组 —— 契约保证不输出任何恢复建议。
            </p>
          </div>

          <div class="col-side">
            <div class="card">
              <div class="clab">故障上下文</div>
              <div v-for="[k, v] in incidentRows" :key="k" class="row">
                <span class="rk">{{ k }}</span><span class="rv">{{ v }}</span>
              </div>
            </div>

            <div class="card">
              <div class="clab">生命周期 timeline</div>
              <div class="tl">
                <div v-for="(t, i) in current.diagnosis.timeline" :key="i" class="tli">
                  <span class="td" :class="{ current: t.status === 'current' }" />
                  <div>
                    <div class="tev">{{ t.event }}</div>
                    <div class="tmeta">{{ shortTime(t.timestamp) }} · {{ t.actor }} · {{ t.status }}</div>
                  </div>
                </div>
              </div>
            </div>

            <div class="card actions-card">
              <div class="clab">处置</div>
              <el-button
                v-if="current.diagnosis.status === 'READY_FOR_HUMAN'"
                type="primary"
                size="small"
                class="wide"
                @click="confirm"
              >确认诊断结论</el-button>
              <el-button
                v-if="canTransfer"
                size="small"
                class="wide"
                @click="transferOpen = true"
              >结构化转派</el-button>
              <el-button
                v-if="canClose"
                size="small"
                class="wide"
                @click="closeOpen = true"
              >关闭归档</el-button>
              <p v-if="current.diagnosis.status === 'NEEDS_INVESTIGATION'" class="hint">
                弃权的诊断需要补充证据后重新诊断才能确认。
              </p>
              <p v-if="current.diagnosis.status === 'CLOSED'" class="hint">已关闭。</p>
            </div>

            <div class="card redline">
              <div class="clab">红线</div>
              <p>
                平台没有生产写执行器：批准只把动作推进到 <code>APPROVED_NOT_EXECUTED</code>，
                真正的变更由有权限的人在 MateClaw 之外执行，回来登记结果。
              </p>
            </div>
          </div>
        </section>
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
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import DerivationChain from './DerivationChain.vue'
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

const incidentRows = computed<[string, string][]>(() => {
  const i = current.value?.diagnosis.incident
  if (!i) return []
  return [
    ['system', i.system],
    ['service', i.service],
    ['errorCode', i.errorCode ?? 'null'],
    ['severity', i.severity],
    ['completeness', i.completeness],
    ['intakeSource', i.intakeSource],
    ['traceId', i.traceId ?? 'null'],
    ['slaRemaining', i.slaRemaining ?? 'null'],
    ['impact', i.impact],
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

.dhead { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.dh-left { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.dcode { font-family: var(--mc-mono, monospace); font-size: 15px; font-weight: 600;
  color: var(--el-text-color-primary); }
.pill {
  display: inline-flex; align-items: center; font-family: var(--mc-mono, monospace);
  font-size: 10.5px; font-weight: 700; padding: 3px 9px; border-radius: 20px;
  background: var(--el-fill-color-light); color: var(--el-text-color-secondary);
}
.pill.ready { background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
.pill.warn { background: var(--el-color-warning-light-9); color: var(--el-color-warning); }
.pill.route { background: var(--el-color-success-light-9); color: var(--el-color-success); }

.warns { margin-bottom: 12px; }
.wlist { margin: 6px 0 0; padding-left: 18px; font-size: 12px; line-height: 1.6; }

.grid { display: grid; grid-template-columns: minmax(0, 1fr) 286px; gap: 14px; }
.col-chain { min-width: 0; }
.col-side { display: flex; flex-direction: column; gap: 12px; }

.sec { font-size: 13px; font-weight: 650; color: var(--el-text-color-primary); margin: 20px 0 10px; }
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
.astate { display: flex; gap: 7px; flex-wrap: wrap; margin-top: 9px; padding-top: 8px;
  border-top: 1px dashed var(--el-border-color-lighter); }
.kv { font-family: var(--mc-mono, monospace); font-size: 10px; color: var(--el-text-color-secondary);
  background: var(--el-fill-color-lighter); border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px; padding: 2px 7px; }
.kv b { color: var(--el-text-color-primary); }
.kv.block b { color: var(--el-color-danger); }
.arow { margin-top: 9px; }
.noact { font-size: 12px; color: var(--el-text-color-secondary); border: 1px dashed
  var(--el-border-color); border-radius: 8px; padding: 13px; text-align: center; }

.card { background: var(--el-fill-color-lighter); border: 1px solid var(--el-border-color-lighter);
  border-radius: 9px; padding: 12px 13px; }
.clab { font-family: var(--mc-mono, monospace); font-size: 10px; font-weight: 700;
  letter-spacing: 0.07em; text-transform: uppercase; color: var(--el-text-color-secondary);
  margin-bottom: 9px; }
.row { display: flex; gap: 8px; font-size: 11.5px; padding: 3px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter); }
.row:last-child { border-bottom: none; }
.rk { color: var(--el-text-color-secondary); width: 84px; flex-shrink: 0;
  font-family: var(--mc-mono, monospace); }
.rv { color: var(--el-text-color-primary); word-break: break-word; }

.tl { display: flex; flex-direction: column; }
.tli { display: flex; gap: 9px; padding-bottom: 10px; }
.tli:last-child { padding-bottom: 0; }
.td { width: 8px; height: 8px; border-radius: 50%; background: var(--el-color-success);
  flex-shrink: 0; margin-top: 5px; }
.td.current { background: var(--el-color-primary); box-shadow: 0 0 0 3px var(--el-color-primary-light-9); }
.tev { font-size: 11.5px; color: var(--el-text-color-primary); line-height: 1.45; }
.tmeta { font-family: var(--mc-mono, monospace); font-size: 9.5px;
  color: var(--el-text-color-placeholder); margin-top: 2px; }

.actions-card .wide { width: 100%; margin: 0 0 8px; }
.hint { font-size: 11px; color: var(--el-text-color-secondary); line-height: 1.55; margin: 4px 0 0; }
.redline { border-color: var(--el-color-danger-light-5); background: var(--el-color-danger-light-9); }
.redline p { font-size: 11px; color: var(--el-text-color-regular); line-height: 1.6; margin: 0; }
.redline code, .noact code, .qempty code {
  font-family: var(--mc-mono, monospace); font-size: 10px;
  background: var(--el-bg-color); border-radius: 3px; padding: 1px 4px;
}
.dlg-alert { margin-bottom: 12px; }
.sub-hint { font-size: 11px; color: var(--el-text-color-secondary); line-height: 1.5; margin-top: 4px; }

@media (max-width: 1100px) {
  .grid { grid-template-columns: 1fr; }
}
</style>
