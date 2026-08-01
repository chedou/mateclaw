import { acceptHMRUpdate, defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus/es/components/message/index'
import {
  troubleshootingApi,
  type DiagnosisExperienceProjection,
  type DiagnosisStatus,
  type DiagnosisSummary,
  type EvidenceChainPreviewRequest,
  type GuanceEvidenceAcceptanceView,
  type GuanceEvidenceReadiness,
  type GuanceEvidenceSpinePreview,
  type GuanceEvidenceValidationReport,
  type RecordedReplayEvaluationCapability,
  type StoredDiagnosis,
  type TopologyProbeEvidenceRun,
} from '@/api'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import {
  canStartGuanceValidation,
  guanceAcceptanceProgress,
  impactMetrics,
} from '@/views/Troubleshooting/formalProjection'
import {
  canAttachGuanceResultToDiagnosis,
  isActiveGuanceValidationSession,
  sameEvidenceChainLookup,
  type GuanceValidationOrigin,
  type GuanceValidationSessionSnapshot,
} from '@/views/Troubleshooting/guanceOnboarding'
import {
  replayEvaluationCaptureContext,
  suggestedEvaluationScenarioKey,
  type EvaluationSampleCaptureContext,
} from '@/views/Troubleshooting/evaluationSamples'
import {
  type WorkbenchDiagnosisViewMode,
  type WorkbenchViewMode,
  diagnosisSelectionMode,
  workbenchViewQuery,
} from '@/views/Troubleshooting/workbenchView'
import { shouldShowDeploymentTopologyProbe } from '@/views/Troubleshooting/deploymentTopologySop'

function errorText(error: unknown) { return error instanceof Error ? error.message : String(error) }

export const useTroubleshootingStore = defineStore('troubleshooting', () => {
  const router = useRouter()
  const route = useRoute()
  const workspaceStore = useWorkspaceStore()

  // ── permissions ──────────────────────────────────────────────
  const canOperateTroubleshooting = computed(() => workspaceStore.can('operate:troubleshooting'))
  const canManageTroubleshooting = computed(() => workspaceStore.can('manage:troubleshooting'))
  const canAcceptGuanceOwner = computed(() => workspaceStore.isAtLeast('owner'))

  // ── core data ────────────────────────────────────────────────
  const rows = ref<DiagnosisSummary[]>([])
  const selectedId = ref<string | null>(null)
  const current = ref<StoredDiagnosis | null>(null)
  const projection = ref<DiagnosisExperienceProjection | null>(null)
  const topologyProbeRuns = ref<TopologyProbeEvidenceRun[]>([])
  const statusFilter = ref<DiagnosisStatus | ''>('')
  const viewMode = ref<WorkbenchViewMode>('LIST')

  // ── search / filter / sort ──────────────────────────────────
  const searchKeyword = ref('')
  const systemFilter = ref('')
  const sortField = ref<'updateTime' | 'createTime' | 'service' | 'status'>('updateTime')
  const sortOrder = ref<'asc' | 'desc'>('desc')

  // ── batch operation ─────────────────────────────────────────
  const selectedIds = ref<Set<string>>(new Set())
  const batchOperating = ref(false)

  // ── loading states ──────────────────────────────────────────
  const listLoading = ref(false)
  const detailLoading = ref(false)
  const actionLoading = ref(false)
  const readinessLoading = ref(false)

  // ── guance state ────────────────────────────────────────────
  const guanceReadiness = ref<GuanceEvidenceReadiness | null>(null)
  const guanceValidation = ref<GuanceEvidenceValidationReport | null>(null)
  const guanceSpinePreview = ref<GuanceEvidenceSpinePreview | null>(null)
  const guanceOwnerAcceptance = ref<GuanceEvidenceAcceptanceView | null>(null)
  const guanceDiagnosisLookup = ref<EvidenceChainPreviewRequest | null>(null)
  const replayCapability = ref<RecordedReplayEvaluationCapability | null>(null)
  const readinessError = ref('')

  // ── version counters (race-condition protection) ────────────
  let selectionVersion = 0
  let guanceValidationSessionVersion = 0

  // ── computed ─────────────────────────────────────────────────
  const business = computed(() => projection.value?.businessSummary ?? null)
  const developer = computed(() => projection.value?.developerEvidence ?? null)
  const closure = computed(() => current.value?.diagnosis.closure ?? null)
  const deploymentTopologyRequired = computed(() =>
    shouldShowDeploymentTopologyProbe(developer.value, selectedId.value))
  const latestTopologyProbeRun = computed(() => topologyProbeRuns.value[0] ?? null)
  const impactMetricList = computed(() => {
    const impact = business.value?.impact
    return impact ? impactMetrics(impact.affectedCustomers, impact.affectedUsers) : []
  })
  const canTransfer = computed(() => canOperateTroubleshooting.value
    && ['CONFIRMED', 'TRANSFERRED'].includes(current.value?.diagnosis.status || ''))
  const canClose = computed(() => canTransfer.value)
  const canValidateGuance = computed(() => {
    const status = guanceReadiness.value?.status
    return status ? canStartGuanceValidation(status) : false
  })
  const guanceAcceptance = computed(() => guanceReadiness.value
    ? guanceAcceptanceProgress(guanceReadiness.value, guanceOwnerAcceptance.value)
    : null)
  const currentDiagnosisEvidenceLookup = computed<EvidenceChainPreviewRequest | null>(() => {
    const incident = current.value?.diagnosis.incident
    if (!incident) return null
    return {
      system: incident.system,
      service: incident.service,
      searchTerm: incident.errorCode || '',
      window: '-15m',
      occurredAt: incident.occurredAt,
    }
  })
  const evaluationCaptureContext = computed<EvaluationSampleCaptureContext | null>(() => {
    const diagnosis = current.value?.diagnosis
    const incident = diagnosis?.incident
    if (!diagnosis || !incident) return null
    const currentLookup = currentDiagnosisEvidenceLookup.value
    const frozenLookup = guanceDiagnosisLookup.value
    const lookup = currentLookup && frozenLookup && sameEvidenceChainLookup(currentLookup, frozenLookup)
      ? frozenLookup
      : currentLookup
    const searchTerm = lookup?.searchTerm.trim() || ''
    if (!searchTerm) return null
    return {
      diagnosisId: diagnosis.diagnosisId,
      system: incident.system,
      service: incident.service,
      scenarioKey: suggestedEvaluationScenarioKey(incident.errorCode),
      searchTerm,
      window: lookup?.window || '-15m',
    }
  })
  const replayEvaluationCaptureContextValue = computed(() => {
    const diagnosis = current.value?.diagnosis
    const incident = diagnosis?.incident
    return replayEvaluationCaptureContext(diagnosis && incident ? {
      diagnosisId: diagnosis.diagnosisId,
      system: incident.system,
      service: incident.service,
    } : null, replayCapability.value)
  })
  const canCaptureEvaluationSample = computed(() => canManageTroubleshooting.value
    && guanceOwnerAcceptance.value?.status === 'ACCEPTED'
    && Boolean(guanceDiagnosisLookup.value)
    && Boolean(evaluationCaptureContext.value)
    && Boolean(guanceSpinePreview.value)
    && guanceSpinePreview.value?.stage !== 'BLOCKED')
  const evaluationCaptureDisabledReason = computed(() => {
    if (!canManageTroubleshooting.value) return '当前 Workspace 缺少 manage:troubleshooting 权限。'
    if (!evaluationCaptureContext.value) return '当前 Diagnosis 没有可安全映射的搜索键。'
    if (guanceOwnerAcceptance.value?.status === 'STALE') return 'Guance 绑定配置已变化，必须重新完成 T7 owner 验收。'
    if (guanceOwnerAcceptance.value?.status !== 'ACCEPTED') return '当前 Guance 绑定尚未完成持久化 T7 owner 验收。'
    return '先在真源验收中取得一条非 BLOCKED Evidence Spine，再采集历史样本。'
  })
  const canCaptureReplayEvaluationSample = computed(() => canManageTroubleshooting.value
    && Boolean(replayEvaluationCaptureContextValue.value))
  const replayCaptureDisabledReason = computed(() => {
    if (!canManageTroubleshooting.value) return '当前 Workspace 缺少 manage:troubleshooting 权限。'
    return replayCapability.value?.reason || '服务端尚未确认 Replay 能力与 fixture 登记范围。'
  })

  // ── filtered / sorted rows ──────────────────────────────────
  const filteredRows = computed(() => {
    let result = rows.value
    if (searchKeyword.value) {
      const kw = searchKeyword.value.toLowerCase()
      result = result.filter(r =>
        r.service?.toLowerCase().includes(kw) ||
        r.errorCode?.toLowerCase().includes(kw) ||
        r.caseId?.toLowerCase().includes(kw) ||
        r.system?.toLowerCase().includes(kw)
      )
    }
    if (systemFilter.value) {
      result = result.filter(r => r.system === systemFilter.value)
    }
    result = [...result].sort((a, b) => {
      const aVal = a[sortField.value] ?? ''
      const bVal = b[sortField.value] ?? ''
      const cmp = aVal < bVal ? -1 : aVal > bVal ? 1 : 0
      return sortOrder.value === 'asc' ? cmp : -cmp
    })
    return result
  })

  const canBatchConfirm = computed(() => selectedIds.value.size > 0)
  const canBatchClose = computed(() => selectedIds.value.size > 0)

  // ── route helpers ────────────────────────────────────────────
  async function replaceWorkbenchRoute(mode: WorkbenchViewMode, diagnosisId?: string | null) {
    const query = { ...route.query }
    delete query.view
    delete query.diagnosisId
    Object.assign(query, workbenchViewQuery(mode, diagnosisId))
    await router.replace({ query })
  }

  // ── guance session helpers ───────────────────────────────────
  function getSelectionVersion() { return selectionVersion }
  function nextGuanceValidationSessionVersion() { return ++guanceValidationSessionVersion }

  function isActiveGuanceValidationRequest(
    version: number,
    session: GuanceValidationSessionSnapshot,
    guanceValidationOpen: boolean,
  ) {
    return version === selectionVersion && isActiveGuanceValidationSession(
      session,
      {
        sessionVersion: guanceValidationSessionVersion,
        origin: session.origin,
        request: { ...session.request },
      },
      guanceValidationOpen,
    )
  }

  function isCurrentGuanceValidationGeneration(
    version: number,
    session: GuanceValidationSessionSnapshot,
  ) {
    return version === selectionVersion
  }

  // ── core actions ─────────────────────────────────────────────
  async function loadList(autoSelect = true) {
    listLoading.value = true
    try {
      const { data } = await troubleshootingApi.list({ status: statusFilter.value || undefined, limit: 100 })
      rows.value = data ?? []
      if (autoSelect && !selectedId.value) {
        const queryId = typeof route.query.diagnosisId === 'string' ? route.query.diagnosisId : null
        const target = queryId || rows.value[0]?.diagnosisId
        if (target) {
          const targetMode = diagnosisSelectionMode(viewMode.value)
          await selectDiagnosis(target, !queryId, targetMode)
        }
      }
    } catch (error) {
      ElMessage.error(`加载排障队列失败：${errorText(error)}`)
    } finally { listLoading.value = false }
  }

  async function selectDiagnosis(
    diagnosisId: string,
    updateQuery = true,
    targetMode: WorkbenchDiagnosisViewMode = diagnosisSelectionMode(viewMode.value),
  ) {
    const version = ++selectionVersion
    viewMode.value = targetMode
    selectedId.value = diagnosisId
    detailLoading.value = true

    // reset shared guance state
    topologyProbeRuns.value = []
    guanceValidation.value = null
    guanceSpinePreview.value = null
    guanceReadiness.value = null
    guanceOwnerAcceptance.value = null
    guanceDiagnosisLookup.value = null
    replayCapability.value = null
    readinessError.value = ''

    try {
      const [projectionResponse, diagnosisResponse] = await Promise.all([
        troubleshootingApi.projection(diagnosisId),
        troubleshootingApi.get(diagnosisId),
      ])
      if (version !== selectionVersion) return
      const topologyRunsResponse = shouldShowDeploymentTopologyProbe(
        projectionResponse.data.developerEvidence,
        diagnosisId,
      )
        ? await troubleshootingApi.diagnosisTopologyProbeRuns(diagnosisId)
        : null
      if (version !== selectionVersion) return
      projection.value = projectionResponse.data
      current.value = diagnosisResponse.data
      topologyProbeRuns.value = topologyRunsResponse?.data ?? []
      const targetQuery = workbenchViewQuery(targetMode, diagnosisId)
      if (updateQuery && (route.query.diagnosisId !== diagnosisId || route.query.view !== targetQuery.view)) {
        await replaceWorkbenchRoute(targetMode, diagnosisId)
      }
      void loadGuanceReadiness(
        diagnosisResponse.data.diagnosis.incident.system,
        diagnosisResponse.data.diagnosis.incident.service,
        version,
      )
    } catch (error) {
      if (version !== selectionVersion) return
      projection.value = null
      current.value = null
      topologyProbeRuns.value = []
      ElMessage.error(`加载诊断投影失败：${errorText(error)}`)
    } finally { if (version === selectionVersion) detailLoading.value = false }
  }

  async function loadGuanceReadiness(system: string, service: string, version = selectionVersion) {
    readinessLoading.value = true
    try {
      const [readinessResponse, acceptanceResponse] = await Promise.all([
        troubleshootingApi.evidenceReadiness({ system, service }),
        troubleshootingApi.guanceEvidenceAcceptance({ system, service }),
      ])
      if (version !== selectionVersion) return
      guanceReadiness.value = readinessResponse.data
      guanceOwnerAcceptance.value = acceptanceResponse.data
      readinessError.value = ''
    } catch {
      if (version !== selectionVersion) return
      guanceReadiness.value = null
      guanceOwnerAcceptance.value = null
      readinessError.value = '真源就绪检查暂不可用；不影响当前 Diagnosis 的阅读和处置。'
    } finally {
      if (version === selectionVersion) readinessLoading.value = false
    }
  }

  async function reload() {
    await Promise.all([
      loadList(false),
      selectedId.value ? selectDiagnosis(selectedId.value, false) : Promise.resolve(),
    ])
  }

  function handleTopologyProbeCompleted(run: TopologyProbeEvidenceRun) {
    topologyProbeRuns.value = [run, ...topologyProbeRuns.value
      .filter(item => item.runId !== run.runId)]
  }

  // ── guance actions ───────────────────────────────────────────
  async function validateGuance(
    request: EvidenceChainPreviewRequest,
    session: GuanceValidationSessionSnapshot,
    version: number,
  ) {
    try {
      const response = await troubleshootingApi.validateGuanceEvidence(request)
      if (!isActiveGuanceValidationRequest(version, session, false)) return response
      if (canAttachGuanceResultToDiagnosis(
        session.origin,
        currentDiagnosisEvidenceLookup.value,
        request,
      )) {
        guanceValidation.value = response.data
        guanceReadiness.value = response.data.readiness
      }
      return response
    } catch (error) {
      if (!isActiveGuanceValidationRequest(version, session, false)) return null
      ElMessage.error(`Guance 只读验证失败：${errorText(error)}`)
      return null
    }
  }

  async function acceptGuanceEvidence(
    request: EvidenceChainPreviewRequest & { checklist: any },
    session: GuanceValidationSessionSnapshot,
    version: number,
  ) {
    try {
      const response = await troubleshootingApi.acceptGuanceEvidence(request)
      if (!isActiveGuanceValidationRequest(version, session, false)) return null
      if (canAttachGuanceResultToDiagnosis(
        session.origin,
        currentDiagnosisEvidenceLookup.value,
        request,
      )) guanceOwnerAcceptance.value = response.data
      return response
    } catch (error) {
      if (!isActiveGuanceValidationRequest(version, session, false)) return null
      ElMessage.error(`T7 owner 验收未记录：${errorText(error)}`)
      return null
    }
  }

  async function previewGuanceSpine(
    request: EvidenceChainPreviewRequest,
    session: GuanceValidationSessionSnapshot,
    version: number,
  ) {
    try {
      const response = await troubleshootingApi.previewGuanceEvidenceSpine(request)
      if (!isActiveGuanceValidationRequest(version, session, false)) return null
      if (canAttachGuanceResultToDiagnosis(
        session.origin,
        currentDiagnosisEvidenceLookup.value,
        request,
      )) {
        guanceSpinePreview.value = response.data
        guanceReadiness.value = response.data.readiness
        guanceDiagnosisLookup.value = { ...request }
      }
      return response
    } catch (error) {
      if (!isActiveGuanceValidationRequest(version, session, false)) return null
      ElMessage.error(`Guance Evidence Spine 验证失败：${errorText(error)}`)
      return null
    }
  }

  async function loadReplayCapability(
    diagnosisId: string,
    version = selectionVersion,
  ) {
    try {
      const response = await troubleshootingApi.recordedReplayEvaluationCapability({
        diagnosisId,
      })
      if (version !== selectionVersion) return
      replayCapability.value = response.data
    } catch {
      if (version !== selectionVersion) return
      replayCapability.value = {
        available: false,
        reasonCode: 'CAPABILITY_UNAVAILABLE',
        reason: '服务端 Replay 能力检查暂不可用。',
        scenarioKey: null,
        searchTerm: null,
        window: null,
      }
    }
  }

  async function applyLifecycle(operation: () => Promise<unknown>, message: string) {
    if (!canOperateTroubleshooting.value) {
      ElMessage.error('当前 Workspace 缺少 operate:troubleshooting 权限')
      return false
    }
    actionLoading.value = true
    try {
      await operation()
      await reload()
      ElMessage.success(message)
      return true
    } catch (error) {
      ElMessage.error(errorText(error))
      return false
    } finally { actionLoading.value = false }
  }

  // ── batch actions ───────────────────────────────────────────
  function toggleSelection(id: string) {
    if (selectedIds.value.has(id)) selectedIds.value.delete(id)
    else selectedIds.value.add(id)
    selectedIds.value = new Set(selectedIds.value)
  }
  function clearSelection() { selectedIds.value = new Set() }
  function selectAll() {
    filteredRows.value.forEach(r => selectedIds.value.add(r.diagnosisId))
    selectedIds.value = new Set(selectedIds.value)
  }

  async function batchConfirm() {
    batchOperating.value = true
    try {
      const ids = [...selectedIds.value]
      await Promise.allSettled(ids.map(id => troubleshootingApi.confirm(id)))
      await loadList(false)
      clearSelection()
    } finally { batchOperating.value = false }
  }

  async function batchClose() {
    batchOperating.value = true
    try {
      const ids = [...selectedIds.value]
      await Promise.allSettled(ids.map(id => troubleshootingApi.close(id, {
        outcome: 'UNRESOLVED',
        summary: '批量关闭',
        recoveryVerified: false,
        createKnowledgeCandidate: false,
      })))
      await loadList(false)
      clearSelection()
    } finally { batchOperating.value = false }
  }

  async function confirmDiagnosis() {
    return applyLifecycle(() => troubleshootingApi.confirm(selectedId.value!), '已确认诊断结论')
  }

  return {
    // permissions
    canOperateTroubleshooting,
    canManageTroubleshooting,
    canAcceptGuanceOwner,
    // core data
    rows,
    selectedId,
    current,
    projection,
    topologyProbeRuns,
    statusFilter,
    viewMode,
    // search / filter / sort
    searchKeyword,
    systemFilter,
    sortField,
    sortOrder,
    filteredRows,
    // batch
    selectedIds,
    batchOperating,
    canBatchConfirm,
    canBatchClose,
    // loading
    listLoading,
    detailLoading,
    actionLoading,
    readinessLoading,
    // guance
    guanceReadiness,
    guanceValidation,
    guanceSpinePreview,
    guanceOwnerAcceptance,
    guanceDiagnosisLookup,
    replayCapability,
    readinessError,
    // computed
    business,
    developer,
    closure,
    deploymentTopologyRequired,
    latestTopologyProbeRun,
    impactMetricList,
    canTransfer,
    canClose,
    canValidateGuance,
    guanceAcceptance,
    currentDiagnosisEvidenceLookup,
    evaluationCaptureContext,
    replayEvaluationCaptureContextValue,
    canCaptureEvaluationSample,
    evaluationCaptureDisabledReason,
    canCaptureReplayEvaluationSample,
    replayCaptureDisabledReason,
    // version helpers
    getSelectionVersion,
    nextGuanceValidationSessionVersion,
    isActiveGuanceValidationRequest,
    isCurrentGuanceValidationGeneration,
    // actions
    loadList,
    selectDiagnosis,
    loadGuanceReadiness,
    reload,
    handleTopologyProbeCompleted,
    replaceWorkbenchRoute,
    validateGuance,
    acceptGuanceEvidence,
    previewGuanceSpine,
    loadReplayCapability,
    applyLifecycle,
    confirmDiagnosis,
    // batch actions
    toggleSelection,
    clearSelection,
    selectAll,
    batchConfirm,
    batchClose,
  }
})

if (import.meta.hot) {
  import.meta.hot.accept(acceptHMRUpdate(useTroubleshootingStore, import.meta.hot))
}
