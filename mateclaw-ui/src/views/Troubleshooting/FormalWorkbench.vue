<template>
  <div
    class="formal-workbench"
    :class="{
      'traditional-list-mode': viewMode === 'LIST',
      'full-detail-mode': viewMode === 'DETAIL',
      'capability-workspace-mode': capabilityWorkspaceActive,
    }"
  >
    <EvaluationSampleLedgerWorkspace
      v-if="evaluationWorkspaceActive"
      :current-diagnosis-id="current?.diagnosis.diagnosisId || null"
      :current-diagnosis-status="current?.diagnosis.status || null"
      :current-diagnosis-rehearsal="current?.diagnosis.rehearsal || false"
      :capture-context="evaluationCaptureContext"
      :replay-capture-context="replayEvaluationCaptureContextValue"
      :capture-enabled="canCaptureEvaluationSample"
      :capture-disabled-reason="evaluationCaptureDisabledReason"
      :replay-capture-enabled="canCaptureReplayEvaluationSample"
      :replay-capture-disabled-reason="replayCaptureDisabledReason"
      :start-pilot-setup="route.query.pilotSetup === '1'"
      @back="closeCapabilityWorkspace"
      @open-diagnosis="openDiagnosisFromEvaluation"
      @open-validation="openCurrentEvaluationValidation"
    />

    <CaseKnowledgeImportWorkspace
      v-else-if="caseKnowledgeWorkspaceActive"
      v-model:form="caseKnowledgeImportForm"
      :knowledge-bases="caseKnowledgeBases"
      :knowledge-bases-loading="caseKnowledgeBasesLoading"
      :result="caseKnowledgeImportResult"
      :vector-status="caseKnowledgeVectorStatus"
      :loading="caseKnowledgeImportLoading"
      :can-submit="canSubmitCaseKnowledgeImport"
      @back="closeCapabilityWorkspace"
      @refresh="prepareCaseKnowledgeWorkspace(false)"
      @manage-wiki="router.push('/wiki')"
      @submit="importHistoricalCases"
    />

    <DiagnosisListView
      v-else-if="viewMode === 'LIST'"
      v-model:status-filter="statusFilter"
      :rows="rows"
      :loading="listLoading"
      :can-operate="canOperateTroubleshooting"
      :can-manage="canManageTroubleshooting"
      :pilot-plan="pilotPlan"
      :pilot-plan-loading="pilotPlanLoading"
      :pilot-plan-error="pilotPlanError"
      @refresh="store.loadList(false)"
      @guide="openFirstUseGuide"
      @launch="openTroubleshootingScenario"
      @open-diagnosis="openDiagnosisFromList"
      @pilot-refresh="loadPilotPlan"
      @pilot-setup="openPilotSetup"
      @pilot-launch-formal="launchFormalPilotIncident"
      @pilot-open-diagnosis="openPilotDiagnosis"
      @pilot-open-evaluation="openPilotEvaluation"
      @switch-view="switchWorkbenchView('QUEUE')"
    />

    <template v-else>
    <DiagnosisQueuePanel
      v-if="shouldShowQueuePanel(viewMode)"
      v-model:status-filter="statusFilter"
      v-model:investigation-mode-filter="investigationModeFilter"
      :rows="rows"
      :selected-id="selectedId"
      :loading="listLoading"
      :can-operate="canOperateTroubleshooting"
      :can-manage="canManageTroubleshooting"
      @refresh="store.loadList(false)"
      @launch="openTroubleshootingScenario"
      @select-diagnosis="store.selectDiagnosis"
      @switch-view="switchWorkbenchView('LIST')"
    />

    <main v-loading="detailLoading" class="work-area">
      <div v-if="!business || !developer || !current" class="detail-empty">
        <div class="empty-mark">MC</div>
        <h1>从粘贴告警开始</h1>
        <p>点「发起排障」，把告警原文贴进去即可。</p>
        <el-button
          v-if="canOperateTroubleshooting || canManageTroubleshooting"
          type="primary"
          plain
          :icon="Plus"
          @click="openTroubleshootingScenario"
        >{{ TROUBLESHOOTING_UI_LABELS.launch }}</el-button>
      </div>

      <template v-else>
        <header class="work-head">
          <div>
            <span class="eyebrow">
              {{ current.diagnosis.rehearsal ? '演练' : '正式排障' }}
            </span>
            <h1>{{ business.problem }}</h1>
          </div>
          <div class="work-head-actions">
            <el-button
              v-if="viewMode === 'DETAIL'"
              size="small"
              plain
              @click="switchWorkbenchView('LIST')"
            >返回排障列表</el-button>
            <el-button size="small" :icon="Refresh" text @click="store.reload">刷新</el-button>
            <el-button v-if="canManageTroubleshooting" size="small" plain @click="openEvaluationLedger">{{ TROUBLESHOOTING_UI_LABELS.evaluation }}</el-button>

          </div>
        </header>

        <nav class="perspective-switch" aria-label="排障详情阅读视角">
          <div class="perspective-switch__copy">
            <b>当前阅读视角</b>
            <span>{{ detailPerspective === 'developer'
              ? '优先看根因、证据和下一步核实方向'
              : '优先看影响、当前判断和是否需要升级三线' }}</span>
          </div>
          <div class="perspective-switch__tabs" role="group" aria-label="切换详情视角">
            <button
              type="button"
              :class="{ active: detailPerspective === 'developer' }"
              :aria-pressed="detailPerspective === 'developer'"
              @click="setDetailPerspective('developer')"
            >三线开发视角 <small>默认</small></button>
            <button
              type="button"
              :class="{ active: detailPerspective === 'support' }"
              :aria-pressed="detailPerspective === 'support'"
              @click="setDetailPerspective('support')"
            >二线保障视角</button>
          </div>
        </nav>

        <div
          v-if="evidenceSourcePresentation.showBanner"
          class="fixture-banner"
          :class="`source-${evidenceSourcePresentation.kind.toLowerCase()}`"
        >
          <span class="fixture-dot" />
          <b>{{ evidenceSourcePresentation.title }}</b>
          <span>{{ evidenceSourcePresentation.detail }}</span>
        </div>

        <BusinessSummaryCard
          :business="business"
          :failure-breakdown="developer.failureBreakdown || null"
          :perspective="detailPerspective"
          :closure="closure"
          :can-operate="canOperateTroubleshooting"
          :can-transfer="canTransfer"
          :can-close="canClose"
          :can-evaluate="canManageTroubleshooting"
          :rehearsal="current.diagnosis.rehearsal"
          :action-loading="actionLoading"
          :status="current.diagnosis.status"
          @confirm="store.confirmDiagnosis"
          @transfer="transferOpen = true"
          @close="closeOpen = true"
          @evaluate="openEvaluationLedger"
        />

        <details v-if="detailPerspective === 'developer'" class="question-progress-fold">
          <summary>
            <div>
              <b>查看排障进度</b>
              <small>按“发生了什么 → 查到了什么 → 结论 → 人工处理”复核</small>
            </div>
            <span>5 个检查点</span>
          </summary>
          <FiveQuestionRail :items="fiveQuestionItems" />
        </details>

        <ScenarioEvidenceRunCard
          v-if="evidenceSpineScenarioPresentation"
          :diagnosis="current.diagnosis"
          :can-operate="canOperateTroubleshooting"
          :loading="scenarioEvidenceLoading"
          :scenario-name="evidenceSpineScenarioPresentation.name"
          :failure-step="evidenceSpineScenarioPresentation.failureStep"
          @run="runScenarioEvidence"
        />

        <TopologyEvidenceCard
          v-if="deploymentTopologyRequired"
          :runs="topologyProbeRuns"
          :diagnosis-id="business?.diagnosisId"
          :can-manage="canManageTroubleshooting"
          :disabled="current.diagnosis.status === 'CLOSED'"
          @run-probe="deploymentTopologyOpen = true"
        />

        <DeveloperEvidencePanel
          v-if="detailPerspective === 'developer'"
          :developer="developer"
          :business="business"
          :current="current"
          :guance-readiness="guanceReadiness"
          :guance-acceptance="guanceAcceptance"
          :guance-owner-acceptance="guanceOwnerAcceptance"
          :readiness-loading="readinessLoading"
          :readiness-error="readinessError"
          :can-manage="canManageTroubleshooting"
          :can-approve-action="canApprove"
          :can-record-outcome-action="canRecordOutcome"
          @open-data-source-validation="openDataSourceValidation"
          @open-evaluation="openEvaluationLedger"
          @approve="openApprove"
          @record-outcome="openOutcome"
        />
      </template>
    </main>
    </template>

    <FirstUseGuideDrawer
      v-model="firstUseGuideOpen"
      @start="startFirstUseRehearsal"
    />

    <TroubleshootingScenarioDialog
      v-model="scenarioLauncherOpen"
      :can-operate="canOperateTroubleshooting"
      :can-manage="canManageTroubleshooting"
      @select="startTroubleshootingScenario"
      @back-to-incident="openIncidentIntake"
    />

    <IncidentReportDialog
      v-model="incidentReportOpen"
      v-model:form="incidentReportForm"
      :route-preview="incidentRoutePreview"
      :open-discovery-readiness="openDiscoveryReadiness"
      :loading="incidentReportLoading"
      :can-submit="canSubmitIncidentReport"
      @pick-scenario="openKnownScenarioPicker"
      @pick-conversation="openConversationIntake"
      @submit="reportIncident"
    />

    <ConversationIntakeDialog
      v-model="conversationIntakeOpen"
      @switch-form="openIncidentIntakeFromConversation"
      @ready="onConversationReady"
    />

    <MessageSendScenarioDialog
      v-model="messageSendScenarioOpen"
      v-model:form="messageSendScenarioForm"
      :loading="messageSendScenarioLoading"
      :can-submit="canSubmitMessageSendScenario"
      @open-playbooks="openPlaybooks"
      @submit="createMessageSendScenario"
    />

    <CtiCreateConversationScenarioDialog
      v-model="ctiCreateConversationScenarioOpen"
      v-model:form="ctiCreateConversationScenarioForm"
      :loading="ctiCreateConversationScenarioLoading"
      :can-submit="canSubmitCtiCreateConversationScenario"
      @open-playbooks="openPlaybooks"
      @submit="createCtiCreateConversationScenario"
    />

    <DeploymentTopologyScenarioDialog
      v-model="deploymentTopologyScenarioOpen"
      v-model:form="deploymentTopologyScenarioForm"
      :selector="deploymentTopologySelector"
      :loading="deploymentTopologyScenarioLoading"
      :can-submit="canSubmitDeploymentTopologyScenario"
      @open-playbooks="openPlaybooks"
      @submit="createDeploymentTopologyScenario"
    />

    <GuanceOnboardingDialog
      v-model="guanceOnboardingOpen"
      :initial-request="guanceOnboardingInitialRequest"
      @start-validation="startGuanceValidationFromOnboarding"
    />

    <DeploymentTopologySopDialog
      v-if="deploymentTopologyRequired"
      v-model="deploymentTopologyOpen"
      :diagnosis-id="business?.diagnosisId"
      @completed="handleTopologyProbeCompleted"
    />

    <GuanceValidationDialog
      v-model="guanceValidationOpen"
      v-model:form="guanceValidationForm"
      v-model:checklist="guanceAcceptanceChecklist"
      :report="validationDialogReport"
      :spine-preview="validationDialogSpinePreview"
      :owner-acceptance="validationDialogOwnerAcceptance"
      :recording-targets="guanceRecordingTargets"
      :recording-batch-ready="recordingBatchReady"
      :can-accept-owner="canAcceptGuanceOwner"
      :can-accept="canAcceptGuance"
      :can-open-evaluation="validationCanOpenCurrentEvaluationLedger"
      :validation-loading="validationLoading"
      :spine-preview-loading="spinePreviewLoading"
      :acceptance-loading="acceptanceLoading"
      @validate="validateGuance"
      @preview-spine="previewGuanceSpine"
      @accept="acceptGuance"
      @open-evaluation="openEvaluationLedger"
    />

    <SynthesisPreviewDialog v-if="!evaluationWorkspaceActive" v-model="synthesisPreviewOpen" />

    <TransferDialog
      v-model="transferOpen"
      :diagnosis-id="selectedId"
      @submitted="store.reload"
    />

    <ApproveActionDialog
      v-model="approveOpen"
      :diagnosis-id="selectedId"
      :target-action="targetAction"
      @submitted="store.reload"
    />

    <RecordOutcomeDialog
      v-model="outcomeOpen"
      :diagnosis-id="selectedId"
      :target-action="targetAction"
      @submitted="store.reload"
    />

    <CloseDiagnosisDialog
      v-model="closeOpen"
      :diagnosis-id="selectedId"
      @submitted="store.reload"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useRoute, useRouter } from 'vue-router'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus/es/components/message/index'
import { vLoading } from 'element-plus/es/components/loading/index'
import {
  troubleshootingApi,
  wikiApi,
  type DiagnosisSummary,
  type EvidenceChainPreviewRequest,
  type GuanceEvidenceAcceptanceView,
  type HistoricalCaseKnowledgeImportResult,
  type OpenDiscoveryReadiness,
  type RecommendedAction,
  type StoredDiagnosis,
  type TopologyProbeEvidenceRun,
  type TroubleshootingPilotModuleScope,
  type TroubleshootingPilotPlan,
} from '@/api'
import { useTroubleshootingStore } from '@/stores/useTroubleshootingStore'
import { diagnosisEvidenceSourcePresentation } from './formalProjection'
import {
  buildFormalIncidentReport,
  EMPTY_FORMAL_INCIDENT,
  formalIncidentFormErrors,
  formalIncidentRoutePreview,
  type FormalIncidentForm,
} from './incidentReport'
import {
  buildDeploymentTopologyScenarioRequest,
  deploymentTopologyScenarioLoadFailureMessage,
  deploymentTopologyScenarioFormErrors,
  deploymentTopologyScenarioProjectionLoaded,
  deploymentTopologyScenarioSelector,
  EMPTY_DEPLOYMENT_TOPOLOGY_SCENARIO,
  type DeploymentTopologyScenarioForm,
} from './deploymentTopologyScenario'
import { isEvidenceSynthesisFocus } from './synthesisPreview'
import {
  normalizeDiagnosisPerspective,
  type DiagnosisPerspective,
} from './diagnosisPerspective'
import {
  normalizeWorkbenchOverlayCapability,
} from './workbenchCapabilityMenu'
import EvaluationSampleLedgerWorkspace from './EvaluationSampleLedgerWorkspace.vue'
import SynthesisPreviewDialog from './SynthesisPreviewDialog.vue'
import GuanceOnboardingDialog from './GuanceOnboardingDialog.vue'
import GuanceValidationDialog from './GuanceValidationDialog.vue'
import DeploymentTopologySopDialog from './DeploymentTopologySopDialog.vue'
import DiagnosisListView from './DiagnosisListView.vue'
import DiagnosisQueuePanel from './DiagnosisQueuePanel.vue'
import FirstUseGuideDrawer from './FirstUseGuideDrawer.vue'
import TroubleshootingScenarioDialog from './TroubleshootingScenarioDialog.vue'
import CaseKnowledgeImportWorkspace from './CaseKnowledgeImportWorkspace.vue'
import IncidentReportDialog from './IncidentReportDialog.vue'
import ConversationIntakeDialog from './ConversationIntakeDialog.vue'
import MessageSendScenarioDialog from './MessageSendScenarioDialog.vue'
import CtiCreateConversationScenarioDialog from './CtiCreateConversationScenarioDialog.vue'
import DeploymentTopologyScenarioDialog from './DeploymentTopologyScenarioDialog.vue'
import TransferDialog from './TransferDialog.vue'
import ApproveActionDialog from './ApproveActionDialog.vue'
import RecordOutcomeDialog from './RecordOutcomeDialog.vue'
import CloseDiagnosisDialog from './CloseDiagnosisDialog.vue'
import BusinessSummaryCard from './BusinessSummaryCard.vue'
import FiveQuestionRail from './FiveQuestionRail.vue'
import { buildFiveQuestionRail } from './fiveQuestionProgress'
import ScenarioEvidenceRunCard from './ScenarioEvidenceRunCard.vue'
import TopologyEvidenceCard from './TopologyEvidenceCard.vue'
import DeveloperEvidencePanel from './DeveloperEvidencePanel.vue'
import {
  canAttachGuanceResultToDiagnosis,
  evidenceOnboardingRequestForScope,
  type GuanceOnboardingValidationPayload,
  type GuanceValidationOrigin,
} from './guanceOnboarding'
import { useGuanceValidationDialog } from './useGuanceValidationDialog'
import {
  EMPTY_MESSAGE_SEND_SCENARIO,
  MESSAGE_SEND_SCENARIO_KEY,
  MESSAGE_SEND_SCENARIO_SELECTOR,
  buildMessageSendScenarioRequest,
  canRunMessageSendEvidence as canRunMessageSendEvidenceForDiagnosis,
  isMessageSendScenarioDiagnosis,
  messageSendScenarioFormErrors,
  type MessageSendScenarioForm,
} from './messageSendScenario'
import {
  CTI_CREATE_CONVERSATION_SCENARIO,
  EMPTY_CTI_CREATE_CONVERSATION_SCENARIO,
  buildCtiCreateConversationScenarioRequest,
  canRunCtiCreateConversationEvidence,
  ctiCreateConversationScenarioFormErrors,
  isCtiCreateConversationDiagnosis,
  type CtiCreateConversationScenarioForm,
} from './ctiCreateConversationScenario'
import {
  DEFAULT_CASE_KNOWLEDGE_IMPORT_LIMIT,
  caseKnowledgeImportCanSubmit,
  caseKnowledgeVectorMessage,
} from './caseKnowledgeImport'
import {
  TROUBLESHOOTING_UI_LABELS,
  isDiagnosisViewMode,
  resolveWorkbenchView,
  shouldShowQueuePanel,
  type TroubleshootingScenarioCommand,
  type WorkbenchCapabilityCommand,
  type WorkbenchViewSwitchMode,
} from './workbenchView'

const router = useRouter()
const route = useRoute()
const store = useTroubleshootingStore()
const {
  canOperateTroubleshooting, canManageTroubleshooting, canAcceptGuanceOwner,
  rows, selectedId, current, projection,
  topologyProbeRuns, statusFilter, investigationModeFilter, viewMode,
  listLoading, detailLoading, actionLoading, readinessLoading,
  guanceReadiness,
  guanceOwnerAcceptance, readinessError,
  guanceRecordingTargets,
  business, developer, closure,
  deploymentTopologyRequired,
  canTransfer, canClose, guanceAcceptance,
  currentDiagnosisEvidenceLookup, evaluationCaptureContext,
  replayEvaluationCaptureContextValue,
  canCaptureEvaluationSample, evaluationCaptureDisabledReason,
  canCaptureReplayEvaluationSample, replayCaptureDisabledReason,
} = storeToRefs(store)

const incidentReportLoading = ref(false)
const messageSendScenarioLoading = ref(false)
const ctiCreateConversationScenarioLoading = ref(false)
const scenarioEvidenceLoading = ref(false)
const caseKnowledgeImportLoading = ref(false)
const caseKnowledgeBasesLoading = ref(false)
const deploymentTopologyScenarioLoading = ref(false)
const pilotPlan = ref<TroubleshootingPilotPlan | null>(null)
const pilotPlanLoading = ref(true)
const pilotPlanError = ref('')

const scenarioLauncherOpen = ref(false)
const firstUseGuideOpen = ref(false)
const incidentReportOpen = ref(false)
const conversationIntakeOpen = ref(false)
const openDiscoveryReadiness = ref<OpenDiscoveryReadiness | null>(null)
const messageSendScenarioOpen = ref(false)
const ctiCreateConversationScenarioOpen = ref(false)
const deploymentTopologyScenarioOpen = ref(false)
const guanceOnboardingOpen = ref(false)
const deploymentTopologyOpen = ref(false)
const synthesisPreviewOpen = ref(false)
const transferOpen = ref(false)
const approveOpen = ref(false)
const outcomeOpen = ref(false)
const closeOpen = ref(false)
const targetAction = ref<RecommendedAction | null>(null)
const incidentReportForm = reactive<FormalIncidentForm>({ ...EMPTY_FORMAL_INCIDENT })
const messageSendScenarioForm = reactive<MessageSendScenarioForm>({
  ...EMPTY_MESSAGE_SEND_SCENARIO,
})
const ctiCreateConversationScenarioForm = reactive<CtiCreateConversationScenarioForm>({
  ...EMPTY_CTI_CREATE_CONVERSATION_SCENARIO,
})
type CaseKnowledgeBaseOption = {
  id: string | number
  name: string
  status: string
  pageCount?: number
  rawCount?: number
}
const caseKnowledgeBases = ref<CaseKnowledgeBaseOption[]>([])
const caseKnowledgeImportForm = reactive({
  knowledgeBaseId: '',
  limit: DEFAULT_CASE_KNOWLEDGE_IMPORT_LIMIT,
})
const caseKnowledgeImportResult = ref<HistoricalCaseKnowledgeImportResult | null>(null)
const deploymentTopologyScenarioForm = reactive<DeploymentTopologyScenarioForm>({
  ...EMPTY_DEPLOYMENT_TOPOLOGY_SCENARIO,
})

const {
  open: guanceValidationOpen,
  validationLoading,
  spinePreviewLoading,
  acceptanceLoading,
  report: validationDialogReport,
  spinePreview: validationDialogSpinePreview,
  ownerAcceptance: validationDialogOwnerAcceptance,
  origin: guanceValidationOrigin,
  form: guanceValidationForm,
  checklist: guanceAcceptanceChecklist,
  begin: beginGuanceValidationSession,
  capture: captureValidationSession,
} = useGuanceValidationDialog()
const guanceOnboardingInitialRequest = computed<EvidenceChainPreviewRequest>(() => {
  const base = currentDiagnosisEvidenceLookup.value || {
    system: 'CSDP',
    service: 'csdp-session-service',
    searchTerm: 'message_send_failed',
    window: '-15m',
    occurredAt: null,
  }
  return evidenceOnboardingRequestForScope(base, {
    system: route.query.system,
    service: route.query.service,
  })
})
const evidenceSourcePresentation = computed(() => diagnosisEvidenceSourcePresentation(
  current.value?.diagnosis.evidence ?? [],
))
const fiveQuestionItems = computed(() => {
  if (!business.value || !developer.value) return []
  return buildFiveQuestionRail(business.value, developer.value)
})
const detailPerspective = computed(() => normalizeDiagnosisPerspective(route.query.perspective))
const validationCanOpenCurrentEvaluationLedger = computed(() =>
  isCurrentDiagnosisValidationRequest(guanceValidationForm))
const incidentReportErrors = computed(() => formalIncidentFormErrors(incidentReportForm))
const incidentRoutePreview = computed(() => formalIncidentRoutePreview(incidentReportForm))
const canSubmitIncidentReport = computed(() => canOperateTroubleshooting.value
  && incidentReportErrors.value.length === 0)
const messageSendScenarioErrors = computed(() =>
  messageSendScenarioFormErrors(messageSendScenarioForm))
const canSubmitMessageSendScenario = computed(() => canOperateTroubleshooting.value
  && messageSendScenarioErrors.value.length === 0)
const ctiCreateConversationScenarioErrors = computed(() =>
  ctiCreateConversationScenarioFormErrors(ctiCreateConversationScenarioForm))
const canSubmitCtiCreateConversationScenario = computed(() => canOperateTroubleshooting.value
  && ctiCreateConversationScenarioErrors.value.length === 0)
const evidenceSpineScenarioPresentation = computed(() => {
  const diagnosis = current.value?.diagnosis
  if (isCtiCreateConversationDiagnosis(diagnosis)) {
    return {
      name: CTI_CREATE_CONVERSATION_SCENARIO.name,
      failureStep: '先用外层错误码 701018 找到失败记录和关联 ID，再在同一链路中核对 701022 与 CSP 错误。',
    }
  }
  if (isMessageSendScenarioDiagnosis(diagnosis)) {
    return {
      name: '会话消息发送失败',
      failureStep: '查找“消息发送失败”样本，取得可用的 PS ID。',
    }
  }
  return null
})
const canSubmitCaseKnowledgeImport = computed(() => caseKnowledgeImportCanSubmit(
  caseKnowledgeImportForm.knowledgeBaseId,
  caseKnowledgeImportForm.limit,
  canManageTroubleshooting.value,
))
const caseKnowledgeVectorStatus = computed(() => caseKnowledgeImportResult.value
  ? caseKnowledgeVectorMessage(caseKnowledgeImportResult.value)
  : { tone: 'warning' as const, text: '' })
const deploymentTopologyScenarioErrors = computed(() =>
  deploymentTopologyScenarioFormErrors(deploymentTopologyScenarioForm))
const deploymentTopologySelector = computed(() =>
  deploymentTopologyScenarioSelector(deploymentTopologyScenarioForm.system))
const canSubmitDeploymentTopologyScenario = computed(() =>
  canManageTroubleshooting.value && deploymentTopologyScenarioErrors.value.length === 0)
const recordingBatchReady = computed(() =>
  (guanceRecordingTargets.value?.executableTargetCount ?? 0) >= 20)
const canAcceptGuance = computed(() => canManageTroubleshooting.value
  && canAcceptGuanceOwner.value
  && recordingBatchReady.value
  && validationDialogReport.value?.stage === 'CANONICAL_CHAIN_OBSERVED'
  && Object.values(guanceAcceptanceChecklist).every(Boolean))
const evaluationWorkspaceActive = computed(() => canManageTroubleshooting.value
  && normalizeWorkbenchOverlayCapability(route.query.capability) === 'ledger')
const caseKnowledgeWorkspaceActive = computed(() => canManageTroubleshooting.value
  && normalizeWorkbenchOverlayCapability(route.query.capability) === 'case-knowledge')
const capabilityWorkspaceActive = computed(() => evaluationWorkspaceActive.value
  || caseKnowledgeWorkspaceActive.value)

async function switchWorkbenchView(mode: WorkbenchViewSwitchMode) {
  viewMode.value = mode
  if (mode === 'LIST') {
    await store.replaceWorkbenchRoute('LIST')
    return
  }

  const queryDiagnosisId = typeof route.query.diagnosisId === 'string'
    ? route.query.diagnosisId
    : null
  const target = queryDiagnosisId || selectedId.value || rows.value[0]?.diagnosisId
  if (target) {
    await store.selectDiagnosis(target, true, 'QUEUE')
  } else {
    await store.replaceWorkbenchRoute('QUEUE')
  }
}

async function setDetailPerspective(perspective: DiagnosisPerspective) {
  await router.replace({
    path: '/troubleshooting',
    query: { ...route.query, perspective },
  })
}

async function openDiagnosisFromList(row: DiagnosisSummary) {
  await store.selectDiagnosis(row.diagnosisId, true, 'DETAIL')
}

async function loadPilotPlan() {
  pilotPlanLoading.value = true
  try {
    const response = await troubleshootingApi.pilotPlan()
    pilotPlan.value = response.data
    pilotPlanError.value = ''
  } catch {
    pilotPlan.value = null
    pilotPlanError.value = '团队试点状态暂时不可用，请稍后重试。'
  } finally {
    pilotPlanLoading.value = false
  }
}

async function openPilotDiagnosis(diagnosisId: string) {
  await store.selectDiagnosis(diagnosisId, true, 'DETAIL')
}

function launchFormalPilotIncident(scope: TroubleshootingPilotModuleScope) {
  if (!canOperateTroubleshooting.value) return
  resetIncidentReportForm()
  incidentReportForm.system = scope.system
  incidentReportForm.service = scope.service
  incidentReportForm.rehearsal = false
  openIncidentIntake()
}

async function openPilotEvaluation(diagnosisId: string) {
  if (!canManageTroubleshooting.value) return
  await store.selectDiagnosis(diagnosisId, false, 'DETAIL')
  await router.push({
    path: '/troubleshooting',
    query: {
      ...workbenchQueryWithoutCapability(),
      view: 'detail',
      diagnosisId,
      capability: 'ledger',
    },
  })
}

function handleCapabilityCommand(command: WorkbenchCapabilityCommand) {
  if (command === 'playbooks') {
    void router.push('/troubleshooting/sops')
  } else if (command === 'observability-assets') {
    void router.push('/troubleshooting/observability-assets')
  } else if (command === 't7-owner-contract') {
    void router.push('/troubleshooting/t7-owner-contract')
  } else if (command === 'guance') {
    openGuanceOnboarding()
  } else if (command === 'ledger') {
    void prepareEvaluationWorkspace()
  } else if (command === 'case-knowledge') {
    void prepareCaseKnowledgeWorkspace()
  }
}

function openPlaybooks() {
  handleCapabilityCommand('playbooks')
}

async function prepareCaseKnowledgeWorkspace(resetResult = true) {
  if (!canManageTroubleshooting.value) return
  if (resetResult) caseKnowledgeImportResult.value = null
  caseKnowledgeBasesLoading.value = true
  try {
    const response = await wikiApi.listKBs()
    caseKnowledgeBases.value = (response.data || []).map((kb: CaseKnowledgeBaseOption) => ({
      id: kb.id,
      name: kb.name,
      status: kb.status,
      pageCount: kb.pageCount,
      rawCount: kb.rawCount,
    }))
    const selectedStillExists = caseKnowledgeBases.value.some(
      kb => String(kb.id) === caseKnowledgeImportForm.knowledgeBaseId && kb.status === 'active',
    )
    if (!selectedStillExists) {
      const recommended = caseKnowledgeBases.value.find(
        kb => kb.status === 'active' && kb.name.includes('排障'),
      ) ?? caseKnowledgeBases.value.find(kb => kb.status === 'active')
      caseKnowledgeImportForm.knowledgeBaseId = recommended ? String(recommended.id) : ''
    }
  } catch (error) {
    caseKnowledgeBases.value = []
    ElMessage.error(`知识库列表加载失败：${errorText(error)}`)
  } finally {
    caseKnowledgeBasesLoading.value = false
  }
}

async function importHistoricalCases() {
  if (!canSubmitCaseKnowledgeImport.value) return
  caseKnowledgeImportLoading.value = true
  caseKnowledgeImportResult.value = null
  try {
    const response = await troubleshootingApi.importHistoricalCases({
      knowledgeBaseId: caseKnowledgeImportForm.knowledgeBaseId,
      limit: caseKnowledgeImportForm.limit,
    })
    caseKnowledgeImportResult.value = response.data
    const vectorStatus = caseKnowledgeVectorMessage(response.data)
    if (vectorStatus.tone === 'success') {
      ElMessage.success('历史案例已入库并完成向量化')
    } else {
      ElMessage.warning('历史案例素材已入库，部分向量待生成')
    }
  } catch (error) {
    ElMessage.error(`历史案例导入失败：${errorText(error)}`)
  } finally {
    caseKnowledgeImportLoading.value = false
  }
}

function openGuanceOnboarding() {
  guanceOnboardingOpen.value = true
}

/**
 * 这颗按钮写着「数据源联调」，原来却把人送到只读的查询规则说明书页，还带了个
 * `?tab=acceptance`——而那页从来没有 tab，参数是空转的。也就是说点它既没联调、
 * 也没落到 acceptance，只是换了个页面。真正的联调就是本页这个对话框。
 */
function openDataSourceValidation() {
  openGuanceOnboarding()
}

function errorText(error: unknown) { return error instanceof Error ? error.message : String(error) }

function resetIncidentReportForm() {
  Object.assign(incidentReportForm, EMPTY_FORMAL_INCIDENT)
}

function resetMessageSendScenarioForm() {
  Object.assign(messageSendScenarioForm, EMPTY_MESSAGE_SEND_SCENARIO)
}

function resetCtiCreateConversationScenarioForm() {
  Object.assign(
    ctiCreateConversationScenarioForm,
    EMPTY_CTI_CREATE_CONVERSATION_SCENARIO,
  )
}

function resetDeploymentTopologyScenarioForm() {
  Object.assign(deploymentTopologyScenarioForm, EMPTY_DEPLOYMENT_TOPOLOGY_SCENARIO)
}

function openDeploymentTopologyScenarioIntake() {
  resetDeploymentTopologyScenarioForm()
  const incident = current.value?.diagnosis.incident
  if (incident) {
    Object.assign(deploymentTopologyScenarioForm, {
      system: incident.system,
      service: incident.service,
      title: incident.title || EMPTY_DEPLOYMENT_TOPOLOGY_SCENARIO.title,
      traceId: incident.traceId || '',
    })
  }
  deploymentTopologyScenarioOpen.value = true
}

function openIncidentIntake() {
  if (!canOperateTroubleshooting.value) return
  scenarioLauncherOpen.value = false
  conversationIntakeOpen.value = false
  incidentReportOpen.value = true
}

function openIncidentIntakeFromConversation() {
  conversationIntakeOpen.value = false
  openIncidentIntake()
}

function openConversationIntake() {
  if (!canOperateTroubleshooting.value) return
  incidentReportOpen.value = false
  scenarioLauncherOpen.value = false
  conversationIntakeOpen.value = true
}

function openKnownScenarioPicker() {
  if (!canOperateTroubleshooting.value && !canManageTroubleshooting.value) return
  incidentReportOpen.value = false
  conversationIntakeOpen.value = false
  scenarioLauncherOpen.value = true
}

async function onConversationReady(payload: {
  diagnosisId: string
  created: boolean | null
  rehearsal: boolean
}) {
  conversationIntakeOpen.value = false
  statusFilter.value = ''
  investigationModeFilter.value = ''
  await store.loadList(false)
  await store.selectDiagnosis(payload.diagnosisId)
  if (payload.created === false) {
    ElMessage.info('已打开既有排障单（对话入口汇合到同一张单）')
  } else {
    ElMessage.success(payload.rehearsal
      ? '对话资料已齐，已生成演练排障单'
      : '对话资料已齐，已生成正式排障单')
  }
}

function openTroubleshootingScenario() {
  if (canOperateTroubleshooting.value) {
    openIncidentIntake()
    return
  }
  if (canManageTroubleshooting.value) {
    scenarioLauncherOpen.value = true
  }
}

function openFirstUseGuide() {
  firstUseGuideOpen.value = true
}

function startFirstUseRehearsal() {
  firstUseGuideOpen.value = false
  openTroubleshootingScenario()
}

function startTroubleshootingScenario(command: TroubleshootingScenarioCommand) {
  if (command === 'cti-create-conversation-failed' && canOperateTroubleshooting.value) {
    resetCtiCreateConversationScenarioForm()
    ctiCreateConversationScenarioOpen.value = true
  } else if (command === 'message-send-failed' && canOperateTroubleshooting.value) {
    resetMessageSendScenarioForm()
    messageSendScenarioOpen.value = true
  } else if (command === 'incident' && canOperateTroubleshooting.value) {
    openIncidentIntake()
  } else if (command === 'deployment' && canManageTroubleshooting.value) {
    openDeploymentTopologyScenarioIntake()
  }
}

async function createCtiCreateConversationScenario() {
  if (!canSubmitCtiCreateConversationScenario.value) {
    if (ctiCreateConversationScenarioErrors.value[0]) {
      ElMessage.warning(ctiCreateConversationScenarioErrors.value[0])
    }
    return
  }
  ctiCreateConversationScenarioLoading.value = true
  try {
    const request = buildCtiCreateConversationScenarioRequest(
      ctiCreateConversationScenarioForm,
    )
    const { data } = await troubleshootingApi.createScenarioDiagnosis(
      CTI_CREATE_CONVERSATION_SCENARIO.scenarioKey,
      request,
    )
    ctiCreateConversationScenarioOpen.value = false
    resetCtiCreateConversationScenarioForm()
    statusFilter.value = ''
    investigationModeFilter.value = ''
    await store.loadList(false)
    await store.selectDiagnosis(data.diagnosis.diagnosisId)
    ElMessage.success(data.created
      ? '排障单已创建，请在详情中开始三次只读取证'
      : '命中五分钟幂等窗口，已打开原排障单')
  } catch (error) {
    ElMessage.error(
      `CTI 场景排障单未创建：${errorText(error)} 请确认排查指南 ${CTI_CREATE_CONVERSATION_SCENARIO.selector} 已回放并审核启用。`,
    )
  } finally {
    ctiCreateConversationScenarioLoading.value = false
  }
}

async function createMessageSendScenario() {
  if (!canSubmitMessageSendScenario.value) {
    if (messageSendScenarioErrors.value[0]) {
      ElMessage.warning(messageSendScenarioErrors.value[0])
    }
    return
  }
  messageSendScenarioLoading.value = true
  try {
    const request = buildMessageSendScenarioRequest(messageSendScenarioForm)
    const { data } = await troubleshootingApi.createScenarioDiagnosis(
      MESSAGE_SEND_SCENARIO_KEY,
      request,
    )
    messageSendScenarioOpen.value = false
    resetMessageSendScenarioForm()
    statusFilter.value = ''
    investigationModeFilter.value = ''
    await store.loadList(false)
    await store.selectDiagnosis(data.diagnosis.diagnosisId)
    ElMessage.success(data.created
      ? '排障单已创建，请在详情中开始三次只读取证'
      : '命中五分钟幂等窗口，已打开原排障单')
  } catch (error) {
    ElMessage.error(
      `场景排障单未创建：${errorText(error)} 请确认排查指南 ${MESSAGE_SEND_SCENARIO_SELECTOR} 已审核启用。`,
    )
  } finally {
    messageSendScenarioLoading.value = false
  }
}

async function runScenarioEvidence() {
  const diagnosis = current.value?.diagnosis
  if (!diagnosis || (!canRunMessageSendEvidenceForDiagnosis(diagnosis)
    && !canRunCtiCreateConversationEvidence(diagnosis))) return
  scenarioEvidenceLoading.value = true
  try {
    await troubleshootingApi.runScenarioEvidence(diagnosis.diagnosisId)
    await store.reload()
    ElMessage.success('三次只读取证已完成，结论与证据链已写入排障详情')
  } catch (error) {
    ElMessage.error(`只读取证未完成：${errorText(error)} 系统未伪造结论，排障单仍保持等待状态。`)
  } finally {
    scenarioEvidenceLoading.value = false
  }
}

function handleTopologyProbeCompleted(run: TopologyProbeEvidenceRun) {
  store.handleTopologyProbeCompleted(run)
}

async function reportIncident() {
  if (!canSubmitIncidentReport.value) {
    if (incidentReportErrors.value[0]) ElMessage.warning(incidentReportErrors.value[0])
    return
  }
  incidentReportLoading.value = true
  try {
    const request = buildFormalIncidentReport(incidentReportForm)
    const { data } = await troubleshootingApi.report(request)
    incidentReportOpen.value = false
    resetIncidentReportForm()
    statusFilter.value = ''
    investigationModeFilter.value = ''
    await store.loadList(false)
    await store.selectDiagnosis(data.diagnosis.diagnosisId)
    if (data.created) {
      ElMessage.success('已生成排障单，进入详情按五问推进')
    } else {
      ElMessage.info('五分钟内同类事件已有排障单，已打开原单')
    }
  } catch (error) {
    const routeBoundary = incidentRoutePreview.value.tone === 'DETERMINISTIC'
      ? '有错误码但未命中已审核标准方案时，系统会明确拒绝，不会瞎猜。'
      : '没有标准方案时的兜底调查未启用或配置不合规时，系统会明确拒绝。'
    ElMessage.error(`未生成排障单：${errorText(error)} ${routeBoundary}`)
  } finally {
    incidentReportLoading.value = false
  }
}

async function createDeploymentTopologyScenario() {
  if (!canSubmitDeploymentTopologyScenario.value) {
    if (deploymentTopologyScenarioErrors.value[0]) {
      ElMessage.warning(deploymentTopologyScenarioErrors.value[0])
    }
    return
  }
  deploymentTopologyScenarioLoading.value = true
  let stored: StoredDiagnosis
  try {
    const request = buildDeploymentTopologyScenarioRequest(
      deploymentTopologyScenarioForm,
    )
    const response = await troubleshootingApi.createDeploymentTopologyScenario(request)
    stored = response.data
  } catch (error) {
    ElMessage.error(
      `场景未创建：${errorText(error)} 请先确认“排障规则库”中已审核启用 ${deploymentTopologySelector.value}。`,
    )
    return
  } finally {
    deploymentTopologyScenarioLoading.value = false
  }

  deploymentTopologyScenarioOpen.value = false
  resetDeploymentTopologyScenarioForm()
  statusFilter.value = ''
  investigationModeFilter.value = ''
  await store.loadList(false)
  await store.selectDiagnosis(stored.diagnosis.diagnosisId)
  const loadedDiagnosis = current.value
  if (!loadedDiagnosis || !deploymentTopologyScenarioProjectionLoaded(
    stored.diagnosis.diagnosisId,
    loadedDiagnosis.diagnosis.diagnosisId,
    Boolean(projection.value),
  )) {
    ElMessage.error(deploymentTopologyScenarioLoadFailureMessage(
      stored.diagnosis.diagnosisId,
    ))
    return
  }
  if (!deploymentTopologyRequired.value) {
    ElMessage.error('场景 Diagnosis 已创建，但服务端未确认部署拓扑拨测能力；已停止打开工具。')
    return
  }
  if (loadedDiagnosis.diagnosis.status === 'CLOSED') {
    ElMessage.warning('命中的既有 Diagnosis 已关闭，不能追加新的拓扑拨测证据。')
    return
  }
  deploymentTopologyOpen.value = true
  if (stored.created) {
    ElMessage.success('部署拓扑场景已进入 Diagnosis 主链，请选择拓扑资产')
  } else {
    ElMessage.info('命中五分钟幂等窗口，已打开既有场景 Diagnosis')
  }
}

function openGuanceValidationDialog(
  request: EvidenceChainPreviewRequest,
  ownerAcceptance: GuanceEvidenceAcceptanceView | null,
  origin: GuanceValidationOrigin,
) {
  store.nextGuanceValidationSessionVersion()
  beginGuanceValidationSession(request, ownerAcceptance, origin)
}

function startGuanceValidationFromOnboarding(payload: GuanceOnboardingValidationPayload) {
  guanceOnboardingOpen.value = false
  openGuanceValidationDialog(payload.request, payload.ownerAcceptance, 'ONBOARDING')
}

function isCurrentDiagnosisValidationRequest(request: EvidenceChainPreviewRequest) {
  return canAttachGuanceResultToDiagnosis(
    guanceValidationOrigin.value,
    currentDiagnosisEvidenceLookup.value,
    request,
  )
}

function captureGuanceValidationSession() {
  return captureValidationSession(store.getSelectionVersion())
}

async function openEvaluationLedger() {
  if (!canManageTroubleshooting.value) return
  const query = { ...route.query, capability: 'ledger' }
  await router.push({ path: '/troubleshooting', query })
}

async function openPilotSetup() {
  if (!canManageTroubleshooting.value) return
  await router.push({
    path: '/troubleshooting',
    query: {
      ...workbenchQueryWithoutCapability(),
      capability: 'ledger',
      pilotSetup: '1',
    },
  })
}

async function openCurrentEvaluationValidation() {
  if (!canManageTroubleshooting.value) return
  await router.push({ path: '/troubleshooting', query: workbenchQueryWithoutCapability() })
  openDataSourceValidation()
}

async function prepareEvaluationWorkspace() {
  const diagnosisId = current.value?.diagnosis.diagnosisId
  if (diagnosisId) await store.loadReplayCapability(diagnosisId, store.getSelectionVersion())
}

async function closeCapabilityWorkspace() {
  await router.push({ path: '/troubleshooting', query: workbenchQueryWithoutCapability() })
  await loadPilotPlan()
}

async function openDiagnosisFromEvaluation(diagnosisId: string) {
  const query = workbenchQueryWithoutCapability()
  query.view = 'detail'
  query.diagnosisId = diagnosisId
  await router.push({ path: '/troubleshooting', query })
  await store.selectDiagnosis(diagnosisId, false, 'DETAIL')
}

function workbenchQueryWithoutCapability() {
  const query = { ...route.query }
  delete query.capability
  delete query.focus
  delete query.pilotSetup
  return query
}

async function validateGuance() {
  const version = store.getSelectionVersion()
  const session = captureGuanceValidationSession()
  const request = session.request
  validationLoading.value = true
  try {
    const response = await store.validateGuance(request, session, version)
    if (response) {
      validationDialogReport.value = response.data
      if (response.data.stage === 'CANONICAL_CHAIN_OBSERVED') {
        ElMessage.success('日志与调用链验证通过；仍需负责人确认，演示数据状态保持不变')
      } else {
        ElMessage.warning('真源验证未通过：来源未就绪或返回数据格式校验未通过')
      }
    }
  } finally {
    if (store.isCurrentGuanceValidationGeneration(version)) validationLoading.value = false
  }
}

async function acceptGuance() {
  if (!canAcceptGuance.value) return
  const version = store.getSelectionVersion()
  const session = captureGuanceValidationSession()
  const request = {
    ...session.request,
    checklist: { ...guanceAcceptanceChecklist },
  }
  acceptanceLoading.value = true
  try {
    const response = await store.acceptGuanceEvidence(request, session, version)
    if (response) {
      validationDialogOwnerAcceptance.value = response.data
      ElMessage.success('当前数据源配置已由负责人确认；配置变化后该记录会自动失效')
    }
  } finally {
    if (store.isCurrentGuanceValidationGeneration(version)) acceptanceLoading.value = false
  }
}

async function previewGuanceSpine() {
  const version = store.getSelectionVersion()
  const session = captureGuanceValidationSession()
  const request = session.request
  spinePreviewLoading.value = true
  try {
    const response = await store.previewGuanceSpine(request, session, version)
    if (response) {
      validationDialogSpinePreview.value = response.data
      if (response.data.stage === 'FULL_SPINE_OBSERVED') {
        ElMessage.success('完整取证流程已验证；仍需负责人确认并积累真实样本')
      } else if (response.data.stage === 'CORE_CHAIN_OBSERVED') {
        ElMessage.warning('核心链路可压缩，但成功样本对照缺失，继续校准期')
      } else {
        ElMessage.warning('完整取证流程未通过：数据源未就绪或返回数据格式校验未通过')
      }
    }
  } finally {
    if (store.isCurrentGuanceValidationGeneration(version)) spinePreviewLoading.value = false
  }
}


function openHistoricalReplay() {
  if (!canManageTroubleshooting.value) return
  synthesisPreviewOpen.value = true
}
function canApprove(action: RecommendedAction) { return action.actionType === 'MANUAL_WRITE' && action.approvalStatus === 'PENDING' && canTransfer.value }
function canRecordOutcome(action: RecommendedAction) { return action.actionType === 'MANUAL_WRITE' && action.approvalStatus === 'APPROVED_NOT_EXECUTED' && canTransfer.value }
function openApprove(action: RecommendedAction) { targetAction.value = action; approveOpen.value = true }
function openOutcome(action: RecommendedAction) { targetAction.value = action; outcomeOpen.value = true }

watch(
  [incidentReportOpen, () => incidentReportForm.system, () => incidentRoutePreview.value.tone],
  async ([open, system, tone]) => {
    if (!open || tone !== 'BOUNDED_DISCOVERY') {
      openDiscoveryReadiness.value = null
      return
    }
    try {
      const { data } = await troubleshootingApi.openDiscoveryReadiness({
        ...(system.trim() ? { system: system.trim() } : {}),
      })
      openDiscoveryReadiness.value = data
    } catch {
      openDiscoveryReadiness.value = null
    }
  },
)

watch(
  [() => route.query.view, () => route.query.diagnosisId],
  ([queryView, diagnosisId]) => {
    const nextMode = resolveWorkbenchView(queryView, diagnosisId)
    viewMode.value = nextMode
    if (isDiagnosisViewMode(nextMode)
      && typeof diagnosisId === 'string'
      && diagnosisId
      && diagnosisId !== selectedId.value) {
      void store.selectDiagnosis(diagnosisId, false, nextMode)
    }
  },
  { immediate: true },
)
watch(
  () => route.query.capability,
  capability => {
    const command = normalizeWorkbenchOverlayCapability(capability)
    if (!command || !canManageTroubleshooting.value) return
    handleCapabilityCommand(command)
  },
  { immediate: true },
)
watch(
  () => route.query.focus,
  focus => {
    if (!isEvidenceSynthesisFocus(focus) || !canManageTroubleshooting.value) return
    synthesisPreviewOpen.value = true
  },
  { immediate: true },
)
watch(synthesisPreviewOpen, open => {
  if (open || !isEvidenceSynthesisFocus(route.query.focus)) return
  const query = { ...route.query }
  delete query.focus
  void router.replace({ path: '/troubleshooting', query })
})
watch(guanceOnboardingOpen, open => {
  const command = normalizeWorkbenchOverlayCapability(route.query.capability)
  if (command !== 'guance' || open) return
    const query = { ...route.query }
    delete query.capability
    void router.replace({ path: '/troubleshooting', query })
})
onMounted(() => {
  void store.loadList(isDiagnosisViewMode(viewMode.value))
  void loadPilotPlan()
})
</script>

<style scoped>
.formal-workbench { --ink:var(--mc-text-primary); --muted:var(--mc-text-secondary); --line:var(--mc-border); --soft:var(--mc-bg-muted); --blue:var(--mc-primary); --green:var(--mc-success); --amber:var(--mc-warning); --red:var(--mc-danger); display:grid; grid-template-columns:var(--mc-ts-side-rail-width) minmax(0,1fr); width:100%; min-width:0; height:100%; overflow:hidden; color:var(--ink); background:var(--mc-bg); }
.formal-workbench.traditional-list-mode { display:block; width:100%; overflow-y:auto; }
.formal-workbench.full-detail-mode { grid-template-columns:minmax(0,1fr); width:100%; }
.formal-workbench.capability-workspace-mode { display:block; width:100%; overflow:hidden; }
.eyebrow { display:block; color:var(--blue); font-size:var(--mc-text-xs); font-weight:750; letter-spacing:.12em; text-transform:uppercase; }
.work-area { width:100%; min-width:0; overflow-y:auto; padding:20px clamp(20px,3vw,40px) 40px; }
.detail-empty { display:grid; place-items:center; align-content:center; min-height:70vh; color:var(--muted); text-align:center; }
.empty-mark { display:grid; place-items:center; width:52px; height:52px; border:1px solid var(--mc-border); border-radius:var(--mc-radius-md); color:var(--blue); background:var(--mc-bg-elevated); font-weight:800; box-shadow:0 10px 30px var(--mc-shadow-soft); }
.detail-empty h1 { margin:16px 0 4px; color:var(--ink); font-size:var(--mc-text-lg); } .detail-empty p { margin:0; font-size:var(--mc-text-sm); } .detail-empty .el-button { margin-top:16px; }
.work-head { display:flex; align-items:flex-end; justify-content:space-between; gap:14px; width:100%; margin:0 0 20px; }
.work-head h1 { margin:5px 0 0; font-size:var(--mc-text-xl); letter-spacing:-.025em; } .work-head-actions { display:flex; gap:8px; }
.perspective-switch { display:flex; align-items:center; justify-content:space-between; gap:18px; width:100%; margin:-4px 0 14px; padding:10px 12px 10px 16px; border:1px solid var(--mc-border); border-radius:var(--mc-radius-sm); background:var(--mc-bg-elevated); }
.perspective-switch__copy b,.perspective-switch__copy span { display:block; }
.perspective-switch__copy b { color:var(--mc-text-primary); font-size:var(--mc-text-xs); }
.perspective-switch__copy span { margin-top:3px; color:var(--mc-text-tertiary); font-size:11px; }
.perspective-switch__tabs { display:flex; gap:4px; padding:3px; border:1px solid var(--mc-border-light); border-radius:var(--mc-radius-sm); background:var(--mc-bg-muted); }
.perspective-switch__tabs button { appearance:none; border:0; border-radius:calc(var(--mc-radius-sm) - 2px); padding:8px 12px; color:var(--mc-text-secondary); background:transparent; font:inherit; font-size:var(--mc-text-xs); font-weight:650; cursor:pointer; }
.perspective-switch__tabs button small { margin-left:3px; color:var(--mc-text-tertiary); font-size:10px; font-weight:500; }
.perspective-switch__tabs button.active { color:var(--mc-primary); background:var(--mc-bg-elevated); box-shadow:0 1px 3px var(--mc-shadow-soft); }
.perspective-switch__tabs button.active small { color:var(--mc-primary); }
.fixture-banner { display:flex; align-items:center; gap:8px; width:100%; margin:0 0 16px; padding:9px 13px; border:1px solid var(--mc-warning); border-radius:var(--mc-radius-sm); color:var(--mc-status-warning-text); background:var(--mc-status-warning-bg); font-size:var(--mc-text-xs); }
.fixture-banner span:last-child { color:var(--mc-status-warning-text); } .fixture-dot { width:7px; height:7px; border-radius:50%; background:var(--mc-warning); box-shadow:0 0 0 4px rgba(245,158,11,0.13); }
.question-progress-fold { width:100%; margin-top:12px; border:1px solid var(--mc-border); border-radius:var(--mc-radius-sm); background:var(--mc-bg-elevated); overflow:hidden; }
.question-progress-fold>summary { display:flex; align-items:center; gap:14px; padding:14px 18px; cursor:pointer; list-style:none; }
.question-progress-fold>summary::-webkit-details-marker { display:none; }
.question-progress-fold>summary>div b,.question-progress-fold>summary>div small { display:block; }
.question-progress-fold>summary>div b { font-size:var(--mc-text-sm); }
.question-progress-fold>summary>div small { margin-top:3px; color:var(--mc-text-secondary); font-size:var(--mc-text-xs); }
.question-progress-fold>summary>span { margin-left:auto; color:var(--mc-text-tertiary); font-size:var(--mc-text-xs); }
.question-progress-fold :deep(.five-question-rail) { margin:0; padding:0 18px 18px; border-top:1px solid var(--mc-border-light); }
.question-progress-fold :deep(.fq-list) { margin-top:14px; }
@media(max-width:760px){.perspective-switch{align-items:stretch;flex-direction:column}.perspective-switch__tabs{display:grid;grid-template-columns:1fr 1fr}.perspective-switch__tabs button{padding:9px 7px}}
.business-card,.developer-fold { width:100%; max-width:none; margin-right:0; margin-left:0; border:1px solid var(--line); border-radius:var(--mc-radius-md); background:var(--mc-bg-elevated); box-shadow:0 8px 28px var(--mc-shadow-soft); }
.business-card { padding:clamp(20px,3vw,36px); } .verdict-head { padding-bottom:16px; }
.badge-row { display:flex; align-items:center; gap:8px; flex-wrap:wrap; } .conclusion-badge,.status-badge,.confidence-badge { padding:4px 9px; border:1px solid var(--line); border-radius:var(--mc-radius-lg); font-size:var(--mc-text-xs); font-weight:700; }
.conclusion-badge.located { color:var(--mc-status-info-text); border-color:var(--mc-border); background:var(--mc-status-info-bg); } .conclusion-badge.excluded { color:var(--mc-text-secondary); background:var(--mc-bg-muted); }
.conclusion-badge.hypothesis { color:var(--mc-status-purple-text); border-color:var(--mc-border); background:var(--mc-status-purple-bg); } .conclusion-badge.insufficient_evidence { color:var(--mc-warning); border-color:var(--mc-warning); background:var(--mc-status-warning-bg); }
.confidence-badge.high { color:var(--green); background:var(--mc-status-success-bg); } .confidence-badge.medium { color:var(--amber); background:var(--mc-status-warning-bg); } .confidence-badge.low { color:var(--red); background:var(--mc-status-error-bg); }
.verdict-copy h2 { margin:14px 0 7px; font-size:clamp(24px,2.5vw,32px); line-height:1.25; letter-spacing:-.035em; } .verdict-copy>p { max-width:820px; margin:0; color:var(--muted); font-size:var(--mc-text-sm); line-height:1.7; }
.route-card { align-self:start; padding:18px; border:1px solid var(--line); border-radius:var(--mc-radius-sm); background:var(--soft); }
.route-card span,.section-label { display:block; color:var(--mc-text-tertiary); font-size:var(--mc-text-xs); font-weight:750; letter-spacing:.1em; text-transform:uppercase; }
.route-card b { display:block; margin:7px 0; font-size:var(--mc-text-xs); } .route-card code { color:var(--blue); font-size:var(--mc-text-xs); word-break:break-all; }
.summary-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); overflow:hidden; border:1px solid var(--line); border-radius:var(--mc-radius-sm); }
.summary-grid article { min-height:140px; padding:20px 22px; } .summary-grid article+article { border-left:1px solid var(--line); }
.summary-grid strong { display:block; margin:12px 0 10px; font-size:var(--mc-text-sm); line-height:1.6; } .summary-grid small { display:block; color:var(--muted); font-size:var(--mc-text-xs); line-height:1.6; }
.impact-metrics { display:flex; gap:7px; margin:8px 0; } .impact-metrics span { padding:2px 7px; border-radius:var(--mc-radius-xs); color:var(--mc-status-info-text); background:var(--mc-status-info-bg); font-size:var(--mc-text-xs); } .capability-boundary { color:var(--amber)!important; }
.closure-result { display:grid; grid-template-columns:180px minmax(0,1fr) auto; align-items:center; gap:12px; margin-top:12px; padding:14px 16px; border:1px solid var(--mc-success); border-radius:var(--mc-radius-sm); background:var(--mc-status-success-bg); }
.closure-result div b { display:block; margin-top:5px; color:var(--green); font-size:var(--mc-text-sm); } .closure-result>strong { font-size:var(--mc-text-xs); line-height:1.6; } .closure-result>small { color:var(--muted); font-size:var(--mc-text-xs); text-align:right; }
.timing-strip { display:grid; grid-template-columns:1fr 16px 1fr 16px 1fr; align-items:center; margin-top:12px; padding:16px 20px; border:1px solid var(--line); border-radius:var(--mc-radius-sm); background:var(--mc-bg-elevated); }
.timing-strip article { display:grid; grid-template-columns:1fr auto; gap:3px 12px; } .timing-strip span { color:var(--muted); font-size:var(--mc-text-xs); } .timing-strip b { color:var(--mc-text-secondary); font-size:var(--mc-text-sm); }
.timing-strip small { grid-column:1/-1; color:var(--mc-text-tertiary); font-size:var(--mc-text-xs); } .timing-strip i { width:5px; height:5px; justify-self:center; border-radius:50%; background:var(--mc-text-tertiary); }
.convergence-grid { display:grid; grid-template-columns:minmax(0,1.6fr) minmax(270px,.8fr); gap:14px; margin-top:12px; } .trace-summary,.draft-summary { padding:18px; border:1px solid var(--line); border-radius:var(--mc-radius-sm); }
.section-head,.developer-section-head { display:flex; align-items:flex-start; justify-content:space-between; gap:16px; } .section-head h3,.developer-section-head h3,.developer-side h3 { margin:5px 0 0; font-size:var(--mc-text-sm); } .section-head>code { color:var(--blue); font-size:var(--mc-text-xs); }
.hop-line { display:flex; align-items:stretch; gap:8px; margin-top:12px; } .hop { flex:1; padding:10px; border:1px solid var(--line); border-radius:var(--mc-radius-xs); background:var(--mc-bg-elevated); }
.hop>span { display:inline-grid; place-items:center; width:18px; height:18px; border-radius:50%; color:var(--mc-text-inverse); background:var(--blue); font-size:var(--mc-text-xs); } .hop b,.hop small { display:block; margin-top:5px; font-size:var(--mc-text-xs); } .hop small { color:var(--muted); }
.hop.anomalous { border-color:var(--mc-danger-border); background:var(--mc-status-error-bg); } .hop.anomalous>span { background:var(--red); }
.empty-evidence { margin:14px 0 0; padding:11px 12px; border:1px dashed var(--mc-border); border-radius:var(--mc-radius-xs); color:var(--muted); background:var(--mc-bg-elevated); font-size:var(--mc-text-xs); line-height:1.65; }
.contrast-row { display:flex; align-items:center; gap:9px; flex-wrap:wrap; margin-top:12px; padding:10px 12px; border-radius:var(--mc-radius-xs); background:var(--mc-status-success-bg); font-size:var(--mc-text-xs); }
.contrast-row>span { color:var(--muted); } .contrast-row em { color:var(--mc-text-tertiary); font-style:normal; } .contrast-row .baseline { color:var(--green); } .contrast-row small { flex-basis:100%; color:var(--muted); } .contrast-row.unavailable { color:var(--amber); background:var(--mc-status-warning-bg); }
.draft-state { padding:2px 7px; border-radius:var(--mc-radius-xs); color:var(--mc-status-purple-text); background:var(--mc-status-purple-bg); font-size:var(--mc-text-xs); font-weight:750; } .draft-summary ol { margin:14px 0 9px; padding-left:20px; color:var(--mc-text-secondary); font-size:var(--mc-text-xs); line-height:1.65; } .draft-summary>small { color:var(--muted); font-size:var(--mc-text-xs); }
.lifecycle-bar { display:flex; align-items:center; gap:9px; margin-top:12px; padding-top:12px; border-top:1px solid var(--line); } .lifecycle-bar>span { margin-left:5px; color:var(--muted); font-size:var(--mc-text-xs); }
.topology-evidence-card { width:100%; max-width:none; margin:20px 0 0; padding:22px 24px; border:1px solid var(--mc-border); border-radius:var(--mc-radius-md); background:var(--mc-status-success-bg); box-shadow:0 8px 28px var(--mc-shadow-soft); }
.topology-evidence-head { display:flex; align-items:flex-start; justify-content:space-between; gap:12px; }
.topology-evidence-head h3 { margin:5px 0; font-size:var(--mc-text-lg); }
.topology-evidence-head p { margin:0; color:var(--muted); font-size:var(--mc-text-xs); line-height:1.65; }
.topology-evidence-head code { color:var(--mc-status-success-text); }
.topology-evidence-result { display:grid; grid-template-columns:minmax(190px,.8fr) minmax(340px,1.4fr); gap:14px 22px; align-items:center; margin-top:12px; padding-top:14px; border-top:1px solid var(--mc-border-light); }
.topology-evidence-result>div:first-child span,.topology-evidence-result>div:first-child b,.topology-evidence-result>div:first-child small { display:block; }
.topology-evidence-result>div:first-child span { color:var(--muted); font-size:var(--mc-text-xs); }
.topology-evidence-result>div:first-child b { margin-top:4px; font-size:var(--mc-text-sm); }
.topology-evidence-result>div:first-child small { margin-top:4px; color:var(--muted); font-size:var(--mc-text-xs); }
.topology-evidence-result dl { display:grid; grid-template-columns:repeat(4,1fr); gap:8px; margin:0; }
.topology-evidence-result dl>div { padding:9px; border:1px solid var(--mc-border-light); border-radius:var(--mc-radius-xs); background:var(--mc-bg-elevated); }
.topology-evidence-result dt { color:var(--muted); font-size:var(--mc-text-xs); }
.topology-evidence-result dd { margin:3px 0 0; color:var(--mc-status-success-text); font-size:var(--mc-text-base); font-weight:800; }
.topology-evidence-result .failed dd { color:var(--red); }
.topology-link-hints { grid-column:1/-1; display:flex; flex-wrap:wrap; align-items:center; gap:7px; color:var(--mc-status-error-text); font-size:var(--mc-text-xs); }
.topology-link-hints code { padding:3px 6px; border-radius:var(--mc-radius-xs); background:var(--mc-status-error-bg); }
.topology-observations { grid-column:1/-1; display:flex; flex-wrap:wrap; gap:7px; align-items:center; color:var(--muted); font-size:var(--mc-text-xs); }
.topology-observations>div { display:flex; align-items:center; gap:6px; padding:6px 8px; border:1px solid var(--mc-border-light); border-radius:var(--mc-radius-xs); background:var(--mc-bg-elevated); }
.topology-observations b { color:var(--ink); }.topology-observations code { color:var(--mc-text-secondary); }.topology-observations em { color:var(--mc-status-success-text); font-style:normal; font-weight:700; }.topology-observations small { color:var(--muted); }
.topology-history-count { grid-column:1/-1; color:var(--muted); font-size:var(--mc-text-xs); }
.topology-run-history { grid-column:1/-1; overflow:hidden; border:1px solid var(--mc-border-light); border-radius:var(--mc-radius-sm); background:var(--mc-bg-elevated); }
.topology-run-history summary { padding:9px 11px; color:var(--mc-status-success-text); cursor:pointer; font-size:var(--mc-text-xs); font-weight:750; }
.topology-run-history ol { display:grid; gap:8px; margin:0; padding:0 10px 10px; list-style:none; }
.topology-run-history li { padding:10px; border:1px solid var(--mc-border-light); border-radius:var(--mc-radius-xs); background:var(--mc-bg-elevated); }
.topology-run-history header { display:flex; justify-content:space-between; gap:12px; align-items:flex-start; }
.topology-run-history header b,.topology-run-history header small { display:block; }.topology-run-history header small { margin-top:3px; color:var(--muted); font-size:var(--mc-text-xs); }.topology-run-history header code { color:var(--mc-text-secondary); font-size:var(--mc-text-xs); }
.topology-run-history p { margin:7px 0 0; color:var(--mc-text-secondary); font-size:var(--mc-text-xs); line-height:1.6; }.topology-run-history .history-links { color:var(--mc-status-error-text); }.topology-run-history .history-warning { color:var(--mc-warning); }
.topology-run-history .history-observations { display:grid; gap:5px; margin:8px 0 0; padding:0; list-style:none; }
.topology-run-history .history-observations li { display:flex; flex-wrap:wrap; align-items:center; gap:6px; padding:6px 7px; border:0; border-radius:6px; background:var(--mc-status-success-bg); color:var(--mc-text-secondary); font-size:var(--mc-text-xs); }
.topology-run-history .history-observations b { color:var(--ink); }.topology-run-history .history-observations code { color:var(--mc-text-secondary); }.topology-run-history .history-observations span { color:var(--mc-status-success-text); font-weight:700; }.topology-run-history .history-observations small { color:var(--muted); }
.developer-fold { margin-top:12px; overflow:hidden; } .developer-fold>summary { display:flex; align-items:center; gap:12px; padding:18px 24px; list-style:none; cursor:pointer; user-select:none; }
.developer-fold>summary::-webkit-details-marker { display:none; } .developer-fold>summary>div b,.developer-fold>summary>div small { display:block; } .developer-fold>summary>div b { font-size:var(--mc-text-sm); } .developer-fold>summary>div small { margin-top:3px; color:var(--muted); font-size:var(--mc-text-xs); }
.developer-fold>summary>span:last-child { margin-left:auto; color:var(--muted); font-size:var(--mc-text-xs); } .fold-caret { width:0; height:0; border-top:5px solid transparent; border-bottom:5px solid transparent; border-left:6px solid var(--mc-text-tertiary); transition:transform .18s; } .developer-fold[open] .fold-caret { transform:rotate(90deg); }
.developer-body { display:grid; grid-template-columns:minmax(0,1.65fr) minmax(300px,.75fr); gap:24px; padding:24px; border-top:1px solid var(--line); background:var(--mc-bg-elevated); } .developer-body>.route-card,.developer-body>.convergence-grid { grid-column:1/-1; margin-top:0; } .developer-section-head>span { padding:3px 8px; border-radius:var(--mc-radius-xs); color:var(--mc-status-info-text); background:var(--mc-status-info-bg); font-size:var(--mc-text-xs); }
.evidence-timeline { min-width:0; } .evidence-step { display:grid; grid-template-columns:74px 20px minmax(0,1fr) auto; gap:10px; padding-top:20px; } .evidence-step time { padding-top:2px; color:var(--mc-text-tertiary); font-family:var(--mc-mono,monospace); font-size:var(--mc-text-xs); }
.step-line { position:relative; display:flex; justify-content:center; } .step-line::after { content:''; position:absolute; top:10px; bottom:-18px; width:1px; background:var(--mc-border); } .evidence-step:last-child .step-line::after { display:none; }
.step-line i { position:relative; z-index:1; width:9px; height:9px; margin-top:3px; border-radius:50%; background:var(--blue); box-shadow:0 0 0 4px var(--mc-border-light); }
.evidence-step.anomaly .step-line i { background:var(--red); box-shadow:0 0 0 4px var(--mc-danger-light); } .evidence-step.excluded .step-line i { background:var(--green); box-shadow:0 0 0 4px var(--mc-success-light); } .evidence-step.unevaluated .step-line i { border:1.5px dashed var(--mc-text-tertiary); background:var(--mc-bg-elevated); box-shadow:none; }
.evidence-step b { font-size:var(--mc-text-xs); } .evidence-step p { margin:4px 0 6px; color:var(--muted); font-size:var(--mc-text-xs); line-height:1.6; } .evidence-step code { color:var(--blue); font-size:var(--mc-text-xs); }
.tone-label { align-self:start; padding:2px 6px; border-radius:var(--mc-radius-xs); color:var(--muted); background:var(--mc-bg-muted); font-size:var(--mc-text-xs); } .evidence-step.anomaly .tone-label { color:var(--red); background:var(--mc-status-error-bg); } .evidence-step.excluded .tone-label { color:var(--green); background:var(--mc-status-success-bg); } .evidence-step.unevaluated .tone-label { color:var(--amber); background:var(--mc-status-warning-bg); }
.developer-side { display:flex; flex-direction:column; gap:14px; } .developer-side>section { padding:16px; border:1px solid var(--line); border-radius:var(--mc-radius-sm); background:var(--mc-bg-elevated); } .capability-list { margin:13px 0 0; padding-left:17px; color:var(--mc-status-error-text); font-size:var(--mc-text-xs); line-height:1.65; } .capability-list li+li { margin-top:7px; }
.source-gate-card { min-height:120px; } .source-gate-head { display:flex; align-items:flex-start; justify-content:space-between; gap:10px; } .source-gate-state { flex:none; padding:3px 7px; border-radius:var(--mc-radius-xs); background:var(--mc-bg-muted); font-size:var(--mc-text-xs); font-weight:700; }
.source-scope { display:flex; align-items:center; gap:5px; margin:12px 0 8px; color:var(--mc-text-tertiary); font-size:var(--mc-text-xs); } .source-scope code { color:var(--mc-text-secondary); word-break:break-all; } .source-meta { display:flex; flex-wrap:wrap; gap:7px; font-size:var(--mc-text-xs); }
.acceptance-ladder { margin:12px 0 0; padding:0; list-style:none; border:1px solid var(--line); border-radius:var(--mc-radius-xs); overflow:hidden; }
.acceptance-ladder li { display:grid; grid-template-columns:30px minmax(0,1fr) auto; align-items:start; gap:8px; padding:8px; background:var(--mc-bg-elevated); }
.acceptance-ladder li+li { border-top:1px solid var(--line); }
.acceptance-ladder li>span { display:grid; place-items:center; width:25px; height:20px; border-radius:4px; color:var(--mc-text-secondary); background:var(--mc-border-light); font:700 10px var(--mc-mono,monospace); }
.acceptance-ladder b,.acceptance-ladder small { display:block; } .acceptance-ladder b { font-size:var(--mc-text-xs); } .acceptance-ladder small { margin-top:3px; color:var(--muted); font-size:var(--mc-text-xs); line-height:1.45; }
.acceptance-ladder strong { font-size:var(--mc-text-xs); white-space:nowrap; } .next-source-action { margin:8px 0 0; padding:8px; border-radius:6px; color:var(--mc-text-secondary); background:var(--mc-status-info-bg); font-size:var(--mc-text-xs); line-height:1.5; } .next-source-action b { display:block; margin-bottom:2px; color:var(--mc-status-info-text); }
.signal-readiness-list { margin:12px 0; padding:0; list-style:none; } .signal-readiness-list li { display:grid; grid-template-columns:minmax(0,1fr) auto; gap:3px 8px; padding:7px 0; border-top:1px solid var(--mc-border-light); } .signal-readiness-list code { font-size:var(--mc-text-xs); } .signal-readiness-list span { font-size:var(--mc-text-xs); } .signal-readiness-list small { grid-column:1/-1; color:var(--mc-text-tertiary); font-size:var(--mc-text-xs); word-break:break-all; }
.action-card { margin-top:12px; padding:12px; border:1px solid var(--line); border-radius:var(--mc-radius-xs); } .action-card.write { border-color:var(--mc-border); } .action-card>div { display:flex; justify-content:space-between; gap:8px; } .action-card code,.action-card>div span { color:var(--muted); font-size:var(--mc-text-xs); }
.action-card>b { display:block; margin-top:7px; font-size:var(--mc-text-xs); } .action-card>p { margin:4px 0 9px; color:var(--muted); font-size:var(--mc-text-xs); line-height:1.5; }
@media(max-width:1100px){.verdict-head,.developer-body{grid-template-columns:1fr}.summary-grid{grid-template-columns:1fr}.summary-grid article+article{border-top:1px solid var(--line);border-left:0}.convergence-grid{grid-template-columns:1fr}}
@media(max-width:760px){.formal-workbench{display:block;height:auto;min-height:100%;overflow:visible}.work-area{overflow:visible;padding:20px 14px 40px}.work-head,.topology-evidence-head{align-items:flex-start;flex-direction:column}.topology-evidence-result{grid-template-columns:1fr}.topology-evidence-result dl{grid-template-columns:repeat(2,1fr)}.timing-strip{grid-template-columns:1fr;gap:12px}.timing-strip i{display:none}.evidence-step{grid-template-columns:52px 16px minmax(0,1fr)}.tone-label{grid-column:3;justify-self:start}}
</style>
