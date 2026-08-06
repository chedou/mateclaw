<template>
  <el-dialog
    v-model="visible"
    :title="TROUBLESHOOTING_UI_LABELS.historyReplay"
    width="min(920px, calc(100vw - 32px))"
    destroy-on-close
    :teleported="false"
    class="synthesis-preview-dialog"
  >
    <el-alert type="warning" :closable="false" class="scope-alert">
      <template #title>
        这里使用服务端保存的 <b>Recorded Replay</b> 历史证据验证取证步骤。
        它不会访问真实观测云、调用模型、创建排障规则或让规则直接生效。
      </template>
    </el-alert>

    <el-form label-position="top" class="preview-form">
      <el-form-item label="system">
        <el-input v-model="form.system" :disabled="loading" placeholder="CSDP" />
      </el-form-item>
      <el-form-item label="service">
        <el-input v-model="form.service" :disabled="loading" placeholder="csdp-session-service" />
      </el-form-item>
      <el-form-item label="场景搜索键">
        <el-input v-model="form.searchTerm" :disabled="loading" placeholder="message_send_failed" />
        <small>只接受已映射的安全标识符，不接受自然语言、DQL 或原始日志。</small>
      </el-form-item>
      <el-form-item label="证据窗口">
        <el-select v-model="form.window" :disabled="loading" style="width: 100%">
          <el-option
            v-for="option in EVIDENCE_WINDOW_OPTIONS"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="故障发生时间（可选，ISO-8601）" class="occurred-at">
        <el-input v-model="form.occurredAt" :disabled="loading" placeholder="留空则由服务端取当前时间" />
      </el-form-item>
    </el-form>

    <div v-if="preview" class="preview-result">
      <header class="result-head">
        <div>
          <span>Evidence Spine</span>
          <h3>历史证据回放完成</h3>
          <p>
            {{ preview.system }} / {{ preview.service }} · {{ preview.searchTerm }} · {{ form.window }}；
            仅验证取证链路；没有创建或批准任何排障规则。
          </p>
        </div>
        <div class="result-facts">
          <b>{{ preview.matchCount }}</b><span>条日志命中</span>
          <code>{{ preview.psId }}</code>
        </div>
      </header>

      <section class="evidence-spine" aria-label="证据脊柱">
        <article v-for="(step, index) in evidenceSteps" :key="step.signalKind">
          <span class="step-number">{{ index + 1 }}</span>
          <div>
            <code>{{ step.signalKind }}</code>
            <b>{{ step.label }}</b>
            <small>
              {{ step.source || '未取得证据' }} · {{ step.queryId || '无引用' }} ·
              {{ step.collectedAt ? shortTime(step.collectedAt) : '未采集' }}
            </small>
          </div>
          <el-tag :type="step.status === 'MISSING' ? 'danger' : step.status === 'ANOMALY' ? 'warning' : 'success'" size="small" effect="plain">
            {{ step.status }}
          </el-tag>
        </article>
      </section>

      <div class="result-grid">
        <section class="trace-card">
          <div class="section-head">
            <div><span>Deterministic skeleton</span><h4>PS 调用链</h4></div>
            <small>{{ preview.skeleton.elapsedMs }} ms · {{ preview.skeleton.sourceEntryCount }} 条规范化事件</small>
          </div>
          <ol class="trace-list">
            <li
              v-for="event in preview.skeleton.timeline"
              :key="`${event.sequenceIndex}-${event.service}`"
              :class="{ anomalous: event.anomalous }"
            >
              <time>+{{ event.offsetMs }} ms</time>
              <div><b>{{ event.service }}</b><span>{{ event.level }}</span><p>{{ event.message }}</p></div>
              <small>{{ event.durationMs == null ? '未记录耗时' : `${event.durationMs} ms` }}</small>
            </li>
          </ol>
          <p v-if="preview.skeleton.omittedEntryCount" class="omitted">
            另有 {{ preview.skeleton.omittedEntryCount }} 条事件因确定性预算被省略。
          </p>
        </section>

        <aside class="contrast-card" :class="{ unavailable: !preview.contrastAvailable }">
          <span>Negative control</span>
          <h4>成功样本对照</h4>
          <template v-if="preview.skeleton.contrast.available">
            <div class="rate-row failure">
              <b>{{ formatSynthesisRate(preview.skeleton.contrast.failureRate) }}</b>
              <small>失败样本命中特征</small>
            </div>
            <div class="rate-row success">
              <b>{{ formatSynthesisRate(preview.skeleton.contrast.successRate) }}</b>
              <small>成功样本命中特征</small>
            </div>
            <strong>{{ formatSynthesisRateDelta(preview.skeleton.contrast.rateDelta) }}</strong>
            <code>{{ preview.skeleton.contrast.discriminatingFeature }}</code>
          </template>
          <p v-else>未取得成功样本；后续草稿只能停留在校准期，不能把缺失对照伪装成已验证。</p>
        </aside>
      </div>

      <ul v-if="preview.warnings.length" class="preview-warnings">
        <li v-for="warning in preview.warnings" :key="warning">{{ warning }}</li>
      </ul>
    </div>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button type="primary" :loading="loading" :disabled="!canPreview" @click="runPreview">
        运行只读证据预览
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  troubleshootingApi,
  type SopSynthesisPreview,
  type SopSynthesisPreviewRequest,
} from '@/api'
import {
  buildSynthesisEvidenceSteps,
  EVIDENCE_WINDOW_OPTIONS,
  formatSynthesisRate,
  formatSynthesisRateDelta,
  normalizeSynthesisPreviewRequest,
} from './synthesisPreview'
import { TROUBLESHOOTING_UI_LABELS } from './workbenchView'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const form = reactive<SopSynthesisPreviewRequest>({
  system: 'CSDP',
  service: 'csdp-session-service',
  searchTerm: 'message_send_failed',
  window: '-15m',
  occurredAt: null,
})
const preview = ref<SopSynthesisPreview | null>(null)
const loading = ref(false)
const safeIdentifier = /^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$/
let previewRequestVersion = 0

const canPreview = computed(() => [form.system, form.service, form.searchTerm]
  .every((value) => safeIdentifier.test(value.trim())) && Boolean(form.window))
const evidenceSteps = computed(() => preview.value
  ? buildSynthesisEvidenceSteps(preview.value)
  : [])

watch(
  () => [form.system, form.service, form.searchTerm, form.window, form.occurredAt],
  () => resetPreview(),
)
watch(visible, (isVisible) => {
  if (!isVisible) resetPreview()
})

async function runPreview() {
  if (!canPreview.value) return
  const requestVersion = ++previewRequestVersion
  loading.value = true
  try {
    const { data } = await troubleshootingApi.previewSopSynthesis(
      normalizeSynthesisPreviewRequest(form),
    )
    if (requestVersion !== previewRequestVersion || !visible.value) return
    preview.value = data
    ElMessage.success('历史证据已完成只读回放与确定性压缩')
  } catch (error) {
    if (requestVersion !== previewRequestVersion || !visible.value) return
    preview.value = null
    ElMessage.error(error instanceof Error ? error.message : String(error))
  } finally {
    if (requestVersion === previewRequestVersion) loading.value = false
  }
}

function resetPreview() {
  previewRequestVersion += 1
  preview.value = null
  loading.value = false
}

function shortTime(value: string) {
  return value.replace('T', ' ').replace(/\.\d+Z?$/, '').slice(0, 19)
}
</script>

<style scoped>
.scope-alert { margin-bottom: 16px; }
.preview-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 14px; }
.preview-form :deep(.el-form-item) { margin-bottom: 13px; }
.preview-form small { display: block; margin-top: 4px; color: var(--el-text-color-secondary); font-size: 10px; }
.occurred-at { grid-column: 1 / -1; }
.preview-result { margin-top: 5px; padding-top: 18px; border-top: 1px solid var(--el-border-color-lighter); }
.result-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; }
.result-head span,.section-head span,.contrast-card>span { color: var(--el-text-color-placeholder); font-size: 9.5px; font-weight: 750; letter-spacing: .1em; text-transform: uppercase; }
.result-head h3 { margin: 5px 0 4px; font-size: 17px; }
.result-head p { margin: 0; color: var(--el-text-color-secondary); font-size: 10.5px; }
.result-facts { display: grid; grid-template-columns: auto auto; align-items: baseline; gap: 2px 7px; text-align: right; }
.result-facts b { color: #2f5cf5; font-size: 21px; }
.result-facts span { color: var(--el-text-color-secondary); font-size: 10px; }
.result-facts code { grid-column: 1 / -1; color: #344054; font-size: 10px; }
.evidence-spine { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 9px; margin-top: 16px; }
.evidence-spine article { display: grid; grid-template-columns: 24px minmax(0, 1fr) auto; gap: 8px; align-items: start; padding: 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; background: var(--el-fill-color-extra-light); }
.step-number { display: grid; place-items: center; width: 22px; height: 22px; border-radius: 50%; color: white; background: #2f5cf5; font-size: 10px; font-weight: 700; }
.evidence-spine code,.evidence-spine b,.evidence-spine small { display: block; }
.evidence-spine code { color: #2f5cf5; font-size: 9.5px; }
.evidence-spine b { margin-top: 4px; font-size: 11px; }
.evidence-spine small { margin-top: 5px; color: var(--el-text-color-secondary); font-size: 8.5px; line-height: 1.45; word-break: break-all; }
.result-grid { display: grid; grid-template-columns: minmax(0, 1.7fr) minmax(220px, .7fr); gap: 11px; margin-top: 11px; }
.trace-card,.contrast-card { padding: 14px; border: 1px solid var(--el-border-color-lighter); border-radius: 9px; }
.section-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.section-head h4,.contrast-card h4 { margin: 5px 0 0; font-size: 13px; }
.section-head>small { color: var(--el-text-color-secondary); font-size: 9.5px; }
.trace-list { margin: 13px 0 0; padding: 0; list-style: none; }
.trace-list li { display: grid; grid-template-columns: 58px minmax(0, 1fr) auto; gap: 10px; padding: 9px 0; border-top: 1px solid var(--el-border-color-lighter); }
.trace-list time,.trace-list small { color: var(--el-text-color-secondary); font-size: 9px; }
.trace-list b { font-size: 11px; }.trace-list span { margin-left: 7px; color: #667085; font-size: 9px; }
.trace-list p { margin: 3px 0 0; color: var(--el-text-color-secondary); font-size: 10px; }
.trace-list li.anomalous b,.trace-list li.anomalous span { color: #d92d20; }
.omitted { margin: 8px 0 0; color: #b54708; font-size: 9.5px; }
.contrast-card { background: #f2fcf7; }.contrast-card.unavailable { background: #fffaeb; }
.rate-row { display: flex; align-items: baseline; justify-content: space-between; margin-top: 13px; padding: 8px 9px; border-radius: 6px; background: white; }
.rate-row b { font-size: 16px; }.rate-row small { color: var(--el-text-color-secondary); font-size: 9px; }
.rate-row.failure b { color: #d92d20; }.rate-row.success b { color: #138a58; }
.contrast-card>strong,.contrast-card>code { display: block; margin-top: 10px; }
.contrast-card>strong { color: #2f5cf5; font-size: 12px; }.contrast-card>code { color: #344054; font-size: 9.5px; }
.contrast-card>p { color: #b54708; font-size: 10px; line-height: 1.55; }
.preview-warnings { margin: 11px 0 0; padding: 10px 12px 10px 28px; border-radius: 7px; color: #7a4e00; background: #fff9e8; font-size: 9.5px; line-height: 1.55; }
@media(max-width: 820px) {
  .preview-form,.evidence-spine,.result-grid { grid-template-columns: 1fr; }
  .occurred-at { grid-column: auto; }
}
</style>
