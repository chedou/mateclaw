<template>
  <details v-if="developer && business && current" class="developer-fold">
    <summary>
      <span class="fold-caret" />
      <div><b>展开开发证据台</b><small>按调查路径 → 证据链 → 判据 → 人工处置复核，不展示模型私有思维链</small></div>
      <span>{{ developer.steps.length }} 个证据 / 判据步骤</span>
    </summary>
    <div class="developer-body" :class="{ 'developer-body--empty-timeline': !developer.steps.length }">
      <div class="route-card">
        <span>调查路径</span>
        <b>{{ investigationRouteLabel(developer) }}</b>
        <code>{{ developer.playbookRef || '未命中已审核的排障方案' }}</code>
        <p class="playbook-help">
          <strong>排障方案（Playbook）</strong>：一套经过审核、可重复使用的排障方法，规定要查什么、怎么判断以及何时停止。
        </p>
        <span
          v-if="developer.knowledgeEvidenceGrade"
          class="knowledge-grade"
          :class="developer.knowledgeEvidenceGrade.toLowerCase()"
        >判据来源 · {{ knowledgeEvidenceGradeLabel(developer.knowledgeEvidenceGrade) }}</span>
      </div>

      <InvestigationTracePanel
        v-if="developer.investigationTrace"
        class="investigation-trace-panel"
        :trace="developer.investigationTrace"
      />
      <section v-else class="investigation-trace-panel empty-evidence">七阶段调查轨迹 · 未记录</section>

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
        <section class="source-status" v-loading="readinessLoading">
          <div class="source-status-copy">
            <span class="section-label">{{ TROUBLESHOOTING_UI_LABELS.guanceSourceStatus }}</span>
            <div class="source-status-title">
              <h3>Guance 只读证据源</h3>
              <strong :class="`is-${sourceStatus.tone}`">{{ sourceStatus.label }}</strong>
            </div>
            <p v-if="guanceReadiness" class="source-scope">
              <code>{{ guanceReadiness.system }}</code><span>/</span><code>{{ guanceReadiness.service }}</code>
            </p>
            <p class="source-context-note">{{ sourceUsage }}</p>
            <p v-if="readinessError" class="source-status-error">{{ readinessError }}</p>
          </div>
          <div class="source-status-governance">
            <small>这是 Workspace 级治理状态，不是当前 Diagnosis 的调查步骤。</small>
            <el-button
              v-if="canManage"
              size="small"
              plain
              @click="$emit('openGuanceOnboarding')"
            >前往接入验收</el-button>
          </div>
        </section>
        <section class="side-card side-card--capability">
          <span class="section-label">当前范围</span><h3>能力边界</h3>
          <ul class="capability-list"><li v-for="item in developer.capabilityLimits" :key="item">{{ item }}</li></ul>
        </section>
        <section class="side-card side-card--provenance">
          <span class="section-label">调查参与者</span><h3>谁参与了，谁没参与</h3>
          <InvestigationProvenancePanel
            :diagnosis-id="current.diagnosis.diagnosisId"
            :diagnosis-version="current.version"
          />
        </section>
        <section
          v-if="supportsDeterministicDerivation(current.diagnosis.investigationMode)"
          class="side-card side-card--derivation"
        >
          <span class="section-label">判定链</span><h3>结论怎么算出来的</h3>
          <DerivationChain :diagnosis="current.diagnosis" />
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
import DerivationChain from './DerivationChain.vue'
import InvestigationProvenancePanel from './InvestigationProvenancePanel.vue'
import { supportsDeterministicDerivation } from './derivationPresentation'
import type {
  BusinessSummary,
  DeveloperEvidenceView,
  EvidenceStepKind,
  EvidenceStepTone,
  GuanceEvidenceAcceptanceView,
  GuanceEvidenceReadiness,
  RecommendedAction,
  StoredDiagnosis,
} from '@/api'
import {
  conclusionLabel,
  diagnosisGuanceUsageLabel,
  guanceAcceptanceProgress,
  guanceDetailSourceState,
  knowledgeEvidenceGradeLabel,
} from './formalProjection'
import {
  formatWorkbenchTime as shortTime,
  TROUBLESHOOTING_UI_LABELS,
} from './workbenchView'
import InvestigationTracePanel from './InvestigationTracePanel.vue'
import { investigationRouteLabel } from './investigationTrace'

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
  readinessLoading: boolean
  readinessError: string
  canManage: boolean
  canApproveAction: (action: RecommendedAction) => boolean
  canRecordOutcomeAction: (action: RecommendedAction) => boolean
}

const props = defineProps<Props>()

defineEmits<{
  openGuanceOnboarding: []
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

const sourceStatus = computed(() => guanceDetailSourceState(
  props.guanceReadiness?.status ?? null,
  props.guanceOwnerAcceptance?.status ?? null,
  props.guanceAcceptance,
))

const sourceUsage = computed(() => diagnosisGuanceUsageLabel(
  props.current?.diagnosis.evidence ?? [],
))

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

</script>

<style scoped>
/* ── Panel shell ── */
.developer-fold { width:100%; max-width:none; margin:14px 0 0; border:1px solid var(--mc-border); border-radius:var(--mc-radius-md); background:var(--mc-bg-elevated); box-shadow:var(--mc-shadow-soft); overflow:hidden; }

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
.developer-body>.route-card,.developer-body>.investigation-trace-panel,.developer-body>.convergence-grid { grid-column:1/-1; }
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
.route-card .playbook-help { margin:9px 0 0; color:var(--mc-text-secondary); font-size:var(--mc-text-xs); line-height:1.55; }
.route-card .playbook-help strong { color:var(--mc-text-primary); }
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

/* ── Compact Guance environment status ── */
.source-status { display:flex; align-items:flex-start; justify-content:space-between; gap:16px; padding:4px 2px 16px; border-bottom:1px solid var(--mc-border-light); }
.source-status-copy { min-width:0; }
.source-status-title { display:flex; align-items:center; flex-wrap:wrap; gap:8px; margin-top:5px; }
.source-status-title h3 { margin:0; }
.source-status-title strong { padding:3px 7px; border-radius:var(--mc-radius-xs); font-size:var(--mc-text-xs); }
.source-status-title strong.is-success { color:var(--mc-status-success-text); background:var(--mc-status-success-bg); }
.source-status-title strong.is-active { color:var(--mc-status-info-text); background:var(--mc-status-info-bg); }
.source-status-title strong.is-warning { color:var(--mc-status-warning-text); background:var(--mc-status-warning-bg); }
.source-status-title strong.is-muted { color:var(--mc-text-secondary); background:var(--mc-bg-muted); }
.source-scope { display:flex; align-items:center; gap:5px; margin:9px 0 0; color:var(--mc-text-tertiary); font-size:var(--mc-text-xs); }
.source-scope code { color:var(--mc-text-secondary); overflow-wrap:anywhere; }
.source-context-note,.source-status-error { margin:7px 0 0; color:var(--mc-text-secondary); font-size:var(--mc-text-xs); line-height:1.5; }
.source-status-error { color:var(--mc-status-warning-text); }
.source-status-governance { display:flex; max-width:180px; flex:none; flex-direction:column; align-items:flex-end; gap:8px; }
.source-status-governance small { color:var(--mc-text-tertiary); font-size:var(--mc-text-xs); line-height:1.45; text-align:right; }

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
  .source-status{flex-direction:column}
  .source-status-governance{max-width:none; align-items:flex-start}
  .source-status-governance small{text-align:left}
}
</style>
