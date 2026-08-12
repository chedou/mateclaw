<template>
  <el-drawer
    :model-value="modelValue"
    title="注册候选排查规则"
    :size="'var(--mc-ts-drawer-width)'"
    destroy-on-close
    class="sop-register-drawer"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <el-alert type="info" :closable="false" class="register-note">
      <template #title>
        用表单编辑更省事；提交时仍会生成标准 JSON，并以 <code>candidate + verified=false</code> 注册。
        sopId 冲突会拒绝覆盖。
      </template>
    </el-alert>

    <div class="toolbar">
      <el-radio-group v-model="editMode" size="small">
        <el-radio-button value="form">表单编辑</el-radio-button>
        <el-radio-button value="json">高级 JSON</el-radio-button>
      </el-radio-group>
      <div class="toolbar-actions">
        <span>第一次接入？</span>
        <el-button size="small" plain @click="$emit('loadTemplate')">
          载入部署拓扑示例
        </el-button>
      </div>
    </div>

    <div v-if="editMode === 'form'" class="form-editor">
      <section class="block">
        <h3>基本信息</h3>
        <div class="grid">
          <el-form-item label="规则 ID sopId">
            <el-input v-model="form.sopId" placeholder="如 manual-xxx-v1" />
          </el-form-item>
          <el-form-item label="系统 system">
            <el-input v-model="form.system" placeholder="如 CSDP" />
          </el-form-item>
          <el-form-item label="错误码/场景键 errorCode">
            <el-input v-model="form.errorCode" placeholder="如 904003 或 scenario:xxx" />
          </el-form-item>
          <el-form-item label="服务 service">
            <el-input v-model="form.service" placeholder="如 csdp-wechat" />
          </el-form-item>
          <el-form-item label="标题 title" class="span-2">
            <el-input v-model="form.title" placeholder="一句话说明这条规则" />
          </el-form-item>
          <el-form-item label="可能原因 cause" class="span-2">
            <el-input v-model="form.cause" type="textarea" :rows="2" />
          </el-form-item>
          <el-form-item label="分类 category">
            <el-input v-model="form.category" placeholder="如 network / middleware" />
          </el-form-item>
          <el-form-item label="责任团队 ownerTeam">
            <el-input v-model="form.ownerTeam" placeholder="可中文" />
          </el-form-item>
        </div>
      </section>

      <section class="block">
        <div class="block-head">
          <h3>取证步骤 evidenceRequests（{{ form.evidenceRequests.length }}）</h3>
          <el-button size="small" type="primary" plain @click="addEvidence">添加取证</el-button>
        </div>
        <div
          v-for="(item, index) in form.evidenceRequests"
          :key="`ev-${index}`"
          class="card"
        >
          <div class="card-head">
            <strong>取证 {{ index + 1 }}</strong>
            <el-button text type="danger" @click="form.evidenceRequests.splice(index, 1)">删除</el-button>
          </div>
          <div class="grid">
            <el-form-item label="步骤 ID requestId">
              <el-input v-model="item.requestId" placeholder="如 EV-1" />
            </el-form-item>
            <el-form-item label="信号类型 signalKind">
              <el-select v-model="item.signalKind" filterable allow-create default-first-option style="width:100%">
                <el-option
                  v-for="option in SIGNAL_KIND_OPTIONS"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="目的 purpose" class="span-2">
              <el-input v-model="item.purpose" placeholder="这一步要证明什么" />
            </el-form-item>
            <el-form-item label="时间窗 window">
              <el-input v-model="item.window" placeholder="-15m" />
            </el-form-item>
            <el-form-item label="是否必做 required">
              <el-switch v-model="item.required" />
            </el-form-item>
            <el-form-item label="目标 target（JSON 对象）" class="span-2">
              <el-input
                v-model="item.targetJson"
                type="textarea"
                :rows="2"
                placeholder='{"assetType":"...","toolKey":"..."}'
              />
            </el-form-item>
          </div>
        </div>
        <p v-if="!form.evidenceRequests.length" class="empty-hint">还没有取证步骤，点「添加取证」开始。</p>
      </section>

      <section class="block">
        <div class="block-head">
          <h3>异常判据 anomalyCriteria（{{ form.anomalyCriteria.length }}）</h3>
          <el-button size="small" type="primary" plain @click="addCriterion">添加判据</el-button>
        </div>
        <div
          v-for="(item, index) in form.anomalyCriteria"
          :key="`cr-${index}`"
          class="card"
        >
          <div class="card-head">
            <strong>判据 {{ index + 1 }}</strong>
            <el-button text type="danger" @click="form.anomalyCriteria.splice(index, 1)">删除</el-button>
          </div>
          <div class="grid">
            <el-form-item label="信号名 signal">
              <el-input v-model="item.signal" placeholder="如 failed_probe_present" />
            </el-form-item>
            <el-form-item label="来自取证 sourceRequestId">
              <el-select v-model="item.sourceRequestId" filterable allow-create style="width:100%">
                <el-option
                  v-for="requestId in evidenceRequestIds"
                  :key="requestId"
                  :label="requestId"
                  :value="requestId"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="说明 description" class="span-2">
              <el-input v-model="item.description" />
            </el-form-item>
            <el-form-item label="规则类型 rule.kind">
              <el-select v-model="item.ruleKind" style="width:100%">
                <el-option
                  v-for="option in RULE_KIND_OPTIONS"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="比较字段 rule.field">
              <el-input v-model="item.ruleField" placeholder="如 failed_probe_count" />
            </el-form-item>
            <el-form-item label="阈值 rule.threshold">
              <el-input-number v-model="item.ruleThreshold" :controls="false" style="width:100%" />
            </el-form-item>
          </div>
        </div>
        <p v-if="!form.anomalyCriteria.length" class="empty-hint">还没有异常判据。</p>
      </section>

      <section class="block">
        <div class="block-head">
          <h3>诊断规则 diagnosisRules（{{ form.diagnosisRules.length }}）</h3>
          <el-button size="small" type="primary" plain @click="addDiagnosis">添加结论规则</el-button>
        </div>
        <div
          v-for="(item, index) in form.diagnosisRules"
          :key="`dr-${index}`"
          class="card"
        >
          <div class="card-head">
            <strong>结论 {{ index + 1 }}</strong>
            <el-button text type="danger" @click="form.diagnosisRules.splice(index, 1)">删除</el-button>
          </div>
          <div class="grid">
            <el-form-item label="规则 ID ruleId">
              <el-input v-model="item.ruleId" placeholder="如 RULE-1" />
            </el-form-item>
            <el-form-item label="置信度 confidence">
              <el-select v-model="item.confidence" style="width:100%">
                <el-option label="LOW 低" value="LOW" />
                <el-option label="MEDIUM 中" value="MEDIUM" />
                <el-option label="HIGH 高" value="HIGH" />
              </el-select>
            </el-form-item>
            <el-form-item label="需要哪些信号 requiredSignals（逗号分隔）" class="span-2">
              <el-input
                v-model="item.requiredSignalsText"
                placeholder="failed_probe_present, other_signal"
              />
            </el-form-item>
            <el-form-item label="根因 rootCause" class="span-2">
              <el-input v-model="item.rootCause" />
            </el-form-item>
            <el-form-item label="摘要 summary" class="span-2">
              <el-input v-model="item.summary" type="textarea" :rows="2" />
            </el-form-item>
            <el-form-item label="是否弃权 abstained">
              <el-switch v-model="item.abstained" />
            </el-form-item>
          </div>
        </div>
        <p v-if="!form.diagnosisRules.length" class="empty-hint">还没有诊断结论规则。</p>
      </section>

      <section class="block">
        <div class="block-head">
          <h3>建议动作 actions（{{ form.actions.length }}，可选）</h3>
          <el-button size="small" type="primary" plain @click="addAction">添加动作</el-button>
        </div>
        <div
          v-for="(item, index) in form.actions"
          :key="`ac-${index}`"
          class="card"
        >
          <div class="card-head">
            <strong>动作 {{ index + 1 }}</strong>
            <el-button text type="danger" @click="form.actions.splice(index, 1)">删除</el-button>
          </div>
          <div class="grid">
            <el-form-item label="动作 ID actionId">
              <el-input v-model="item.actionId" />
            </el-form-item>
            <el-form-item label="类型 actionType">
              <el-select v-model="item.actionType" style="width:100%">
                <el-option label="只读核查 READ_ONLY" value="READ_ONLY" />
                <el-option label="人工处置 HUMAN" value="HUMAN" />
                <el-option label="人工联系 HUMAN_CONTACT" value="HUMAN_CONTACT" />
                <el-option label="生产写入 MANUAL_WRITE（会被拦截）" value="MANUAL_WRITE" />
              </el-select>
            </el-form-item>
            <el-form-item label="标题 title" class="span-2">
              <el-input v-model="item.title" />
            </el-form-item>
          </div>
        </div>
      </section>
    </div>

    <el-input
      v-else
      :model-value="registerJson"
      type="textarea"
      :rows="22"
      resize="vertical"
      spellcheck="false"
      class="json-input"
      @update:model-value="onJsonInput"
    />

    <div class="validation" :class="{ valid: importValidation.sop }">
      <template v-if="importValidation.sop">
        <span class="validation-dot" />
        可提交：<code>{{ importValidation.sop.system }}:{{ importValidation.sop.errorCode }}</code>
        · {{ importValidation.sop.evidenceRequests.length }} 取证
        · {{ importValidation.sop.anomalyCriteria.length }} 判据
        · {{ importValidation.sop.diagnosisRules.length }} 结论
      </template>
      <template v-else>{{ importValidation.error }}</template>
    </div>

    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button
        type="primary"
        :disabled="!importValidation.sop"
        :loading="registering"
        @click="$emit('register')"
      >注册为 candidate</el-button>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import type { SopEntry } from '@/api'

const SIGNAL_KIND_OPTIONS = [
  { value: 'log_search', label: '日志检索 log_search' },
  { value: 'log_trace_bundle', label: '链路还原 log_trace_bundle' },
  { value: 'contrast_sample', label: '成败对照 contrast_sample' },
  { value: 'log_count', label: '日志计数 log_count' },
  { value: 'metric', label: '指标 metric' },
  { value: 'trace', label: '调用链 trace' },
  { value: 'synthetic_probe', label: '拨测 synthetic_probe' },
  { value: 'error_log_scan', label: '错误日志扫描 error_log_scan' },
  { value: 'monitor_event_scan', label: '监控事件 monitor_event_scan' },
]

const RULE_KIND_OPTIONS = [
  { value: 'numeric_gte', label: '数值 ≥ numeric_gte' },
  { value: 'numeric_lte', label: '数值 ≤ numeric_lte' },
  { value: 'boolean_equals', label: '布尔等于 boolean_equals' },
  { value: 'ratio_of_sum_gt', label: '占比大于 ratio_of_sum_gt' },
  { value: 'multiple_gt', label: '倍数大于 multiple_gt' },
]

type EvidenceForm = {
  requestId: string
  signalKind: string
  purpose: string
  window: string
  required: boolean
  targetJson: string
}

type CriterionForm = {
  signal: string
  sourceRequestId: string
  description: string
  ruleKind: string
  ruleField: string
  ruleThreshold: number
}

type DiagnosisForm = {
  ruleId: string
  requiredSignalsText: string
  rootCause: string
  summary: string
  confidence: string
  abstained: boolean
}

type ActionForm = {
  actionId: string
  actionType: string
  title: string
}

type SopFormState = {
  sopId: string
  system: string
  errorCode: string
  service: string
  title: string
  cause: string
  category: string
  ownerTeam: string
  evidenceRequests: EvidenceForm[]
  anomalyCriteria: CriterionForm[]
  diagnosisRules: DiagnosisForm[]
  actions: ActionForm[]
}

const props = defineProps<{
  modelValue: boolean
  registerJson: string
  registering: boolean
  importValidation: { sop: SopEntry | null; error: string | null }
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  'update:registerJson': [value: string]
  register: []
  loadTemplate: []
}>()

const editMode = ref<'form' | 'json'>('form')
const syncingFromJson = ref(false)
const form = reactive<SopFormState>(emptyForm())

const evidenceRequestIds = computed(() =>
  form.evidenceRequests.map(item => item.requestId.trim()).filter(Boolean),
)

watch(() => props.modelValue, (open) => {
  if (open) {
    editMode.value = 'form'
    hydrateFormFromJson(props.registerJson)
  }
})

watch(() => props.registerJson, (value) => {
  if (editMode.value === 'form') hydrateFormFromJson(value)
})

watch(form, () => {
  if (editMode.value !== 'form' || syncingFromJson.value) return
  emit('update:registerJson', JSON.stringify(buildSopFromForm(form), null, 2))
}, { deep: true })

watch(editMode, (mode, previous) => {
  if (mode === 'form' && previous === 'json') {
    hydrateFormFromJson(props.registerJson)
  }
  if (mode === 'json' && previous === 'form') {
    emit('update:registerJson', JSON.stringify(buildSopFromForm(form), null, 2))
  }
})

function onJsonInput(value: string) {
  emit('update:registerJson', value)
}

function emptyForm(): SopFormState {
  return {
    sopId: '',
    system: '',
    errorCode: '',
    service: '',
    title: '',
    cause: '',
    category: '',
    ownerTeam: '',
    evidenceRequests: [],
    anomalyCriteria: [],
    diagnosisRules: [],
    actions: [],
  }
}

function addEvidence() {
  const index = form.evidenceRequests.length + 1
  form.evidenceRequests.push({
    requestId: `EV-${index}`,
    signalKind: 'log_search',
    purpose: '',
    window: '-15m',
    required: true,
    targetJson: '{}',
  })
}

function addCriterion() {
  form.anomalyCriteria.push({
    signal: '',
    sourceRequestId: evidenceRequestIds.value[0] || '',
    description: '',
    ruleKind: 'numeric_gte',
    ruleField: '',
    ruleThreshold: 1,
  })
}

function addDiagnosis() {
  const index = form.diagnosisRules.length + 1
  form.diagnosisRules.push({
    ruleId: `RULE-${index}`,
    requiredSignalsText: '',
    rootCause: '',
    summary: '',
    confidence: 'MEDIUM',
    abstained: false,
  })
}

function addAction() {
  const index = form.actions.length + 1
  form.actions.push({
    actionId: `ACT-${index}`,
    actionType: 'HUMAN',
    title: '',
  })
}

function hydrateFormFromJson(source: string) {
  syncingFromJson.value = true
  try {
    const parsed = JSON.parse(source) as Record<string, unknown>
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return
    Object.assign(form, emptyForm(), {
      sopId: String(parsed.sopId || ''),
      system: String(parsed.system || ''),
      errorCode: String(parsed.errorCode || ''),
      service: String(parsed.service || ''),
      title: String(parsed.title || ''),
      cause: String(parsed.cause || ''),
      category: String(parsed.category || ''),
      ownerTeam: String(parsed.ownerTeam || ''),
      evidenceRequests: Array.isArray(parsed.evidenceRequests)
        ? parsed.evidenceRequests.map(toEvidenceForm)
        : [],
      anomalyCriteria: Array.isArray(parsed.anomalyCriteria)
        ? parsed.anomalyCriteria.map(toCriterionForm)
        : [],
      diagnosisRules: Array.isArray(parsed.diagnosisRules)
        ? parsed.diagnosisRules.map(toDiagnosisForm)
        : [],
      actions: Array.isArray(parsed.actions)
        ? parsed.actions.map(toActionForm)
        : [],
    })
  } catch {
    // Keep current form when JSON is temporarily invalid while typing in JSON mode.
  } finally {
    syncingFromJson.value = false
  }
}

function toEvidenceForm(value: unknown): EvidenceForm {
  const row = asObject(value)
  return {
    requestId: String(row.requestId || ''),
    signalKind: String(row.signalKind || 'log_search'),
    purpose: String(row.purpose || ''),
    window: String(row.window || '-15m'),
    required: row.required !== false,
    targetJson: JSON.stringify(row.target && typeof row.target === 'object' ? row.target : {}, null, 2),
  }
}

function toCriterionForm(value: unknown): CriterionForm {
  const row = asObject(value)
  const rule = asObject(row.rule)
  return {
    signal: String(row.signal || ''),
    sourceRequestId: String(row.sourceRequestId || ''),
    description: String(row.description || ''),
    ruleKind: String(rule.kind || 'numeric_gte'),
    ruleField: String(rule.field || ''),
    ruleThreshold: Number(rule.threshold ?? 1),
  }
}

function toDiagnosisForm(value: unknown): DiagnosisForm {
  const row = asObject(value)
  const signals = Array.isArray(row.requiredSignals)
    ? row.requiredSignals.map(item => String(item)).join(', ')
    : ''
  return {
    ruleId: String(row.ruleId || ''),
    requiredSignalsText: signals,
    rootCause: String(row.rootCause || ''),
    summary: String(row.summary || ''),
    confidence: String(row.confidence || 'MEDIUM'),
    abstained: Boolean(row.abstained),
  }
}

function toActionForm(value: unknown): ActionForm {
  const row = asObject(value)
  return {
    actionId: String(row.actionId || ''),
    actionType: String(row.actionType || 'HUMAN'),
    title: String(row.title || ''),
  }
}

function buildSopFromForm(state: SopFormState) {
  return {
    sopId: state.sopId.trim(),
    contractVersion: 'sop.v1',
    system: state.system.trim(),
    errorCode: state.errorCode.trim(),
    service: state.service.trim(),
    title: state.title.trim(),
    cause: state.cause.trim(),
    category: state.category.trim(),
    ownerTeam: state.ownerTeam.trim() || null,
    status: 'candidate',
    verified: false,
    evidenceRequests: state.evidenceRequests.map((item) => ({
      requestId: item.requestId.trim(),
      signalKind: item.signalKind.trim(),
      purpose: item.purpose.trim(),
      target: parseTarget(item.targetJson),
      window: item.window.trim() || '-15m',
      required: item.required,
    })),
    anomalyCriteria: state.anomalyCriteria.map((item) => ({
      signal: item.signal.trim(),
      sourceRequestId: item.sourceRequestId.trim(),
      description: item.description.trim(),
      rule: {
        kind: item.ruleKind,
        field: item.ruleField.trim(),
        threshold: item.ruleThreshold,
      },
    })),
    diagnosisRules: state.diagnosisRules.map((item) => ({
      ruleId: item.ruleId.trim(),
      requiredSignals: item.requiredSignalsText
        .split(',')
        .map(part => part.trim())
        .filter(Boolean),
      rootCause: item.rootCause.trim(),
      summary: item.summary.trim(),
      confidence: item.confidence,
      abstained: item.abstained,
    })),
    actions: state.actions.map((item) => ({
      actionId: item.actionId.trim(),
      actionType: item.actionType,
      title: item.title.trim(),
    })),
  }
}

function parseTarget(source: string) {
  try {
    const parsed = JSON.parse(source || '{}')
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}
  } catch {
    return {}
  }
}

function asObject(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {}
}
</script>

<style scoped>
.register-note { margin-bottom: 12px; }
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.form-editor {
  max-height: none;
  overflow: visible;
  padding-right: 4px;
}
.block {
  margin-bottom: 14px;
  padding: 12px 14px;
  border: 1px solid var(--mc-border-light, var(--el-border-color-lighter));
  border-radius: 12px;
  background: var(--mc-bg-elevated, var(--el-bg-color));
}
.block h3 {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: 13px;
}
.block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}
.grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 4px 14px;
}
.grid .span-2 { grid-column: 1 / -1; }
.card {
  margin-top: 10px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--mc-bg-muted, var(--el-fill-color-blank));
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}
.empty-hint {
  margin: 8px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.register-note code,
.validation code { font-family: var(--mc-mono, monospace); }
.json-input :deep(textarea) {
  font-family: var(--mc-mono, monospace);
  font-size: 11px;
  line-height: 1.55;
}
.validation {
  min-height: 20px;
  margin-top: 10px;
  color: var(--el-color-danger);
  font-size: 12px;
  line-height: 1.5;
}
.validation.valid { color: var(--el-color-success); }
.validation-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 5px;
  border-radius: 50%;
  background: currentColor;
}
@media (max-width: 860px) {
  .grid { grid-template-columns: 1fr; }
  .toolbar { align-items: flex-start; flex-direction: column; }
}
</style>
