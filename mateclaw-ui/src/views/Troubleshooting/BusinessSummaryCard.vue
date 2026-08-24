<template>
  <section v-if="business" class="business-card" :class="`is-${perspective}`">
    <section v-if="perspective === 'developer'" class="cause-hero">
      <div class="developer-conclusion-head">
        <span>问题原因</span>
        <strong class="developer-state" :class="business.conclusionType.toLowerCase()">{{ developerExplanation.state }}</strong>
      </div>
      <ul class="provenance-strip" aria-label="证据成色">
        <li
          v-for="chip in provenanceChips"
          :key="chip.key"
          class="provenance-chip"
          :class="`is-${chip.tone}`"
        >{{ chip.label }}</li>
      </ul>
      <h2>{{ rootCauseAnswer }}</h2>
      <div class="cause-summary">
        <section class="cause-primary">
          <span>{{ business.conclusionType === 'LOCATED' ? '根因结论' : '当前能确认' }}</span>
          <strong>{{ developerExplanation.known }}</strong>
        </section>
        <section class="cause-gap">
          <span>{{ business.conclusionType === 'LOCATED' ? '仍需确认' : '距离根因还缺' }}</span>
          <strong>{{ developerExplanation.unknown }}</strong>
        </section>
      </div>
    </section>

    <section v-if="perspective === 'developer'" class="problem-brief">
      <div class="problem-brief-title">
        <span>故障背景</span>
        <strong>{{ problemBrief.title }}</strong>
      </div>
      <dl>
        <div><dt>系统 / 服务</dt><dd>{{ problemBrief.scope }}</dd></div>
        <div><dt>发生时间</dt><dd>{{ problemBrief.occurredAt }}</dd></div>
        <div><dt>告警信息</dt><dd>{{ problemBrief.alertSignal }}</dd></div>
      </dl>
    </section>

    <div v-if="perspective === 'support'" class="verdict-head">
      <div class="verdict-copy">
        <div class="verdict-state">
          <span class="verdict-dot" :class="business.conclusionType.toLowerCase()" />
          <span>{{ conclusionLabel(business.conclusionType) }}</span>
          <i />
          <span>{{ decisionStatusLabel }}</span>
        </div>
        <ul class="provenance-strip" aria-label="证据成色">
          <li
            v-for="chip in provenanceChips"
            :key="chip.key"
            class="provenance-chip"
            :class="`is-${chip.tone}`"
          >{{ chip.label }}</li>
        </ul>
        <span class="verdict-label">当前处理判断</span>
        <h2>{{ hero.title }}</h2>
        <p>{{ hero.summary }}</p>
      </div>
    </div>

    <div v-if="perspective === 'support'" class="summary-grid support-summary-grid">
      <article>
        <span class="section-label">发生了什么</span>
        <strong>{{ business.problem }}</strong>
      </article>
      <article>
        <span class="section-label">影响到什么</span>
        <strong>{{ showImpact ? business.impact.functionScope : unknownImpact.statement }}</strong>
        <div v-if="showImpact && impactMetricList.length" class="impact-metrics">
          <span v-for="metric in impactMetricList" :key="metric">{{ metric }}</span>
        </div>
        <p v-else class="impact-conservative">{{ unknownImpact.conservativeAction }}</p>
      </article>
      <article>
        <span class="section-label">是否需要升级三线</span>
        <strong>{{ supportHandoff }}</strong>
      </article>
      <article>
        <span class="section-label">二线现在怎么处理</span>
        <strong>{{ supportAction }}</strong>
      </article>
    </div>

    <section v-if="closure" class="closure-result">
      <div>
        <span class="section-label">最终处置结果</span>
        <b>{{ closureOutcomeLabel(closure.outcome) }}</b>
      </div>
      <strong>{{ closure.summary }}</strong>
      <small>
        {{ closure.recoveryVerified ? '恢复已经人工验证' : '未声明恢复已经验证' }}
        · {{ shortTime(closure.closedAt) }}
      </small>
    </section>

    <section class="decision-panel" :class="status?.toLowerCase()">
      <div>
        <span class="section-label">{{ perspective === 'developer' ? '接下来做什么' : '二线下一步' }}</span>
        <strong>{{ nextStepPanel.title }}</strong>
        <p>{{ nextStepPanel.detail }}</p>
        <small v-if="nextStepPanel.boundary">{{ nextStepPanel.boundary }}</small>
        <small v-if="nextStepPanel.blocker" class="action-blocker">{{ nextStepPanel.blocker }}</small>
        <small v-if="rehearsal">这是演练记录：可以体验确认和关闭流程，但不会计入正式系统负责人验收目标。</small>
      </div>
      <div class="lifecycle-actions">
        <el-button
          v-if="showConfirmAction"
          :type="nextStepPanel.primaryAction === 'confirm' ? 'primary' : undefined"
          :loading="actionLoading"
          @click="$emit('confirm')"
        >复核后确认定位</el-button>
        <el-button
          v-if="canTransfer"
          :type="nextStepPanel.primaryAction === 'transfer' ? 'primary' : undefined"
          :disabled="actionLoading"
          @click="$emit('transfer')"
        >转给负责人继续查</el-button>
        <el-button
          v-if="canClose"
          :type="nextStepPanel.primaryAction === 'close' ? 'primary' : undefined"
          :disabled="actionLoading"
          @click="$emit('close')"
        >登记实际结果</el-button>
        <el-button
          v-if="status === 'CLOSED' && canEvaluate"
          :type="nextStepPanel.primaryAction === 'evaluate' ? 'primary' : undefined"
          plain
          @click="$emit('evaluate')"
        >把这张单纳入试点评估</el-button>
      </div>
    </section>

    <details v-if="perspective === 'developer'" class="supporting-details">
      <summary>
        <div><b>查看判断依据</b><small>需要质疑结论或交接复核时再看</small></div>
        <span>{{ confidencePresentation.label }}</span>
      </summary>

      <section class="judgement-basis">
        <div>
          <span>事实来源</span>
          <strong>{{ evidenceBasisPresentation.title }}</strong>
          <p>{{ developerExplanation.reason }}</p>
        </div>
        <div>
          <span>当前边界</span>
          <strong>{{ developerExplanation.unknown }}</strong>
          <p>{{ confidencePresentation.detail }}</p>
        </div>
      </section>

      <section v-if="failureBreakdown?.available" class="root-cause-candidates">
        <div class="candidate-intro">
          <span class="section-label">候选线索</span>
          <strong>{{ failureBreakdown.totalRequests }} 个失败请求，识别出 {{ failureBreakdown.groups.length }} 类线索</strong>
          <small>多类线索同时出现时，平台不会任选一个冒充唯一根因。</small>
        </div>
        <div class="candidate-list">
          <article v-for="(group, index) in failureBreakdown.groups" :key="group.code">
            <span>{{ index + 1 }}</span>
            <div><b>{{ group.label }}</b><small>{{ group.requestCount }} 个失败请求出现</small></div>
          </article>
          <article v-if="hasUnclassifiedRequests" class="candidate-pending">
            <span>?</span>
            <div><b>尚未归类</b><small>{{ failureBreakdown.unclassifiedRequests }} 个失败请求仍需继续调查</small></div>
          </article>
        </div>
      </section>

      <details class="north-star">
        <summary>
          <span class="ns-title">查看阶段耗时</span>
          <span class="ns-note">补问 / 调查 / 采纳分开计，不合成总时长</span>
        </summary>

      <ol class="ns-stages">
        <li v-for="stage in stages" :key="stage.key" class="ns-stage" :class="stage.key + ' ' + stage.state">
          <div class="ns-head">
            <span class="ns-index">{{ stage.index }}</span>
            <span class="ns-label">{{ stage.label }}</span>
            <span class="ns-owner">{{ stage.owner }}</span>
          </div>
          <b class="ns-cost">{{ stage.display }}</b>
          <div class="ns-bar" :class="{ empty: stage.share === null }">
            <i v-if="stage.share !== null" :style="{ width: Math.max(stage.share * 100, 2) + '%' }" />
          </div>
          <small class="ns-range">
            {{ timeRange(stage.from, stage.to, stage.key === 'adopt') }}
            <template v-if="stage.share !== null"> · 占比 {{ Math.round(stage.share * 100) }}%</template>
          </small>
        </li>
      </ol>

      <p v-if="!stagesComplete" class="ns-incomplete">
        有阶段尚未记录，因此不显示占比。
      </p>
      </details>
    </details>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type {
  BusinessSummary,
  ClosureRecord,
  DiagnosisStatus,
  FailureBreakdownView,
  IncidentContext,
} from '@/api'
import {
  closureOutcomeLabel,
  conclusionLabel,
  impactMetrics,
  northStarStages,
} from './formalProjection'
import {
  diagnosisStatusLabel as statusLabel,
  formatWorkbenchTime as shortTime,
} from './workbenchView'
import { diagnosisConfidencePresentation } from './evidencePlainLanguage'
import {
  diagnosisConfirmedCandidateGuidance,
  diagnosisDeveloperExplanation,
  diagnosisDecisionStatusLabel,
  diagnosisPerspectiveHero,
  diagnosisProblemBrief,
  diagnosisRootCauseAnswer,
  diagnosisSupportAction,
  type DiagnosisPerspective,
} from './diagnosisPerspective'
import {
  diagnosisNextStepPanel,
  diagnosisProvenanceChips,
  diagnosisSupportHandoffCopy,
  diagnosisUnknownImpactCopy,
} from './diagnosisDetailPresentation'

interface Props {
  business: BusinessSummary | null
  incident: IncidentContext
  closure: ClosureRecord | null
  canOperate: boolean
  canTransfer: boolean
  canClose: boolean
  canEvaluate: boolean
  rehearsal: boolean
  actionLoading: boolean
  status: DiagnosisStatus | null
  perspective: DiagnosisPerspective
  failureBreakdown: FailureBreakdownView | null
}

const props = defineProps<Props>()

/** Three separately owned cost segments; never summed into one number (D14). */
const stages = computed(() => northStarStages(props.business?.timings))
const stagesComplete = computed(() => stages.value.every((stage) => stage.share !== null))
const confidencePresentation = computed(() => diagnosisConfidencePresentation(props.business?.confidence))
const problemBrief = computed(() => diagnosisProblemBrief(
  props.incident,
  props.business?.problem || '',
))
const rootCauseAnswer = computed(() => {
  const business = props.business
  if (!business) return '正在读取诊断结果'
  return diagnosisRootCauseAnswer({
    conclusionType: business.conclusionType,
    rootCause: business.rootCause,
    headline: business.headline,
  })
})
const decisionStatusLabel = computed(() => {
  const business = props.business
  if (!business) return '状态未记录'
  return diagnosisDecisionStatusLabel(
    business.status,
    business.conclusionType,
    statusLabel(business.status),
  )
})
const evidenceBasisPresentation = computed(() => {
  const basis = props.business?.evidenceBasis
  if (basis === 'OBSERVED') {
    return {
      title: '第一个事实来自只读数据源',
      detail: '系统只使用本次已经记录的取证结果，不用模型猜测补齐缺失事实。',
    }
  }
  if (basis === 'RECORDED_REPLAY') {
    return {
      title: '第一个事实来自录制样本',
      detail: '这是可复现的历史样本回放，不等同于本次故障现场的实时取证。',
    }
  }
  return {
    title: '第一个事实来自告警原文',
    detail: '系统只确认告警明确上报的失败点，没有把它当作已验证的上游根因。',
  }
})
const hasUnclassifiedRequests = computed(() => {
  const value = props.failureBreakdown?.unclassifiedRequests
  return typeof value === 'number' ? value > 0 : typeof value === 'string' && value !== '0'
})
const hero = computed(() => {
  const business = props.business
  if (!business) return { title: '等待诊断加载', summary: '正在读取本次排障事实。' }
  return diagnosisPerspectiveHero({
    perspective: props.perspective,
    conclusionType: business.conclusionType,
    rootCause: business.rootCause,
    headline: business.headline,
    narrative: business.narrative,
    candidateCount: props.failureBreakdown?.available ? props.failureBreakdown.groups.length : 0,
  })
})
const developerExplanation = computed(() => {
  const business = props.business
  if (!business) {
    return {
      state: '正在加载',
      known: '正在读取本次排障事实。',
      unknown: '等待诊断加载完成。',
      reason: '加载完成后会说明判断依据。',
    }
  }
  return diagnosisDeveloperExplanation({
    conclusionType: business.conclusionType,
    evidenceBasis: business.evidenceBasis,
    keyEvidence: business.keyEvidence,
    rootCause: business.rootCause,
    candidateCount: props.failureBreakdown?.available ? props.failureBreakdown.groups.length : 0,
  })
})
const impactMetricList = computed(() => {
  const impact = props.business?.impact
  return impact ? impactMetrics(impact.affectedCustomers, impact.affectedUsers) : []
})
const showImpact = computed(() => {
  const impact = props.business?.impact
  if (!impact) return false
  return impact.blastRadius !== 'UNKNOWN'
    || impactMetricList.value.length > 0
})
const supportHandoff = computed(() => props.business
  ? diagnosisSupportHandoffCopy({
    conclusionType: props.business.conclusionType,
    impactKnown: showImpact.value,
  })
  : '等待排障事实加载。')
const supportAction = computed(() => props.business
  ? diagnosisSupportAction(props.business.conclusionType)
  : '等待排障事实加载。')
const unknownImpact = computed(() => diagnosisUnknownImpactCopy())
const provenanceChips = computed(() => {
  const business = props.business
  if (!business) return []
  return diagnosisProvenanceChips({
    evidenceBasis: business.evidenceBasis,
    fixtureMode: business.fixtureMode,
    rehearsal: props.rehearsal,
  })
})
const nextStepPanel = computed(() => {
  const confirmedCandidateGuidance = props.perspective === 'developer' && props.business
    ? diagnosisConfirmedCandidateGuidance({
      status: props.business.status,
      conclusionType: props.business.conclusionType,
      nextStep: props.business.nextStep.text,
    })
    : null
  const panel = diagnosisNextStepPanel({
    perspective: props.perspective,
    status: props.status,
    conclusionType: props.business?.conclusionType || 'INSUFFICIENT_EVIDENCE',
    nextStep: props.business?.nextStep || null,
    canOperate: props.canOperate,
    canTransfer: props.canTransfer,
    canClose: props.canClose,
    canEvaluate: props.canEvaluate,
  })
  if (!confirmedCandidateGuidance) return panel
  return {
    ...panel,
    title: confirmedCandidateGuidance.title,
    detail: confirmedCandidateGuidance.detail,
  }
})
const showConfirmAction = computed(() => nextStepPanel.value.primaryAction === 'confirm')

defineEmits<{
  confirm: []
  transfer: []
  close: []
  evaluate: []
}>()

function timeRange(from: string | null, to: string | null, pending = false) {
  if (!from && !to) return pending ? '尚未发生交接' : '阶段时间戳尚未纳入 Diagnosis'
  return `${shortTime(from)} → ${shortTime(to)}`
}
</script>

<style scoped>
.business-card { width:100%; max-width:none; margin:0; border:1px solid var(--mc-border); border-radius:var(--mc-radius-md); background:var(--mc-bg-elevated); box-shadow:var(--mc-shadow-soft); padding:clamp(18px,2.2vw,26px); }
.business-card.is-developer { padding:clamp(24px,2.6vw,32px); box-shadow:0 14px 42px color-mix(in srgb,var(--mc-text-primary) 7%,transparent); }
.cause-hero { padding-bottom:clamp(18px,2vw,24px); }
.cause-hero h2 { max-width:980px; margin:13px 0 18px; font-size:clamp(32px,3.8vw,52px); font-weight:760; line-height:1.08; letter-spacing:-.05em; text-wrap:balance; }
.problem-brief { padding:15px 2px 17px; border-top:1px solid var(--mc-border); border-bottom:1px solid var(--mc-border); }
.problem-brief-title { display:grid; grid-template-columns:84px minmax(0,1fr); gap:16px; align-items:baseline; }
.problem-brief-title>span { color:var(--mc-text-tertiary); font-size:11px; font-weight:700; }
.problem-brief-title>strong { overflow:hidden; font-size:14px; line-height:1.45; text-overflow:ellipsis; white-space:nowrap; }
.problem-brief dl { display:grid; grid-template-columns:minmax(220px,1.2fr) minmax(180px,.85fr) minmax(170px,.75fr); gap:0; margin:13px 0 0 100px; }
.problem-brief dl>div { min-width:0; padding-right:20px; }
.problem-brief dl>div+div { padding-left:20px; border-left:1px solid var(--mc-border-light); }
.problem-brief dt { color:var(--mc-text-tertiary); font-size:11px; font-weight:700; }
.problem-brief dd { overflow:hidden; margin:5px 0 0; color:var(--mc-text-primary); font-size:13px; font-weight:650; line-height:1.45; text-overflow:ellipsis; white-space:nowrap; }
.verdict-head { padding-bottom:15px; }
.verdict-label { display:block; margin-top:13px; color:var(--mc-text-tertiary); font-size:11px; font-weight:750; letter-spacing:.08em; }
.verdict-state { display:flex; align-items:center; gap:8px; color:var(--mc-text-secondary); font-size:12px; font-weight:650; }
.verdict-state i { width:1px; height:12px; background:var(--mc-border); }
.verdict-dot { width:9px; height:9px; border-radius:50%; background:var(--mc-text-tertiary); }
.verdict-dot.located { background:var(--mc-success); }
.verdict-dot.hypothesis { background:var(--mc-warning); }
.verdict-dot.excluded { background:var(--mc-text-tertiary); }
.verdict-dot.insufficient_evidence { background:var(--mc-danger); }
.verdict-copy h2 { max-width:980px; margin:8px 0 6px; font-size:clamp(21px,2vw,28px); line-height:1.2; letter-spacing:-.03em; }
.verdict-copy>p { max-width:900px; margin:0; color:var(--mc-text-secondary); font-size:12px; line-height:1.6; }
.developer-conclusion-head { display:flex; align-items:center; gap:10px; }
.developer-conclusion-head>span { color:var(--mc-text-tertiary); font-size:12px; font-weight:700; }
.provenance-strip { display:flex; flex-wrap:wrap; gap:6px; margin:10px 0 0; padding:0; list-style:none; }
.provenance-chip { display:inline-flex; align-items:center; min-height:24px; padding:0 9px; border-radius:999px; border:1px solid var(--mc-border); color:var(--mc-text-secondary); background:var(--mc-bg-muted); font-size:11px; font-weight:700; }
.provenance-chip.is-observed { color:var(--mc-status-success-text); background:var(--mc-status-success-bg); border-color:color-mix(in srgb,var(--mc-success) 35%,var(--mc-border)); }
.provenance-chip.is-reported { color:var(--mc-status-warning-text); background:var(--mc-status-warning-bg); border-color:color-mix(in srgb,var(--mc-warning) 35%,var(--mc-border)); }
.provenance-chip.is-replay,
.provenance-chip.is-fixture { color:var(--mc-status-info-text); background:var(--mc-status-info-bg); border-color:color-mix(in srgb,var(--mc-primary) 30%,var(--mc-border)); }
.provenance-chip.is-rehearsal { color:var(--mc-text-secondary); background:var(--mc-bg-elevated); }
.impact-conservative { margin:8px 0 0; color:var(--mc-text-secondary); font-size:12px; line-height:1.55; }
.action-blocker { display:block; margin-top:6px; color:var(--mc-status-warning-text); }
.developer-state { display:inline-flex; align-items:center; min-height:28px; padding:0 11px; border-radius:999px; color:var(--mc-status-warning-text); background:var(--mc-status-warning-bg); font-size:12px; font-weight:750; }
.developer-state.located { color:var(--mc-status-success-text); background:var(--mc-status-success-bg); }
.developer-state.insufficient_evidence { color:var(--mc-status-error-text); background:var(--mc-status-error-bg); }
.cause-summary { display:grid; grid-template-columns:minmax(0,1.55fr) minmax(260px,.75fr); border-top:1px solid var(--mc-border); border-bottom:1px solid var(--mc-border); }
.cause-summary section { min-width:0; padding:15px 2px 17px; }
.cause-summary section+section { padding-left:24px; border-left:1px solid var(--mc-border-light); }
.cause-summary span,.cause-summary strong { display:block; }
.cause-summary span { color:var(--mc-text-tertiary); font-size:12px; font-weight:700; }
.cause-summary strong { max-width:900px; margin-top:7px; font-size:clamp(15px,1.5vw,18px); line-height:1.55; }
.cause-gap strong { color:var(--mc-text-secondary); font-size:14px; font-weight:620; }
.root-cause-candidates { display:grid; grid-template-columns:minmax(240px,.8fr) minmax(0,1.4fr); gap:20px; margin:16px 0 0; padding:16px; border:1px solid var(--mc-border); border-left:3px solid var(--mc-primary); border-radius:var(--mc-radius-sm); background:var(--mc-bg-muted); }
.candidate-intro strong,.candidate-intro small { display:block; }
.candidate-intro strong { margin-top:8px; font-size:var(--mc-text-sm); line-height:1.5; }
.candidate-intro small { margin-top:6px; color:var(--mc-text-secondary); font-size:12px; line-height:1.55; }
.candidate-list { display:grid; gap:8px; }
.candidate-list article { display:flex; align-items:center; gap:10px; min-height:52px; padding:9px 12px; border:1px solid var(--mc-border-light); border-radius:var(--mc-radius-sm); background:var(--mc-bg-elevated); }
.candidate-list article>span { display:grid; place-items:center; flex:0 0 24px; width:24px; height:24px; border-radius:50%; color:#fff; background:var(--mc-primary); font-size:12px; font-weight:700; }
.candidate-list b,.candidate-list small { display:block; }
.candidate-list b { font-size:13px; line-height:1.4; }
.candidate-list small { margin-top:2px; color:var(--mc-text-tertiary); font-size:11px; }
.candidate-list .candidate-pending>span { background:var(--mc-text-tertiary); }
.section-label { display:block; color:var(--mc-text-tertiary); font-size:12px; font-weight:750; letter-spacing:.1em; text-transform:uppercase; }
.summary-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); overflow:hidden; border:1px solid var(--mc-border); border-radius:var(--mc-radius-sm); }
.support-summary-grid { grid-template-columns:repeat(2,minmax(0,1fr)); }
.support-summary-grid article:nth-child(3) { border-top:1px solid var(--mc-border); border-left:0; }
.support-summary-grid article:nth-child(4) { border-top:1px solid var(--mc-border); }
.summary-grid article { padding:14px 16px; }
.summary-grid article+article { border-left:1px solid var(--mc-border); }
.summary-grid strong { display:block; margin:8px 0 0; font-size:var(--mc-text-sm); line-height:1.55; white-space:pre-line; }
.impact-metrics { display:flex; gap:7px; margin:8px 0 0; } .impact-metrics span { padding:2px 7px; border-radius:var(--mc-radius-xs); color:var(--mc-status-info-text); background:var(--mc-status-info-bg); font-size:12px; }
.closure-result { display:grid; grid-template-columns:180px minmax(0,1fr) auto; align-items:center; gap:18px; margin-top:14px; padding:14px 16px; border:1px solid var(--mc-success); border-radius:var(--mc-radius-sm); background:var(--mc-status-success-bg); }
.closure-result div b { display:block; margin-top:5px; color:var(--mc-success); font-size:var(--mc-text-sm); } .closure-result>strong { font-size:var(--mc-text-sm); line-height:1.55; } .closure-result>small { color:var(--mc-text-secondary); font-size:12px; text-align:right; }
.decision-panel { display:grid; grid-template-columns:minmax(0,1fr) auto; align-items:center; gap:24px; margin-top:14px; padding:13px 16px; border-left:4px solid var(--mc-primary); border-radius:var(--mc-radius-sm); background:var(--mc-bg-muted); }
.is-developer .decision-panel { margin-top:16px; padding:15px 18px; border-left-width:3px; }
.decision-panel strong { display:block; margin-top:6px; font-size:15px; line-height:1.45; }
.is-developer .decision-panel strong { max-width:680px; font-size:17px; }
.decision-panel p { margin:5px 0 0; color:var(--mc-text-secondary); font-size:12px; line-height:1.6; }
.decision-panel small { display:block; margin-top:7px; color:var(--mc-status-purple-text); font-size:11px; line-height:1.5; }
.lifecycle-actions { display:flex; align-items:center; justify-content:flex-end; gap:8px; flex-wrap:wrap; }
.supporting-details { margin-top:14px; border-top:1px solid var(--mc-border); }
.supporting-details>summary { display:flex; align-items:center; gap:16px; padding:14px 2px 0; cursor:pointer; list-style:none; }
.supporting-details>summary::-webkit-details-marker { display:none; }
.supporting-details>summary>div b,.supporting-details>summary>div small { display:block; }
.supporting-details>summary>div b { font-size:13px; }
.supporting-details>summary>div small { margin-top:3px; color:var(--mc-text-tertiary); font-size:11px; }
.supporting-details>summary>span { margin-left:auto; color:var(--mc-text-tertiary); font-size:11px; }
.supporting-details>summary::before { content:'＋'; color:var(--mc-primary); font-size:16px; }
.supporting-details[open]>summary::before { content:'－'; }
.judgement-basis { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:1px; overflow:hidden; margin-top:14px; border:1px solid var(--mc-border); border-radius:var(--mc-radius-sm); background:var(--mc-border); }
.judgement-basis>div { padding:16px; background:var(--mc-bg-muted); }
.judgement-basis span,.judgement-basis strong,.judgement-basis p { display:block; }
.judgement-basis span { color:var(--mc-text-tertiary); font-size:11px; font-weight:700; }
.judgement-basis strong { margin-top:7px; font-size:13px; line-height:1.5; }
.judgement-basis p { margin:5px 0 0; color:var(--mc-text-secondary); font-size:11px; line-height:1.6; }
.north-star { margin-top:14px; padding:10px 16px 12px; border:1px solid var(--mc-border); border-radius:var(--mc-radius-sm); background:var(--mc-bg-elevated); }
.north-star > summary { display:flex; align-items:baseline; gap:10px; flex-wrap:wrap; cursor:pointer; list-style:none; }
.north-star > summary::-webkit-details-marker { display:none; }
.ns-title { color:var(--mc-text-primary); font-size:12px; font-weight:600; letter-spacing:.04em; }
.ns-note { color:var(--mc-text-tertiary); font-size:12px; }
.ns-stages { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:10px; margin:10px 0 0; padding:0; list-style:none; }
.ns-stage { display:grid; gap:5px; padding:10px 12px; border:1px solid var(--mc-border); border-radius:var(--mc-radius-sm); background:var(--mc-bg); border-left-width:3px; }
.ns-stage.intake { border-left-color:var(--mc-primary); }
.ns-stage.investigate { border-left-color:var(--mc-success); }
.ns-stage.adopt { border-left-color:var(--mc-warning); }
.ns-stage.PENDING, .ns-stage.UNRECORDED { border-style:dashed; border-left-style:solid; }
.ns-head { display:flex; align-items:center; gap:7px; flex-wrap:wrap; }
.ns-index { display:grid; place-items:center; width:17px; height:17px; border-radius:50%; color:#fff; font-size:11px; font-variant-numeric:tabular-nums; }
.ns-stage.intake .ns-index { background:var(--mc-primary); }
.ns-stage.investigate .ns-index { background:var(--mc-success); }
.ns-stage.adopt .ns-index { background:var(--mc-warning); }
.ns-stage.PENDING .ns-index, .ns-stage.UNRECORDED .ns-index { background:var(--mc-text-tertiary); }
.ns-label { color:var(--mc-text-primary); font-size:12px; font-weight:600; }
.ns-owner { margin-left:auto; color:var(--mc-text-tertiary); font-size:12px; }
.ns-cost { color:var(--mc-text-primary); font-size:17px; font-variant-numeric:tabular-nums; letter-spacing:-.01em; }
.ns-stage.PENDING .ns-cost, .ns-stage.UNRECORDED .ns-cost { color:var(--mc-text-tertiary); font-size:var(--mc-text-sm); }
.ns-bar { height:3px; border-radius:2px; background:var(--mc-border); overflow:hidden; }
.ns-bar.empty { background:transparent; border-top:1px dashed var(--mc-border); height:1px; border-radius:0; }
.ns-bar i { display:block; height:100%; border-radius:2px; }
.ns-stage.intake .ns-bar i { background:var(--mc-primary); }
.ns-stage.investigate .ns-bar i { background:var(--mc-success); }
.ns-stage.adopt .ns-bar i { background:var(--mc-warning); }
.ns-range { color:var(--mc-text-tertiary); font-size:12px; }
.ns-incomplete { margin:10px 0 0; color:var(--mc-text-tertiary); font-size:12px; line-height:1.6; }
.active { color:var(--mc-primary)!important; } .success { color:var(--mc-success)!important; } .warning { color:var(--mc-warning)!important; } .muted { color:var(--mc-text-tertiary)!important; }
@media(max-width:1100px){.problem-brief dl{grid-template-columns:1fr 1fr}.problem-brief dl>div:nth-child(3){grid-column:1/-1;margin-top:14px;padding-top:14px;padding-left:0;border-top:1px solid var(--mc-border-light);border-left:0}.summary-grid,.support-summary-grid{grid-template-columns:1fr}.summary-grid article+article,.support-summary-grid article+article{border-top:1px solid var(--mc-border);border-left:0}.root-cause-candidates,.judgement-basis{grid-template-columns:1fr}.decision-panel{grid-template-columns:1fr}.lifecycle-actions{justify-content:flex-start}}
@media(max-width:760px){.business-card.is-developer{padding:22px 18px}.cause-hero h2{font-size:clamp(30px,10vw,42px)}.problem-brief-title{grid-template-columns:1fr;gap:5px}.problem-brief-title>strong{white-space:normal}.problem-brief dl,.cause-summary{grid-template-columns:1fr;margin-left:0}.problem-brief dl>div{padding:9px 0}.problem-brief dl>div+div,.problem-brief dl>div:nth-child(3){grid-column:auto;margin:0;padding:9px 0;border-top:1px solid var(--mc-border-light);border-left:0}.problem-brief dd{white-space:normal}.cause-summary section+section{padding-left:2px;border-top:1px solid var(--mc-border-light);border-left:0}.ns-stages{grid-template-columns:1fr}.north-star>summary{align-items:flex-start;flex-direction:column;gap:4px}.lifecycle-actions{align-items:stretch;flex-direction:column}.lifecycle-actions :deep(.el-button){margin-left:0}}
</style>
