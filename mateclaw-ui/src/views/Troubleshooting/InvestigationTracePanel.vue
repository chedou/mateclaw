<template>
  <section class="trace-panel">
    <div class="trace-toolbar">
      <div>
        <span>运行记录</span>
        <h3>这次排障是怎么一步步推进的</h3>
        <p>从收到问题到给出结论或明确停止共 7 步；只展示系统实际记录的事实，缺失就明确显示“未记录”。</p>
      </div>
      <div class="trace-actions">
        <span>本次排障用时 <b>{{ traceDuration(trace.investigationDuration) }}</b></span>
        <div class="trace-tabs" role="tablist">
          <button :class="{ active: activeView === 'trace' }" @click="activeView = 'trace'">执行过程</button>
          <button :class="{ active: activeView === 'relation' }" @click="activeView = 'relation'">证据关系</button>
        </div>
      </div>
    </div>

    <div v-if="activeView === 'trace'" class="trace-workspace">
      <nav class="stage-rail" aria-label="排障执行的七个步骤">
        <button
          v-for="stage in trace.stages"
          :key="stage.key"
          :class="[
            `is-${stage.status.toLowerCase()}`,
            { active: selectedStage?.key === stage.key },
          ]"
          @click="selectedStageKey = stage.key"
        >
          <i>{{ stage.sequence }}</i>
          <span><b>{{ investigationStagePresentation(stage.key).title }}</b><small>{{ stage.summary }}</small></span>
          <em>{{ investigationStageStatusLabel(stage.status) }}</em>
        </button>
      </nav>

      <article v-if="selectedStage" class="stage-inspector">
        <header>
          <div>
            <span>第 {{ selectedStage.sequence }} 步，共 7 步</span>
            <h4>{{ investigationStagePresentation(selectedStage.key).title }}</h4>
            <p>{{ investigationStagePresentation(selectedStage.key).description }}</p>
            <small class="stage-technical">
              技术标识 · {{ selectedStage.title }} · {{ selectedStage.key }}
            </small>
          </div>
          <strong :class="`is-${selectedStage.status.toLowerCase()}`">
            {{ investigationStageStatusLabel(selectedStage.status) }}
          </strong>
        </header>

        <section class="stage-run-summary">
          <span>本次实际记录</span>
          <p>{{ selectedStage.summary }}</p>
        </section>

        <div class="stage-timing">
          <div><span>开始</span><b>{{ traceTime(selectedStage.startedAt) }}</b></div>
          <div><span>完成</span><b>{{ traceTime(selectedStage.completedAt) }}</b></div>
          <div><span>耗时</span><b>{{ traceDuration(selectedStage.duration) }}</b></div>
        </div>

        <dl class="stage-fields">
          <div v-for="field in selectedStage.fields" :key="field.label">
            <dt>{{ field.label }}</dt><dd>{{ traceDisplay(field.value) }}</dd>
          </div>
          <div v-if="!selectedStage.fields.length"><dt>其他记录</dt><dd>未记录</dd></div>
        </dl>

        <section v-if="selectedStage.key === 'EVIDENCE_CONTRACT'" class="detail-block">
          <div class="detail-block-head"><b>本次固定要查的数据（证据合同）</b><span>{{ trace.evidenceContracts.length }} 份</span></div>
          <article v-for="contract in trace.evidenceContracts" :key="contract.requestId" class="contract-card">
            <header><code>{{ contract.requestId }}</code><span>{{ contract.required ? '必需' : '可选' }}</span></header>
            <b>{{ contract.purpose }}</b>
            <dl>
              <div><dt>信号类型</dt><dd>{{ contract.signalKind }}</dd></div>
              <div><dt>时间窗口</dt><dd>{{ traceDisplay(contract.window) }}</dd></div>
            </dl>
            <label>目标参数（冻结值）</label><pre>{{ pretty(contract.target) }}</pre>
          </article>
          <p v-if="!trace.evidenceContracts.length" class="unrecorded-block">未记录</p>
        </section>

        <section
          v-if="selectedStage.key === 'ADAPTER_SELECTION' || selectedStage.key === 'EVIDENCE_COLLECTION'"
          class="detail-block"
        >
          <div class="detail-block-head"><b>查询工具和只读结果</b><span>{{ trace.adapterAttempts.length }} 份最终结果</span></div>
          <article v-for="attempt in trace.adapterAttempts" :key="attempt.evidenceRef" class="attempt-card">
            <header>
              <div><code>{{ attempt.evidenceRef }}</code><b>{{ attempt.adapterSource }}</b></div>
              <span :class="`is-${attempt.status.toLowerCase()}`">{{ evidenceStatusLabel(attempt.status) }}</span>
            </header>
            <p>{{ attempt.summary }}</p>
            <dl>
              <div><dt>合同请求</dt><dd>{{ traceDisplay(attempt.requestId) }}</dd></div>
              <div><dt>信号类型</dt><dd>{{ traceDisplay(attempt.signalKind) }}</dd></div>
              <div><dt>采集时间</dt><dd>{{ traceTime(attempt.collectedAt) }}</dd></div>
              <div><dt>本次耗时</dt><dd>{{ traceDuration(attempt.duration) }}</dd></div>
              <div><dt>尝试历史</dt><dd>仅最终结果已记录</dd></div>
            </dl>
            <details>
              <summary>查看安全技术结果</summary>
              <label>查询原文（不进入该投影）</label><pre>{{ traceDisplay(attempt.query) }}</pre>
              <label>规范化 observed（安全字段）</label><pre>{{ pretty(attempt.observed) }}</pre>
            </details>
          </article>
          <p v-if="!trace.adapterAttempts.length" class="unrecorded-block">未记录</p>
        </section>

        <section v-if="selectedStage.key === 'CONCLUSION'" class="stop-reason">
          <span>停止原因</span>
          <b>{{ stopReasonLabel(trace.stopReason.code) }}</b>
          <p>{{ trace.stopReason.message }}</p>
          <small>停止时间 · {{ traceTime(trace.stopReason.stoppedAt) }}</small>
        </section>

        <div class="stage-refs">
          <span>证据引用</span>
          <div v-if="selectedStage.evidenceRefs.length">
            <code v-for="refValue in selectedStage.evidenceRefs" :key="refValue">{{ refValue }}</code>
          </div>
          <b v-else>未记录</b>
        </div>
      </article>
    </div>

    <EvidenceRelationGraph v-else :view="trace.evidenceRelation" />
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type {
  EvidenceStatus,
  InvestigationStageKey,
  InvestigationStopReasonCode,
  InvestigationTraceView,
} from '@/api'
import EvidenceRelationGraph from './EvidenceRelationGraph.vue'
import { formatDuration } from './formalProjection'
import {
  defaultInvestigationStage,
  investigationStagePresentation,
  investigationStageStatusLabel,
  traceDisplay,
} from './investigationTrace'
import { formatWorkbenchTime } from './workbenchView'

const props = defineProps<{ trace: InvestigationTraceView }>()
const activeView = ref<'trace' | 'relation'>('trace')
const selectedStageKey = ref<InvestigationStageKey | null>(
  defaultInvestigationStage(props.trace.stages)?.key ?? null,
)

watch(
  () => props.trace.diagnosisId,
  () => {
    selectedStageKey.value = defaultInvestigationStage(props.trace.stages)?.key ?? null
    activeView.value = 'trace'
  },
)

const selectedStage = computed(() => props.trace.stages.find(
  stage => stage.key === selectedStageKey.value,
) ?? props.trace.stages[0])

function traceTime(value: string | null) {
  return value ? formatWorkbenchTime(value) : '未记录'
}

function traceDuration(value: string | null) {
  return value ? formatDuration(value) : '未记录'
}

function pretty(value: Record<string, unknown>) {
  return Object.keys(value).length ? JSON.stringify(value, null, 2) : '未记录'
}

function evidenceStatusLabel(value: EvidenceStatus) {
  return { NORMAL: '正常', ANOMALY: '异常', MISSING: '缺失' }[value]
}

function stopReasonLabel(value: InvestigationStopReasonCode) {
  return {
    CONCLUSION_RECORDED: '已形成可复核结论',
    EVIDENCE_MISSING: '必需证据缺失',
    SOURCE_UNAVAILABLE: '只读证据源不可用',
    ABSTAINED: '系统弃权',
    UNRECORDED: '未记录',
  }[value]
}
</script>

<style scoped>
.trace-panel{display:flex;min-width:0;flex-direction:column;gap:18px;padding:18px;border:1px solid var(--mc-border);border-radius:var(--mc-radius-sm);background:var(--mc-bg-elevated)}
.trace-toolbar{display:flex;align-items:flex-start;justify-content:space-between;gap:20px}
.trace-toolbar>div:first-child>span{color:var(--mc-text-secondary);font-size:var(--mc-text-xs);font-weight:700;letter-spacing:.1em;text-transform:uppercase}
.trace-toolbar h3{margin:5px 0 0;font-size:var(--mc-text-base)}
.trace-toolbar p{margin:6px 0 0;color:var(--mc-text-secondary);font-size:var(--mc-text-xs);line-height:1.55}
.trace-actions{display:flex;flex:none;flex-direction:column;align-items:flex-end;gap:7px}.trace-actions>span{color:var(--mc-text-tertiary);font-size:var(--mc-text-xs)}.trace-actions>span b{color:var(--mc-text-primary);font-family:var(--mc-mono,monospace)}
.trace-tabs{display:flex;flex:none;padding:3px;border:1px solid var(--mc-border);border-radius:var(--mc-radius-sm);background:var(--mc-bg-muted)}
.trace-tabs button{padding:6px 12px;border:0;border-radius:var(--mc-radius-xs);background:transparent;color:var(--mc-text-secondary);font-size:var(--mc-text-xs);cursor:pointer}
.trace-tabs button.active{color:var(--mc-text-inverse)!important;background:var(--mc-primary);font-weight:700}
.trace-workspace{display:grid;grid-template-columns:minmax(230px,.58fr) minmax(0,1.5fr);gap:16px;min-width:0}
.stage-rail{display:flex;flex-direction:column;gap:5px}
.stage-rail button{display:grid;grid-template-columns:26px minmax(0,1fr) auto;align-items:start;gap:9px;width:100%;padding:10px;border:1px solid transparent;border-radius:var(--mc-radius-sm);background:transparent;color:var(--mc-text-primary);text-align:left;cursor:pointer;transition:background .15s,border-color .15s}
.stage-rail button:hover{background:var(--mc-bg-muted)}
.stage-rail button.active{border-color:var(--mc-primary);background:var(--mc-primary-soft,var(--mc-bg-muted))}
.stage-rail i{display:inline-grid;place-items:center;width:24px;height:24px;border-radius:50%;color:var(--mc-text-inverse);background:var(--mc-primary);font-size:var(--mc-text-xs);font-style:normal}
.stage-rail button.is-unrecorded i{color:var(--mc-text-secondary);background:var(--mc-bg-muted);box-shadow:inset 0 0 0 1px var(--mc-border)}
.stage-rail button.is-partial i{background:var(--mc-warning)}
.stage-rail button.is-stopped i{background:var(--mc-danger)}
.stage-rail span b,.stage-rail span small{display:block}
.stage-rail span b{font-size:var(--mc-text-xs);line-height:1.4}
.stage-rail span small{display:-webkit-box;margin-top:3px;overflow:hidden;color:var(--mc-text-tertiary);font-size:10px;line-height:1.35;-webkit-box-orient:vertical;-webkit-line-clamp:2}
.stage-rail em{padding:2px 5px;border-radius:999px;color:var(--mc-text-secondary);background:var(--mc-bg-muted);font-size:10px;font-style:normal;white-space:nowrap}
.stage-inspector{min-width:0;padding:16px;border:1px solid var(--mc-border);border-radius:var(--mc-radius-sm);background:var(--mc-bg-muted)}
.stage-inspector>header{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}
.stage-inspector>header span{color:var(--mc-text-tertiary);font-size:var(--mc-text-xs)}
.stage-inspector>header h4{margin:5px 0 0;font-size:var(--mc-text-base)}
.stage-inspector>header p{margin:5px 0 0;color:var(--mc-text-secondary);font-size:var(--mc-text-xs);line-height:1.5}
.stage-technical{display:block;margin-top:7px;color:var(--mc-text-tertiary);font-family:var(--mc-mono,monospace);font-size:10px;overflow-wrap:anywhere}
.stage-inspector>header strong{padding:4px 8px;border-radius:999px;color:var(--mc-status-success-text);background:var(--mc-status-success-bg);font-size:var(--mc-text-xs);white-space:nowrap}
.stage-inspector>header strong.is-partial,.stage-inspector>header strong.is-unrecorded{color:var(--mc-status-warning-text);background:var(--mc-status-warning-bg)}
.stage-inspector>header strong.is-stopped{color:var(--mc-status-error-text);background:var(--mc-status-error-bg)}
.stage-run-summary{margin-top:14px;padding:10px 12px;border-left:3px solid var(--mc-primary);border-radius:var(--mc-radius-xs);background:var(--mc-bg-elevated)}
.stage-run-summary span{color:var(--mc-text-tertiary);font-size:10px;font-weight:700;letter-spacing:.06em}
.stage-run-summary p{margin:5px 0 0;color:var(--mc-text-primary);font-size:var(--mc-text-xs);line-height:1.5}
.stage-timing{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px;margin-top:14px}
.stage-timing>div{padding:9px 10px;border:1px solid var(--mc-border-light);border-radius:var(--mc-radius-xs);background:var(--mc-bg-elevated)}
.stage-timing span,.stage-timing b{display:block;font-size:var(--mc-text-xs)}
.stage-timing span{color:var(--mc-text-tertiary)}
.stage-timing b{margin-top:4px;font-family:var(--mc-mono,monospace);font-weight:500}
.stage-fields{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0;margin:14px 0 0;border:1px solid var(--mc-border);border-radius:var(--mc-radius-xs);background:var(--mc-bg-elevated);overflow:hidden}
.stage-fields>div{display:grid;grid-template-columns:110px minmax(0,1fr);gap:10px;padding:8px 10px;border-bottom:1px solid var(--mc-border-light);font-size:var(--mc-text-xs)}
.stage-fields>div:nth-last-child(-n+2){border-bottom:0}
.stage-fields dt{color:var(--mc-text-tertiary)}
.stage-fields dd{margin:0;overflow-wrap:anywhere;white-space:pre-wrap}
.detail-block{margin-top:14px;padding:12px;border:1px solid var(--mc-border);border-radius:var(--mc-radius-xs);background:var(--mc-bg-elevated)}
.detail-block-head{display:flex;align-items:center;justify-content:space-between;font-size:var(--mc-text-xs)}
.detail-block-head span{color:var(--mc-text-secondary)}
.contract-card,.attempt-card{margin-top:10px;padding:11px;border:1px solid var(--mc-border-light);border-radius:var(--mc-radius-xs)}
.contract-card>header,.attempt-card>header{display:flex;align-items:flex-start;justify-content:space-between;gap:10px}
.contract-card>header span,.attempt-card>header>span{padding:2px 6px;border-radius:999px;color:var(--mc-primary);background:var(--mc-primary-soft,var(--mc-bg-muted));font-size:10px}
.contract-card>b{display:block;margin-top:8px;font-size:var(--mc-text-xs)}
.contract-card dl,.attempt-card dl{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:6px 12px;margin:9px 0;font-size:var(--mc-text-xs)}
.contract-card dl>div,.attempt-card dl>div{display:flex;gap:7px}
.contract-card dt,.attempt-card dt{color:var(--mc-text-tertiary)}
.contract-card dd,.attempt-card dd{margin:0;overflow-wrap:anywhere}
.contract-card label,.attempt-card label{display:block;margin:8px 0 5px;color:var(--mc-text-tertiary);font-size:10px}
.contract-card pre,.attempt-card pre{max-height:260px;margin:0;padding:9px;border-radius:var(--mc-radius-xs);background:var(--mc-bg-muted);font-size:11px;line-height:1.5;overflow:auto;white-space:pre-wrap;word-break:break-all}
.attempt-card>header>div code,.attempt-card>header>div b{display:block}
.attempt-card>header>div b{margin-top:4px;font-size:var(--mc-text-xs)}
.attempt-card>header>span.is-anomaly{color:var(--mc-status-error-text);background:var(--mc-status-error-bg)}
.attempt-card>header>span.is-missing{color:var(--mc-status-warning-text);background:var(--mc-status-warning-bg)}
.attempt-card>p{margin:9px 0;color:var(--mc-text-secondary);font-size:var(--mc-text-xs)}
.attempt-card details{margin-top:8px;border-top:1px solid var(--mc-border-light)}
.attempt-card summary{padding-top:9px;color:var(--mc-primary);font-size:var(--mc-text-xs);cursor:pointer}
.unrecorded-block{margin:10px 0 0;padding:12px;border:1px dashed var(--mc-border);border-radius:var(--mc-radius-xs);color:var(--mc-text-tertiary);font-size:var(--mc-text-xs);text-align:center}
.stop-reason{margin-top:14px;padding:11px;border-left:3px solid var(--mc-primary);border-radius:var(--mc-radius-xs);background:var(--mc-bg-elevated)}
.stop-reason span,.stop-reason b,.stop-reason small{display:block;font-size:var(--mc-text-xs)}
.stop-reason span,.stop-reason small{color:var(--mc-text-tertiary)}
.stop-reason b{margin-top:5px}.stop-reason p{margin:5px 0;color:var(--mc-text-secondary);font-size:var(--mc-text-xs);line-height:1.5}
.stage-refs{display:flex;align-items:flex-start;gap:10px;margin-top:14px;padding-top:12px;border-top:1px solid var(--mc-border);font-size:var(--mc-text-xs)}
.stage-refs>span{color:var(--mc-text-tertiary);white-space:nowrap}.stage-refs>div{display:flex;flex-wrap:wrap;gap:5px}.stage-refs code{padding:2px 6px;border-radius:var(--mc-radius-xs);color:var(--mc-primary);background:var(--mc-bg-elevated)}
@media(max-width:1050px){.trace-workspace{grid-template-columns:1fr}.stage-rail{display:grid;grid-template-columns:repeat(2,minmax(0,1fr))}}
@media(max-width:700px){.trace-toolbar{flex-direction:column}.trace-actions{align-items:flex-start}.stage-rail{grid-template-columns:1fr}.stage-timing,.stage-fields,.contract-card dl,.attempt-card dl{grid-template-columns:1fr}.stage-fields>div{grid-template-columns:100px minmax(0,1fr);border-bottom:1px solid var(--mc-border-light)!important}}
</style>
