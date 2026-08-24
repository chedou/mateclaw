import type {
  ClosureOutcome,
  ConclusionType,
  EvidenceResult,
  GuanceEvidenceAcceptanceView,
  GuanceEvidenceReadiness,
  GuanceRecordingBatchReadiness,
  GuanceReadinessStatus,
  GuanceSignalStatus,
  GuanceSpinePreviewStage,
  GuanceValidationStage,
  InvestigationMode,
  KnowledgeEvidenceGrade,
  OpenDiscoveryReadiness,
  RouteAuthority,
  RouteSemanticsProvenance,
} from '@/api'

const CONCLUSION_LABEL: Record<ConclusionType, string> = {
  LOCATED: '已定位原因',
  EXCLUDED: '已排除（非定位）',
  HYPOTHESIS: '最可能方向',
  INSUFFICIENT_EVIDENCE: '证据不足',
}

const CLOSURE_OUTCOME_LABEL: Record<ClosureOutcome, string> = {
  RECOVERED: '已恢复',
  FALSE_POSITIVE: '误报',
  TRANSFERRED_OUT: '已转出处置',
  UNRESOLVED: '未解决',
}

const INVESTIGATION_LABEL: Record<InvestigationMode, string> = {
  ERROR_CODE_PLAYBOOK: '标准排障方法（按错误码）',
  SCENARIO_PLAYBOOK: '标准排障方法（按场景）',
  OPEN_DISCOVERY: '通用只读调查',
}

const AUTHORITY_LABEL: Record<RouteAuthority, string> = {
  EXPLICIT: '直接命中',
  RULE_MATCHED: '自动匹配',
  MODEL_PROPOSED: 'AI 规划',
  POLICY_PROPOSED: '受限规划',
}

const KNOWLEDGE_EVIDENCE_GRADE_LABEL: Record<KnowledgeEvidenceGrade, string> = {
  RECORDED_AGGREGATE: '真实录制聚合',
  AUTHORED_FIXTURE: '手写验证夹具',
  UNVERIFIED: '来源未核实',
}

const GUANCE_READINESS_LABEL: Record<GuanceReadinessStatus, string> = {
  DISABLED: '适配器未启用',
  CONFIGURATION_INCOMPLETE: '运行时配置不完整',
  UNAUTHORIZED: 'Workspace 资产未授权',
  READY_FOR_VALIDATION: '可执行单次验证',
  CANONICAL_SIGNALS_OBSERVED: '核心规范化信号已分别观测',
}

const GUANCE_SIGNAL_LABEL: Record<GuanceSignalStatus, string> = {
  NOT_ROUTED: '未路由到 Guance',
  UNAUTHORIZED: '资产未授权',
  INVALID_BINDING: '绑定无效',
  READY_FOR_VALIDATION: '待单次验证',
  CANONICAL_RESULT_OBSERVED: '已观测规范化结果',
}

const GUANCE_VALIDATION_LABEL: Record<GuanceValidationStage, string> = {
  BLOCKED: '日志与调用链验证未通过',
  CANONICAL_CHAIN_OBSERVED: '日志与调用链验证通过（待负责人确认）',
}

const GUANCE_SPINE_PREVIEW_LABEL: Record<GuanceSpinePreviewStage, string> = {
  BLOCKED: '完整取证流程未形成',
  CORE_CHAIN_OBSERVED: '日志与调用链已取得，成功样本对照缺失',
  FULL_SPINE_OBSERVED: '完整取证流程已验证（待负责人确认）',
}

const GUANCE_OWNER_BLOCKER_LABEL: Record<string, string> = {
  'the current Guance binding has not been explicitly accepted by an owner':
    '当前数据源配置尚未由 Workspace 负责人确认。',
}

export interface OpenDiscoveryReadinessPresentation {
  title: string
  detail: string
  alertType: 'success' | 'warning' | 'error'
}

export type GuanceAcceptanceState = 'BLOCKED' | 'READY' | 'OWNER_EVIDENCE_REQUIRED'

export function canStartGuanceValidation(value: GuanceReadinessStatus) {
  return value === 'READY_FOR_VALIDATION' || value === 'CANONICAL_SIGNALS_OBSERVED'
}

export function guanceRecordingBatchReady(
  batch: GuanceRecordingBatchReadiness | null,
) {
  return batch?.readyForOwnerAcceptance === true
}

export function guanceRecordingBatchLabel(
  batch: GuanceRecordingBatchReadiness | null,
) {
  return batch
    ? `Workspace 首批录制目标 · ${batch.executableTargetCount} / 20`
    : 'Workspace 首批录制目标未加载'
}

export function guanceAcceptanceStateLabel(value: GuanceAcceptanceState) {
  if (value === 'READY') return '就绪'
  if (value === 'OWNER_EVIDENCE_REQUIRED') return '待负责人确认'
  return '阻断'
}

export interface GuanceAcceptanceStage {
  code: 'T6' | 'T7' | 'T8'
  state: GuanceAcceptanceState
  title: string
  detail: string
}

export interface GuanceAcceptanceProgress {
  stages: GuanceAcceptanceStage[]
  nextAction: string
}

export type GuanceDetailSourceTone = 'success' | 'active' | 'warning' | 'muted'

export interface GuanceDetailSourceState {
  label: string
  tone: GuanceDetailSourceTone
}

export type DiagnosisEvidenceSourceKind =
  | 'GUANCE'
  | 'RECORDED_REPLAY'
  | 'MIXED'
  | 'OTHER'
  | 'NO_USABLE_EVIDENCE'
  | 'UNRECORDED'

export interface DiagnosisEvidenceSourcePresentation {
  kind: DiagnosisEvidenceSourceKind
  title: string
  detail: string
  showBanner: boolean
}

export type GuanceAcceptanceInput = Pick<
  GuanceEvidenceReadiness,
  'status' | 'uniqueAssetAuthorized' | 'signals'
>

export function conclusionLabel(value: ConclusionType) {
  return CONCLUSION_LABEL[value]
}

export function closureOutcomeLabel(value: ClosureOutcome) {
  return CLOSURE_OUTCOME_LABEL[value]
}

/**
 * The readiness API intentionally keeps operational diagnostics for admins.
 * An intake user only needs to know whether they can start and what action to
 * take next, so raw blockers and internal execution topology never reach this
 * surface.
 */
export function openDiscoveryReadinessPresentation(
  readiness: Pick<OpenDiscoveryReadiness, 'status'>,
): OpenDiscoveryReadinessPresentation {
  switch (readiness.status) {
    case 'READY_FOR_BOUNDED_FALLBACK':
      return {
        title: '通用只读调查已就绪',
        detail: '信息填完整后即可开始；提交时会按当前系统和服务再做一次安全检查。',
        alertType: 'success',
      }
    case 'READY_FOR_REHEARSAL':
      return {
        title: '通用只读调查当前只能演练',
        detail: '要调查真实告警，请联系管理员在“接入系统”中完成真实数据连接和只读能力验收。',
        alertType: 'warning',
      }
    case 'DISABLED':
      return {
        title: '通用只读调查未启用',
        detail: '你可以先使用演练；要调查真实告警，请联系管理员开通通用只读调查。',
        alertType: 'error',
      }
    case 'BLOCKED':
      return {
        title: '通用只读调查尚未就绪',
        detail: '请联系管理员到“接入系统”补齐当前系统和服务的数据连接与只读能力。',
        alertType: 'error',
      }
  }
}

function failureText(error: unknown) {
  if (error instanceof Error) return error.message
  if (typeof error === 'string') return error
  if (error && typeof error === 'object' && 'message' in error
    && typeof error.message === 'string') return error.message
  return ''
}

/** Translate formal admission internals into one user-owned next action. */
export function formalAdmissionErrorMessage(error: unknown) {
  const message = failureText(error)
  if (/changed during formal|configuration changed|authority changed/i.test(message)) {
    return '调查期间配置发生变化，系统已安全停止。请刷新页面后重新开始正式只读调查。'
  }
  if (/requires structured system and service/i.test(message)) {
    return '请先填写系统、服务和故障现象，再开始正式只读调查。'
  }
  if (/requires an enabled pilot plan containing the exact system\/service/i.test(message)) {
    return '当前系统和服务还未开通正式只读调查。请联系管理员在“接入系统”中完成接入后重试。'
  }
  if (/尚未验收通用调查所需的只读能力|accepted read-only capabilities|supported accepted read-only capability/i.test(message)) {
    return '当前系统和服务还缺少可用的只读查询能力。请联系管理员到“接入系统”补齐并验证后重试。'
  }
  if (/owner acceptance|binding has not been explicitly accepted|T7 owner/i.test(message)) {
    return '当前系统和服务的数据源尚未完成正式验收。请联系管理员到“接入系统”完成只读数据验证后重试。'
  }
  if (/bounded read-only (?:planner|investigation).*(?:unavailable|disabled)/i.test(message)) {
    return '通用只读调查当前不可用。你可以先使用演练，或联系管理员检查只读调查配置。'
  }
  return '正式只读调查未完成。请稍后重试；如持续失败，请联系管理员。'
}

export function investigationLabel(mode: InvestigationMode, authority: RouteAuthority) {
  return `${INVESTIGATION_LABEL[mode]} · ${AUTHORITY_LABEL[authority]}`
}

export function investigationModeLabel(mode: InvestigationMode) {
  return INVESTIGATION_LABEL[mode]
}

/**
 * Queue rows must expose whether their v4 route semantics were persisted.
 * Legacy rows stay visible, but the browser never guesses typed values from
 * the compatibility-only RouteMode field.
 */
export function diagnosisSummaryRouteLabel(
  mode: InvestigationMode | null,
  authority: RouteAuthority | null,
  provenance: RouteSemanticsProvenance,
) {
  if (provenance === 'LEGACY_DERIVED') {
    return '旧版记录推导 · 详情可见兼容值'
  }
  if (mode == null || authority == null) {
    return '路由字段缺失'
  }
  return investigationLabel(mode, authority)
}

export function knowledgeEvidenceGradeLabel(value: KnowledgeEvidenceGrade) {
  return KNOWLEDGE_EVIDENCE_GRADE_LABEL[value]
}

export function guanceReadinessLabel(value: GuanceReadinessStatus) {
  return GUANCE_READINESS_LABEL[value]
}

export function guanceSignalLabel(value: GuanceSignalStatus) {
  return GUANCE_SIGNAL_LABEL[value]
}

export function guanceValidationLabel(value: GuanceValidationStage) {
  return GUANCE_VALIDATION_LABEL[value]
}

export function guanceSpinePreviewLabel(value: GuanceSpinePreviewStage) {
  return GUANCE_SPINE_PREVIEW_LABEL[value]
}

/** Keep unknown diagnostics visible while presenting known owner blockers in the UI language. */
export function guanceOwnerBlockerLabel(value: string) {
  return GUANCE_OWNER_BLOCKER_LABEL[value] || value
}

/**
 * Details only need the current environment gate, not the full governance
 * ladder. A current workspace batch blocker wins over an older owner
 * acceptance; otherwise keep the T7 blocker visible in one compact label.
 */
export function guanceDetailSourceState(
  readinessStatus: GuanceReadinessStatus | null,
  ownerAcceptanceStatus: GuanceEvidenceAcceptanceView['status'] | null,
  progress: GuanceAcceptanceProgress | null,
): GuanceDetailSourceState {
  const confirmation = progress?.stages.find(stage => stage.code === 'T7')
  if (confirmation?.state === 'BLOCKED') {
    return { label: confirmation.title, tone: 'warning' }
  }
  if (ownerAcceptanceStatus === 'ACCEPTED') {
    return { label: '当前绑定已验收', tone: 'success' }
  }
  if (ownerAcceptanceStatus === 'STALE') {
    return { label: '配置变化，验收已过期', tone: 'warning' }
  }
  if (confirmation) {
    return {
      label: confirmation.title,
      tone: confirmation.state === 'READY' ? 'active' : 'warning',
    }
  }
  if (readinessStatus) {
    return {
      label: guanceReadinessLabel(readinessStatus),
      tone: canStartGuanceValidation(readinessStatus) ? 'active' : 'warning',
    }
  }
  return { label: '状态暂不可用', tone: 'muted' }
}

/**
 * Evidence-source copy must come from persisted evidence rows, not the
 * conservative fixtureMode fallback. An empty or all-MISSING run proves
 * neither Guance nor Recorded Replay produced usable evidence.
 */
export function diagnosisEvidenceSourcePresentation(
  evidence: readonly Pick<EvidenceResult, 'source' | 'status'>[],
): DiagnosisEvidenceSourcePresentation {
  if (!evidence.length) {
    return {
      kind: 'UNRECORDED',
      title: '证据尚未采集',
      detail: '当前 Diagnosis 还没有记录证据来源，不能判定是真源还是回放。',
      showBanner: true,
    }
  }

  const usable = evidence.filter(item => item.status !== 'MISSING')
  if (!usable.length) {
    return {
      kind: 'NO_USABLE_EVIDENCE',
      title: '尚未取得可用证据',
      detail: '只读取证已结束，但没有返回可进入判据计算的规范化样本；系统已按设计弃权。',
      showBanner: true,
    }
  }

  const hasGuance = usable.some(item => item.source.startsWith('guance:'))
  const hasReplay = usable.some(item => item.source.startsWith('recorded-replay'))
  if (hasGuance && hasReplay) {
    return {
      kind: 'MIXED',
      title: '证据来源需要复核',
      detail: '当前 Diagnosis 同时记录了 Guance 与 Recorded Replay 可用证据，不应直接作为真源结论。',
      showBanner: true,
    }
  }
  if (hasGuance) {
    return {
      kind: 'GUANCE',
      title: 'Guance 真实只读证据',
      detail: '当前 Diagnosis 包含 Guance 返回的可用规范化证据。',
      showBanner: false,
    }
  }
  if (hasReplay) {
    return {
      kind: 'RECORDED_REPLAY',
      title: 'Recorded Replay · 非真实观测云',
      detail: '当前数据来自受控回放；页面不会把回放证据描述成真实生产观测。',
      showBanner: true,
    }
  }
  return {
    kind: 'OTHER',
    title: '其他只读证据源',
    detail: '当前 Diagnosis 包含可用证据，但来源不是 Guance 或 Recorded Replay。',
    showBanner: false,
  }
}

/** Makes environment governance impossible to confuse with this Diagnosis' evidence. */
export function diagnosisGuanceUsageLabel(
  evidence: readonly Pick<EvidenceResult, 'source' | 'status'>[],
): string {
  const state = diagnosisEvidenceSourcePresentation(evidence)
  if (state.kind === 'GUANCE') return '当前 Diagnosis 包含观测云只读证据。'
  if (state.kind === 'RECORDED_REPLAY') {
    return '当前 Diagnosis 使用 Recorded Replay；接入状态仅说明 Workspace 环境能力。'
  }
  if (state.kind === 'NO_USABLE_EVIDENCE') {
    return '当前 Diagnosis 只记录到缺失结果，尚未取得可用的观测云或回放证据。'
  }
  if (state.kind === 'UNRECORDED') return '当前 Diagnosis 尚未记录证据来源。'
  if (state.kind === 'MIXED') return state.detail
  return '当前 Diagnosis 未取得观测云证据；接入状态不会改变现有结论。'
}

/**
 * Projects the architecture acceptance ladder without pretending that one
 * process-local observation proves owner acceptance or the historical baseline.
 */
export function guanceAcceptanceProgress(
  readiness: GuanceAcceptanceInput,
  ownerAcceptance: GuanceEvidenceAcceptanceView | null = null,
  recordingBatch: GuanceRecordingBatchReadiness | null = null,
): GuanceAcceptanceProgress {
  const { status } = readiness
  const coreSignalsAuthorized = ['log_search', 'log_trace_bundle'].every(signalKind =>
    readiness.signals.some(signal => signal.signalKind === signalKind
      && (signal.status === 'READY_FOR_VALIDATION'
        || signal.status === 'CANONICAL_RESULT_OBSERVED')),
  )
  const sourceAuthorized = readiness.uniqueAssetAuthorized && coreSignalsAuthorized
  const sourceReady = canStartGuanceValidation(status)
  const coreSignalsObserved = status === 'CANONICAL_SIGNALS_OBSERVED'
  const ownerAccepted = ownerAcceptance?.status === 'ACCEPTED'
  const ownerAcceptanceStale = ownerAcceptance?.status === 'STALE'
  const executableTargetCount = recordingBatch?.executableTargetCount ?? 0
  const recordingBatchReady = guanceRecordingBatchReady(recordingBatch)
  const recordingBatchUnknown = recordingBatch === null
  // A loaded but incomplete Workspace batch is an unconditional production
  // blocker. Historical owner acceptance must not override the current T7
  // batch, even while the Guance runtime itself is disabled or incomplete.
  const recordingBatchBlocked = recordingBatch !== null && !recordingBatchReady
  const recordingBatchUnavailable = recordingBatchUnknown || recordingBatchBlocked
  const recordingTargetDetail = recordingBatch
    ? `Workspace 首批录制目标当前 ${executableTargetCount} / 20 个可执行（已固定 ${recordingBatch.frozenTargetCount} 个）。这是整个 Workspace 的生产验收批次门，不是当前 system/service 的模块计数；已采集目标不能重复计数，也不代表当前 Diagnosis 没有真源证据。`
    : 'Workspace 首批录制目标尚未加载，不能用当前 system/service 的模块计数替代。'

  const stages: GuanceAcceptanceStage[] = [
    {
      code: 'T6',
      state: sourceAuthorized ? 'READY' : 'BLOCKED',
      title: sourceAuthorized ? '观测资产与核心查询已就绪' : '数据源接入未完成',
      detail: sourceAuthorized
        ? '当前 Workspace 观测资产与日志、调用链查询已经通过授权检查。'
        : '必须先为当前系统登记唯一观测资产，并绑定日志和调用链查询规则。',
    },
    {
      code: 'T7',
      state: recordingBatchUnavailable
        ? 'BLOCKED'
        : ownerAccepted
          ? 'READY'
          : ownerAcceptanceStale || coreSignalsObserved
            ? 'OWNER_EVIDENCE_REQUIRED'
            : sourceReady ? 'READY' : 'BLOCKED',
      title: recordingBatchUnavailable
        ? recordingBatchUnknown
          ? '生产验收批次状态未知'
          : '生产验收批次未准备好'
        : ownerAccepted
          ? '负责人已确认当前数据源配置'
          : ownerAcceptanceStale
            ? '数据源配置已变化，原确认失效'
            : coreSignalsObserved
              ? '日志与调用链已取得，待负责人确认'
              : sourceReady
                ? '等待验证日志与调用链'
                : sourceAuthorized ? '真实数据源运行条件未就绪' : '数据源接入未完成',
      detail: recordingBatchUnavailable
        ? recordingTargetDetail
        : ownerAccepted
          ? `当前配置已由 ${ownerAcceptance?.acceptance?.acceptedBy || '负责人'} 于 ${ownerAcceptance?.acceptance?.acceptedAt || '已记录时间'} 核对；这不会把演示样本当成真实数据。`
          : ownerAcceptanceStale
            ? '查询模板、字段映射、路由或端点发生变化，必须重新验证同一 PS ID 的日志与调用链，并由负责人确认。'
            : coreSignalsObserved
              ? '系统已经分别取得日志和调用链；仍需确认它们属于同一 PS ID，并核实数据集、字段、索引、时间范围和历史冲突。'
              : sourceReady
                ? '使用一条真实历史故障，依次验证失败日志和 PS ID 调用链。'
                : sourceAuthorized
                  ? '端点、运行时凭据或适配器尚未就绪，不得发起真实查询。'
                  : '数据源接入未完成前不得查询真实观测资产。',
    },
    {
      code: 'T8',
      state: recordingBatchReady && ownerAccepted && sourceReady ? 'READY' : 'BLOCKED',
      title: recordingBatchReady && ownerAccepted && sourceReady
        ? '可以开始积累真实样本'
        : '真实样本尚未开始',
      detail: recordingBatchReady && ownerAccepted && sourceReady
        ? '可开始积累 20–30 条真实样本；当前只是具备采集条件，不代表效果已经达标。'
        : recordingBatchUnavailable
          ? recordingTargetDetail
          : ownerAccepted
            ? '负责人确认仍然有效，但当前真实数据源运行条件未就绪，不能采集样本。'
            : '等待当前数据源配置通过负责人确认，再建立 20–30 条真实样本。',
    },
  ]

  const nextAction = (() => {
    if (recordingBatchBlocked || (recordingBatchUnknown && sourceReady)) {
      return recordingBatch
        ? `先在 Workspace 首批录制批次中准备至少 20 个尚未采集的真实案例；当前可执行 ${executableTargetCount} 个。案例达标并补齐历史故障时间后，再安排负责人进行内网验证。`
        : 'Workspace 首批录制批次未加载；请重新获取批次状态，不要用单个模块的目标数量代替。'
    }
    if (ownerAccepted && sourceReady) {
      return '当前数据源配置已由负责人确认；从已关闭排障单积累 20–30 条真实样本、固定参考答案并运行单模型基线。'
    }
    if (ownerAcceptanceStale) {
      return 'Guance 配置已经变化；重新验证同一 PS ID 的日志与调用链，并完成负责人确认清单。'
    }
    switch (status) {
      case 'DISABLED':
        return '由负责人启用 Guance 数据源；启用本身不会授予任何 Workspace 资产。'
      case 'UNAUTHORIZED':
        return '为当前 Workspace / system / service 配置唯一资产授权与 log_search、log_trace_bundle 绑定。'
      case 'CONFIGURATION_INCOMPLETE':
        return '在精确资产授权后补齐 Guance 端点与运行时凭据；凭据不得进入页面、日志或领域表。'
      case 'READY_FOR_VALIDATION':
        return '由管理员用会议案例执行一次只读真源链路，核对同一 PS ID 与每步 Guance 取证耗时。'
      case 'CANONICAL_SIGNALS_OBSERVED':
        return '由负责人根据验证报告确认同一 PS ID 和字段映射，再开始积累真实样本；演示数据状态继续保持开启。'
    }
  })()

  return { stages, nextAction }
}

/** Unknown means unknown: an absent count never becomes a visible zero. */
export function impactMetrics(customers: number | null, users: number | null) {
  const result: string[] = []
  if (customers != null) result.push(`${customers} 个客户`)
  if (users != null) result.push(`${users} 名用户`)
  return result
}

/** Java Duration serializes as ISO-8601 (PT1M25S). */
export function formatDuration(value: string | null) {
  if (!value) return null
  const match = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$/.exec(value)
  if (!match) return value
  const hours = Number(match[1] || 0)
  const minutes = Number(match[2] || 0)
  const seconds = Number(match[3] || 0)
  if (!hours && !minutes && seconds > 0 && seconds < 1) return '<1秒'
  const parts: string[] = []
  if (hours) parts.push(`${hours}小时`)
  if (minutes) parts.push(`${minutes}分${seconds ? '' : '钟'}`)
  if (seconds) parts.push(`${Math.round(seconds)}秒`)
  return parts.join('') || '0秒'
}

export function timingState(
  timestamp: string | null,
  duration: string | null,
  emptyState: 'recorded' | 'pending',
) {
  const formatted = formatDuration(duration)
  if (formatted) return formatted
  if (timestamp) return '已记录'
  return emptyState === 'pending' ? '未发生' : '未记录'
}

/** Seconds carried by an ISO-8601 Java Duration, or null when unrecorded. */
export function durationSeconds(value: string | null): number | null {
  if (!value) return null
  const match = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$/.exec(value)
  if (!match) return null
  return Number(match[1] || 0) * 3600 + Number(match[2] || 0) * 60 + Number(match[3] || 0)
}

export type NorthStarStageKey = 'intake' | 'investigate' | 'adopt'

export interface NorthStarStage {
  key: NorthStarStageKey
  index: number
  /** What is being measured, in the operator's words. */
  label: string
  /** Who pays this cost — the three segments are owned by three different parties. */
  owner: string
  from: string | null
  to: string | null
  cost: string | null
  seconds: number | null
  state: 'RECORDED' | 'PENDING' | 'UNRECORDED'
  display: string
  /** 0–1 share of the total; null unless every stage is recorded (see below). */
  share: number | null
}

interface NorthStarTimingsLike {
  reportedAt: string | null
  readyAt: string | null
  conclusionAt: string | null
  handoffAt: string | null
  intakeCost: string | null
  investigateCost: string | null
  adoptCost: string | null
}

const STAGE_META: Record<NorthStarStageKey, { label: string; owner: string }> = {
  intake: { label: '补问成本', owner: '报障人 ↔ 助手' },
  investigate: { label: '系统调查成本', owner: '平台' },
  adopt: { label: '人的采纳成本', owner: '处置人' },
}

/**
 * Projects D14 timings into three explicitly separate stages.
 *
 * The three segments measure costs paid by three different parties, so they are
 * never summed into one number: a single total cannot tell you whether to
 * improve the follow-up questions, the investigation, or the presentation.
 *
 * `share` is only populated when all three stages are recorded. A proportional
 * bar drawn over a partially recorded set would silently imply a total that the
 * system does not actually know.
 */
export function northStarStages(timings: NorthStarTimingsLike | null | undefined): NorthStarStage[] {
  const source: NorthStarTimingsLike = timings ?? {
    reportedAt: null, readyAt: null, conclusionAt: null, handoffAt: null,
    intakeCost: null, investigateCost: null, adoptCost: null,
  }
  const raw: Array<{
    key: NorthStarStageKey; from: string | null; to: string | null
    cost: string | null; emptyState: 'recorded' | 'pending'
  }> = [
    { key: 'intake', from: source.reportedAt, to: source.readyAt, cost: source.intakeCost, emptyState: 'recorded' },
    { key: 'investigate', from: source.readyAt, to: source.conclusionAt, cost: source.investigateCost, emptyState: 'recorded' },
    { key: 'adopt', from: source.conclusionAt, to: source.handoffAt, cost: source.adoptCost, emptyState: 'pending' },
  ]

  const stages = raw.map((item, index) => {
    const seconds = durationSeconds(item.cost)
    const state: NorthStarStage['state'] = seconds !== null
      ? 'RECORDED'
      : item.emptyState === 'pending' ? 'PENDING' : 'UNRECORDED'
    return {
      key: item.key,
      index: index + 1,
      label: STAGE_META[item.key].label,
      owner: STAGE_META[item.key].owner,
      from: item.from,
      to: item.to,
      cost: item.cost,
      seconds,
      state,
      display: timingState(item.to, item.cost, item.emptyState),
      share: null as number | null,
    }
  })

  const complete = stages.every((stage) => stage.seconds !== null)
  const total = stages.reduce((sum, stage) => sum + (stage.seconds ?? 0), 0)
  if (complete && total > 0) {
    for (const stage of stages) stage.share = (stage.seconds ?? 0) / total
  }
  return stages
}
