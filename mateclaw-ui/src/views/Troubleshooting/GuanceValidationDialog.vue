<template>
  <el-dialog
    v-model="open"
    :title="TROUBLESHOOTING_UI_LABELS.guanceValidation"
    width="min(620px, calc(100vw - 32px))"
  >
    <el-alert type="warning" :closable="false" class="dialog-alert">
      两种验证都只读取真实观测数据，不保存原始日志，也不会使用演示数据兜底。先验证日志与调用链，再验证成功样本对照和结构化归纳；验证结果仍需负责人确认。
    </el-alert>
    <el-form label-position="top">
      <div class="validation-scope">
        <span>Workspace 资产</span>
        <code>{{ form.system }} / {{ form.service }}</code>
      </div>
      <el-form-item label="可安全插入 DQL 模板的搜索键">
        <el-input v-model="form.searchTerm" placeholder="例如 message_send_failed" />
        <p class="form-hint">仅允许资源标识符字符；未提供 errorCode 时不会用自由文本故障描述自动填充。</p>
      </el-form-item>
      <el-form-item label="时间窗口">
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
        <el-date-picker
          v-model="form.occurredAt"
          type="datetime"
          format="YYYY-MM-DD HH:mm:ss"
          value-format="YYYY-MM-DDTHH:mm:ssZ"
          placeholder="不选择则使用当前时间"
          clearable
          style="width: 100%"
        />
        <p class="form-hint">默认带入当前 Diagnosis 的故障时间；清空后，服务端会以点击验证时的当前时间作为查询结束点，不会改写 Diagnosis。</p>
      </el-form-item>
    </el-form>

    <div v-if="report" class="dialog-validation-result">
      <b>{{ guanceValidationLabel(report.stage) }}</b>
      <ul>
        <li v-for="step in report.steps" :key="step.signalKind">
          <code>{{ step.signalKind }}</code>
          <span>{{ step.detail }}</span>
          <time>{{ step.durationMs == null ? '未执行' : `${step.durationMs} ms` }}</time>
        </li>
      </ul>
      <p>端到端 {{ report.totalDurationMs }} ms</p>
      <small v-for="warning in report.warnings" :key="warning">{{ warning }}</small>
    </div>

    <div
      v-if="ownerAcceptance"
      class="dialog-validation-result owner-acceptance-result"
      :class="ownerAcceptanceTone(ownerAcceptance.status)"
    >
      <b>{{ ownerAcceptanceStateLabel(ownerAcceptance.status) }}</b>
      <p v-if="ownerAcceptance.acceptance">
        {{ ownerAcceptance.acceptance.acceptedBy }} ·
        {{ formatWorkbenchTime(ownerAcceptance.acceptance.acceptedAt) }}
      </p>
      <small>
        当前配置版本
        <code>{{ shortFingerprint(ownerAcceptance.currentBindingFingerprint) }}</code>
      </small>
      <small v-for="blocker in ownerAcceptance.blockers" :key="blocker">
        {{ guanceOwnerBlockerLabel(blocker) }}
      </small>
    </div>

    <div
      v-if="recordingTargets"
      class="dialog-validation-result"
      :class="recordingBatchReady ? 'passed' : 'blocked'"
    >
      <b>真实案例准备进度 · {{ recordingTargets.executableTargetCount }} / 20</b>
      <p>服务端已固定 {{ recordingTargets.frozenTargetCount }} 个待采集案例；只有与当前三项查询绑定完全匹配的案例才计入。</p>
      <small v-for="blocker in recordingTargets.blockers" :key="blocker">{{ blocker }}</small>
    </div>

    <div
      v-if="report?.stage === 'CANONICAL_CHAIN_OBSERVED' && ownerAcceptance?.status !== 'ACCEPTED' && canAcceptOwner && recordingBatchReady"
      class="t7-owner-checklist"
    >
      <b>负责人核实清单</b>
      <el-checkbox v-model="checklist.measurementAndFieldsVerified">已核实真实 measurement 与 canonical 字段映射</el-checkbox>
      <el-checkbox v-model="checklist.indexVerified">已核实索引、数据范围与查询资产</el-checkbox>
      <el-checkbox v-model="checklist.psIdJoinVerified">已确认 log_search 与 trace 使用同一 PS ID</el-checkbox>
      <el-checkbox v-model="checklist.timestampUnitVerified">已核实时间戳单位</el-checkbox>
      <el-checkbox v-model="checklist.timeWindowVerified">已核实时间窗口语义</el-checkbox>
      <el-checkbox v-model="checklist.dqlLatencyReviewed">已在 Guance 侧核对 DQL 延迟</el-checkbox>
      <el-checkbox v-model="checklist.legacyRouteConflictReviewed">已复核 903001 与历史 route key 冲突</el-checkbox>
      <p class="form-hint">提交时服务端会再次验证日志与调用链，并将确认记录绑定到当前查询模板、字段映射、端点和路由；不保存搜索键、PS ID 原文、查询语句、凭据或日志。</p>
    </div>
    <p
      v-else-if="report?.stage === 'CANONICAL_CHAIN_OBSERVED' && ownerAcceptance?.status !== 'ACCEPTED' && !recordingBatchReady"
      class="source-blocker"
    >真实案例尚未准备到 20 条，目前只能验证单条查询规则，不能完成负责人确认。</p>
    <p
      v-else-if="report?.stage === 'CANONICAL_CHAIN_OBSERVED' && ownerAcceptance?.status !== 'ACCEPTED'"
      class="source-blocker"
    >只有当前 Workspace 负责人可以确认；管理员可以执行只读验证，但不能代替负责人完成确认。</p>

    <div v-if="spinePreview" class="dialog-validation-result spine-dialog-result">
      <b>{{ guanceSpinePreviewLabel(spinePreview.stage) }}</b>
      <ul>
        <li v-for="step in spinePreview.steps" :key="step.signalKind">
          <code>{{ step.signalKind }}</code>
          <span>{{ spineStepStatusLabel(step.status) }}</span>
          <time>{{ step.collectedAt ? formatWorkbenchTime(step.collectedAt).slice(11) : '未执行' }}</time>
        </li>
      </ul>
      <div v-if="spinePreview.stage !== 'BLOCKED'" class="spine-facts">
        <p><span>调用链骨架</span><b>{{ spinePreview.serviceSequence.join(' → ') }}</b></p>
        <p><span>核心样本</span><b>{{ spinePreview.matchCount }} 条命中 · {{ spinePreview.traceEntries }} 个节点 · {{ spinePreview.anomalyCount }} 个异常点</b></p>
        <p v-if="spinePreview.contrast.available"><span>失败 ↔ 成功对照</span><b>{{ spinePreview.contrast.failureMatchCount }}/{{ spinePreview.contrast.failureSampleCount }}（{{ percent(spinePreview.contrast.failureRate) }}） ↔ {{ spinePreview.contrast.successMatchCount }}/{{ spinePreview.contrast.successSampleCount }}（{{ percent(spinePreview.contrast.successRate) }}）</b></p>
        <p v-else><span>失败 ↔ 成功对照</span><b>未取得，继续校准期</b></p>
        <p><span>应用侧总耗时</span><b>{{ spinePreview.totalDurationMs }} ms</b></p>
      </div>
      <small v-for="warning in spinePreview.warnings" :key="warning">{{ warning }}</small>
    </div>

    <template #footer>
      <el-button @click="open = false">关闭</el-button>
      <el-button plain :loading="validationLoading" :disabled="!form.searchTerm" @click="$emit('validate')">
        验证日志与调用链
      </el-button>
      <el-button type="primary" :loading="spinePreviewLoading" :disabled="!form.searchTerm" @click="$emit('preview-spine')">
        验证完整取证流程
      </el-button>
      <el-button
        v-if="report?.stage === 'CANONICAL_CHAIN_OBSERVED' && ownerAcceptance?.status !== 'ACCEPTED' && canAcceptOwner"
        type="success"
        :loading="acceptanceLoading"
        :disabled="!canAccept"
        @click="$emit('accept')"
      >确认当前数据源配置</el-button>
      <el-button
        v-if="spinePreview && spinePreview.stage !== 'BLOCKED' && canOpenEvaluation"
        type="success"
        plain
        @click="$emit('open-evaluation')"
      >进入{{ TROUBLESHOOTING_UI_LABELS.evaluation }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import type {
  EvidenceChainPreviewRequest,
  GuanceEvidenceAcceptanceChecklist,
  GuanceEvidenceAcceptanceView,
  GuanceEvidenceSpinePreview,
  GuanceEvidenceValidationReport,
  GuanceRecordingTargetCatalogView,
  GuanceSpinePreviewStepStatus,
} from '@/api'
import {
  guanceOwnerBlockerLabel,
  guanceSpinePreviewLabel,
  guanceValidationLabel,
} from './formalProjection'
import { EVIDENCE_WINDOW_OPTIONS } from './synthesisPreview'
import { TROUBLESHOOTING_UI_LABELS, formatWorkbenchTime } from './workbenchView'

defineProps<{
  report: GuanceEvidenceValidationReport | null
  spinePreview: GuanceEvidenceSpinePreview | null
  ownerAcceptance: GuanceEvidenceAcceptanceView | null
  recordingTargets: GuanceRecordingTargetCatalogView | null
  recordingBatchReady: boolean
  canAcceptOwner: boolean
  canAccept: boolean
  canOpenEvaluation: boolean
  validationLoading: boolean
  spinePreviewLoading: boolean
  acceptanceLoading: boolean
}>()

const open = defineModel<boolean>({ required: true })
const form = defineModel<EvidenceChainPreviewRequest>('form', { required: true })
const checklist = defineModel<GuanceEvidenceAcceptanceChecklist>('checklist', { required: true })

defineEmits<{
  validate: []
  'preview-spine': []
  accept: []
  'open-evaluation': []
}>()

function ownerAcceptanceStateLabel(value: GuanceEvidenceAcceptanceView['status']) {
  if (value === 'ACCEPTED') return '当前绑定已验收'
  if (value === 'STALE') return '配置变化，验收已过期'
  if (value === 'NOT_ACCEPTED') return '尚未完成负责人确认'
  return '当前绑定不可验收'
}

function ownerAcceptanceTone(value: GuanceEvidenceAcceptanceView['status']) {
  if (value === 'ACCEPTED') return 'passed'
  return value === 'STALE' ? 'blocked' : 'pending'
}

function shortFingerprint(value?: string | null) {
  return value ? `${value.slice(0, 12)}…` : '不可用'
}

function spineStepStatusLabel(value: GuanceSpinePreviewStepStatus) {
  if (value === 'CANONICAL_RESULT_OBSERVED') return '规范化证据已观测'
  if (value === 'MISSING') return '证据缺失 / 来源不可用'
  return '未执行'
}

function percent(value: number) {
  return `${Math.round(Number(value) * 100)}%`
}
</script>

<style scoped>
.dialog-alert { margin-bottom:14px; }
.form-hint { margin:4px 0 0; color:var(--mc-text-secondary); font-size:var(--mc-text-xs); }
.validation-scope { margin-bottom:14px; padding:10px 12px; border:1px solid var(--mc-border); border-radius:var(--mc-radius-xs); background:var(--mc-bg-elevated); }
.validation-scope span,.validation-scope code { display:block; }
.validation-scope span { color:var(--mc-text-secondary); font-size:var(--mc-text-xs); }
.validation-scope code { margin-top:5px; color:var(--mc-primary); font-size:var(--mc-text-xs); }
.dialog-validation-result { margin-top:12px; padding:12px; border:1px solid var(--mc-border); border-radius:var(--mc-radius-xs); background:var(--mc-bg-elevated); }
.dialog-validation-result>b { font-size:var(--mc-text-sm); }
.dialog-validation-result ul { margin:10px 0; padding:0; list-style:none; }
.dialog-validation-result li { display:grid; grid-template-columns:auto minmax(0,1fr) auto; gap:10px; padding:5px 0; color:var(--mc-text-secondary); font-size:var(--mc-text-xs); }
.dialog-validation-result li code { color:var(--mc-primary); }
.dialog-validation-result li time { color:var(--mc-text-secondary); font-family:var(--mc-mono,monospace); font-size:var(--mc-text-xs); white-space:nowrap; }
.dialog-validation-result>p { margin:8px 0; color:var(--mc-text-secondary); font-size:var(--mc-text-xs); font-weight:700; }
.dialog-validation-result>small { display:block; color:var(--mc-warning); font-size:var(--mc-text-xs); line-height:1.5; }
.owner-acceptance-result code { font-size:var(--mc-text-xs); overflow-wrap:anywhere; }
.owner-acceptance-result.passed { border-color:var(--mc-success); background:var(--mc-status-success-bg); }
.owner-acceptance-result.blocked { border-color:var(--mc-warning); background:var(--mc-status-warning-bg); }
.owner-acceptance-result.pending { border-color:var(--mc-border); background:var(--mc-status-info-bg); }
.t7-owner-checklist { display:grid; gap:7px; margin-top:12px; padding:12px; border:1px solid var(--mc-border); border-radius:var(--mc-radius-xs); background:var(--mc-status-info-bg); }
.t7-owner-checklist>b { margin-bottom:2px; color:var(--mc-status-info-text); font-size:var(--mc-text-sm); }
.t7-owner-checklist .el-checkbox { height:auto; margin-right:0; white-space:normal; }
.t7-owner-checklist .form-hint { margin-top:5px; line-height:1.6; }
.source-blocker { margin:7px 0; padding:7px 8px; border-radius:var(--mc-radius-xs); color:var(--mc-status-error-text); background:var(--mc-status-error-bg); font-size:var(--mc-text-xs); line-height:1.5; }
.spine-facts { display:grid; gap:7px; margin:10px 0; padding:10px; border-radius:var(--mc-radius-xs); background:var(--mc-status-info-bg); }
.spine-facts p { display:grid; grid-template-columns:110px minmax(0,1fr); gap:10px; margin:0; font-size:var(--mc-text-xs); line-height:1.5; }
.spine-facts span { color:var(--mc-text-secondary); }
.spine-facts b { color:var(--mc-text-secondary); overflow-wrap:anywhere; }

@media(max-width:760px){.spine-facts p{grid-template-columns:1fr;gap:2px}}
</style>
