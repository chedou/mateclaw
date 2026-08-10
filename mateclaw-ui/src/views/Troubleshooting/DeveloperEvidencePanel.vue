<template>
  <details v-if="developer && business && current" class="developer-fold">
    <summary>
      <span class="fold-caret" />
      <div><b>展开排障过程与证据</b><small>先看这次排障怎么推进，再按需查看证据、规则和技术记录</small></div>
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
      <section v-else class="investigation-trace-panel empty-evidence">本次排障过程 · 未记录</section>

      <div class="convergence-grid">
        <section class="trace-summary">
          <div class="section-head chain-head">
            <div>
              <span class="section-label">关联日志摘要</span>
              <h3>PS ID 关联日志轨迹</h3>
              <p>按时间查看同一 PS ID 命中的日志记录；这里不是完整的跨服务 Trace。</p>
            </div>
            <div class="chain-identity"><span>PS ID</span><code>{{ developer.callChain.psId || '未贯通' }}</code></div>
          </div>
          <template v-if="developer.callChain.hops.length">
            <div class="chain-metrics">
              <div><strong>{{ developer.callChain.hops.length }}</strong><span>关联日志</span></div>
              <div :class="{ anomalous: chainAnomalyCount > 0 }"><strong>{{ chainAnomalyCount }}</strong><span>异常日志</span></div>
              <div><strong>{{ chainDurationCount }}</strong><span>有耗时记录</span></div>
            </div>
            <div class="chain-route">
              <span>服务分布</span>
              <div>
                <template v-for="(group, index) in chainServiceGroups" :key="`${group.service}-${index}`">
                  <b :class="{ anomalous: group.anomalous }">{{ group.service }} <em>× {{ group.count }}</em></b>
                  <i v-if="index < chainServiceGroups.length - 1">→</i>
                </template>
              </div>
            </div>
            <details class="chain-details">
              <summary>
                <span>查看全部 {{ developer.callChain.hops.length }} 条关联日志</span>
                <small>{{ anomalousHopSummary }}</small>
              </summary>
              <ol class="hop-list">
                <li v-for="(hop, index) in developer.callChain.hops" :key="hop.hopId" :class="{ anomalous: hop.anomalous }">
                  <span>{{ index + 1 }}</span><b>{{ hop.service }}</b><small>{{ hop.duration }}</small><em>{{ hop.anomalous ? '异常' : '正常' }}</em>
                </li>
              </ol>
            </details>
          </template>
          <p v-else class="empty-evidence">{{ developer.callChain.emptyReason }}</p>
          <section class="contrast-summary" :class="{ unavailable: !developer.contrast.available }">
            <header><div><span>请求表现对比</span><b>故障请求和正常请求有什么不同</b></div><em>{{ developer.contrast.available ? '已比较' : '未取得' }}</em></header>
            <template v-if="contrastNarrative">
              <div class="contrast-human">
                <strong>{{ contrastNarrative.summary }}</strong>
                <p>{{ contrastNarrative.interpretation }}</p>
                <small>{{ contrastNarrative.scope }}</small>
              </div>
              <details class="contrast-technical">
                <summary>查看精确数量与证据引用</summary>
                <p>
                  失败请求共 {{ developer.contrast.failedRequests?.totalRequests }} 个，其中
                  {{ developer.contrast.failedRequests?.requestsWithFeature }} 个出现该现象；
                  正常请求共 {{ developer.contrast.normalRequests?.totalRequests }} 个，其中
                  {{ developer.contrast.normalRequests?.requestsWithFeature }} 个出现。
                </p>
                <code>{{ developer.contrast.evidenceRefs.join('、') }}</code>
              </details>
            </template>
            <p>{{ developer.contrast.note }}</p>
          </section>
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
              @click="$emit('openDataSourceValidation')"
            >检查数据连接</el-button>
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
import { ref, computed } from 'vue'
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
import { evidenceComparisonNarrative } from './evidencePlainLanguage'

const STEP_TONE_LABEL: Record<EvidenceStepTone, string> = {
  NORMAL: '正常', ANOMALY: '发现异常', EXCLUDED: '已排除', UNEVALUATED: '未求值',
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
  openDataSourceValidation: []
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

const chainAnomalyCount = computed(() => (
  props.developer?.callChain.hops.filter(hop => hop.anomalous).length ?? 0
))

const chainDurationCount = computed(() => (
  props.developer?.callChain.hops.filter(hop => hop.duration?.trim() && hop.duration !== '未记录').length ?? 0
))

const contrastNarrative = computed(() => {
  const contrast = props.developer?.contrast
  if (!contrast?.available || !contrast.failedRequests || !contrast.normalRequests) return null
  return evidenceComparisonNarrative({
    featureCode: contrast.featureCode,
    failureRequestCount: contrast.failedRequests.totalRequests,
    failureWithFeatureCount: contrast.failedRequests.requestsWithFeature,
    normalRequestCount: contrast.normalRequests.totalRequests,
    normalWithFeatureCount: contrast.normalRequests.requestsWithFeature,
  })
})

const chainServiceGroups = computed(() => {
  const groups: Array<{ service: string; count: number; anomalous: boolean }> = []
  for (const hop of props.developer?.callChain.hops ?? []) {
    const last = groups.at(-1)
    if (last?.service === hop.service) {
      last.count += 1
      last.anomalous ||= hop.anomalous
    } else {
      groups.push({ service: hop.service, count: 1, anomalous: hop.anomalous })
    }
  }
  return groups
})

const anomalousHopSummary = computed(() => {
  const hops = props.developer?.callChain.hops ?? []
  const positions = hops.flatMap((hop, index) => hop.anomalous ? [index + 1] : [])
  if (!positions.length) return '未标记异常日志'
  return `异常日志：第 ${positions.join('、')} 条`
})

function stepToneLabel(value: EvidenceStepTone) { return STEP_TONE_LABEL[value] }

function evidenceTime(kind: EvidenceStepKind, value: string | null) {
  return kind === 'CRITERION' ? '判据' : shortTime(value).slice(11)
}

</script>

<style scoped>
/* ── Panel shell ── */
.developer-fold { width:100%; max-width:none; container-type:inline-size; margin:14px 0 0; border:1px solid var(--mc-border); border-radius:var(--mc-radius-md); background:var(--mc-bg-elevated); box-shadow:var(--mc-shadow-soft); overflow:hidden; }

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

/* ── Call-chain summary ── */
.chain-head>div:first-child p { margin:6px 0 0; color:var(--mc-text-secondary); font-size:var(--mc-text-xs); line-height:1.5; }
.chain-identity { max-width:48%; text-align:right; }
.chain-identity span,.chain-identity code { display:block; }
.chain-identity span { color:var(--mc-text-tertiary); font-size:10px; }
.chain-identity code { margin-top:5px; color:var(--mc-primary); font-size:var(--mc-text-xs); overflow-wrap:anywhere; }
.chain-metrics { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); margin-top:17px; border-top:1px solid var(--mc-border-light); border-bottom:1px solid var(--mc-border-light); }
.chain-metrics>div { padding:13px 14px; }
.chain-metrics>div+div { border-left:1px solid var(--mc-border-light); }
.chain-metrics strong,.chain-metrics span { display:block; }
.chain-metrics strong { font-size:var(--mc-text-lg); line-height:1; }
.chain-metrics span { margin-top:6px; color:var(--mc-text-tertiary); font-size:10px; }
.chain-metrics>div.anomalous strong { color:var(--mc-danger); }
.chain-route { display:grid; grid-template-columns:72px minmax(0,1fr); align-items:start; gap:12px; padding:14px 2px; }
.chain-route>span { padding-top:4px; color:var(--mc-text-tertiary); font-size:10px; }
.chain-route>div { display:flex; align-items:center; flex-wrap:wrap; gap:7px; min-width:0; }
.chain-route b { padding:5px 8px; border-radius:var(--mc-radius-xs); color:var(--mc-text-primary); background:var(--mc-bg-muted); font-size:var(--mc-text-xs); font-weight:600; overflow-wrap:anywhere; }
.chain-route b.anomalous { color:var(--mc-status-error-text); background:var(--mc-status-error-bg); }
.chain-route b em { color:var(--mc-text-tertiary); font-style:normal; font-weight:500; }
.chain-route i { color:var(--mc-text-tertiary); font-style:normal; }
.chain-details { border-top:1px solid var(--mc-border-light); }
.chain-details>summary { display:flex; align-items:center; justify-content:space-between; gap:12px; padding:12px 2px 0; color:var(--mc-primary); font-size:var(--mc-text-xs); cursor:pointer; list-style:none; }
.chain-details>summary::-webkit-details-marker { display:none; }
.chain-details>summary span::before { content:'＋'; display:inline-block; width:18px; }
.chain-details[open]>summary span::before { content:'－'; }
.chain-details>summary small { color:var(--mc-text-tertiary); font-size:10px; text-align:right; }
.hop-list { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:0 14px; margin:12px 0 0; padding:0; list-style:none; }
.hop-list li { display:grid; grid-template-columns:24px minmax(0,1fr) auto auto; align-items:center; gap:8px; min-width:0; padding:8px 2px; border-top:1px solid var(--mc-border-light); }
.hop-list li>span { display:inline-grid; place-items:center; width:20px; height:20px; border-radius:50%; color:var(--mc-text-secondary); background:var(--mc-bg-muted); font-size:10px; }
.hop-list li>b { min-width:0; font-size:var(--mc-text-xs); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.hop-list li>small { color:var(--mc-text-tertiary); font-size:10px; }
.hop-list li>em { color:var(--mc-text-secondary); font-size:10px; font-style:normal; }
.hop-list li.anomalous>span { color:var(--mc-text-inverse); background:var(--mc-danger); }
.hop-list li.anomalous>em { color:var(--mc-danger); font-weight:700; }

/* ── Empty state ── */
.empty-evidence { margin:14px 0 0; padding:11px 12px; border:1px dashed var(--mc-border); border-radius:var(--mc-radius-sm); color:var(--mc-text-secondary); background:var(--mc-bg-elevated); font-size:var(--mc-text-xs); line-height:1.65; }

/* ── Failed/success contrast ── */
.contrast-summary { margin-top:16px; padding-top:15px; border-top:1px solid var(--mc-border); }
.contrast-summary>header { display:flex; align-items:flex-start; justify-content:space-between; gap:12px; }
.contrast-summary>header span,.contrast-summary>header b { display:block; }
.contrast-summary>header span { color:var(--mc-text-tertiary); font-size:10px; }
.contrast-summary>header b { margin-top:4px; font-size:var(--mc-text-sm); }
.contrast-summary>header>em { padding:3px 7px; border-radius:999px; color:var(--mc-status-success-text); background:var(--mc-status-success-bg); font-size:10px; font-style:normal; }
.contrast-human { margin-top:12px; padding:13px 14px; border-left:3px solid var(--mc-success); border-radius:var(--mc-radius-xs); background:var(--mc-status-success-bg); }
.contrast-human strong,.contrast-human p,.contrast-human small { display:block; }
.contrast-human strong { font-size:var(--mc-text-sm); line-height:1.55; }
.contrast-human p { margin:6px 0 0; color:var(--mc-text-secondary); font-size:var(--mc-text-xs); line-height:1.55; }
.contrast-human small { margin-top:7px; color:var(--mc-text-tertiary); font-size:10px; line-height:1.5; }
.contrast-technical { margin-top:9px; color:var(--mc-text-secondary); font-size:var(--mc-text-xs); }
.contrast-technical summary { color:var(--mc-primary); cursor:pointer; }
.contrast-technical p { margin:8px 0; }
.contrast-technical code { overflow-wrap:anywhere; }
.contrast-summary>p { margin:10px 0 0; color:var(--mc-text-secondary); font-size:var(--mc-text-xs); line-height:1.55; }
.contrast-summary.unavailable { padding:12px; border:0; border-radius:var(--mc-radius-sm); color:var(--mc-warning); background:var(--mc-status-warning-bg); }
.contrast-summary.unavailable>header>em { color:var(--mc-status-warning-text); background:var(--mc-bg-elevated); }

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

/* ── Responsive ── */
@container (max-width:900px){
  .developer-body,.convergence-grid{grid-template-columns:1fr}
  .developer-body>.evidence-timeline,.developer-body>.developer-side{grid-column:1/-1}
  .developer-body--empty-timeline>.developer-side{grid-template-columns:1fr}
  .source-status{flex-direction:column}
  .source-status-governance{max-width:none; align-items:flex-start}
  .source-status-governance small{text-align:left}
  .hop-list{grid-template-columns:1fr}
}
@container (max-width:480px){
  .chain-head{flex-direction:column}
  .chain-identity{max-width:none; text-align:left}
}
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
  .hop-list{grid-template-columns:1fr}
}
</style>
