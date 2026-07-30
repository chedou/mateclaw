<template>
  <el-dialog
    v-model="visible"
    title="部署图拨测 SOP"
    width="min(900px, calc(100vw - 32px))"
  >
    <el-alert type="warning" :closable="false" class="topology-alert">
      入口会解析部署图中每个节点的拨测元数据，并通过服务端已授权的 Guance Adapter 批量只读查询。
      上传链接不能控制 API 主机或 DQL；本次不调用模型、不保存原始响应，也不把未覆盖节点描述成健康。
    </el-alert>

    <label class="snapshot-picker" :class="{ loaded: snapshotPreview }">
      <input
        ref="fileInput"
        type="file"
        accept=".json,application/json"
        @change="selectSnapshot"
      >
      <span>{{ snapshotFileName || '选择部署图运行时快照 JSON' }}</span>
      <small>最大 512 KiB · 最多 32 个拨测 · chain-board.runtime-topology-snapshot</small>
    </label>

    <p v-if="loadError" class="topology-error">{{ loadError }}</p>

    <section v-if="snapshotPreview" class="snapshot-preview">
      <div>
        <span>已识别系统</span>
        <b>{{ snapshotPreview.systemLabel }}</b>
        <code>{{ snapshotPreview.system }} · schema {{ snapshotPreview.schemaVersion }}</code>
      </div>
      <dl>
        <div><dt>节点</dt><dd>{{ snapshotPreview.nodeCount }}</dd></div>
        <div><dt>链路</dt><dd>{{ snapshotPreview.linkCount }}</dd></div>
        <div><dt>可执行拨测</dt><dd>{{ snapshotPreview.configuredProbeNodes }}</dd></div>
        <div><dt>未覆盖节点</dt><dd>{{ snapshotPreview.unconfiguredNodeCount }}</dd></div>
      </dl>
    </section>

    <section v-if="result" class="topology-result">
      <header>
        <div>
          <span>真实只读分析结果</span>
          <h3>{{ deploymentAnalysisLabel(result.status) }}</h3>
          <small>{{ result.systemLabel }} · {{ shortTime(result.completedAt) }}</small>
        </div>
        <strong :class="deploymentAnalysisTone(result.status)">{{ result.status }}</strong>
      </header>

      <div class="result-metrics">
        <article><span>拨测覆盖</span><b>{{ result.summary.configuredProbeNodes }}/{{ result.summary.nodeCount }}</b></article>
        <article><span>匹配证据</span><b>{{ result.summary.observedProbeNodes }}</b></article>
        <article class="healthy"><span>可达</span><b>{{ result.summary.healthyProbeNodes }}</b></article>
        <article class="failed"><span>失败</span><b>{{ result.summary.failingProbeNodes }}</b></article>
        <article><span>不可用</span><b>{{ result.summary.unavailableProbeNodes }}</b></article>
      </div>

      <div v-if="result.observations.length" class="observation-list">
        <article v-for="observation in result.observations" :key="observation.nodeKey">
          <div class="observation-head">
            <div><b>{{ observation.label }}</b><code>{{ observation.nodeKey }}</code></div>
            <strong :class="observationTone(observation.status)">
              {{ observationStatusLabel(observation.status) }}
            </strong>
          </div>
          <p>{{ observation.probeName }}</p>
          <code>{{ observation.targetUrl }}</code>
          <div class="observation-meta">
            <span>窗口 {{ observation.window }}</span>
            <span>HTTP {{ observation.statusCode ?? '—' }}</span>
            <span>{{ observation.evidenceRef }}</span>
          </div>
          <small>{{ observation.detail }}</small>
        </article>
      </div>

      <section v-if="result.suspectLinks.length" class="suspect-links">
        <b>拓扑相邻疑似链路</b>
        <span v-for="link in result.suspectLinks" :key="`${link.source}-${link.target}`">
          <code>{{ link.source }}</code> → <code>{{ link.target }}</code>
        </span>
      </section>

      <details v-if="result.unconfiguredNodeKeys.length" class="unconfigured-nodes">
        <summary>{{ result.unconfiguredNodeKeys.length }} 个节点没有拨测元数据</summary>
        <code>{{ result.unconfiguredNodeKeys.join(' · ') }}</code>
      </details>

      <ul class="result-warnings">
        <li v-for="warning in result.warnings" :key="warning">{{ warning }}</li>
      </ul>
    </section>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button
        type="primary"
        :loading="running"
        :disabled="!snapshot || !snapshotPreview?.configuredProbeNodes"
        @click="run"
      >运行只读拨测 SOP</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  troubleshootingApi,
  type DeploymentTopologySopResult,
} from '@/api'
import {
  deploymentAnalysisLabel,
  deploymentAnalysisTone,
  inspectDeploymentTopologySnapshot,
  MAX_DEPLOYMENT_SNAPSHOT_BYTES,
  observationStatusLabel,
  observationTone,
  type DeploymentTopologySnapshotPreview,
} from './deploymentTopologySop'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})
const fileInput = ref<HTMLInputElement | null>(null)
const snapshot = ref<Record<string, unknown> | null>(null)
const snapshotPreview = ref<DeploymentTopologySnapshotPreview | null>(null)
const snapshotFileName = ref('')
const loadError = ref('')
const running = ref(false)
const result = ref<DeploymentTopologySopResult | null>(null)
let runVersion = 0

watch(visible, (open) => {
  runVersion += 1
  running.value = false
  if (!open) return
  snapshot.value = null
  snapshotPreview.value = null
  snapshotFileName.value = ''
  loadError.value = ''
  result.value = null
  if (fileInput.value) fileInput.value.value = ''
})

async function selectSnapshot(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  snapshot.value = null
  snapshotPreview.value = null
  snapshotFileName.value = ''
  loadError.value = ''
  result.value = null
  if (!file) return
  if (file.size > MAX_DEPLOYMENT_SNAPSHOT_BYTES) {
    loadError.value = '部署图快照不能超过 512 KiB。'
    input.value = ''
    return
  }
  try {
    const parsed: unknown = JSON.parse(await file.text())
    const preview = inspectDeploymentTopologySnapshot(parsed)
    snapshot.value = parsed as Record<string, unknown>
    snapshotPreview.value = preview
    snapshotFileName.value = file.name
    if (!preview.configuredProbeNodes) {
      loadError.value = '快照中没有同时配置 url 与 guance_url 的节点，当前没有可执行拨测。'
    }
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : String(error)
    input.value = ''
  }
}

async function run() {
  if (!snapshot.value || !snapshotPreview.value?.configuredProbeNodes) return
  const version = ++runVersion
  running.value = true
  result.value = null
  try {
    const response = await troubleshootingApi.analyzeDeploymentTopology(snapshot.value)
    if (version !== runVersion || !visible.value) return
    result.value = response.data
    if (response.data.status === 'NETWORK_PROBLEM_DETECTED') {
      ElMessage.warning('部署图拨测发现失败节点，请结合相邻链路继续核查')
    } else if (response.data.status === 'NO_PROBLEM_OBSERVED') {
      ElMessage.success('全部已配置拨测均返回可达状态')
    } else {
      ElMessage.info('拨测已完成，但覆盖或证据不足，未宣称整张网络健康')
    }
  } catch (error) {
    if (version !== runVersion || !visible.value) return
    ElMessage.error(`部署图拨测 SOP 未完成：${error instanceof Error ? error.message : String(error)}`)
  } finally {
    if (version === runVersion) running.value = false
  }
}

function shortTime(value: string) {
  return value.replace('T', ' ').replace(/\.\d+Z?$/, '').slice(0, 19)
}
</script>

<style scoped>
.topology-alert { margin-bottom: 16px; line-height: 1.6; }
.snapshot-picker { display: grid; gap: 5px; padding: 18px; border: 1px dashed var(--el-border-color); border-radius: 10px; background: var(--el-fill-color-lighter); cursor: pointer; transition: border-color .2s, background .2s; }
.snapshot-picker:hover,
.snapshot-picker.loaded { border-color: var(--el-color-primary); background: var(--el-color-primary-light-9); }
.snapshot-picker input { position: absolute; width: 1px; height: 1px; opacity: 0; pointer-events: none; }
.snapshot-picker span { color: var(--el-text-color-primary); font-weight: 700; }
.snapshot-picker small { color: var(--el-text-color-secondary); }
.topology-error { margin: 10px 0 0; color: var(--el-color-danger); font-size: 12px; }
.snapshot-preview { display: grid; grid-template-columns: minmax(180px, 1fr) 2fr; gap: 18px; margin-top: 16px; padding: 15px; border: 1px solid var(--el-border-color-lighter); border-radius: 10px; }
.snapshot-preview>div { display: grid; gap: 5px; }
.snapshot-preview span,
.topology-result header span { color: var(--el-text-color-secondary); font-size: 11px; text-transform: uppercase; letter-spacing: .08em; }
.snapshot-preview code { color: var(--el-color-primary); font-size: 11px; }
.snapshot-preview dl { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin: 0; }
.snapshot-preview dl div { padding: 10px; border-radius: 8px; background: var(--el-fill-color-light); }
.snapshot-preview dt { color: var(--el-text-color-secondary); font-size: 11px; }
.snapshot-preview dd { margin: 3px 0 0; font-size: 20px; font-weight: 800; }
.topology-result { margin-top: 18px; padding-top: 18px; border-top: 1px solid var(--el-border-color-lighter); }
.topology-result>header { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; }
.topology-result h3 { margin: 4px 0; font-size: 18px; }
.topology-result header strong,
.observation-head>strong { padding: 4px 8px; border-radius: 999px; font-size: 10px; }
.success { color: #067647; background: #ecfdf3; }
.danger { color: #b42318; background: #fef3f2; }
.warning { color: #b54708; background: #fffaeb; }
.neutral { color: #344054; background: #f2f4f7; }
.result-metrics { display: grid; grid-template-columns: repeat(5, 1fr); gap: 8px; margin: 14px 0; }
.result-metrics article { padding: 11px; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; }
.result-metrics span { display: block; color: var(--el-text-color-secondary); font-size: 10px; }
.result-metrics b { display: block; margin-top: 3px; font-size: 18px; }
.result-metrics .healthy b { color: #067647; }
.result-metrics .failed b { color: #b42318; }
.observation-list { display: grid; gap: 9px; }
.observation-list>article { padding: 13px; border: 1px solid var(--el-border-color-lighter); border-radius: 9px; background: var(--el-bg-color); }
.observation-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.observation-head>div { display: flex; align-items: baseline; gap: 8px; }
.observation-list p { margin: 9px 0 4px; font-weight: 700; }
.observation-list>article>code { color: var(--el-color-primary); overflow-wrap: anywhere; }
.observation-meta { display: flex; flex-wrap: wrap; gap: 7px; margin: 9px 0 5px; }
.observation-meta span { padding: 3px 6px; border-radius: 5px; background: var(--el-fill-color-light); color: var(--el-text-color-secondary); font-size: 10px; }
.observation-list small { color: var(--el-text-color-secondary); }
.suspect-links { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 12px; padding: 11px; border-radius: 8px; background: #fef3f2; color: #912018; }
.suspect-links span { padding-left: 8px; border-left: 1px solid #fecdca; }
.unconfigured-nodes { margin-top: 12px; color: var(--el-text-color-secondary); font-size: 11px; }
.unconfigured-nodes code { display: block; margin-top: 8px; line-height: 1.7; overflow-wrap: anywhere; }
.result-warnings { margin: 12px 0 0; padding-left: 18px; color: var(--el-text-color-secondary); font-size: 11px; line-height: 1.6; }
@media (max-width: 720px) {
  .snapshot-preview { grid-template-columns: 1fr; }
  .snapshot-preview dl { grid-template-columns: repeat(2, 1fr); }
  .result-metrics { grid-template-columns: repeat(2, 1fr); }
}
</style>
