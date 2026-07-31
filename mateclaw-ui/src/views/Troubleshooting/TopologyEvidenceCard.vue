<template>
  <section class="topology-evidence-card">
    <div class="topology-evidence-head">
      <div>
        <span class="section-label">场景证据 · deployment_topology_probe</span>
        <h3>部署拓扑拨测</h3>
        <p>选择 Workspace 已导入拓扑，经 <code>topology_synthetic_probe</code> 只读取证，结果归属当前 Diagnosis。</p>
      </div>
      <el-button
        v-if="canManage"
        type="primary"
        plain
        :disabled="disabled"
        @click="$emit('runProbe')"
      >选择拓扑并运行</el-button>
    </div>
    <div v-if="latestRun" class="topology-evidence-result">
      <div>
        <span>最新运行</span>
        <b>{{ deploymentAnalysisLabel(latestRun.result.status) }}</b>
        <small>{{ shortTime(latestRun.completedAt) }} · {{ latestRun.actorRef }}</small>
      </div>
      <dl>
        <div><dt>已配置</dt><dd>{{ latestRun.result.summary.configuredProbeNodes }}</dd></div>
        <div><dt>已观测</dt><dd>{{ latestRun.result.summary.observedProbeNodes }}</dd></div>
        <div class="failed"><dt>失败</dt><dd>{{ latestRun.result.summary.failingProbeNodes }}</dd></div>
        <div><dt>不可用</dt><dd>{{ latestRun.result.summary.unavailableProbeNodes }}</dd></div>
      </dl>
      <div v-if="latestRun.result.suspectLinks.length" class="topology-link-hints">
        <span>需核查相邻链路</span>
        <code v-for="link in latestRun.result.suspectLinks" :key="`${link.source}-${link.target}`">
          {{ link.source }} → {{ link.target }}
        </code>
      </div>
      <div v-if="latestRun.result.observations.length" class="topology-observations">
        <span>节点观测</span>
        <div v-for="observation in latestRun.result.observations" :key="observation.nodeKey">
          <b>{{ observation.label }}</b>
          <code>{{ observation.nodeKey }}</code>
          <em>{{ observationStatusLabel(observation.status) }}</em>
          <small v-if="observation.statusCode">HTTP {{ observation.statusCode }}</small>
        </div>
      </div>
      <small class="topology-history-count">已保留 {{ runs.length }} 次安全证据运行；异常节点和相邻链路是核查提示，不等于根因。</small>
      <details class="topology-run-history">
        <summary>查看运行历史（{{ runs.length }}）</summary>
        <ol>
          <li v-for="run in runs" :key="run.runId">
            <header>
              <div>
                <b>{{ deploymentAnalysisLabel(run.result.status) }}</b>
                <small>{{ shortTime(run.completedAt) }} · {{ run.actorRef }}</small>
              </div>
              <code>{{ run.topologyId }}</code>
            </header>
            <p>
              已观测 {{ run.result.summary.observedProbeNodes }} / 已配置 {{ run.result.summary.configuredProbeNodes }}；
              失败 {{ run.result.summary.failingProbeNodes }}；不可用 {{ run.result.summary.unavailableProbeNodes }}
            </p>
            <p v-if="run.result.suspectLinks.length" class="history-links">
              核查链路：{{ run.result.suspectLinks.map(link => `${link.source} → ${link.target}`).join('、') }}
            </p>
            <p v-if="run.result.warnings.length" class="history-warning">
              {{ run.result.warnings.join('；') }}
            </p>
            <ul v-if="run.result.observations.length" class="history-observations">
              <li v-for="observation in run.result.observations" :key="observation.nodeKey">
                <b>{{ observation.label }}</b>
                <code>{{ observation.nodeKey }}</code>
                <span>{{ observationStatusLabel(observation.status) }}</span>
                <small v-if="observation.statusCode">HTTP {{ observation.statusCode }}</small>
                <small>{{ observation.evidenceRef }}</small>
              </li>
            </ul>
          </li>
        </ol>
      </details>
    </div>
    <div v-else class="empty-evidence">当前 Diagnosis 还没有部署拓扑拨测证据。</div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { TopologyProbeEvidenceRun } from '@/api'
import {
  deploymentAnalysisLabel,
  observationStatusLabel,
} from './deploymentTopologySop'
import {
  formatWorkbenchTime as shortTime,
} from './workbenchView'

interface Props {
  runs: TopologyProbeEvidenceRun[]
  diagnosisId: string | null
  canManage: boolean
  disabled: boolean
}

const props = defineProps<Props>()

defineEmits<{
  runProbe: []
}>()

const latestRun = computed(() => props.runs[0] ?? null)
</script>

<style scoped>
.topology-evidence-card { max-width:1320px; margin:14px auto 0; padding:18px 20px; border:1px solid var(--mc-success); border-radius:var(--mc-radius-md); background:var(--mc-status-success-bg); box-shadow:var(--mc-shadow-soft); }
.topology-evidence-head { display:flex; align-items:flex-start; justify-content:space-between; gap:18px; }
.topology-evidence-head h3 { margin:5px 0; font-size:16px; }
.topology-evidence-head p { margin:0; color:var(--mc-text-secondary); font-size:var(--mc-text-xs); line-height:1.6; }
.topology-evidence-head code { color:var(--mc-status-success-text); }
.section-label { display:block; color:var(--mc-text-tertiary); font-size:9px; font-weight:750; letter-spacing:.1em; text-transform:uppercase; }
.topology-evidence-result { display:grid; grid-template-columns:minmax(190px,.8fr) minmax(340px,1.4fr); gap:14px 22px; align-items:center; margin-top:14px; padding-top:14px; border-top:1px solid var(--mc-border-light); }
.topology-evidence-result>div:first-child span,.topology-evidence-result>div:first-child b,.topology-evidence-result>div:first-child small { display:block; }
.topology-evidence-result>div:first-child span { color:var(--mc-text-secondary); font-size:9px; }
.topology-evidence-result>div:first-child b { margin-top:4px; font-size:var(--mc-text-sm); }
.topology-evidence-result>div:first-child small { margin-top:4px; color:var(--mc-text-secondary); font-size:9px; }
.topology-evidence-result dl { display:grid; grid-template-columns:repeat(4,1fr); gap:8px; margin:0; }
.topology-evidence-result dl>div { padding:9px; border:1px solid var(--mc-border-light); border-radius:var(--mc-radius-xs); background:var(--mc-bg-elevated); }
.topology-evidence-result dt { color:var(--mc-text-secondary); font-size:9px; }
.topology-evidence-result dd { margin:3px 0 0; color:var(--mc-status-success-text); font-size:var(--mc-text-base); font-weight:800; }
.topology-evidence-result .failed dd { color:var(--mc-danger); }
.topology-link-hints { grid-column:1/-1; display:flex; flex-wrap:wrap; align-items:center; gap:7px; color:var(--mc-status-error-text); font-size:9px; }
.topology-link-hints code { padding:3px 6px; border-radius:var(--mc-radius-xs); background:var(--mc-status-error-bg); }
.topology-observations { grid-column:1/-1; display:flex; flex-wrap:wrap; gap:7px; align-items:center; color:var(--mc-text-secondary); font-size:9px; }
.topology-observations>div { display:flex; align-items:center; gap:6px; padding:6px 8px; border:1px solid var(--mc-border-light); border-radius:var(--mc-radius-sm); background:var(--mc-bg-elevated); }
.topology-observations b { color:var(--mc-text-primary); }.topology-observations code { color:var(--mc-text-secondary); }.topology-observations em { color:var(--mc-status-success-text); font-style:normal; font-weight:700; }.topology-observations small { color:var(--mc-text-secondary); }
.topology-history-count { grid-column:1/-1; color:var(--mc-text-secondary); font-size:9px; }
.topology-run-history { grid-column:1/-1; overflow:hidden; border:1px solid var(--mc-border-light); border-radius:var(--mc-radius-sm); background:var(--mc-bg-elevated); }
.topology-run-history summary { padding:9px 11px; color:var(--mc-status-success-text); cursor:pointer; font-size:10px; font-weight:750; }
.topology-run-history ol { display:grid; gap:8px; margin:0; padding:0 10px 10px; list-style:none; }
.topology-run-history li { padding:10px; border:1px solid var(--mc-border-light); border-radius:var(--mc-radius-xs); background:var(--mc-bg-elevated); }
.topology-run-history header { display:flex; justify-content:space-between; gap:12px; align-items:flex-start; }
.topology-run-history header b,.topology-run-history header small { display:block; }.topology-run-history header small { margin-top:3px; color:var(--mc-text-secondary); font-size:9px; }.topology-run-history header code { color:var(--mc-text-secondary); font-size:9px; }
.topology-run-history p { margin:7px 0 0; color:var(--mc-text-secondary); font-size:9px; line-height:1.55; }.topology-run-history .history-links { color:var(--mc-status-error-text); }.topology-run-history .history-warning { color:var(--mc-status-warning-text); }
.topology-run-history .history-observations { display:grid; gap:5px; margin:8px 0 0; padding:0; list-style:none; }
.topology-run-history .history-observations li { display:flex; flex-wrap:wrap; align-items:center; gap:6px; padding:6px 7px; border:0; border-radius:6px; background:var(--mc-status-success-bg); color:var(--mc-text-secondary); font-size:9px; }
.topology-run-history .history-observations b { color:var(--mc-text-primary); }.topology-run-history .history-observations code { color:var(--mc-text-secondary); }.topology-run-history .history-observations span { color:var(--mc-status-success-text); font-weight:700; }.topology-run-history .history-observations small { color:var(--mc-text-secondary); }
.empty-evidence { margin:14px 0 0; padding:11px 12px; border:1px dashed var(--mc-border); border-radius:var(--mc-radius-sm); color:var(--mc-text-secondary); background:var(--mc-bg-elevated); font-size:var(--mc-text-xs); line-height:1.65; }
@media(max-width:760px){.topology-evidence-head{align-items:flex-start;flex-direction:column}.topology-evidence-result{grid-template-columns:1fr}.topology-evidence-result dl{grid-template-columns:repeat(2,1fr)}}
</style>
