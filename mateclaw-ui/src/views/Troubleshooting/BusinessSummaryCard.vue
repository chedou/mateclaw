<template>
  <section v-if="business" class="business-card">
    <div class="verdict-head">
      <div class="verdict-copy">
        <div class="badge-row">
          <span class="conclusion-badge" :class="business.conclusionType.toLowerCase()">
            {{ conclusionLabel(business.conclusionType) }}
          </span>
          <span class="status-badge" :class="statusTone(business.status)">{{ statusLabel(business.status) }}</span>
          <span class="confidence-badge" :class="business.confidence.toLowerCase()">
            可信等级 {{ business.confidence }}
          </span>
        </div>
        <h2>{{ business.headline }}</h2>
        <p>{{ business.narrative }}</p>
      </div>
    </div>

    <div class="summary-grid">
      <article>
        <span class="section-label">问题</span>
        <strong>{{ business.problem }}</strong>
      </article>
      <article>
        <span class="section-label">影响</span>
        <strong>{{ business.impact.functionScope }}</strong>
        <div v-if="impactMetricList.length" class="impact-metrics">
          <span v-for="metric in impactMetricList" :key="metric">{{ metric }}</span>
        </div>
        <small>{{ blastRadiusLabel(business.impact.blastRadius) }} · {{ business.impact.note }}</small>
      </article>
      <article>
        <span class="section-label">{{ business.nextStep.label }}</span>
        <strong>{{ business.nextStep.text }}</strong>
        <small class="capability-boundary">{{ business.nextStep.capabilityBoundary }}</small>
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

    <div class="timing-strip">
      <article>
        <span>补问 / Intake</span>
        <b>{{ timingState(business.timings.readyAt, business.timings.intakeCost, 'recorded') }}</b>
        <small>{{ timeRange(business.timings.reportedAt, business.timings.readyAt) }}</small>
      </article>
      <i />
      <article>
        <span>调查 / Investigate</span>
        <b>{{ timingState(business.timings.conclusionAt, business.timings.investigateCost, 'recorded') }}</b>
        <small>{{ timeRange(business.timings.readyAt, business.timings.conclusionAt) }}</small>
      </article>
      <i />
      <article>
        <span>采纳 / Handoff</span>
        <b>{{ timingState(business.timings.handoffAt, business.timings.adoptCost, 'pending') }}</b>
        <small>{{ timeRange(business.timings.conclusionAt, business.timings.handoffAt, true) }}</small>
      </article>
    </div>

    <div class="lifecycle-bar">
      <el-button
        v-if="canOperate && status === 'READY_FOR_HUMAN'"
        type="primary"
        :loading="actionLoading"
        @click="$emit('confirm')"
      >确认结论</el-button>
      <el-button v-if="canTransfer" :disabled="actionLoading" @click="$emit('transfer')">结构化转派</el-button>
      <el-button v-if="canClose" :disabled="actionLoading" @click="$emit('close')">关闭并沉淀知识</el-button>
      <span v-if="status === 'NEEDS_INVESTIGATION'">当前已弃权：补齐证据后才能重新形成结论。</span>
      <span v-else>按钮只推进领域状态，MateClaw 不执行任何生产变更。</span>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type {
  BlastRadius,
  BusinessSummary,
  ClosureRecord,
  DiagnosisStatus,
} from '@/api'
import {
  closureOutcomeLabel,
  conclusionLabel,
  impactMetrics,
  timingState,
} from './formalProjection'
import {
  diagnosisStatusLabel as statusLabel,
  diagnosisStatusTone as statusTone,
  formatWorkbenchTime as shortTime,
} from './workbenchView'

const BLAST_RADIUS_LABEL: Record<BlastRadius, string> = {
  SINGLE_CUSTOMER: '单客户影响', MULTI_CUSTOMER: '多客户影响', SYSTEM_WIDE: '系统级影响', UNKNOWN: '影响范围未知',
}

interface Props {
  business: BusinessSummary | null
  closure: ClosureRecord | null
  canOperate: boolean
  canTransfer: boolean
  canClose: boolean
  actionLoading: boolean
  status: DiagnosisStatus | null
}

const props = defineProps<Props>()

defineEmits<{
  confirm: []
  transfer: []
  close: []
}>()

const impactMetricList = computed(() => {
  const impact = props.business?.impact
  return impact ? impactMetrics(impact.affectedCustomers, impact.affectedUsers) : []
})

function blastRadiusLabel(value: BlastRadius) { return BLAST_RADIUS_LABEL[value] }

function timeRange(from: string | null, to: string | null, pending = false) {
  if (!from && !to) return pending ? '尚未发生交接' : '阶段时间戳尚未纳入 Diagnosis'
  return `${shortTime(from)} → ${shortTime(to)}`
}
</script>

<style scoped>
.business-card { max-width:1320px; margin:0 auto; border:1px solid var(--mc-border); border-radius:var(--mc-radius-md); background:var(--mc-bg-elevated); box-shadow:var(--mc-shadow-soft); padding:clamp(18px,2.5vw,30px); }
.verdict-head { padding-bottom:22px; }
.badge-row { display:flex; align-items:center; gap:8px; flex-wrap:wrap; } .conclusion-badge,.status-badge,.confidence-badge { padding:4px 9px; border:1px solid var(--mc-border); border-radius:var(--mc-radius-lg); font-size:12px; font-weight:700; }
.conclusion-badge.located { color:var(--mc-status-info-text); border-color:var(--mc-border-light); background:var(--mc-status-info-bg); } .conclusion-badge.excluded { color:var(--mc-text-secondary); background:var(--mc-bg-muted); }
.conclusion-badge.hypothesis { color:var(--mc-status-purple-text); border-color:var(--mc-border); background:var(--mc-status-purple-bg); } .conclusion-badge.insufficient_evidence { color:var(--mc-status-warning-text); border-color:var(--mc-warning); background:var(--mc-status-warning-bg); }
.confidence-badge.high { color:var(--mc-success); background:var(--mc-status-success-bg); } .confidence-badge.medium { color:var(--mc-warning); background:var(--mc-status-warning-bg); } .confidence-badge.low { color:var(--mc-danger); background:var(--mc-status-error-bg); }
.verdict-copy h2 { margin:14px 0 7px; font-size:clamp(21px,2vw,29px); line-height:1.25; letter-spacing:-.035em; } .verdict-copy>p { max-width:820px; margin:0; color:var(--mc-text-secondary); font-size:var(--mc-text-sm); line-height:1.75; }
.section-label { display:block; color:var(--mc-text-tertiary); font-size:12px; font-weight:750; letter-spacing:.1em; text-transform:uppercase; }
.summary-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); overflow:hidden; border:1px solid var(--mc-border); border-radius:var(--mc-radius-sm); }
.summary-grid article { min-height:130px; padding:17px 18px; } .summary-grid article+article { border-left:1px solid var(--mc-border); }
.summary-grid strong { display:block; margin:10px 0 8px; font-size:var(--mc-text-sm); line-height:1.55; } .summary-grid small { display:block; color:var(--mc-text-secondary); font-size:12px; line-height:1.55; }
.impact-metrics { display:flex; gap:7px; margin:8px 0; } .impact-metrics span { padding:2px 7px; border-radius:var(--mc-radius-xs); color:var(--mc-status-info-text); background:var(--mc-status-info-bg); font-size:12px; } .capability-boundary { color:var(--mc-warning)!important; }
.closure-result { display:grid; grid-template-columns:180px minmax(0,1fr) auto; align-items:center; gap:18px; margin-top:14px; padding:14px 16px; border:1px solid var(--mc-success); border-radius:var(--mc-radius-sm); background:var(--mc-status-success-bg); }
.closure-result div b { display:block; margin-top:5px; color:var(--mc-success); font-size:var(--mc-text-sm); } .closure-result>strong { font-size:var(--mc-text-sm); line-height:1.55; } .closure-result>small { color:var(--mc-text-secondary); font-size:12px; text-align:right; }
.timing-strip { display:grid; grid-template-columns:1fr 16px 1fr 16px 1fr; align-items:center; margin-top:14px; padding:13px 16px; border:1px solid var(--mc-border); border-radius:var(--mc-radius-sm); background:var(--mc-bg-elevated); }
.timing-strip article { display:grid; grid-template-columns:1fr auto; gap:3px 12px; } .timing-strip span { color:var(--mc-text-secondary); font-size:12px; } .timing-strip b { color:var(--mc-text-secondary); font-size:var(--mc-text-sm); }
.timing-strip small { grid-column:1/-1; color:var(--mc-text-tertiary); font-size:12px; } .timing-strip i { width:5px; height:5px; justify-self:center; border-radius:50%; background:var(--mc-text-tertiary); }
.lifecycle-bar { display:flex; align-items:center; gap:9px; margin-top:19px; padding-top:17px; border-top:1px solid var(--mc-border); } .lifecycle-bar>span { margin-left:5px; color:var(--mc-text-secondary); font-size:12px; }
.active { color:var(--mc-primary)!important; } .success { color:var(--mc-success)!important; } .warning { color:var(--mc-warning)!important; } .muted { color:var(--mc-text-tertiary)!important; }
@media(max-width:1100px){.summary-grid{grid-template-columns:1fr}.summary-grid article+article{border-top:1px solid var(--mc-border);border-left:0}}
@media(max-width:760px){.timing-strip{grid-template-columns:1fr;gap:12px}.timing-strip i{display:none}}
</style>
