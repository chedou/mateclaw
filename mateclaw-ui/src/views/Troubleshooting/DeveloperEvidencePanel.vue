<template>
  <details v-if="developer && business && current" class="developer-fold">
    <summary>
      <span class="fold-caret" />
      <div><b>展开开发证据台</b><small>证据、判据与能力边界，可复核但不展示模型私有思维链</small></div>
      <span>{{ developer.steps.length }} 个证据 / 判据步骤</span>
    </summary>
    <div class="developer-body" :class="{ 'developer-body--empty-timeline': !developer.steps.length }">
      <div class="route-card">
        <span>调查路径</span>
        <b>{{ investigationLabel(developer.investigationMode, developer.routeAuthority) }}</b>
        <code>{{ developer.playbookRef || '未命中已审核 Playbook' }}</code>
        <span
          v-if="developer.knowledgeEvidenceGrade"
          class="knowledge-grade"
          :class="developer.knowledgeEvidenceGrade.toLowerCase()"
        >判据来源 · {{ knowledgeEvidenceGradeLabel(developer.knowledgeEvidenceGrade) }}</span>
      </div>

      <div class="convergence-grid">
        <section class="trace-summary">
          <div class="section-head">
            <div><span class="section-label">证据收敛</span><h3>PS / Trace 全链路</h3></div>
            <code>{{ developer.callChain.psId || '未贯通' }}</code>
          </div>
          <div v-if="developer.callChain.hops.length" class="hop-line">
            <div
              v-for="(hop, index) in developer.callChain.hops"
              :key="hop.hopId"
              class="hop"
              :class="{ anomalous: hop.anomalous }"
            >
              <span>{{ index + 1 }}</span><b>{{ hop.service }}</b><small>{{ hop.duration }}</small>
            </div>
          </div>
          <p v-else class="empty-evidence">{{ developer.callChain.emptyReason }}</p>
          <div class="contrast-row" :class="{ unavailable: !developer.contrast.available }">
            <span>成功样本对照</span>
            <template v-if="developer.contrast.available">
              <b>{{ developer.contrast.failedSample }}</b><em>vs</em>
              <b class="baseline">{{ developer.contrast.baselineSample }}</b>
            </template>
            <b v-else>未取得</b>
            <small>{{ developer.contrast.note }}</small>
          </div>
          <div v-if="developer.contrast.available" class="contrast-diff-section">
            <div class="contrast-diff-header">
              <span class="contrast-diff-title">差异对比</span>
              <span class="contrast-diff-status available">可用</span>
            </div>
            <div ref="diffContainerRef" class="contrast-diff-container" />
            <div v-if="developer.contrast.note" class="contrast-diff-note">{{ developer.contrast.note }}</div>
          </div>
        </section>

        <aside class="draft-summary">
          <div class="section-head">
            <div><span class="section-label">知识草稿</span><h3>{{ developer.draft.title }}</h3></div>
            <span class="draft-state">{{ developer.draft.reviewStatus }}</span>
          </div>
          <ol v-if="developer.draft.steps.length">
            <li v-for="step in developer.draft.steps" :key="step">{{ step }}</li>
          </ol>
          <p v-else class="empty-evidence">{{ developer.draft.emptyReason }}</p>
          <small>{{ developer.draft.stateNote }}</small>
        </aside>
      </div>

      <section class="evidence-timeline">
        <div class="developer-section-head">
          <div><span class="section-label">证据时间线</span><h3>事实与判据逐行复核</h3></div>
          <span>{{ conclusionLabel(business.conclusionType) }} · {{ business.confidence }}</span>
        </div>
        <div v-if="developer.steps.length" class="timeline-filter-bar">
          <button
            v-for="filter in timelineFilters"
            :key="filter.value"
            class="timeline-filter-btn"
            :class="{ active: activeTimelineFilter === filter.value }"
            @click="activeTimelineFilter = filter.value"
          >{{ filter.label }}</button>
        </div>
        <template
          v-for="(step, index) in filteredSteps"
          :key="`${step.kind}-${step.at || ''}-${step.ref}`"
        >
          <div v-if="index > 0 && step.kind !== filteredSteps[index - 1].kind" class="timeline-phase-divider">
            <span>{{ step.kind === 'CRITERION' ? '判据评估' : '证据采集' }}</span>
          </div>
          <article
            class="evidence-step"
            :class="step.tone.toLowerCase()"
          >
            <time>{{ evidenceTime(step.kind, step.at) }}</time>
            <span class="step-line"><i /></span>
            <div><b>{{ step.title }}</b><p>{{ step.detail }}</p><code>{{ step.ref }}</code></div>
            <span class="tone-label">{{ stepToneLabel(step.tone) }}</span>
          </article>
        </template>
        <div v-if="!filteredSteps.length" class="empty-evidence">
          {{ developer.steps.length
            ? '当前筛选条件下没有匹配的证据或判据。'
            : '当前 Diagnosis 尚未形成可复核的证据或判据。请先完成真源验证，或补充日志、Trace / PS 线索后重新调查。' }}
        </div>
      </section>

      <aside class="developer-side">
        <section class="side-card side-card--guance" v-loading="readinessLoading">
          <div class="source-gate-head">
            <div><span class="section-label">{{ TROUBLESHOOTING_UI_LABELS.guanceOnboarding }}</span><h3>Guance 只读证据适配器</h3></div>
            <span v-if="guanceReadiness" class="source-gate-state" :class="readinessTone(guanceReadiness.status)">
              {{ guanceReadinessLabel(guanceReadiness.status) }}
            </span>
          </div>
          <template v-if="guanceReadiness">
            <p class="source-scope"><code>{{ guanceReadiness.system }}</code><span>/</span><code>{{ guanceReadiness.service }}</code></p>
            <div class="source-meta">
              <span :class="guanceReadiness.endpointConfigured ? 'success' : 'warning'">端点 {{ guanceReadiness.endpointConfigured ? '已配置' : '未就绪' }}</span>
              <span :class="guanceReadiness.uniqueAssetAuthorized ? 'success' : 'warning'">Workspace 资产 {{ guanceReadiness.uniqueAssetAuthorized ? '唯一授权' : '未唯一授权' }}</span>
            </div>
            <p v-if="business.fixtureMode" class="source-context-note">
              这是 Workspace 环境级能力，不代表当前 Recorded Replay Diagnosis 已使用 Guance 真源证据。
            </p>
            <ol v-if="guanceAcceptance" class="acceptance-ladder">
              <li v-for="stage in guanceAcceptance.stages" :key="stage.code">
                <span>{{ stage.code }}</span>
                <div><b>{{ stage.title }}</b><small>{{ stage.detail }}</small></div>
                <strong :class="acceptanceTone(stage.state)">{{ guanceAcceptanceStateLabel(stage.state) }}</strong>
              </li>
            </ol>
            <div
              v-if="guanceOwnerAcceptance"
              class="validation-result owner-acceptance-result"
              :class="ownerAcceptanceTone(guanceOwnerAcceptance.status)"
            >
              <b>{{ ownerAcceptanceStateLabel(guanceOwnerAcceptance.status) }}</b>
              <span v-if="guanceOwnerAcceptance.acceptance">
                {{ guanceOwnerAcceptance.acceptance.acceptedBy }} ·
                {{ shortTime(guanceOwnerAcceptance.acceptance.acceptedAt) }}
              </span>
              <small>
                当前配置指纹
                <code>{{ shortFingerprint(guanceOwnerAcceptance.currentBindingFingerprint) }}</code>
                <template v-if="guanceOwnerAcceptance.acceptance">
                  · 验收指纹
                  <code>{{ shortFingerprint(guanceOwnerAcceptance.acceptance.bindingFingerprint) }}</code>
                </template>
              </small>
              <small v-for="blocker in guanceOwnerAcceptance.blockers" :key="blocker">{{ guanceOwnerBlockerLabel(blocker) }}</small>
            </div>
            <p v-if="guanceAcceptance" class="next-source-action"><b>下一步</b>{{ guanceAcceptance.nextAction }}</p>
            <ul class="signal-readiness-list">
              <li v-for="signal in guanceReadiness.signals" :key="signal.signalKind">
                <code>{{ signal.signalKind }}</code>
                <span :class="signalTone(signal.status)">{{ guanceSignalLabel(signal.status) }}</span>
                <small>{{ signal.bindingRef || '无 binding' }}</small>
              </li>
            </ul>
            <p v-for="blocker in guanceReadiness.blockers" :key="blocker" class="source-blocker">{{ blocker }}</p>
            <div v-if="guanceValidation" class="validation-result" :class="guanceValidation.stage === 'CANONICAL_CHAIN_OBSERVED' ? 'passed' : 'blocked'">
              <b>{{ guanceValidationLabel(guanceValidation.stage) }}</b>
              <span v-if="guanceValidation.stage === 'CANONICAL_CHAIN_OBSERVED'">
                {{ guanceValidation.matchCount }} 条命中 · PS {{ guanceValidation.psId }} · {{ guanceValidation.traceEntries }} 个链路节点 · 总耗时 {{ guanceValidation.totalDurationMs }} ms
              </span>
              <small>{{ guanceValidation.warnings[0] }}</small>
            </div>
            <div v-if="guanceSpinePreview" class="validation-result spine-preview-result" :class="guanceSpinePreview.stage === 'FULL_SPINE_OBSERVED' ? 'passed' : 'blocked'">
              <b>{{ guanceSpinePreviewLabel(guanceSpinePreview.stage) }}</b>
              <span v-if="guanceSpinePreview.stage !== 'BLOCKED'">
                {{ guanceSpinePreview.serviceSequence.join(' → ') }} · {{ guanceSpinePreview.anomalyCount }} 个异常点 · {{ guanceSpinePreview.totalDurationMs }} ms
              </span>
              <span v-if="guanceSpinePreview.contrast.available">
                失败 {{ percent(guanceSpinePreview.contrast.failureRate) }} ↔ 成功 {{ percent(guanceSpinePreview.contrast.successRate) }}
              </span>
              <small>{{ guanceSpinePreview.warnings[0] }}</small>
            </div>
            <el-button
              v-if="canManage"
              size="small"
              plain
              :disabled="!canValidateGuance"
              @click="$emit('openGuanceValidation')"
            >打开真源验收</el-button>
            <small class="gate-note">单次读链不会自动通过 T7。owner 验收会绑定当前配置指纹；配置变化后自动过期。T8 仍需 20–30 条真实样本，fixtureMode 不会自动关闭。</small>
          </template>
          <p v-else class="empty-evidence">{{ readinessError || '正在检查当前 Workspace 的真源绑定…' }}</p>
        </section>
        <section class="side-card side-card--capability">
          <span class="section-label">当前范围</span><h3>能力边界</h3>
          <ul class="capability-list"><li v-for="item in developer.capabilityLimits" :key="item">{{ item }}</li></ul>
        </section>
        <section v-if="current.diagnosis.recommendedActions.length" class="side-card side-card--actions">
          <span class="section-label">人工处置动作</span><h3>平台不执行</h3>
          <article
            v-for="action in current.diagnosis.recommendedActions"
            :key="action.actionId"
            class="action-card"
            :class="{ write: action.actionType === 'MANUAL_WRITE' }"
          >
            <div><code>{{ action.actionType }}</code><span>{{ action.approvalStatus }}</span></div>
            <b>{{ action.title }}</b><p>{{ action.description }}</p>
            <el-button v-if="canApproveAction(action)" size="small" type="warning" plain @click="$emit('approve', action)">批准（不执行）</el-button>
            <el-button v-if="canRecordOutcomeAction(action)" size="small" plain @click="$emit('recordOutcome', action)">登记外部结果</el-button>
          </article>
        </section>
      </aside>
    </div>
  </details>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, onBeforeUnmount } from 'vue'
import { vLoading } from 'element-plus/es/components/loading/index'
import type {
  BusinessSummary,
  DeveloperEvidenceView,
  EvidenceStepKind,
  EvidenceStepTone,
  GuanceEvidenceAcceptanceView,
  GuanceEvidenceReadiness,
  GuanceEvidenceSpinePreview,
  GuanceEvidenceValidationReport,
  GuanceReadinessStatus,
  GuanceSignalStatus,
  RecommendedAction,
  StoredDiagnosis,
} from '@/api'
import {
  canStartGuanceValidation,
  conclusionLabel,
  guanceAcceptanceProgress,
  guanceAcceptanceStateLabel,
  guanceOwnerBlockerLabel,
  guanceReadinessLabel,
  guanceSignalLabel,
  guanceSpinePreviewLabel,
  guanceValidationLabel,
  investigationLabel,
  knowledgeEvidenceGradeLabel,
} from './formalProjection'
import {
  formatWorkbenchTime as shortTime,
  TROUBLESHOOTING_UI_LABELS,
} from './workbenchView'

const STEP_TONE_LABEL: Record<EvidenceStepTone, string> = {
  NORMAL: '正常', ANOMALY: '异常 / 命中', EXCLUDED: '已排除', UNEVALUATED: '未求值',
}

interface Props {
  developer: DeveloperEvidenceView | null
  business: BusinessSummary | null
  current: StoredDiagnosis | null
  guanceReadiness: GuanceEvidenceReadiness | null
  guanceAcceptance: ReturnType<typeof guanceAcceptanceProgress> | null
  guanceOwnerAcceptance: GuanceEvidenceAcceptanceView | null
  guanceValidation: GuanceEvidenceValidationReport | null
  guanceSpinePreview: GuanceEvidenceSpinePreview | null
  readinessLoading: boolean
  readinessError: string
  canManage: boolean
  canValidateGuance: boolean
  canApproveAction: (action: RecommendedAction) => boolean
  canRecordOutcomeAction: (action: RecommendedAction) => boolean
}

const props = defineProps<Props>()

defineEmits<{
  openGuanceValidation: []
  openEvaluation: []
  approve: [action: RecommendedAction]
  recordOutcome: [action: RecommendedAction]
}>()

/* ── Timeline filter ── */
const activeTimelineFilter = ref<'all' | 'evidence' | 'criterion' | 'anomaly'>('all')

const timelineFilters = [
  { label: '全部', value: 'all' as const },
  { label: '仅证据', value: 'evidence' as const },
  { label: '仅判据', value: 'criterion' as const },
  { label: '仅异常', value: 'anomaly' as const },
]

const filteredSteps = computed(() => {
  const steps = props.developer?.steps || []
  switch (activeTimelineFilter.value) {
    case 'evidence': return steps.filter(s => s.kind === 'EVIDENCE')
    case 'criterion': return steps.filter(s => s.kind === 'CRITERION')
    case 'anomaly': return steps.filter(s => s.tone === 'ANOMALY')
    default: return steps
  }
})

/* ── Monaco diff editor ── */
const diffContainerRef = ref<HTMLElement | null>(null)
let diffEditor: any = null

async function initDiffEditor() {
  if (diffEditor) return
  const monaco = await import('monaco-editor')

  self.MonacoEnvironment = {
    getWorker(_id: string, _label: string) {
      return new Worker(new URL('monaco-editor/esm/vs/editor/editor.worker.js', import.meta.url), { type: 'module' })
    },
  }

  if (diffContainerRef.value) {
    const isDark = window.matchMedia('(prefers-color-scheme: dark)').matches
    diffEditor = monaco.editor.createDiffEditor(diffContainerRef.value, {
      readOnly: true,
      automaticLayout: true,
      renderSideBySide: true,
      fontSize: 12,
      minimap: { enabled: false },
      scrollBeyondLastLine: false,
      theme: isDark ? 'vs-dark' : 'vs',
    })
    updateDiffContent(monaco)
  }
}

async function updateDiffContent(monacoOverride?: any) {
  const contrast = props.developer?.contrast
  if (!contrast?.available) return
  const monaco = monacoOverride || await import('monaco-editor')
  if (!diffEditor) return
  diffEditor.setModel({
    original: monaco.editor.createModel(contrast.baselineSample || '', 'text'),
    modified: monaco.editor.createModel(contrast.failedSample || '', 'text'),
  })
}

watch(
  () => props.developer?.contrast?.available,
  (available) => {
    if (available) nextTick(() => initDiffEditor())
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  diffEditor?.dispose()
})

function stepToneLabel(value: EvidenceStepTone) { return STEP_TONE_LABEL[value] }

function evidenceTime(kind: EvidenceStepKind, value: string | null) {
  return kind === 'CRITERION' ? '判据' : shortTime(value).slice(11)
}

function readinessTone(value: GuanceReadinessStatus) {
  if (canStartGuanceValidation(value)) return 'active'
  return 'warning'
}

function signalTone(value: GuanceSignalStatus) {
  if (value === 'CANONICAL_RESULT_OBSERVED') return 'success'
  if (value === 'READY_FOR_VALIDATION') return 'active'
  return 'warning'
}

function acceptanceTone(value: 'BLOCKED' | 'READY' | 'OWNER_EVIDENCE_REQUIRED') {
  if (value === 'READY') return 'success'
  if (value === 'OWNER_EVIDENCE_REQUIRED') return 'active'
  return 'warning'
}

function ownerAcceptanceStateLabel(value: GuanceEvidenceAcceptanceView['status']) {
  if (value === 'ACCEPTED') return '当前绑定已验收'
  if (value === 'STALE') return '配置变化，验收已过期'
  if (value === 'NOT_ACCEPTED') return '尚未完成 owner 验收'
  return '当前绑定不可验收'
}

function ownerAcceptanceTone(value: GuanceEvidenceAcceptanceView['status']) {
  if (value === 'ACCEPTED') return 'passed'
  return value === 'STALE' ? 'blocked' : 'pending'
}

function shortFingerprint(value?: string | null) {
  return value ? `${value.slice(0, 12)}…` : '不可用'
}

function percent(value: number) { return `${Math.round(Number(value) * 100)}%` }
</script>

<style scoped>
/* ── Panel shell ── */
.developer-fold { max-width:1320px; margin:0 auto; border:1px solid var(--mc-border); border-radius:var(--mc-radius-md); background:var(--mc-bg-elevated); box-shadow:var(--mc-shadow-soft); margin-top:14px; overflow:hidden; }

/* ── Summary / fold bar ── */
.developer-fold>summary { display:flex; align-items:center; gap:12px; padding:16px 20px; list-style:none; cursor:pointer; user-select:none; transition:background .15s; }
.developer-fold>summary:hover { background:var(--mc-bg-muted); }
.developer-fold>summary::-webkit-details-marker { display:none; }
.developer-fold>summary>div b,.developer-fold>summary>div small { display:block; }
.developer-fold>summary>div b { font-size:var(--mc-text-sm); }
.developer-fold>summary>div small { margin-top:3px; color:var(--mc-text-secondary); font-size:var(--mc-text-xs); }
.developer-fold>summary>span:last-child { margin-left:auto; color:var(--mc-text-secondary); font-size:var(--mc-text-xs); }
.fold-caret { width:0; height:0; border-top:5px solid transparent; border-bottom:5px solid transparent; border-left:6px solid var(--mc-text-tertiary); transition:transform .18s; }
.developer-fold[open] .fold-caret { transform:rotate(90deg); }

/* ── Body grid ── */
.developer-body { display:grid; grid-template-columns:minmax(0,1.65fr) minmax(300px,.75fr); gap:20px; padding:22px; border-top:1px solid var(--mc-border); background:var(--mc-bg-elevated); }
.developer-body>.route-card,.developer-body>.convergence-grid { grid-column:1/-1; }
.developer-body>.route-card { margin-top:0; }
.developer-body>.convergence-grid { margin-top:16px; }
.developer-body--empty-timeline>.evidence-timeline { grid-column:1/-1; }
.developer-body--empty-timeline>.developer-side { grid-column:1/-1; display:grid; grid-template-columns:minmax(0,1.55fr) minmax(280px,.75fr); align-items:start; }
.developer-body--empty-timeline .side-card--actions { grid-column:1/-1; }

/* ── Section label — enhanced visual weight ── */
.section-label { display:block; color:var(--mc-text-secondary); font-size:var(--mc-text-xs); font-weight:700; letter-spacing:.1em; text-transform:uppercase; }

/* ── Section head ── */
.developer-section-head { display:flex; align-items:flex-start; justify-content:space-between; gap:16px; }
.developer-section-head h3 { margin:5px 0 0; font-size:var(--mc-text-base); }
.developer-section-head>span { padding:3px 8px; border-radius:var(--mc-radius-xs); color:var(--mc-status-info-text); background:var(--mc-status-info-bg); font-size:var(--mc-text-xs); }

/* ── Route card — left accent bar ── */
.route-card { align-self:start; padding:15px; border:1px solid var(--mc-border); border-left:3px solid var(--mc-primary); border-radius:var(--mc-radius-sm); background:var(--mc-bg-muted); }
.route-card span { display:block; color:var(--mc-text-secondary); font-size:var(--mc-text-xs); font-weight:700; letter-spacing:.1em; text-transform:uppercase; }
.route-card b { display:block; margin:7px 0; font-size:var(--mc-text-sm); }
.route-card code { color:var(--mc-primary); font-size:var(--mc-text-xs); word-break:break-all; }
.route-card .knowledge-grade { display:inline-flex; margin-top:10px; padding:3px 8px; border:1px solid var(--mc-border); border-radius:999px; letter-spacing:0; text-transform:none; }
.route-card .knowledge-grade.recorded_aggregate { color:var(--mc-success); background:var(--mc-status-success-bg); border-color:var(--mc-success); }
.route-card .knowledge-grade.authored_fixture { color:var(--mc-warning); background:var(--mc-status-warning-bg); border-color:var(--mc-warning); }

/* ── Convergence grid — simplified single-layer ── */
.convergence-grid { display:grid; grid-template-columns:minmax(0,1.6fr) minmax(270px,.8fr); gap:14px; }
.trace-summary,.draft-summary { padding:18px; border:1px solid var(--mc-border); border-radius:var(--mc-radius-sm); }
.section-head { display:flex; align-items:flex-start; justify-content:space-between; gap:16px; }
.section-head h3 { margin:5px 0 0; font-size:var(--mc-text-base); }
.section-head>code { color:var(--mc-primary); font-size:var(--mc-text-xs); }

/* ── Hop line ── */
.hop-line { display:flex; align-items:stretch; gap:8px; margin-top:17px; }
.hop { flex:1; padding:10px; border:1px solid var(--mc-border); border-radius:var(--mc-radius-xs); background:var(--mc-bg-elevated); }
.hop>span { display:inline-grid; place-items:center; width:18px; height:18px; border-radius:50%; color:var(--mc-text-inverse); background:var(--mc-primary); font-size:var(--mc-text-xs); }
.hop b,.hop small { display:block; margin-top:5px; font-size:var(--mc-text-xs); }
.hop small { color:var(--mc-text-secondary); }
.hop.anomalous { border-color:var(--mc-danger-border); background:var(--mc-status-error-bg); }
.hop.anomalous>span { background:var(--mc-danger); }

/* ── Empty state ── */
.empty-evidence { margin:14px 0 0; padding:11px 12px; border:1px dashed var(--mc-border); border-radius:var(--mc-radius-sm); color:var(--mc-text-secondary); background:var(--mc-bg-elevated); font-size:var(--mc-text-xs); line-height:1.65; }

/* ── Contrast row ── */
.contrast-row { display:flex; align-items:center; gap:9px; flex-wrap:wrap; margin-top:14px; padding:10px 12px; border-radius:var(--mc-radius-sm); background:var(--mc-status-success-bg); font-size:var(--mc-text-xs); }
.contrast-row>span { color:var(--mc-text-secondary); }
.contrast-row em { color:var(--mc-text-tertiary); font-style:normal; }
.contrast-row .baseline { color:var(--mc-success); }
.contrast-row small { flex-basis:100%; color:var(--mc-text-secondary); }
.contrast-row.unavailable { color:var(--mc-warning); background:var(--mc-status-warning-bg); }

/* ── Draft summary ── */
.draft-state { padding:2px 7px; border-radius:var(--mc-radius-xs); color:var(--mc-status-purple-text); background:var(--mc-status-purple-bg); font-size:var(--mc-text-xs); font-weight:700; }
.draft-summary ol { margin:14px 0 9px; padding-left:20px; color:var(--mc-text-secondary); font-size:var(--mc-text-xs); line-height:1.6; }
.draft-summary>small { color:var(--mc-text-secondary); font-size:var(--mc-text-xs); }

/* ── Evidence timeline ── */
.evidence-timeline { min-width:0; }
.evidence-step { display:grid; grid-template-columns:74px 20px minmax(0,1fr) auto; gap:8px; padding-top:17px; padding-left:4px; padding-right:4px; border-radius:var(--mc-radius-sm); transition:background .15s; }
.evidence-step:hover { background:var(--mc-bg-muted); }
.evidence-step time { padding-top:2px; color:var(--mc-text-tertiary); font-family:var(--mc-mono,monospace); font-size:var(--mc-text-xs); }

/* ── Step line — gradient fade ── */
.step-line { position:relative; display:flex; justify-content:center; }
.step-line::after { content:''; position:absolute; top:10px; bottom:-18px; width:1px; background:linear-gradient(to bottom, var(--mc-border) 0%, var(--mc-border-light) 60%, transparent 100%); }
.evidence-step:last-child .step-line::after { display:none; }
.step-line i { position:relative; z-index:1; width:9px; height:9px; margin-top:3px; border-radius:50%; background:var(--mc-primary); box-shadow:0 0 0 4px var(--mc-border-light); }

/* ── Tone dots ── */
.evidence-step.anomaly .step-line i { background:var(--mc-danger); box-shadow:0 0 0 4px var(--mc-danger-light); animation:pulse-red 2s infinite; }
.evidence-step.excluded .step-line i { background:var(--mc-text-tertiary); box-shadow:0 0 0 4px var(--mc-bg-muted); }
.evidence-step.unevaluated .step-line i { border:2px dashed var(--mc-text-tertiary); background:var(--mc-bg-elevated); box-shadow:none; }
.evidence-step.criterion .step-line i { width:9px; height:9px; border-radius:2px; background:var(--mc-status-purple-text, var(--mc-primary)); transform:rotate(45deg); box-shadow:0 0 0 4px var(--mc-status-purple-bg, var(--mc-bg-muted)); }

/* ── Pulse animation — reduced amplitude ── */
@keyframes pulse-red { 0%,100% { box-shadow:0 0 0 4px var(--mc-danger-light); } 50% { box-shadow:0 0 0 6px rgba(224,90,74,.08); } }

/* ── Step content ── */
.evidence-step b { font-size:var(--mc-text-sm); }
.evidence-step p { margin:4px 0 6px; color:var(--mc-text-secondary); font-size:var(--mc-text-sm); line-height:1.55; }
.evidence-step code { color:var(--mc-primary); font-size:var(--mc-text-xs); }

/* ── Tone labels ── */
.tone-label { align-self:start; padding:2px 6px; border-radius:var(--mc-radius-xs); color:var(--mc-text-secondary); background:var(--mc-bg-muted); font-size:var(--mc-text-xs); }
.evidence-step.anomaly .tone-label { color:var(--mc-danger); background:var(--mc-status-error-bg); }
.evidence-step.excluded .tone-label { color:var(--mc-text-tertiary); background:var(--mc-bg-muted); }
.evidence-step.unevaluated .tone-label { color:var(--mc-warning); background:var(--mc-status-warning-bg); }

/* ── Developer sidebar — card-style sections ── */
.developer-side { display:flex; flex-direction:column; gap:14px; }
.side-card { padding:16px; border:1px solid var(--mc-border); border-radius:var(--mc-radius-sm); background:var(--mc-bg-elevated); }
.developer-side h3 { margin:5px 0 0; font-size:var(--mc-text-base); }

/* ── Capability list — neutral color (not error) ── */
.capability-list { margin:13px 0 0; padding-left:17px; color:var(--mc-text-secondary); font-size:var(--mc-text-xs); line-height:1.6; }
.capability-list li+li { margin-top:7px; }

/* ── Source gate (Guance adapter) ── */
.source-gate-card,.side-card--guance { min-height:120px; }
.source-gate-head { display:flex; align-items:flex-start; justify-content:space-between; gap:10px; }
.source-gate-state { flex:none; padding:3px 7px; border-radius:var(--mc-radius-xs); background:var(--mc-bg-muted); font-size:var(--mc-text-xs); font-weight:700; }
.source-scope { display:flex; align-items:center; gap:5px; margin:12px 0 8px; color:var(--mc-text-tertiary); font-size:var(--mc-text-xs); }
.source-scope code { color:var(--mc-text-secondary); word-break:break-all; }
.source-meta { display:flex; flex-wrap:wrap; gap:7px; font-size:var(--mc-text-xs); }
.source-context-note { margin:10px 0 0; padding-left:10px; border-left:2px solid var(--mc-warning); color:var(--mc-text-secondary); font-size:var(--mc-text-xs); line-height:1.55; }

/* ── Acceptance ladder ── */
.acceptance-ladder { margin:12px 0 0; padding:0; list-style:none; border:1px solid var(--mc-border); border-radius:var(--mc-radius-sm); overflow:hidden; }
.acceptance-ladder li { display:grid; grid-template-columns:30px minmax(0,1fr) auto; align-items:start; gap:8px; padding:8px; background:var(--mc-bg-elevated); }
.acceptance-ladder li+li { border-top:1px solid var(--mc-border); }
.acceptance-ladder li>span { display:grid; place-items:center; width:25px; height:20px; border-radius:4px; color:var(--mc-text-secondary); background:var(--mc-border-light); font:700 var(--mc-text-xs) var(--mc-mono,monospace); }
.acceptance-ladder b,.acceptance-ladder small { display:block; }
.acceptance-ladder b { font-size:var(--mc-text-xs); }
.acceptance-ladder small { margin-top:3px; color:var(--mc-text-secondary); font-size:var(--mc-text-xs); line-height:1.45; }
.acceptance-ladder strong { font-size:var(--mc-text-xs); white-space:nowrap; }

/* ── Next action ── */
.next-source-action { margin:8px 0 0; padding:8px; border-radius:6px; color:var(--mc-text-secondary); background:var(--mc-status-info-bg); font-size:var(--mc-text-xs); line-height:1.5; }
.next-source-action b { display:block; margin-bottom:2px; color:var(--mc-status-info-text); }

/* ── Signal readiness ── */
.signal-readiness-list { margin:12px 0; padding:0; list-style:none; }
.signal-readiness-list li { display:grid; grid-template-columns:minmax(0,1fr) auto; gap:3px 8px; padding:7px 0; border-top:1px solid var(--mc-border-light); }
.signal-readiness-list code { font-size:var(--mc-text-xs); }
.signal-readiness-list span { font-size:var(--mc-text-xs); }
.signal-readiness-list small { grid-column:1/-1; color:var(--mc-text-tertiary); font-size:var(--mc-text-xs); word-break:break-all; }

/* ── Source blocker ── */
.source-blocker { margin:7px 0; padding:7px 8px; border-radius:var(--mc-radius-xs); color:var(--mc-status-error-text); background:var(--mc-status-error-bg); font-size:var(--mc-text-xs); line-height:1.5; }
.gate-note { display:block; margin-top:9px; color:var(--mc-text-secondary); font-size:var(--mc-text-xs); line-height:1.55; }

/* ── Validation result ── */
.validation-result { margin:9px 0; padding:9px; border-radius:var(--mc-radius-sm); font-size:var(--mc-text-xs); }
.validation-result.passed { color:var(--mc-status-success-text); background:var(--mc-status-success-bg); }
.validation-result.blocked { color:var(--mc-status-warning-text); background:var(--mc-status-warning-bg); }
.validation-result.pending { color:var(--mc-status-info-text); background:var(--mc-status-info-bg); }
.validation-result b,.validation-result span,.validation-result small { display:block; }
.validation-result span { margin-top:4px; }
.validation-result small { margin-top:5px; color:var(--mc-text-secondary); line-height:1.45; }

/* ── Owner acceptance ── */
.owner-acceptance-result code { font-size:var(--mc-text-xs); overflow-wrap:anywhere; }
.owner-acceptance-result.passed { border-color:var(--mc-success); background:var(--mc-status-success-bg); }
.owner-acceptance-result.blocked { border-color:var(--mc-warning); background:var(--mc-status-warning-bg); }
.owner-acceptance-result.pending { border-color:var(--mc-border-light); background:var(--mc-status-info-bg); }
.spine-preview-result span { overflow-wrap:anywhere; line-height:1.45; }

/* ── Action cards ── */
.action-card { margin-top:12px; padding:12px; border:1px solid var(--mc-border); border-radius:var(--mc-radius-xs); }
.action-card.write { border-color:var(--mc-danger-border); }
.action-card>div { display:flex; justify-content:space-between; gap:8px; }
.action-card code,.action-card>div span { color:var(--mc-text-secondary); font-size:var(--mc-text-xs); }
.action-card>b { display:block; margin-top:7px; font-size:var(--mc-text-sm); }
.action-card>p { margin:4px 0 9px; color:var(--mc-text-secondary); font-size:var(--mc-text-xs); line-height:1.5; }

/* ── Utility color classes — keep !important ── */
.active { color:var(--mc-primary)!important; }
.success { color:var(--mc-success)!important; }
.warning { color:var(--mc-warning)!important; }

/* ── Timeline filter bar ── */
.timeline-filter-bar { display:flex; gap:6px; margin:14px 0 14px; }
.timeline-filter-btn { padding:4px 12px; border:1px solid var(--mc-border); border-radius:var(--mc-radius-xs); background:var(--mc-bg-elevated); color:var(--mc-text-secondary); font-size:var(--mc-text-xs); cursor:pointer; transition:all .15s; }
.timeline-filter-btn:hover { border-color:var(--mc-primary); color:var(--mc-primary); }
.timeline-filter-btn.active { background:var(--mc-primary); border-color:var(--mc-primary); color:var(--mc-text-inverse); font-weight:600; }

/* ── Phase divider ── */
.timeline-phase-divider { display:flex; align-items:center; gap:12px; margin:20px 0 14px; color:var(--mc-text-tertiary); font-size:var(--mc-text-xs); font-weight:700; letter-spacing:.08em; text-transform:uppercase; }
.timeline-phase-divider::before,.timeline-phase-divider::after { content:''; flex:1; height:1px; background:var(--mc-border-light); }

/* ── Contrast diff (Monaco) ── */
.contrast-diff-section { margin-top:14px; border:1px solid var(--mc-border); border-radius:var(--mc-radius-sm); overflow:hidden; }
.contrast-diff-header { display:flex; justify-content:space-between; align-items:center; padding:10px 14px; background:var(--mc-bg-muted); border-bottom:1px solid var(--mc-border); }
.contrast-diff-title { font-size:var(--mc-text-sm); font-weight:600; }
.contrast-diff-status { font-size:var(--mc-text-xs); padding:2px 7px; border-radius:var(--mc-radius-xs); }
.contrast-diff-status.available { color:var(--mc-success); background:var(--mc-status-success-bg); }
.contrast-diff-container { min-height:250px; max-height:400px; background:var(--mc-bg-elevated); }
.contrast-diff-note { padding:8px 14px; font-size:var(--mc-text-xs); color:var(--mc-text-secondary); border-top:1px solid var(--mc-border); }

/* ── Responsive ── */
@media(max-width:1100px){
  .convergence-grid{grid-template-columns:1fr}
  .developer-body--empty-timeline>.developer-side{grid-template-columns:1fr}
}
@media(max-width:900px){
  .developer-body{grid-template-columns:1fr}
  .developer-body>.evidence-timeline,.developer-body>.developer-side{grid-column:1/-1}
}
</style>
