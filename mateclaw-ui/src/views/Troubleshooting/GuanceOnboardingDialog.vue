<template>
  <el-dialog
    v-model="visible"
    title="P2 Guance 真源接入"
    width="min(760px, calc(100vw - 32px))"
  >
    <el-alert type="warning" :closable="false" class="onboarding-alert">
      向导只检查秘密无关的授权与 binding 状态，不接收、显示或保存 API Key。
      真实凭据必须由部署环境的密钥系统注入。
    </el-alert>

    <el-form label-position="top" class="onboarding-form">
      <div class="scope-grid">
        <el-form-item label="system">
          <el-input v-model="form.system" maxlength="128" placeholder="CSDP" />
        </el-form-item>
        <el-form-item label="service">
          <el-input v-model="form.service" maxlength="128" placeholder="csdp-session-service" />
        </el-form-item>
      </div>
      <div class="scope-grid three">
        <el-form-item label="T7 安全搜索键">
          <el-input v-model="form.searchTerm" maxlength="128" placeholder="message_send_failed" />
        </el-form-item>
        <el-form-item label="证据窗口">
          <el-select v-model="form.window" style="width: 100%">
            <el-option
              v-for="option in EVIDENCE_WINDOW_OPTIONS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="故障时间（可选）">
          <el-input v-model="form.occurredAt" placeholder="ISO-8601" />
        </el-form-item>
      </div>
      <p v-if="scopeErrors.length" class="scope-error">{{ scopeErrors.join('；') }}</p>
      <p v-if="!safeSearchTerm" class="scope-error">T7 搜索键必须是 1–128 位安全资源标识符。</p>
    </el-form>

    <section v-if="guide" class="configuration-guide">
      <div class="guide-head">
        <div>
          <span>外部配置骨架</span>
          <b>Workspace {{ workspaceId }} · 不含凭据</b>
        </div>
        <el-button size="small" text @click="copyGuide">复制全部</el-button>
      </div>
      <pre>{{ guide.externalConfig }}</pre>
      <pre>{{ guide.runtimeEnvironment }}</pre>
      <p>binding 名、measurement、字段映射与查询模板必须在 T7 内网核实；向导不会把占位符写回服务端。</p>
    </section>

    <section v-if="currentReadiness" class="readiness-result">
      <div class="readiness-title">
        <div><span>当前实时状态</span><b>{{ currentReadiness.system }} / {{ currentReadiness.service }}</b></div>
        <strong :class="readinessTone(currentReadiness.status)">{{ guanceReadinessLabel(currentReadiness.status) }}</strong>
      </div>
      <div class="readiness-meta">
        <span>适配器 {{ currentReadiness.adapterEnabled ? '已启用' : '未启用' }}</span>
        <span>端点 {{ currentReadiness.endpointConfigured ? '已配置' : '未配置' }}</span>
        <span>Workspace 资产 {{ currentReadiness.uniqueAssetAuthorized ? '唯一授权' : '未唯一授权' }}</span>
        <span>运行时凭据 {{ credentialLabel(currentReadiness.credentialState) }}</span>
      </div>
      <ol v-if="acceptanceProgress" class="onboarding-ladder">
        <li v-for="stage in acceptanceProgress.stages" :key="stage.code">
          <code>{{ stage.code }}</code>
          <div><b>{{ stage.title }}</b><small>{{ stage.detail }}</small></div>
          <strong :class="acceptanceTone(stage.state)">{{ guanceAcceptanceStateLabel(stage.state) }}</strong>
        </li>
      </ol>
      <ul class="onboarding-signals">
        <li v-for="signal in currentReadiness.signals" :key="signal.signalKind">
          <code>{{ signal.signalKind }}</code>
          <span>{{ guanceSignalLabel(signal.status) }}</span>
          <small>{{ signal.bindingRef || signal.detail || '无 binding' }}</small>
        </li>
      </ul>
      <p v-if="acceptanceProgress" class="next-action"><b>下一步</b>{{ acceptanceProgress.nextAction }}</p>
      <p v-for="blocker in currentReadiness.blockers" :key="blocker" class="blocker">{{ blocker }}</p>
    </section>
    <p v-else-if="inspectedScopeKey" class="stale-result">Workspace 或 system/service 已修改，请重新检查接入条件。</p>
    <p v-if="loadError" class="scope-error">{{ loadError }}</p>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button plain :loading="loading" :disabled="!canInspect" @click="inspect">检查接入条件</el-button>
      <el-button
        type="primary"
        :disabled="!canEnterValidation"
        @click="startValidation"
      >进入 T7 只读验收</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  troubleshootingApi,
  type EvidenceChainPreviewRequest,
  type GuanceCredentialState,
  type GuanceEvidenceAcceptanceView,
  type GuanceEvidenceReadiness,
  type GuanceReadinessStatus,
} from '@/api'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import { copyToClipboard } from '@/utils/clipboard'
import {
  canStartGuanceValidation,
  guanceAcceptanceProgress,
  guanceAcceptanceStateLabel,
  guanceReadinessLabel,
  guanceSignalLabel,
} from './formalProjection'
import { EVIDENCE_WINDOW_OPTIONS } from './synthesisPreview'
import {
  buildGuanceOnboardingGuide,
  guanceOnboardingScopeErrors,
  guanceOnboardingScopeKey,
  isSafeGuanceSearchTerm,
  type GuanceOnboardingValidationPayload,
} from './guanceOnboarding'

const props = defineProps<{
  modelValue: boolean
  initialRequest: EvidenceChainPreviewRequest
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  'start-validation': [payload: GuanceOnboardingValidationPayload]
}>()

const workspaceStore = useWorkspaceStore()
const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})
const form = reactive<EvidenceChainPreviewRequest>({
  system: 'CSDP',
  service: 'csdp-session-service',
  searchTerm: 'message_send_failed',
  window: '-15m',
  occurredAt: null,
})
const readiness = ref<GuanceEvidenceReadiness | null>(null)
const ownerAcceptance = ref<GuanceEvidenceAcceptanceView | null>(null)
const inspectedScopeKey = ref('')
const loading = ref(false)
const loadError = ref('')
let requestVersion = 0

const workspaceId = computed(() => String(workspaceStore.currentWorkspaceId || ''))
const scopeKey = computed(() => guanceOnboardingScopeKey({
  workspaceId: workspaceId.value,
  system: form.system,
  service: form.service,
}))
const scopeErrors = computed(() => guanceOnboardingScopeErrors({
  workspaceId: workspaceId.value,
  system: form.system,
  service: form.service,
}))
const canInspect = computed(() => scopeErrors.value.length === 0)
const guide = computed(() => {
  if (!canInspect.value) return null
  return buildGuanceOnboardingGuide({
    workspaceId: workspaceId.value,
    system: form.system,
    service: form.service,
  })
})
const currentReadiness = computed(() => inspectedScopeKey.value === scopeKey.value
  ? readiness.value
  : null)
const currentOwnerAcceptance = computed(() => inspectedScopeKey.value === scopeKey.value
  ? ownerAcceptance.value
  : null)
const acceptanceProgress = computed(() => currentReadiness.value
  ? guanceAcceptanceProgress(currentReadiness.value, currentOwnerAcceptance.value)
  : null)
const safeSearchTerm = computed(() => isSafeGuanceSearchTerm(form.searchTerm))
const canEnterValidation = computed(() => Boolean(currentReadiness.value
  && canStartGuanceValidation(currentReadiness.value.status)
  && safeSearchTerm.value
  && form.window))

watch(visible, (open) => {
  requestVersion += 1
  readiness.value = null
  ownerAcceptance.value = null
  inspectedScopeKey.value = ''
  loadError.value = ''
  loading.value = false
  if (!open) return
  Object.assign(form, props.initialRequest)
  void inspect()
})

async function inspect() {
  if (!canInspect.value) return
  const version = ++requestVersion
  const requestedKey = scopeKey.value
  loading.value = true
  loadError.value = ''
  try {
    const scope = { system: form.system.trim(), service: form.service.trim() }
    const [readinessResponse, acceptanceResponse] = await Promise.all([
      troubleshootingApi.evidenceReadiness(scope),
      troubleshootingApi.guanceEvidenceAcceptance(scope),
    ])
    if (version !== requestVersion || !visible.value) return
    readiness.value = readinessResponse.data
    ownerAcceptance.value = acceptanceResponse.data
    inspectedScopeKey.value = requestedKey
  } catch (error) {
    if (version !== requestVersion || !visible.value) return
    readiness.value = null
    ownerAcceptance.value = null
    inspectedScopeKey.value = requestedKey
    loadError.value = `接入条件检查失败：${error instanceof Error ? error.message : String(error)}`
  } finally {
    if (version === requestVersion) loading.value = false
  }
}

async function copyGuide() {
  if (!guide.value) return
  try {
    await copyToClipboard(`${guide.value.externalConfig}\n\n${guide.value.runtimeEnvironment}`)
    ElMessage.success('已复制秘密无关的 Guance 接入骨架')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : String(error))
  }
}

function startValidation() {
  if (!canEnterValidation.value || !currentReadiness.value || !currentOwnerAcceptance.value) return
  emit('start-validation', {
    request: {
      system: form.system.trim(),
      service: form.service.trim(),
      searchTerm: form.searchTerm.trim(),
      window: form.window,
      occurredAt: form.occurredAt?.trim() || null,
    },
    ownerAcceptance: currentOwnerAcceptance.value,
  })
}

function readinessTone(value: GuanceReadinessStatus) {
  return canStartGuanceValidation(value) ? 'ready' : 'blocked'
}
function credentialLabel(value: GuanceCredentialState) {
  if (value === 'CONFIGURED') return '已注入'
  if (value === 'MISSING') return '未注入'
  return '未检查（先通过授权门）'
}
function acceptanceTone(value: 'BLOCKED' | 'READY' | 'OWNER_EVIDENCE_REQUIRED') {
  if (value === 'READY') return 'ready'
  if (value === 'OWNER_EVIDENCE_REQUIRED') return 'pending'
  return 'blocked'
}
</script>

<style scoped>
.onboarding-alert { margin-bottom: 16px; }
.scope-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.scope-grid.three { grid-template-columns: 1.1fr .75fr 1fr; }
.scope-error { margin: 0 0 12px; color: var(--el-color-danger); font-size: 12px; }
.configuration-guide,
.readiness-result { margin-top: 14px; padding: 14px; border: 1px solid var(--el-border-color-light); border-radius: 8px; background: var(--el-fill-color-lighter); }
.guide-head,
.readiness-title { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.guide-head div,
.readiness-title div { display: grid; gap: 4px; }
.guide-head span,
.readiness-title span { color: var(--el-text-color-secondary); font-size: 11px; }
.guide-head b,
.readiness-title b { font-size: 13px; }
.configuration-guide pre { margin: 10px 0 0; padding: 12px; overflow: auto; border-radius: 6px; color: #d0d5dd; background: #101828; font: 11px/1.55 ui-monospace, SFMono-Regular, Consolas, monospace; }
.configuration-guide p,
.stale-result { margin: 10px 0 0; color: var(--el-text-color-secondary); font-size: 11px; line-height: 1.55; }
.readiness-title > strong { padding: 4px 7px; border-radius: 999px; font-size: 10px; }
.ready { color: #067647; background: #ecfdf3; }
.pending { color: #175cd3; background: #eff4ff; }
.blocked { color: #b54708; background: #fffaeb; }
.readiness-meta { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 12px; }
.readiness-meta span { padding: 4px 7px; border-radius: 4px; color: var(--el-text-color-secondary); background: var(--el-bg-color); font-size: 10px; }
.onboarding-ladder,
.onboarding-signals { margin: 12px 0 0; padding: 0; list-style: none; }
.onboarding-ladder li { display: grid; grid-template-columns: 30px 1fr auto; gap: 10px; align-items: start; padding: 9px 0; border-top: 1px solid var(--el-border-color-lighter); }
.onboarding-ladder div { display: grid; gap: 3px; }
.onboarding-ladder small { color: var(--el-text-color-secondary); line-height: 1.45; }
.onboarding-ladder strong { padding: 3px 6px; border-radius: 4px; font-size: 9px; white-space: nowrap; }
.onboarding-signals li { display: grid; grid-template-columns: 150px 1fr; gap: 4px 10px; padding: 7px 0; border-top: 1px solid var(--el-border-color-lighter); }
.onboarding-signals span { font-size: 11px; }
.onboarding-signals small { grid-column: 1 / -1; color: var(--el-text-color-secondary); word-break: break-all; }
.next-action { margin: 12px 0 0; padding: 10px; color: #344054; background: #eff4ff; font-size: 11px; line-height: 1.55; }
.next-action b { display: block; margin-bottom: 3px; color: #175cd3; }
.blocker { margin: 7px 0 0; color: #b54708; font-size: 10px; }
@media (max-width: 720px) {
  .scope-grid,
  .scope-grid.three { grid-template-columns: 1fr; gap: 0; }
}
</style>
