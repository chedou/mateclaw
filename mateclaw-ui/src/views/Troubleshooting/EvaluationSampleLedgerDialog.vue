<template>
  <el-dialog
    :model-value="modelValue"
    :title="TROUBLESHOOTING_UI_LABELS.evaluation"
    width="min(980px, calc(100vw - 32px))"
    class="evaluation-ledger-dialog"
    @update:model-value="updateOpen"
  >
    <el-alert type="warning" :closable="false" class="ledger-alert">
      台账只积累脱敏的 Evidence Spine 结构事实与人工参考解。达到 20–30 条不等于 T8 通过，且不会自动关闭 fixtureMode。
    </el-alert>

    <div v-loading="loading" class="ledger-body">
      <section v-if="ledger" class="summary-panel">
        <div class="summary-grid">
          <article><span>累计样本</span><b>{{ ledger.summary.total }}</b><small>Guance {{ ledger.summary.guance }} · Replay {{ ledger.summary.recordedReplay }}</small></article>
          <article><span>可评估</span><b>{{ ledger.summary.readyForEvaluation }}</b><small>待参考解 {{ ledger.summary.evidenceCaptured }}</small></article>
          <article><span>完整脊柱</span><b>{{ ledger.summary.fullSpineObserved }}</b><small>仅核心链 {{ ledger.summary.coreChainObserved }}</small></article>
          <article><span>关联 fixture Diagnosis</span><b>{{ ledger.summary.linkedFixtureDiagnoses }}</b><small>真源样本与 Diagnosis 状态分别记录</small></article>
        </div>
        <div class="progress-row">
          <div><b>{{ progress.label }}</b><small>{{ progress.note }}</small></div>
          <el-progress :percentage="progress.percent" :stroke-width="8" :show-text="false" />
        </div>
      </section>

      <section v-if="ledger" class="latency-panel">
        <div class="panel-head">
          <div><span>T8 描述性指标</span><h3>分来源应用侧时延</h3></div>
          <el-tag size="small" type="info">{{ ledger.summary.timingMeasuredSamples }} 条完整计时</el-tag>
        </div>
        <div class="latency-grid">
          <article v-for="card in latencyCards" :key="card.key">
            <header><b>{{ card.source }}</b><small>{{ card.sampleCount }} 条可测样本</small></header>
            <dl>
              <div><dt>证据源往返</dt><dd>{{ card.evidence }}</dd></div>
              <div><dt>确定性压缩</dt><dd>{{ card.compression }}</dd></div>
              <div><dt>端到端预览</dt><dd>{{ card.total }}</dd></div>
            </dl>
          </article>
        </div>
        <p class="latency-note">
          统计仅覆盖当前筛选范围，Guance 与 Recorded Replay 不混算。这里是 MateClaw 应用侧墙钟时间，
          不是 Guance 服务端 DQL 耗时；模型耗时与结构质量在下方基线面板单独统计，任何数值都不代表 T8 已通过。
        </p>
      </section>

      <section v-if="baselineLedger" class="baseline-panel">
        <div class="panel-head">
          <div><span>T8 单 Agent 基线</span><h3>固定输入、固定模型版本的描述性结果</h3></div>
          <el-tag size="small" type="info">{{ baselineLedger.summary.total }} 次运行 · 无 Gate 结论</el-tag>
        </div>
        <div class="baseline-grid">
          <article v-for="card in baselineCards" :key="card.key">
            <header><b>{{ card.source }} · {{ card.cohort }}</b><small>{{ card.runCount }} 次基线</small></header>
            <p>{{ card.evidenceMode }} · {{ card.classifications }}</p>
            <dl>
              <div><dt>模型调用</dt><dd>{{ card.modelLatency }}</dd></div>
              <div><dt>证据 + 模型</dt><dd>{{ card.composedLatency }}</dd></div>
              <div><dt>Token</dt><dd>{{ card.tokens }}</dd></div>
            </dl>
          </article>
        </div>
        <p class="latency-note">
          每个结果只比较一条冻结样本与一个固定模型配置，不生成候选 SOP、不进入审批，也不改变 fixtureMode。
          “有帮助 / 无帮助 / 危险已拦截 / 技术失败”是逐样本分类，不能替代 20–30 条固定集和 owner Gate。
        </p>
      </section>

      <section v-if="captureContext || replayCaptureContext" class="capture-panel">
        <div class="panel-head">
          <div><span>T8 样本采集</span><h3>按来源重放同一 Evidence Spine 合同</h3></div>
          <el-tag v-if="displayCaptureContext" size="small" type="info">{{ displayCaptureContext.system }} / {{ displayCaptureContext.service }}</el-tag>
        </div>
        <p>不会保存当前浏览器预览；每次都重新执行服务端读链。输入指纹未变时复用既有样本，发生漂移时自动新增不可变 revision，绝不覆盖旧 oracle。</p>
        <div v-if="captureContext" class="capture-form">
          <label>
            <span>Guance 场景键</span>
            <el-input v-model="captureForm.scenarioKey" size="small" placeholder="message_send_failed" />
          </label>
          <label>
            <span>搜索键</span>
            <el-input :model-value="captureContext.searchTerm" size="small" disabled />
          </label>
          <label>
            <span>时间窗口</span>
            <el-input :model-value="captureContext.window" size="small" disabled />
          </label>
          <div class="capture-actions">
            <el-button
              type="primary"
              plain
              :loading="captureLoading"
              :disabled="!captureEnabled || !captureFormValid"
              @click="captureSample('GUANCE')"
            >采集 Guance 真源</el-button>
          </div>
        </div>
        <small v-if="captureContext && !captureEnabled" class="disabled-reason">Guance：{{ captureDisabledReason }}</small>
        <small v-else-if="captureContext && !captureFormValid" class="disabled-reason">Guance 场景键须为 2–64 位小写结构化 key，不接受自由文本。</small>

        <div v-if="replayCaptureContext" class="capture-form">
          <label>
            <span>Replay 场景键</span>
            <el-input :model-value="replayCaptureContext.scenarioKey" size="small" disabled />
          </label>
          <label>
            <span>服务端登记搜索键</span>
            <el-input :model-value="replayCaptureContext.searchTerm" size="small" disabled />
          </label>
          <label>
            <span>服务端登记窗口</span>
            <el-input :model-value="replayCaptureContext.window" size="small" disabled />
          </label>
          <div class="capture-actions">
            <el-button
              type="info"
              plain
              :loading="replayCaptureLoading"
              :disabled="!replayCaptureEnabled || !replayCaptureFormValid"
              @click="captureSample('RECORDED_REPLAY')"
            >采集 Replay 对照</el-button>
          </div>
        </div>
        <small v-if="!replayCaptureEnabled" class="disabled-reason">Replay：{{ replayCaptureDisabledReason }}</small>
        <small v-else-if="!replayCaptureFormValid" class="disabled-reason">服务端 Replay 目标不是合法的结构化场景键。</small>
      </section>

      <section class="sample-panel">
        <div class="panel-head sample-head">
          <div><span>固定评估集候选</span><h3>历史样本</h3></div>
          <div class="sample-filters">
            <el-switch
              v-model="onlyCurrent"
              size="small"
              :disabled="!currentDiagnosisId"
              active-text="仅当前 Diagnosis"
              @change="loadLedger"
            />
            <el-button size="small" text @click="loadLedger">刷新</el-button>
          </div>
        </div>

        <div v-if="ledger?.samples.length" class="sample-list">
          <article
            v-for="sample in ledger.samples"
            :key="sample.sampleId"
            class="sample-row"
            :class="{ current: sample.diagnosisId === currentDiagnosisId }"
          >
            <div class="sample-main">
              <div class="sample-tags">
                <el-tag size="small" :type="sample.sourcePlatform === 'GUANCE' ? 'success' : 'info'">
                  {{ evaluationSourceLabel(sample.sourcePlatform) }}
                </el-tag>
                <el-tag size="small" :type="sample.referenceStatus === 'READY_FOR_EVALUATION' ? 'primary' : 'warning'">
                  {{ evaluationReferenceStatusLabel(sample.referenceStatus) }}
                </el-tag>
                <el-tag v-if="sample.diagnosisFixtureMode" size="small" type="warning" effect="plain">关联 fixture Diagnosis</el-tag>
                <el-tag v-if="sample.evidence.fixtureMode" size="small" type="info" effect="plain">fixture 证据</el-tag>
                <el-tag v-if="sample.expectedDisposition" size="small" type="info" effect="plain">
                  {{ evaluationExpectedDispositionLabel(sample.expectedDisposition) }}
                </el-tag>
                <el-tag size="small" type="info" effect="plain">采集 r{{ sample.captureRevision }}</el-tag>
              </div>
              <b>{{ sample.system }} / {{ sample.service }}</b>
              <p><code>{{ sample.scenarioKey }}</code><span>Diagnosis {{ sample.diagnosisId }}</span></p>
              <small>
                {{ stageLabel(sample.evidence.stage) }} · {{ sample.evidence.matchCount }} 条命中 ·
                {{ sample.evidence.traceEntries }} 个节点 · {{ shortTime(sample.capturedAt) }}
              </small>
              <small v-if="sample.outcome" class="outcome-line">
                权威结果 {{ sample.outcome.outcome }} · {{ sample.outcome.summary }}
              </small>
            </div>
            <div class="sample-actions">
              <el-button
                v-if="sample.diagnosisId !== currentDiagnosisId"
                size="small"
                text
                @click="openDiagnosis(sample.diagnosisId)"
              >打开 Diagnosis</el-button>
              <el-button
                v-else-if="sample.referenceStatus === 'EVIDENCE_CAPTURED'"
                size="small"
                type="primary"
                plain
                :disabled="currentDiagnosisStatus !== 'CLOSED'"
                @click="openReference(sample)"
              >{{ currentDiagnosisStatus === 'CLOSED' ? '填写人工参考解' : '关闭后填写参考解' }}</el-button>
              <template v-if="sample.referenceStatus === 'READY_FOR_EVALUATION'">
                <div v-if="baselineRunFor(sample)" class="baseline-result">
                  <el-tag
                    size="small"
                    :type="baselineClassificationTagType(baselineRunFor(sample)!.quality.classification)"
                  >{{ baselineClassificationLabel(baselineRunFor(sample)!.quality.classification) }}</el-tag>
                  <small>{{ baselineStatusLabel(baselineRunFor(sample)!.status) }}</small>
                  <small>
                    {{ baselineRunFor(sample)!.model.provider }} / {{ baselineRunFor(sample)!.model.modelName }} ·
                    {{ baselineRunFor(sample)!.composedTotalDurationMs }} ms
                  </small>
                </div>
                <el-button
                  v-if="baselineRunnable(sample)"
                  size="small"
                  type="primary"
                  plain
                  :loading="baselineRunningSampleId === sample.sampleId"
                  @click="runBaseline(sample)"
                >{{ baselineRunFor(sample) ? '运行 / 读取当前模型版本' : '运行单 Agent 基线' }}</el-button>
                <span v-else class="immutable-mark">{{ baselineUnavailableReason(sample) }}</span>
                <span class="immutable-mark">参考解已冻结 · v{{ sample.version }}</span>
              </template>
            </div>
          </article>
        </div>
        <el-empty v-else-if="!loading" description="尚未积累 T8 历史样本" :image-size="64" />
      </section>

      <section v-if="referenceSample" class="reference-panel">
        <div class="panel-head">
          <div><span>人工结构化 oracle</span><h3>{{ referenceSample.scenarioKey }}</h3></div>
          <el-button size="small" text @click="referenceSample = null">取消</el-button>
        </div>
        <el-alert type="info" :closable="false">
          每行一个 intent key。结果、恢复验证和关闭时间由服务端读取当前 CLOSED Diagnosis，浏览器不能填写或覆盖。
        </el-alert>
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
        <div class="reference-actions">
          <el-button
            type="primary"
            :loading="referenceLoading"
            :disabled="Boolean(referenceError)"
            @click="finalizeReference"
          >冻结参考解</el-button>
        </div>
      </section>
    </div>

    <template #footer>
      <el-button @click="updateOpen(false)">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus/es/components/message/index'
import { vLoading } from 'element-plus/es/components/loading/index'
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

const props = withDefaults(defineProps<{
  modelValue: boolean
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
  captureDisabledReason: '先在真源验收中取得一条非 BLOCKED Evidence Spine，再采集历史样本。',
  replayCaptureEnabled: false,
  replayCaptureDisabledReason: '当前 Diagnosis 不在可管理的 Replay fixture 范围。',
})

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
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

watch(() => props.modelValue, (open) => {
  if (!open) return
  syncCaptureForm()
  referenceSample.value = null
  void loadLedger()
})
watch(() => props.captureContext, syncCaptureForm, { deep: true })

function syncCaptureForm() {
  captureForm.scenarioKey = props.captureContext?.scenarioKey || ''
}

function updateOpen(value: boolean) {
  emit('update:modelValue', value)
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
  } catch (error) {
    ElMessage.error(`加载 T8 样本与基线台账失败：${errorText(error)}`)
  } finally {
    loading.value = false
  }
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
    emit('captured', response.data.sample)
    ElMessage.success(response.data.created
      ? `证据输入发生变化，已写入不可变采集 r${response.data.sample.captureRevision}`
      : `输入指纹未变化，已复用采集 r${response.data.sample.captureRevision}`)
  } catch (error) {
    ElMessage.error(`T8 样本采集失败：${errorText(error)}`)
  } finally {
    if (source === 'GUANCE') captureLoading.value = false
    else replayCaptureLoading.value = false
  }
}

function openReference(sample: EvidenceEvaluationSample) {
  if (sample.diagnosisId !== props.currentDiagnosisId
    || props.currentDiagnosisStatus !== 'CLOSED') return
  referenceSample.value = sample
  referenceForm.required = ''
  referenceForm.forbidden = ''
  referenceForm.expectedDisposition = 'DRAFT'
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
    await loadLedger()
    ElMessage.success('人工参考解已冻结；权威结果来自关联的 CLOSED Diagnosis')
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
    return '先打开当前 Diagnosis 以恢复冻结查询窗口'
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
      ? '单 Agent 基线已运行并保存结构化结果'
      : '该样本与模型版本已有基线结果，已返回既有记录')
  } catch (error) {
    ElMessage.error(`单 Agent 基线运行失败：${errorText(error)}`)
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
  return stage === 'FULL_SPINE_OBSERVED' ? '完整 Evidence Spine' : '核心链已观测'
}

function shortTime(value: string) {
  return value.replace('T', ' ').replace(/\.\d+Z?$/, '').slice(0, 19)
}

function errorText(error: unknown) {
  return error instanceof Error ? error.message : String(error)
}
</script>

<style scoped>
.ledger-alert { margin-bottom: 14px; }
.ledger-body { min-height: 220px; }
.summary-panel,.latency-panel,.baseline-panel,.capture-panel,.sample-panel,.reference-panel { padding: 15px; border: 1px solid #e1e6ef; border-radius: 10px; background: #fff; }
.latency-panel,.baseline-panel,.capture-panel,.sample-panel,.reference-panel { margin-top: 12px; }
.summary-grid { display: grid; grid-template-columns: repeat(4,minmax(0,1fr)); gap: 9px; }
.summary-grid article { padding: 11px; border-radius: 8px; background: #f5f7fb; }
.summary-grid span,.summary-grid small { display: block; color: #667085; font-size: 10px; }
.summary-grid b { display: block; margin: 4px 0; color: #172033; font-size: 20px; }
.progress-row { display: grid; grid-template-columns: minmax(220px,1fr) minmax(180px,.7fr); align-items: center; gap: 18px; margin-top: 12px; }
.progress-row b,.progress-row small { display: block; }
.progress-row b { font-size: 12px; }
.progress-row small { margin-top: 4px; color: #b54708; font-size: 10px; line-height: 1.5; }
.latency-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 9px; margin-top: 12px; }
.latency-grid article { padding: 12px; border: 1px solid #e7eaf0; border-radius: 8px; background: #fbfcfe; }
.latency-grid header { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.latency-grid header b { font-size: 11px; }
.latency-grid header small { color: #667085; font-size: 9px; }
.latency-grid dl { display: grid; gap: 7px; margin: 10px 0 0; }
.latency-grid dl>div { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.latency-grid dt { color: #667085; font-size: 9.5px; }
.latency-grid dd { margin: 0; color: #172033; font-size: 9.5px; font-weight: 650; text-align: right; }
.baseline-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 9px; margin-top: 12px; }
.baseline-grid article { padding: 12px; border: 1px solid #d8e2f5; border-radius: 8px; background: #f8faff; }
.baseline-grid header { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.baseline-grid header b { font-size: 11px; }
.baseline-grid header small { color: #667085; font-size: 9px; }
.baseline-grid p { margin: 8px 0; color: #344054; font-size: 9.5px; line-height: 1.5; }
.baseline-grid dl { display: grid; gap: 7px; margin: 0; }
.baseline-grid dl>div { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.baseline-grid dt { color: #667085; font-size: 9.5px; }
.baseline-grid dd { margin: 0; color: #172033; font-size: 9.5px; font-weight: 650; text-align: right; }
.latency-note { margin: 10px 0 0; color: #667085; font-size: 9.5px; line-height: 1.55; }
.panel-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.panel-head span { color: #2f5cf5; font-size: 9px; font-weight: 750; letter-spacing: .08em; text-transform: uppercase; }
.panel-head h3 { margin: 4px 0 0; font-size: 14px; }
.capture-panel>p { margin: 9px 0; color: #667085; font-size: 10.5px; line-height: 1.55; }
.capture-form { display: grid; grid-template-columns: 1.2fr 1.2fr .7fr auto; align-items: end; gap: 9px; }
.capture-actions { display: flex; align-items: center; gap: 7px; }
.capture-form label>span,.reference-form label>span { display: block; margin-bottom: 5px; color: #475467; font-size: 10px; font-weight: 650; }
.disabled-reason { display: block; margin-top: 7px; color: #b54708; font-size: 10px; }
.sample-head { align-items: center; }
.sample-filters { display: flex; align-items: center; gap: 8px; }
.sample-list { display: grid; gap: 8px; margin-top: 12px; }
.sample-row { display: grid; grid-template-columns: minmax(0,1fr) auto; align-items: center; gap: 12px; padding: 12px; border: 1px solid #e7eaf0; border-radius: 8px; background: #fbfcfe; }
.sample-row.current { border-color: #b2ccff; background: #f5f8ff; }
.sample-tags { display: flex; flex-wrap: wrap; gap: 5px; margin-bottom: 7px; }
.sample-main>b { display: block; font-size: 12px; }
.sample-main>p { display: flex; flex-wrap: wrap; gap: 9px; margin: 5px 0; color: #667085; font-size: 10px; }
.sample-main code { color: #2f5cf5; }
.sample-main>small { display: block; color: #667085; font-size: 9.5px; line-height: 1.5; }
.sample-main .outcome-line { margin-top: 4px; color: #138a58; }
.sample-actions { display: flex; min-width: 160px; flex-direction: column; align-items: flex-end; gap: 6px; text-align: right; }
.baseline-result { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; }
.baseline-result small { color: #667085; font-size: 9px; }
.immutable-mark { color: #667085; font-size: 9.5px; }
.reference-form { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-top: 12px; }
.disposition-field { grid-column: 1 / -1; }
.reference-error { margin: 8px 0 0; color: #d92d20; font-size: 10px; }
.reference-actions { display: flex; justify-content: flex-end; margin-top: 10px; }
@media(max-width:760px){.summary-grid,.latency-grid,.baseline-grid{grid-template-columns:1fr 1fr}.progress-row,.capture-form,.reference-form{grid-template-columns:1fr}.capture-actions{align-items:stretch;flex-direction:column}.sample-row{grid-template-columns:1fr}.sample-actions,.baseline-result{align-items:flex-start;text-align:left}}
@media(max-width:520px){.summary-grid,.latency-grid,.baseline-grid{grid-template-columns:1fr}}
</style>
