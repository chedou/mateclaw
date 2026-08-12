<template>
  <CapabilityWorkspaceShell
    eyebrow="复盘与沉淀"
    :title="TROUBLESHOOTING_UI_LABELS.evaluation"
    description="用真实故障和历史样本核对取证是否稳定、结论是否靠谱。"
    :refresh-loading="loading"
    @back="$emit('back')"
    @refresh="loadLedger"
  >
    <div class="evaluation-ledger-workspace">
      <el-alert type="warning" :closable="false" show-icon class="ledger-alert">
        这里只积累脱敏样本与人工参考解。样本够多不等于验收通过，也不会自动关闭演示标记。
      </el-alert>

      <div class="status-bar">
        <div class="status-metrics">
          <span>累计 {{ ledger?.summary.total ?? 0 }}</span>
          <span>可评估 {{ ledger?.summary.readyForEvaluation ?? 0 }}</span>
          <span>待参考解 {{ ledger?.summary.evidenceCaptured ?? 0 }}</span>
          <div class="progress-inline">
            <b>{{ progress.label }}</b>
            <el-progress :percentage="progress.percent" :stroke-width="6" :show-text="false" />
          </div>
        </div>
        <div class="status-actions">
          <el-switch
            v-model="onlyCurrent"
            size="small"
            :disabled="!currentDiagnosisId"
            active-text="仅当前 Diagnosis"
            @change="loadLedger"
          />
          <el-button type="primary" plain @click="openReplayDrawer">回放一条历史样本</el-button>
          <el-button plain @click="openCaptureDrawer">采集样本</el-button>
          <el-button plain @click="openMetricsDrawer">查看指标</el-button>
        </div>
      </div>

      <div v-loading="loading" class="ledger-body">
        <el-table
          v-if="ledger?.samples.length"
          :data="ledger.samples"
          row-key="sampleId"
          height="100%"
          :row-class-name="sampleRowClassName"
          @row-click="selectSample"
        >
          <el-table-column label="来源" width="120">
            <template #default="{ row }">
              <el-tag
                size="small"
                effect="plain"
                :type="row.sourcePlatform === 'GUANCE' ? 'success' : 'info'"
              >{{ evaluationSourceLabel(row.sourcePlatform) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="系统 / 服务" min-width="160">
            <template #default="{ row }">
              <div class="cell-stack">
                <strong>{{ row.system }} / {{ row.service }}</strong>
                <small>Diagnosis {{ row.diagnosisId }}</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="场景键" min-width="140">
            <template #default="{ row }">
              <code class="scenario-key">{{ row.scenarioKey }}</code>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="130">
            <template #default="{ row }">
              <el-tag
                size="small"
                effect="plain"
                :type="row.referenceStatus === 'READY_FOR_EVALUATION' ? 'primary' : 'warning'"
              >{{ evaluationReferenceStatusLabel(row.referenceStatus) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="取证摘要" min-width="220">
            <template #default="{ row }">
              <span class="muted">
                {{ stageLabel(row.evidence.stage) }} ·
                取得 {{ row.evidence.traceEntries }} 条关联日志
              </span>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="168">
            <template #default="{ row }">
              <span class="mono muted">{{ shortTime(row.capturedAt) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else-if="!loading" description="台账还没有采集样本。点「采集样本」写入评估台账；「回放」只验证取证链，不会写入这里。" :image-size="64" />
      </div>

      <el-drawer
        :model-value="drawerOpen"
        :size="'var(--mc-ts-drawer-width)'"
        destroy-on-close
        class="evaluation-detail-drawer"
        :title="drawerTitle"
        @update:model-value="onDrawerOpenChange"
      >
        <div v-if="drawerPanel === 'replay'" class="drawer-panel">
          <el-button text type="primary" class="back-link" @click="backToDetailOrClose">← 返回</el-button>
          <p class="panel-lead">
            只验证取证链路；不会写入评估台账。要进列表请改用「采集样本」。
          </p>
          <SynthesisPreviewDialog embedded />
        </div>

        <div v-else-if="drawerPanel === 'capture'" class="drawer-panel">
          <el-button text type="primary" class="back-link" @click="backToDetailOrClose">← 返回</el-button>
          <p class="panel-lead">
            每次都重新走服务端只读取证；输入没变会复用已有样本，变了会新增版本，不会覆盖旧参考解。
          </p>
          <div v-if="displayCaptureContext" class="scope-chip">
            {{ displayCaptureContext.system }} / {{ displayCaptureContext.service }}
          </div>

          <section v-if="captureContext" class="capture-block">
            <h3>观测云样本</h3>
            <div class="capture-form">
              <label>
                <span>场景键</span>
                <el-input v-model="captureForm.scenarioKey" placeholder="message_send_failed" />
              </label>
              <label>
                <span>搜索键</span>
                <el-input :model-value="captureContext.searchTerm" disabled />
              </label>
              <label>
                <span>时间窗口</span>
                <el-input :model-value="captureContext.window" disabled />
              </label>
            </div>
            <el-button
              type="primary"
              plain
              :loading="captureLoading"
              :disabled="!captureEnabled || !captureFormValid"
              @click="captureSample('GUANCE')"
            >采集观测云样本</el-button>
            <small v-if="!captureEnabled" class="disabled-reason">{{ captureDisabledReason }}</small>
            <small v-else-if="!captureFormValid" class="disabled-reason">场景键须为 2–64 位小写结构化 key。</small>
          </section>

          <section v-if="replayCaptureContext" class="capture-block">
            <h3>回放对照</h3>
            <div class="capture-form">
              <label>
                <span>场景键</span>
                <el-input :model-value="replayCaptureContext.scenarioKey" disabled />
              </label>
              <label>
                <span>搜索键</span>
                <el-input :model-value="replayCaptureContext.searchTerm" disabled />
              </label>
              <label>
                <span>时间窗口</span>
                <el-input :model-value="replayCaptureContext.window" disabled />
              </label>
            </div>
            <el-button
              type="info"
              plain
              :loading="replayCaptureLoading"
              :disabled="!replayCaptureEnabled || !replayCaptureFormValid"
              @click="captureSample('RECORDED_REPLAY')"
            >采集回放对照</el-button>
            <small v-if="!replayCaptureEnabled" class="disabled-reason">{{ replayCaptureDisabledReason }}</small>
            <small v-else-if="!replayCaptureFormValid" class="disabled-reason">服务端回放目标不是合法场景键。</small>
          </section>

          <el-empty
            v-if="!captureContext && !replayCaptureContext"
            description="先打开一条 Diagnosis，并完成可用的取证预览后再采集"
            :image-size="56"
          />
        </div>

        <div v-else-if="drawerPanel === 'metrics'" class="drawer-panel">
          <el-button text type="primary" class="back-link" @click="backToDetailOrClose">← 返回</el-button>
          <p class="panel-lead">
            时延与基线只是描述性统计，不代表验收通过。观测云与回放样本分开算，不混在一起。
          </p>

          <section v-if="ledger" class="metrics-block">
            <div class="section-title">
              <span>分来源应用侧时延</span>
              <el-tag size="small" type="info" effect="plain">{{ ledger.summary.timingMeasuredSamples }} 条完整计时</el-tag>
            </div>
            <div class="metric-list">
              <article v-for="card in latencyCards" :key="card.key">
                <header><b>{{ card.source }}</b><small>{{ card.sampleCount }} 条可测</small></header>
                <dl>
                  <div><dt>证据源往返</dt><dd>{{ card.evidence }}</dd></div>
                  <div><dt>确定性压缩</dt><dd>{{ card.compression }}</dd></div>
                  <div><dt>端到端预览</dt><dd>{{ card.total }}</dd></div>
                </dl>
              </article>
            </div>
          </section>

          <section v-if="baselineLedger" class="metrics-block">
            <div class="section-title">
              <span>单模型基线摘要</span>
              <el-tag size="small" type="info" effect="plain">{{ baselineLedger.summary.total }} 次运行</el-tag>
            </div>
            <div class="metric-list">
              <article v-for="card in baselineCards" :key="card.key">
                <header><b>{{ card.source }} · {{ card.cohort }}</b><small>{{ card.runCount }} 次</small></header>
                <p>{{ card.evidenceMode }} · {{ card.classifications }}</p>
                <dl>
                  <div><dt>模型调用</dt><dd>{{ card.modelLatency }}</dd></div>
                  <div><dt>证据 + 模型</dt><dd>{{ card.composedLatency }}</dd></div>
                  <div><dt>Token</dt><dd>{{ card.tokens }}</dd></div>
                  <div><dt>系统置信度</dt><dd>{{ card.systemConfidence }}</dd></div>
                </dl>
              </article>
            </div>
          </section>
        </div>

        <div v-else-if="drawerPanel === 'reference' && referenceSample" class="drawer-panel">
          <el-button text type="primary" class="back-link" @click="drawerPanel = 'detail'">← 回到样本详情</el-button>
          <p class="panel-lead">
            每行一个步骤键。结果、恢复验证和关闭时间由服务端读取已关闭的 Diagnosis，浏览器不能改。
          </p>
          <div class="reference-form">
            <label class="disposition-field">
              <span>期望模型行为</span>
              <el-select v-model="referenceForm.expectedDisposition" placeholder="选择期望行为">
                <el-option label="生成结构化排障草案" value="DRAFT" />
                <el-option label="证据不足时安全拒答" value="ABSTAIN" />
              </el-select>
            </label>
            <label>
              <span>必须步骤（按顺序）</span>
              <el-input
                v-model="referenceForm.required"
                type="textarea"
                :rows="5"
                placeholder="locate_failed_request&#10;trace_ps_id&#10;verify_recovery"
              />
            </label>
            <label>
              <span>禁止步骤</span>
              <el-input
                v-model="referenceForm.forbidden"
                type="textarea"
                :rows="5"
                placeholder="restart_production"
              />
            </label>
          </div>
          <p v-if="referenceError" class="reference-error">{{ referenceError }}</p>
          <div class="drawer-actions">
            <el-button
              type="primary"
              :loading="referenceLoading"
              :disabled="Boolean(referenceError)"
              @click="finalizeReference"
            >冻结参考解</el-button>
          </div>
        </div>

        <div v-else-if="selectedSample" class="drawer-panel">
          <template v-for="sample in [selectedSample]" :key="sample.sampleId">
          <header class="detail-head">
            <div>
              <span class="eyebrow">采集 r{{ sample.captureRevision }} · v{{ sample.version }}</span>
              <h2>{{ sample.scenarioKey }}</h2>
              <p>{{ sample.system }} / {{ sample.service }}</p>
            </div>
            <el-tag
              size="small"
              effect="plain"
              :type="sample.referenceStatus === 'READY_FOR_EVALUATION' ? 'primary' : 'warning'"
            >{{ evaluationReferenceStatusLabel(sample.referenceStatus) }}</el-tag>
          </header>

          <dl class="meta-grid">
            <div><dt>来源</dt><dd>{{ evaluationSourceLabel(sample.sourcePlatform) }}</dd></div>
            <div><dt>Diagnosis</dt><dd class="mono">{{ sample.diagnosisId }}</dd></div>
            <div><dt>取证阶段</dt><dd>{{ stageLabel(sample.evidence.stage) }}</dd></div>
            <div><dt>关联日志</dt><dd>{{ sample.evidence.traceEntries }} 条关联日志</dd></div>
            <div v-if="sample.expectedDisposition">
              <dt>期望行为</dt>
              <dd>{{ evaluationExpectedDispositionLabel(sample.expectedDisposition) }}</dd>
            </div>
            <div><dt>采集时间</dt><dd class="mono">{{ shortTime(sample.capturedAt) }}</dd></div>
          </dl>

          <div v-if="sampleContrastNarrative(sample)" class="contrast-box">
            <strong>{{ sampleContrastNarrative(sample)?.summary }}</strong>
            <small>{{ sampleContrastNarrative(sample)?.interpretation }}</small>
          </div>

          <p v-if="sample.outcome" class="outcome-line">
            权威结果 {{ sample.outcome.outcome }} · {{ sample.outcome.summary }}
          </p>

          <template v-if="selectedBaselineRun">
            <div class="section-title"><span>基线结果</span></div>
            <div class="baseline-result">
              <el-tag
                size="small"
                :type="baselineClassificationTagType(selectedBaselineRun.quality.classification)"
              >{{ baselineClassificationLabel(selectedBaselineRun.quality.classification) }}</el-tag>
              <small>{{ baselineStatusLabel(selectedBaselineRun.status) }}</small>
              <small>
                {{ selectedBaselineRun.model.provider }} / {{ selectedBaselineRun.model.modelName }} ·
                {{ selectedBaselineRun.composedTotalDurationMs }} ms
              </small>
            </div>
          </template>

          <div class="drawer-actions">
            <el-button
              v-if="sample.diagnosisId !== currentDiagnosisId"
              @click="openDiagnosis(sample.diagnosisId)"
            >打开 Diagnosis</el-button>
            <el-button
              v-else-if="sample.referenceStatus === 'EVIDENCE_CAPTURED'"
              type="primary"
              plain
              :disabled="currentDiagnosisStatus !== 'CLOSED'"
              @click="openReference(sample)"
            >{{ currentDiagnosisStatus === 'CLOSED' ? '填写参考解' : '关闭后填写参考解' }}</el-button>
            <el-button
              v-if="sample.referenceStatus === 'READY_FOR_EVALUATION' && baselineRunnable(sample)"
              type="primary"
              plain
              :loading="baselineRunningSampleId === sample.sampleId"
              @click="runBaseline(sample)"
            >{{ selectedBaselineRun ? '运行 / 读取当前模型版本' : '运行基线核对' }}</el-button>
            <span
              v-else-if="sample.referenceStatus === 'READY_FOR_EVALUATION'"
              class="immutable-mark"
            >{{ baselineUnavailableReason(sample) }}</span>
          </div>
          <p v-if="sample.referenceStatus === 'READY_FOR_EVALUATION'" class="immutable-mark">
            参考解已冻结 · v{{ sample.version }}
          </p>
          </template>
        </div>

        <div v-else class="drawer-empty">
          <strong>选择一条样本查看详情</strong>
          <p>也可先采集样本，或查看时延与基线摘要。</p>
        </div>
      </el-drawer>
    </div>
  </CapabilityWorkspaceShell>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus/es/components/message/index'
import { vLoading } from 'element-plus/es/components/loading/index'
import CapabilityWorkspaceShell from './CapabilityWorkspaceShell.vue'
import SynthesisPreviewDialog from './SynthesisPreviewDialog.vue'
import {
  troubleshootingApi,
  type BaselineClassification,
  type BaselineEvaluationLedger,
  type BaselineEvaluationRun,
  type DiagnosisStatus,
  type EvidenceEvaluationSample,
  type EvidenceEvaluationSampleLedger,
  type EvaluationExpectedDisposition,
} from '@/api'
import {
  type EvaluationSampleCaptureContext,
  baselineClassificationLabel,
  baselineStatusLabel,
  evaluationBaselineCards,
  evaluationExpectedDispositionLabel,
  evaluationLatencyCards,
  evaluationReferenceStatusLabel,
  evaluationSampleProgress,
  evaluationSourceCaptureContext,
  evaluationSourceLabel,
  parseEvaluationIntentKeys,
} from './evaluationSamples'
import { TROUBLESHOOTING_UI_LABELS } from './workbenchView'
import { evidenceComparisonNarrative } from './evidencePlainLanguage'

type DrawerPanel = 'detail' | 'reference' | 'capture' | 'metrics' | 'replay'

const props = withDefaults(defineProps<{
  currentDiagnosisId?: string | null
  currentDiagnosisStatus?: DiagnosisStatus | null
  captureContext?: EvaluationSampleCaptureContext | null
  replayCaptureContext?: EvaluationSampleCaptureContext | null
  captureEnabled?: boolean
  captureDisabledReason?: string
  replayCaptureEnabled?: boolean
  replayCaptureDisabledReason?: string
}>(), {
  currentDiagnosisId: null,
  currentDiagnosisStatus: null,
  captureContext: null,
  replayCaptureContext: null,
  captureEnabled: false,
  captureDisabledReason: '先完成一次可用的取证预览，再采集历史样本。',
  replayCaptureEnabled: false,
  replayCaptureDisabledReason: '当前 Diagnosis 不在可管理的回放范围。',
})

const emit = defineEmits<{
  back: []
  'open-diagnosis': [diagnosisId: string]
  captured: [sample: EvidenceEvaluationSample]
}>()

const ledger = ref<EvidenceEvaluationSampleLedger | null>(null)
const baselineLedger = ref<BaselineEvaluationLedger | null>(null)
const loading = ref(false)
const captureLoading = ref(false)
const replayCaptureLoading = ref(false)
const referenceLoading = ref(false)
const baselineRunningSampleId = ref<string | null>(null)
const onlyCurrent = ref(false)
const selectedSampleId = ref<string | null>(null)
const drawerPanel = ref<DrawerPanel>('detail')
const referenceSample = ref<EvidenceEvaluationSample | null>(null)
const captureForm = reactive({ scenarioKey: '' })
const referenceForm = reactive<{
  required: string
  forbidden: string
  expectedDisposition: EvaluationExpectedDisposition
}>({ required: '', forbidden: '', expectedDisposition: 'DRAFT' })

const progress = computed(() => ledger.value
  ? evaluationSampleProgress(ledger.value.summary)
  : { label: '0 / 20 条可评估样本', percent: 0, note: '' })
const latencyCards = computed(() => ledger.value
  ? evaluationLatencyCards(ledger.value.summary)
  : [])
const baselineCards = computed(() => baselineLedger.value
  ? evaluationBaselineCards(baselineLedger.value.summary)
  : [])
const captureFormValid = computed(() => {
  const parsed = parseEvaluationIntentKeys(captureForm.scenarioKey)
  return parsed.invalid.length === 0
    && parsed.values.length === 1
    && parsed.values[0] === captureForm.scenarioKey.trim()
})
const replayCaptureFormValid = computed(() => {
  const scenarioKey = props.replayCaptureContext?.scenarioKey || ''
  const parsed = parseEvaluationIntentKeys(scenarioKey)
  return parsed.invalid.length === 0
    && parsed.values.length === 1
    && parsed.values[0] === scenarioKey.trim()
})
const displayCaptureContext = computed(() => props.captureContext || props.replayCaptureContext)
const parsedRequired = computed(() => parseEvaluationIntentKeys(referenceForm.required))
const parsedForbidden = computed(() => parseEvaluationIntentKeys(referenceForm.forbidden))
const referenceError = computed(() => {
  if (!parsedRequired.value.values.length) return '至少填写一个必须步骤 intent key。'
  if (!parsedForbidden.value.values.length) return '至少填写一个禁止步骤 intent key。'
  const invalid = [...parsedRequired.value.invalid, ...parsedForbidden.value.invalid]
  if (invalid.length) return `以下内容不是结构化 intent key：${invalid.join('、')}`
  if (parsedRequired.value.values.length > 20 || parsedForbidden.value.values.length > 20) {
    return '必须步骤和禁止步骤各最多 20 个。'
  }
  const overlap = parsedRequired.value.values.find(value => parsedForbidden.value.values.includes(value))
  return overlap ? `intent key 不能同时为必须与禁止：${overlap}` : ''
})

const selectedSample = computed(() => ledger.value?.samples.find(
  sample => sample.sampleId === selectedSampleId.value,
) || null)

const selectedBaselineRun = computed(() => {
  const sample = selectedSample.value
  return sample ? baselineRunFor(sample) : null
})

const drawerOpen = computed(() => Boolean(
  selectedSampleId.value
    || drawerPanel.value === 'capture'
    || drawerPanel.value === 'metrics'
    || drawerPanel.value === 'reference'
    || drawerPanel.value === 'replay',
))

const drawerTitle = computed(() => {
  if (drawerPanel.value === 'replay') return TROUBLESHOOTING_UI_LABELS.historyReplay
  if (drawerPanel.value === 'capture') return '采集样本'
  if (drawerPanel.value === 'metrics') return '时延与基线指标'
  if (drawerPanel.value === 'reference') return '填写参考解'
  if (selectedSample.value) return selectedSample.value.scenarioKey
  return '样本详情'
})

onMounted(() => {
  syncCaptureForm()
  referenceSample.value = null
  void loadLedger()
})
watch(() => props.captureContext, syncCaptureForm, { deep: true })

function syncCaptureForm() {
  captureForm.scenarioKey = props.captureContext?.scenarioKey || ''
}

async function loadLedger() {
  loading.value = true
  try {
    const params = {
      diagnosisId: onlyCurrent.value ? props.currentDiagnosisId || undefined : undefined,
      limit: 100,
    }
    const [sampleResponse, baselineResponse] = await Promise.all([
      troubleshootingApi.evaluationSamples(params),
      troubleshootingApi.evaluationBaselineRuns(params),
    ])
    ledger.value = sampleResponse.data
    baselineLedger.value = baselineResponse.data
    if (selectedSampleId.value && !ledger.value?.samples.some(sample => sample.sampleId === selectedSampleId.value)) {
      selectedSampleId.value = null
      if (drawerPanel.value === 'detail' || drawerPanel.value === 'reference') {
        drawerPanel.value = 'detail'
        referenceSample.value = null
      }
    }
  } catch (error) {
    ElMessage.error(`加载评估样本失败：${errorText(error)}`)
  } finally {
    loading.value = false
  }
}

function selectSample(sample: EvidenceEvaluationSample) {
  selectedSampleId.value = sample.sampleId
  drawerPanel.value = 'detail'
  referenceSample.value = null
}

function openCaptureDrawer() {
  drawerPanel.value = 'capture'
}

function openReplayDrawer() {
  drawerPanel.value = 'replay'
}

function openMetricsDrawer() {
  drawerPanel.value = 'metrics'
}

function onDrawerOpenChange(open: boolean) {
  if (open) return
  selectedSampleId.value = null
  drawerPanel.value = 'detail'
  referenceSample.value = null
}

function backToDetailOrClose() {
  if (selectedSampleId.value) {
    drawerPanel.value = 'detail'
    return
  }
  onDrawerOpenChange(false)
}

function sampleRowClassName({ row }: { row: EvidenceEvaluationSample }) {
  const classes = []
  if (row.sampleId === selectedSampleId.value) classes.push('selected-row')
  if (row.diagnosisId === props.currentDiagnosisId) classes.push('current-row')
  return classes.join(' ')
}

async function captureSample(source: 'GUANCE' | 'RECORDED_REPLAY') {
  const context = source === 'GUANCE'
    ? props.captureContext
    : props.replayCaptureContext
  const enabled = source === 'GUANCE' ? props.captureEnabled : props.replayCaptureEnabled
  const formValid = source === 'GUANCE' ? captureFormValid.value : replayCaptureFormValid.value
  if (!context || !enabled || !formValid) return
  if (source === 'GUANCE') captureLoading.value = true
  else replayCaptureLoading.value = true
  try {
    const response = source === 'GUANCE'
      ? await troubleshootingApi.captureGuanceEvaluationSample({
          diagnosisId: context.diagnosisId,
          scenarioKey: captureForm.scenarioKey.trim(),
          searchTerm: context.searchTerm,
          window: context.window,
        })
      : await troubleshootingApi.captureRecordedReplayEvaluationSample({
          diagnosisId: context.diagnosisId,
        })
    await loadLedger()
    selectedSampleId.value = response.data.sample.sampleId
    drawerPanel.value = 'detail'
    emit('captured', response.data.sample)
    ElMessage.success(response.data.created
      ? `证据输入发生变化，已写入采集 r${response.data.sample.captureRevision}`
      : `输入未变化，已复用采集 r${response.data.sample.captureRevision}`)
  } catch (error) {
    ElMessage.error(`样本采集失败：${errorText(error)}`)
  } finally {
    if (source === 'GUANCE') captureLoading.value = false
    else replayCaptureLoading.value = false
  }
}

function openReference(sample: EvidenceEvaluationSample) {
  if (sample.diagnosisId !== props.currentDiagnosisId
    || props.currentDiagnosisStatus !== 'CLOSED') return
  selectedSampleId.value = sample.sampleId
  referenceSample.value = sample
  referenceForm.required = ''
  referenceForm.forbidden = ''
  referenceForm.expectedDisposition = 'DRAFT'
  drawerPanel.value = 'reference'
}

async function finalizeReference() {
  const sample = referenceSample.value
  if (!sample || referenceError.value) return
  referenceLoading.value = true
  try {
    await troubleshootingApi.finalizeEvaluationSampleReference(sample.sampleId, {
      expectedVersion: sample.version,
      requiredStepIntents: parsedRequired.value.values,
      forbiddenStepIntents: parsedForbidden.value.values,
      expectedDisposition: referenceForm.expectedDisposition,
    })
    referenceSample.value = null
    drawerPanel.value = 'detail'
    await loadLedger()
    ElMessage.success('参考解已冻结；权威结果来自已关闭的 Diagnosis')
  } catch (error) {
    ElMessage.error(`参考解冻结失败：${errorText(error)}`)
  } finally {
    referenceLoading.value = false
  }
}

function baselineRunFor(sample: EvidenceEvaluationSample): BaselineEvaluationRun | null {
  return baselineLedger.value?.runs.find(run => run.sampleId === sample.sampleId) || null
}

function baselineRunnable(sample: EvidenceEvaluationSample) {
  return baselineUnavailableReason(sample) === ''
}

function baselineCaptureContext(sample: EvidenceEvaluationSample) {
  return evaluationSourceCaptureContext(
    sample.sourcePlatform,
    props.captureContext,
    props.replayCaptureContext,
  )
}

function baselineUnavailableReason(sample: EvidenceEvaluationSample) {
  if (!sample.modelInputHash || !sample.evidenceOccurredAt || !sample.expectedDisposition) {
    return '旧样本需重新采集并冻结参考解'
  }
  if (sample.diagnosisId !== props.currentDiagnosisId) return '打开 Diagnosis 后运行'
  const context = baselineCaptureContext(sample)
  if (!context || context.diagnosisId !== sample.diagnosisId) {
    return '先打开当前 Diagnosis 以恢复查询窗口'
  }
  return ''
}

async function runBaseline(sample: EvidenceEvaluationSample) {
  const context = baselineCaptureContext(sample)
  if (!context || !baselineRunnable(sample)) return
  baselineRunningSampleId.value = sample.sampleId
  try {
    const response = await troubleshootingApi.runEvaluationBaseline(sample.sampleId, {
      expectedSampleVersion: sample.version,
      searchTerm: context.searchTerm,
      window: context.window,
    })
    await loadLedger()
    ElMessage.success(response.data.created
      ? '基线核对已运行并保存结果'
      : '该样本与模型版本已有基线结果，已返回既有记录')
  } catch (error) {
    ElMessage.error(`基线核对失败：${errorText(error)}`)
  } finally {
    baselineRunningSampleId.value = null
  }
}

function baselineClassificationTagType(value: BaselineClassification) {
  if (value === 'HELPFUL') return 'success'
  if (value === 'UNHELPFUL') return 'warning'
  return 'danger'
}

function openDiagnosis(diagnosisId: string) {
  emit('open-diagnosis', diagnosisId)
}

function stageLabel(stage: EvidenceEvaluationSample['evidence']['stage']) {
  return stage === 'FULL_SPINE_OBSERVED'
    ? '失败日志、关联日志和请求对比已齐全'
    : '已取得失败日志和关联日志'
}

function sampleContrastNarrative(sample: EvidenceEvaluationSample) {
  const contrast = sample.evidence.contrast
  if (!contrast.available) return null
  return evidenceComparisonNarrative({
    featureCode: contrast.discriminatingFeature,
    failureRequestCount: contrast.failureSampleCount,
    failureWithFeatureCount: contrast.failureMatchCount,
    normalRequestCount: contrast.successSampleCount,
    normalWithFeatureCount: contrast.successMatchCount,
  })
}

function shortTime(value: string) {
  return value.replace('T', ' ').replace(/\.\d+Z?$/, '').slice(0, 19)
}

function errorText(error: unknown) {
  return error instanceof Error ? error.message : String(error)
}
</script>

<style scoped>
.evaluation-ledger-workspace {
  display: flex;
  flex-direction: column;
  gap: 14px;
  height: 100%;
  min-height: 0;
  color: var(--mc-text-primary);
}

.ledger-alert { margin: 0; }

.status-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex: 0 0 auto;
  padding: 12px 14px;
  border: 1px solid var(--mc-border-light);
  border-radius: 10px;
  background: var(--mc-bg-elevated);
}

.status-metrics {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 14px;
  min-width: 0;
  color: var(--mc-text-secondary);
  font-size: 12px;
}

.progress-inline {
  display: grid;
  grid-template-columns: auto minmax(120px, 180px);
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.progress-inline b {
  color: var(--mc-text-primary);
  font-size: 12px;
  font-weight: 650;
  white-space: nowrap;
}

.status-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  justify-content: flex-end;
}

.ledger-body {
  flex: 1;
  min-height: 280px;
  border: 1px solid var(--mc-border-light);
  border-radius: 10px;
  overflow: hidden;
  background: var(--mc-bg-elevated);
}

.cell-stack {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.cell-stack strong {
  font-size: 12px;
  color: var(--mc-text-primary);
}

.cell-stack small,
.muted {
  color: var(--mc-text-secondary);
  font-size: 11px;
}

.mono { font-family: var(--mc-mono, monospace); }

.scenario-key {
  color: var(--mc-primary);
  font: 600 11px var(--mc-mono, monospace);
}

:deep(.el-table) {
  --el-table-row-hover-bg-color: color-mix(in srgb, var(--mc-primary) 7%, var(--mc-bg));
}

:deep(.el-table__row) { cursor: pointer; }

:deep(.selected-row > td.el-table__cell) {
  background: color-mix(in srgb, var(--mc-primary) 12%, var(--mc-bg)) !important;
}

:deep(.selected-row > td:first-child) {
  box-shadow: inset 3px 0 0 var(--mc-primary);
}

:deep(.current-row > td.el-table__cell) {
  background: color-mix(in srgb, var(--mc-accent, var(--mc-primary)) 5%, var(--mc-bg));
}

.drawer-panel { display: grid; gap: 14px; padding-bottom: 8px; }
.back-link { justify-self: start; margin: -4px 0 0; }
.panel-lead {
  margin: 0;
  color: var(--mc-text-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.scope-chip {
  width: fit-content;
  padding: 4px 8px;
  border-radius: 999px;
  color: var(--mc-primary);
  background: var(--mc-primary-bg);
  font: 600 11px var(--mc-mono, monospace);
}

.capture-block,
.metrics-block {
  display: grid;
  gap: 10px;
  padding: 14px;
  border: 1px solid var(--mc-border-light);
  border-radius: 10px;
  background: var(--mc-bg-muted);
}

.capture-block h3,
.section-title span {
  margin: 0;
  font-size: 13px;
  font-weight: 650;
}

.section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.capture-form {
  display: grid;
  gap: 10px;
}

.capture-form label > span,
.reference-form label > span {
  display: block;
  margin-bottom: 5px;
  color: var(--mc-text-secondary);
  font-size: 11px;
  font-weight: 650;
}

.disabled-reason {
  color: var(--mc-warning);
  font-size: 11px;
  line-height: 1.5;
}

.metric-list {
  display: grid;
  gap: 10px;
}

.metric-list article {
  padding: 12px;
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  background: var(--mc-bg-elevated);
}

.metric-list header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.metric-list header b { font-size: 12px; }
.metric-list header small,
.metric-list p {
  color: var(--mc-text-secondary);
  font-size: 11px;
  line-height: 1.5;
}

.metric-list p { margin: 8px 0; }

.metric-list dl {
  display: grid;
  gap: 7px;
  margin: 0;
}

.metric-list dl > div {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

.metric-list dt { color: var(--mc-text-secondary); font-size: 11px; }
.metric-list dd {
  margin: 0;
  color: var(--mc-text-primary);
  font-size: 11px;
  font-weight: 650;
  text-align: right;
}

.detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.eyebrow {
  color: var(--mc-text-secondary);
  font: 10px var(--mc-mono, monospace);
}

.detail-head h2 {
  margin: 4px 0;
  font-size: 18px;
  line-height: 1.35;
}

.detail-head p {
  margin: 0;
  color: var(--mc-text-secondary);
  font-size: 12px;
}

.meta-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  margin: 0;
  border-top: 1px solid var(--mc-border-light);
}

.meta-grid > div {
  padding: 10px 0;
  border-bottom: 1px solid var(--mc-border-light);
}

.meta-grid > div:nth-child(odd) { padding-right: 12px; }
.meta-grid dt { color: var(--mc-text-secondary); font-size: 10px; }
.meta-grid dd { margin: 4px 0 0; font-size: 12px; overflow-wrap: anywhere; }

.contrast-box {
  padding: 10px 12px;
  border-left: 2px solid var(--mc-success);
  border-radius: 6px;
  background: color-mix(in srgb, var(--mc-success) 8%, var(--mc-bg));
}

.contrast-box strong,
.contrast-box small { display: block; }
.contrast-box strong { font-size: 12px; line-height: 1.5; }
.contrast-box small {
  margin-top: 4px;
  color: var(--mc-text-secondary);
  font-size: 11px;
  line-height: 1.5;
}

.outcome-line {
  margin: 0;
  color: var(--mc-success);
  font-size: 12px;
  line-height: 1.5;
}

.baseline-result {
  display: grid;
  gap: 4px;
  justify-items: start;
}

.baseline-result small {
  color: var(--mc-text-secondary);
  font-size: 11px;
}

.drawer-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.immutable-mark {
  color: var(--mc-text-secondary);
  font-size: 11px;
  line-height: 1.5;
}

.reference-form {
  display: grid;
  gap: 12px;
}

.disposition-field :deep(.el-select) { width: 100%; }

.reference-error {
  margin: 0;
  color: var(--mc-danger);
  font-size: 12px;
}

.drawer-empty {
  display: grid;
  place-content: center;
  gap: 6px;
  min-height: 240px;
  text-align: center;
  color: var(--mc-text-secondary);
}

.drawer-empty strong { color: var(--mc-text-primary); font-size: 13px; }
.drawer-empty p { margin: 0; font-size: 12px; line-height: 1.5; }

@media (max-width: 900px) {
  .status-bar {
    align-items: flex-start;
    flex-direction: column;
  }

  .status-actions {
    width: 100%;
    justify-content: flex-start;
  }

  .progress-inline {
    grid-template-columns: 1fr;
    width: 100%;
  }

  .meta-grid { grid-template-columns: 1fr; }
  .meta-grid > div:nth-child(odd) { padding-right: 0; }
}
</style>
