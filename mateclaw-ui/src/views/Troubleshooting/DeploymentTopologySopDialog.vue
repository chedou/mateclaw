<template>
  <el-dialog
    v-model="visible"
    :title="TROUBLESHOOTING_UI_LABELS.deploymentTopology"
    width="min(920px, calc(100vw - 32px))"
  >
    <el-alert type="warning" :closable="false" class="topology-alert">
      导入的拓扑快照是当前 Workspace 的共享资产；拨测作为只读场景 Tool 执行，仅将安全投影保存到当前 Diagnosis。
      原始响应、DQL 和凭据不落库，未覆盖节点也不会被描述成健康。
    </el-alert>

    <div v-if="diagnosisId" class="diagnosis-binding">
      <span>证据归属</span><code>{{ diagnosisId }}</code>
    </div>
    <el-alert v-else type="error" :closable="false" class="topology-alert">
      请先创建或打开一条 Diagnosis，再运行部署拓扑拨测。
    </el-alert>

    <section class="topology-library">
      <header class="library-head">
        <div>
          <span>Workspace 共享拓扑</span>
          <b>选择已经导入的拓扑</b>
          <small>同一 Workspace 的管理员导入后，其他管理员无需重复上传即可直接使用。</small>
        </div>
        <el-button plain @click="importOpen = !importOpen">
          {{ importOpen ? '收起导入' : '导入新拓扑' }}
        </el-button>
      </header>

      <el-select
        v-model="selectedTopologyId"
        class="topology-selector"
        placeholder="选择一个共享拓扑"
        filterable
        :loading="libraryLoading"
      >
        <el-option
          v-for="topology in topologies"
          :key="topology.topologyId"
          :label="deploymentTopologyOptionLabel(topology)"
          :value="topology.topologyId"
        />
      </el-select>

      <div v-if="!libraryLoading && !topologies.length" class="library-empty">
        <div><b>当前 Workspace 还没有共享拓扑</b><span>先参考下方案例准备 JSON，再由管理员导入。</span></div>
        <el-button type="primary" plain @click="importOpen = true">导入第一个拓扑</el-button>
      </div>
    </section>

    <p v-if="loadError" class="topology-error">{{ loadError }}</p>

    <section v-if="selectedTopology" class="snapshot-preview">
      <div>
        <span>{{ selectedTopology.name }}</span>
        <b>{{ selectedTopology.systemLabel }}</b>
        <code>{{ selectedTopology.system }} · schema {{ selectedTopology.schemaVersion }}</code>
        <small>{{ selectedTopology.importedBy }} 导入于 {{ shortTime(selectedTopology.importedAt) }}</small>
      </div>
      <dl>
        <div><dt>节点</dt><dd>{{ selectedTopology.nodeCount }}</dd></div>
        <div><dt>链路</dt><dd>{{ selectedTopology.linkCount }}</dd></div>
        <div><dt>可执行拨测</dt><dd>{{ selectedTopology.configuredProbeNodes }}</dd></div>
        <div><dt>未覆盖节点</dt><dd>{{ selectedTopology.nodeCount - selectedTopology.configuredProbeNodes }}</dd></div>
      </dl>
    </section>

    <section v-if="importOpen" class="import-panel">
      <header>
        <div><span>导入到共享图库</span><b>拓扑一经导入保持不可变</b></div>
        <small>同名拓扑不会被覆盖；内容变更时请使用新名称导入。</small>
      </header>
      <el-input
        v-model="importName"
        maxlength="128"
        show-word-limit
        placeholder="给团队一个容易识别的名称，例如：马来西亚生产拓扑"
      />
      <label class="snapshot-picker" :class="{ loaded: importPreview }">
        <input
          ref="fileInput"
          type="file"
          accept=".json,application/json"
          @change="selectSnapshot"
        >
        <span>{{ importFileName || '选择部署图运行时快照 JSON' }}</span>
        <small>最大 512 KiB · 最多 100 节点 / 300 链路 / 32 个拨测</small>
      </label>
      <div v-if="importPreview" class="import-preview-line">
        <b>{{ importPreview.systemLabel }}</b>
        <span>{{ importPreview.nodeCount }} 节点 · {{ importPreview.linkCount }} 链路 · {{ importPreview.configuredProbeNodes }} 拨测</span>
      </div>
      <el-button
        type="primary"
        :loading="importing"
        :disabled="!importSnapshot || !importPreview || !importName.trim()"
        @click="importTopology"
      >导入并选中</el-button>
    </section>

    <section class="example-guide">
      <header>
        <div><span>第一次导入案例</span><b>照着这个例子准备拓扑 JSON</b></div>
        <el-button text :loading="exampleLoading" @click="downloadExample">下载示例 JSON</el-button>
      </header>
      <ol>
        <li><b>系统信息</b><span>填写 schemaVersion、kind、exportedAt 和 system。</span></li>
        <li><b>网络元点</b><span>在 topology.nodes 中填写 key、label、type；需要拨测的节点同时填写 url 与 guance_url。</span></li>
        <li><b>网络关系</b><span>在 topology.links 中用 source / target 连接已声明的节点。</span></li>
      </ol>
      <details v-if="exampleJson">
        <summary>查看案例内容</summary>
        <pre>{{ exampleJson }}</pre>
      </details>
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
        :disabled="!diagnosisId || !selectedTopology || !selectedTopology.configuredProbeNodes"
        @click="run"
      >运行并写入 Diagnosis 证据</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  troubleshootingApi,
  type DeploymentTopologyAssetSummary,
  type DeploymentTopologySopResult,
  type TopologyProbeEvidenceRun,
} from '@/api'
import {
  deploymentAnalysisLabel,
  deploymentAnalysisTone,
  deploymentTopologyOptionLabel,
  inspectDeploymentTopologySnapshot,
  MAX_DEPLOYMENT_SNAPSHOT_BYTES,
  observationStatusLabel,
  observationTone,
  type DeploymentTopologySnapshotPreview,
} from './deploymentTopologySop'
import { TROUBLESHOOTING_UI_LABELS } from './workbenchView'

const props = defineProps<{ modelValue: boolean; diagnosisId?: string }>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  completed: [run: TopologyProbeEvidenceRun]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})
const fileInput = ref<HTMLInputElement | null>(null)
const topologies = ref<DeploymentTopologyAssetSummary[]>([])
const selectedTopologyId = ref('')
const selectedTopology = computed(() => topologies.value.find(
  topology => topology.topologyId === selectedTopologyId.value,
) || null)
const libraryLoading = ref(false)
const importOpen = ref(false)
const importName = ref('')
const importSnapshot = ref<Record<string, unknown> | null>(null)
const importPreview = ref<DeploymentTopologySnapshotPreview | null>(null)
const importFileName = ref('')
const importing = ref(false)
const example = ref<Record<string, unknown> | null>(null)
const exampleLoading = ref(false)
const exampleJson = computed(() => example.value ? JSON.stringify(example.value, null, 2) : '')
const loadError = ref('')
const running = ref(false)
const result = ref<DeploymentTopologySopResult | null>(null)
let runVersion = 0
let sessionVersion = 0

watch(visible, (open) => {
  runVersion += 1
  const version = ++sessionVersion
  running.value = false
  importing.value = false
  loadError.value = ''
  result.value = null
  if (!open) return
  resetImport()
  void loadTopologyLibrary(version)
  void loadExample(version)
})

watch(selectedTopologyId, () => {
  runVersion += 1
  running.value = false
  result.value = null
})

async function loadTopologyLibrary(version = sessionVersion, preferredId = '') {
  libraryLoading.value = true
  try {
    const response = await troubleshootingApi.listDeploymentTopologies()
    if (version !== sessionVersion || !visible.value) return
    topologies.value = response.data
    const nextId = preferredId || selectedTopologyId.value
    selectedTopologyId.value = response.data.some(item => item.topologyId === nextId)
      ? nextId
      : response.data[0]?.topologyId || ''
  } catch (error) {
    if (version !== sessionVersion || !visible.value) return
    topologies.value = []
    selectedTopologyId.value = ''
    ElMessage.error(`共享拓扑加载失败：${error instanceof Error ? error.message : String(error)}`)
  } finally {
    if (version === sessionVersion) libraryLoading.value = false
  }
}

async function loadExample(version = sessionVersion) {
  if (example.value) return
  exampleLoading.value = true
  try {
    const response = await troubleshootingApi.deploymentTopologyExample()
    if (version !== sessionVersion || !visible.value) return
    example.value = response.data
  } catch (error) {
    if (version !== sessionVersion || !visible.value) return
    ElMessage.error(`导入案例加载失败：${error instanceof Error ? error.message : String(error)}`)
  } finally {
    if (version === sessionVersion) exampleLoading.value = false
  }
}

function resetImport() {
  importName.value = ''
  importSnapshot.value = null
  importPreview.value = null
  importFileName.value = ''
  if (fileInput.value) fileInput.value.value = ''
}

async function selectSnapshot(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  importSnapshot.value = null
  importPreview.value = null
  importFileName.value = ''
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
    importSnapshot.value = parsed as Record<string, unknown>
    importPreview.value = preview
    importFileName.value = file.name
    if (!importName.value.trim()) {
      importName.value = file.name.replace(/\.json$/i, '').slice(0, 128)
    }
    if (!preview.configuredProbeNodes) {
      loadError.value = '快照中没有同时配置 url 与 guance_url 的节点，当前没有可执行拨测。'
    }
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : String(error)
    input.value = ''
  }
}

async function importTopology() {
  if (!importSnapshot.value || !importPreview.value || !importName.value.trim()) return
  const version = sessionVersion
  importing.value = true
  loadError.value = ''
  try {
    const response = await troubleshootingApi.importDeploymentTopology({
      name: importName.value.trim(),
      snapshot: importSnapshot.value,
    })
    if (version !== sessionVersion || !visible.value) return
    await loadTopologyLibrary(version, response.data.topology.topologyId)
    if (version !== sessionVersion || !visible.value) return
    importOpen.value = false
    resetImport()
    ElMessage.success(response.data.created ? '拓扑已导入共享图库' : '该拓扑已存在，已为你选中')
  } catch (error) {
    if (version !== sessionVersion || !visible.value) return
    loadError.value = error instanceof Error ? error.message : String(error)
  } finally {
    if (version === sessionVersion) importing.value = false
  }
}

async function run() {
  if (!props.diagnosisId || !selectedTopology.value?.configuredProbeNodes) return
  const version = ++runVersion
  running.value = true
  result.value = null
  try {
    const response = await troubleshootingApi.runDiagnosisTopologyProbe(
      props.diagnosisId,
      selectedTopology.value.topologyId,
    )
    if (version !== runVersion || !visible.value) return
    result.value = response.data.result
    emit('completed', response.data)
    if (response.data.result.status === 'NETWORK_PROBLEM_DETECTED') {
      ElMessage.warning('部署图拨测发现失败节点，请结合相邻链路继续核查')
    } else if (response.data.result.status === 'NO_PROBLEM_OBSERVED') {
      ElMessage.success('全部已配置拨测均返回可达状态')
    } else {
      ElMessage.info('拨测已完成，但覆盖或证据不足，未宣称整张网络健康')
    }
  } catch (error) {
    if (version !== runVersion || !visible.value) return
    ElMessage.error(`${TROUBLESHOOTING_UI_LABELS.deploymentTopology}未完成：${error instanceof Error ? error.message : String(error)}`)
  } finally {
    if (version === runVersion) running.value = false
  }
}

async function downloadExample() {
  if (!example.value) await loadExample()
  if (!example.value) return
  const blob = new Blob([JSON.stringify(example.value, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = 'deployment-topology-example.json'
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 0)
}

function shortTime(value: string) {
  return value.replace('T', ' ').replace(/\.\d+Z?$/, '').slice(0, 19)
}
</script>

<style scoped>
.topology-alert { margin-bottom: 16px; line-height: 1.6; }
.diagnosis-binding { display: flex; align-items: center; gap: 10px; margin: -4px 0 14px; padding: 9px 12px; border-radius: 8px; background: #eff6ff; color: #1d4ed8; font-size: 12px; }
.diagnosis-binding code { font-weight: 700; }
.topology-library { padding: 16px; border: 1px solid var(--el-border-color-lighter); border-radius: 12px; background: #f8fafc; }
.library-head,
.import-panel>header,
.example-guide>header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.library-head>div,
.import-panel>header>div,
.example-guide>header>div { display: grid; gap: 4px; }
.library-head span,
.import-panel header span,
.example-guide header span { color: var(--el-color-primary); font-size: 10px; font-weight: 800; letter-spacing: .08em; text-transform: uppercase; }
.library-head small,
.import-panel header small { color: var(--el-text-color-secondary); line-height: 1.5; }
.topology-selector { width: 100%; margin-top: 14px; }
.library-empty { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 14px; padding: 12px; border-radius: 8px; background: #fff; }
.library-empty>div { display: grid; gap: 4px; }
.library-empty span { color: var(--el-text-color-secondary); font-size: 12px; }
.import-panel { display: grid; gap: 12px; margin-top: 14px; padding: 16px; border: 1px solid #cbd5e1; border-radius: 12px; }
.import-preview-line { display: flex; justify-content: space-between; gap: 12px; color: var(--el-text-color-secondary); font-size: 12px; }
.import-panel>.el-button { justify-self: end; }
.example-guide { margin-top: 14px; padding: 16px; border: 1px solid #dbe4ff; border-radius: 12px; background: #f6f8ff; }
.example-guide ol { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; margin: 14px 0 0; padding: 0; list-style: none; counter-reset: guide; }
.example-guide li { display: grid; gap: 4px; padding: 10px; border-radius: 8px; background: #fff; counter-increment: guide; }
.example-guide li b::before { content: '0' counter(guide) ' '; color: var(--el-color-primary); }
.example-guide li span { color: var(--el-text-color-secondary); font-size: 11px; line-height: 1.5; }
.example-guide details { margin-top: 12px; }
.example-guide summary { cursor: pointer; color: var(--el-color-primary); font-size: 12px; font-weight: 700; }
.example-guide pre { max-height: 260px; margin: 10px 0 0; padding: 12px; overflow: auto; border-radius: 8px; color: #dce7ff; background: #172033; font-size: 10px; line-height: 1.6; }
.snapshot-picker { display: grid; gap: 5px; padding: 18px; border: 1px dashed var(--el-border-color); border-radius: 10px; background: var(--el-fill-color-lighter); cursor: pointer; transition: border-color .2s, background .2s; }
.snapshot-picker:hover,
.snapshot-picker.loaded { border-color: var(--el-color-primary); background: var(--el-color-primary-light-9); }
.snapshot-picker input { position: absolute; width: 1px; height: 1px; opacity: 0; pointer-events: none; }
.snapshot-picker span { color: var(--el-text-color-primary); font-weight: 700; }
.snapshot-picker small { color: var(--el-text-color-secondary); }
.topology-error { margin: 10px 0 0; color: var(--el-color-danger); font-size: 12px; }
.snapshot-preview { display: grid; grid-template-columns: minmax(180px, 1fr) 2fr; gap: 18px; margin-top: 16px; padding: 15px; border: 1px solid var(--el-border-color-lighter); border-radius: 10px; }
.snapshot-preview>div { display: grid; gap: 5px; }
.snapshot-preview>div small { color: var(--el-text-color-secondary); font-size: 11px; }
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
  .library-head,
  .import-panel>header,
  .example-guide>header,
  .library-empty,
  .import-preview-line { align-items: stretch; flex-direction: column; }
  .example-guide ol { grid-template-columns: 1fr; }
  .snapshot-preview { grid-template-columns: 1fr; }
  .snapshot-preview dl { grid-template-columns: repeat(2, 1fr); }
  .result-metrics { grid-template-columns: repeat(2, 1fr); }
}
</style>
