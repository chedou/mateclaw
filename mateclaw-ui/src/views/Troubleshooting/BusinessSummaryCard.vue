<template>
  <section v-if="business" class="business-card">
    <div class="verdict-head">
      <div class="verdict-copy">
        <div class="badge-row">
          <span class="conclusion-badge" :class="business.conclusionType.toLowerCase()">
            {{ conclusionLabel(business.conclusionType) }}
          </span>
          <span class="status-badge" :class="statusTone(business.status)">{{ statusLabel(business.status) }}</span>
          <span
            class="confidence-badge"
            :class="business.confidence.toLowerCase()"
            :title="confidencePresentation.detail"
          >
            {{ confidencePresentation.label }}
          </span>
        </div>
        <span class="verdict-label">一句话结论</span>
        <h2>{{ business.rootCause || business.headline }}</h2>
        <p v-if="!business.rootCause">{{ business.narrative }}</p>
      </div>
    </div>

    <div class="summary-grid">
      <article>
        <span class="section-label">为什么这样判断</span>
        <strong>{{ business.keyEvidence || '当前没有可展示的关键对照数字，请展开下方排障过程复核。' }}</strong>
      </article>
      <article>
        <span class="section-label">影响到什么</span>
        <strong>{{ showImpact ? business.impact.functionScope : '影响范围尚未确认' }}</strong>
        <div v-if="showImpact && impactMetricList.length" class="impact-metrics">
          <span v-for="metric in impactMetricList" :key="metric">{{ metric }}</span>
        </div>
      </article>
      <article>
        <span class="section-label">你现在需要做什么</span>
        <strong>{{ business.nextStep.text }}</strong>
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

    <details class="north-star">
      <summary>
        <span class="ns-title">耗时</span>
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

    <section class="human-review-guide" :class="status?.toLowerCase()">
      <div>
        <span class="section-label">现在由你决定</span>
        <strong>{{ reviewGuidance.title }}</strong>
      </div>
      <p>{{ reviewGuidance.detail }}</p>
      <small v-if="rehearsal">这是演练记录：可以体验确认和关闭流程，但不会计入正式系统负责人验收目标。</small>
    </section>

    <div class="lifecycle-bar">
      <el-button
        v-if="canOperate && status === 'READY_FOR_HUMAN'"
        type="primary"
        :loading="actionLoading"
        @click="$emit('confirm')"
      >复核后确认定位</el-button>
      <el-button v-if="canTransfer" :disabled="actionLoading" @click="$emit('transfer')">转给其他人继续查</el-button>
      <el-button v-if="canClose" :disabled="actionLoading" @click="$emit('close')">登记结果并关闭</el-button>
      <el-button
        v-if="status === 'CLOSED' && canEvaluate"
        type="primary"
        plain
        @click="$emit('evaluate')"
      >把这张单纳入试点评估</el-button>
      <span v-if="status === 'NEEDS_INVESTIGATION'">当前已弃权：补齐证据后才能重新形成结论。</span>
      <span v-else-if="status === 'CLOSED' && !canEvaluate">请有管理权限的负责人把这张单纳入试点评估。</span>
      <span v-else>只记录人的判断和结果，不改生产。</span>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type {
  BusinessSummary,
  ClosureRecord,
  DiagnosisStatus,
} from '@/api'
import {
  closureOutcomeLabel,
  conclusionLabel,
  impactMetrics,
  northStarStages,
} from './formalProjection'
import {
  diagnosisStatusLabel as statusLabel,
  diagnosisStatusTone as statusTone,
  formatWorkbenchTime as shortTime,
} from './workbenchView'
import { diagnosisConfidencePresentation } from './evidencePlainLanguage'

interface Props {
  business: BusinessSummary | null
  closure: ClosureRecord | null
  canOperate: boolean
  canTransfer: boolean
  canClose: boolean
  canEvaluate: boolean
  rehearsal: boolean
  actionLoading: boolean
  status: DiagnosisStatus | null
}

const props = defineProps<Props>()

/** Three separately owned cost segments; never summed into one number (D14). */
const stages = computed(() => northStarStages(props.business?.timings))
const stagesComplete = computed(() => stages.value.every((stage) => stage.share !== null))
const confidencePresentation = computed(() => diagnosisConfidencePresentation(props.business?.confidence))
const reviewGuidance = computed(() => {
  const guidance: Record<DiagnosisStatus, { title: string; detail: string }> = {
    READY_FOR_HUMAN: {
      title: '先判断：你是否认可这个定位？',
      detail: props.canTransfer
        ? '认可就确认；不认可或还需要专业判断，就转给其他人继续查。两种选择都不会修改生产环境。'
        : '认可就确认；不认可或还需要专业判断，就先不要确认，联系有转派权限的负责人继续查。',
    },
    NEEDS_INVESTIGATION: {
      title: '先补证据，不要确认',
      detail: '页面已经说明缺什么。补齐数据源、查询规则或现场信息后，再重新形成可复核结论。',
    },
    CONFIRMED: {
      title: '去平台外完成处置，回来登记结果',
      detail: props.canClose
        ? '修复、放行或回滚由授权人员执行。完成后登记是否恢复、实际原因和对排障方法的反馈。'
        : '修复、放行或回滚由授权人员执行。完成后请有关闭权限的负责人登记恢复情况、实际原因和方法反馈。',
    },
    TRANSFERRED: {
      title: '等待接手人继续处理并回填结果',
      detail: '转派信息和已有证据已经保留，接手人不需要从头查；处理完成后仍要登记真实结果。',
    },
    CLOSED: {
      title: '结果已登记，下一步验证这套方法是否真的有效',
      detail: props.canEvaluate
        ? '把这张单纳入试点评估：保存脱敏证据、人工确认的标准答案和原来人工定位所需时间。'
        : '最终结果已经保存；请有管理权限的负责人把它纳入试点评估和后续知识审核。',
    },
  }
  return props.status
    ? guidance[props.status]
    : { title: '等待排障状态加载', detail: '状态加载完成后，页面会告诉你下一步由谁做什么。' }
})

defineEmits<{
  confirm: []
  transfer: []
  close: []
  evaluate: []
}>()

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

function timeRange(from: string | null, to: string | null, pending = false) {
  if (!from && !to) return pending ? '尚未发生交接' : '阶段时间戳尚未纳入 Diagnosis'
  return `${shortTime(from)} → ${shortTime(to)}`
}
</script>

<style scoped>
.business-card { width:100%; max-width:none; margin:0; border:1px solid var(--mc-border); border-radius:var(--mc-radius-md); background:var(--mc-bg-elevated); box-shadow:var(--mc-shadow-soft); padding:clamp(18px,2.5vw,30px); }
.verdict-head { padding-bottom:22px; }
.verdict-label { display:block; margin-top:18px; color:var(--mc-text-tertiary); font-size:12px; font-weight:750; letter-spacing:.08em; }
.badge-row { display:flex; align-items:center; gap:8px; flex-wrap:wrap; } .conclusion-badge,.status-badge,.confidence-badge { padding:4px 9px; border:1px solid var(--mc-border); border-radius:var(--mc-radius-lg); font-size:12px; font-weight:700; }
.conclusion-badge.located { color:var(--mc-status-info-text); border-color:var(--mc-border-light); background:var(--mc-status-info-bg); } .conclusion-badge.excluded { color:var(--mc-text-secondary); background:var(--mc-bg-muted); }
.conclusion-badge.hypothesis { color:var(--mc-status-purple-text); border-color:var(--mc-border); background:var(--mc-status-purple-bg); } .conclusion-badge.insufficient_evidence { color:var(--mc-status-warning-text); border-color:var(--mc-warning); background:var(--mc-status-warning-bg); }
.confidence-badge.high { color:var(--mc-success); background:var(--mc-status-success-bg); } .confidence-badge.medium { color:var(--mc-warning); background:var(--mc-status-warning-bg); } .confidence-badge.low { color:var(--mc-danger); background:var(--mc-status-error-bg); }
.verdict-copy h2 { margin:14px 0 7px; font-size:clamp(21px,2vw,29px); line-height:1.25; letter-spacing:-.035em; } .verdict-copy>p { max-width:820px; margin:0; color:var(--mc-text-secondary); font-size:var(--mc-text-sm); line-height:1.75; }
.section-label { display:block; color:var(--mc-text-tertiary); font-size:12px; font-weight:750; letter-spacing:.1em; text-transform:uppercase; }
.summary-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); overflow:hidden; border:1px solid var(--mc-border); border-radius:var(--mc-radius-sm); }
.summary-grid article { padding:14px 16px; }
.summary-grid article+article { border-left:1px solid var(--mc-border); }
.summary-grid strong { display:block; margin:8px 0 0; font-size:var(--mc-text-sm); line-height:1.55; white-space:pre-line; }
.impact-metrics { display:flex; gap:7px; margin:8px 0 0; } .impact-metrics span { padding:2px 7px; border-radius:var(--mc-radius-xs); color:var(--mc-status-info-text); background:var(--mc-status-info-bg); font-size:12px; }
.closure-result { display:grid; grid-template-columns:180px minmax(0,1fr) auto; align-items:center; gap:18px; margin-top:14px; padding:14px 16px; border:1px solid var(--mc-success); border-radius:var(--mc-radius-sm); background:var(--mc-status-success-bg); }
.closure-result div b { display:block; margin-top:5px; color:var(--mc-success); font-size:var(--mc-text-sm); } .closure-result>strong { font-size:var(--mc-text-sm); line-height:1.55; } .closure-result>small { color:var(--mc-text-secondary); font-size:12px; text-align:right; }
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
.human-review-guide { display:grid; grid-template-columns:minmax(220px,.75fr) minmax(340px,1.25fr); gap:8px 24px; align-items:start; margin-top:14px; padding:15px 16px; border-top:1px solid var(--mc-border); border-bottom:1px solid var(--mc-border); background:var(--mc-bg-muted); }
.human-review-guide strong { display:block; margin-top:5px; color:var(--mc-text-primary); font-size:14px; line-height:1.5; }
.human-review-guide p { margin:0; color:var(--mc-text-secondary); font-size:12px; line-height:1.65; }
.human-review-guide small { grid-column:2; color:var(--mc-status-purple-text); font-size:11px; line-height:1.5; }
.lifecycle-bar { display:flex; align-items:center; gap:9px; margin-top:19px; padding-top:17px; border-top:1px solid var(--mc-border); } .lifecycle-bar>span { margin-left:5px; color:var(--mc-text-secondary); font-size:12px; }
.active { color:var(--mc-primary)!important; } .success { color:var(--mc-success)!important; } .warning { color:var(--mc-warning)!important; } .muted { color:var(--mc-text-tertiary)!important; }
@media(max-width:1100px){.summary-grid{grid-template-columns:1fr}.summary-grid article+article{border-top:1px solid var(--mc-border);border-left:0}}
@media(max-width:760px){.ns-stages,.human-review-guide{grid-template-columns:1fr}.human-review-guide small{grid-column:1}.north-star>summary{flex-direction:column;gap:4px}.lifecycle-bar{align-items:flex-start;flex-direction:column}.lifecycle-bar>span{margin-left:0}}
</style>
