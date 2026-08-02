import type {
  ClosureOutcome,
  ConclusionType,
  GuanceEvidenceAcceptanceView,
  GuanceEvidenceReadiness,
  GuanceReadinessStatus,
  GuanceSignalStatus,
  GuanceSpinePreviewStage,
  GuanceValidationStage,
  InvestigationMode,
  KnowledgeEvidenceGrade,
  RouteAuthority,
} from '@/api'

const CONCLUSION_LABEL: Record<ConclusionType, string> = {
  LOCATED: '已定位',
  EXCLUDED: '已排除（非定位）',
  HYPOTHESIS: '根因假设',
  INSUFFICIENT_EVIDENCE: '证据不足',
}

const CLOSURE_OUTCOME_LABEL: Record<ClosureOutcome, string> = {
  RECOVERED: '已恢复',
  FALSE_POSITIVE: '误报',
  TRANSFERRED_OUT: '已转出处置',
  UNRESOLVED: '未解决',
}

const INVESTIGATION_LABEL: Record<InvestigationMode, string> = {
  ERROR_CODE_PLAYBOOK: '错误码 Playbook',
  SCENARIO_PLAYBOOK: '场景 Playbook',
  OPEN_DISCOVERY: '开放调查',
}

const AUTHORITY_LABEL: Record<RouteAuthority, string> = {
  EXPLICIT: '显式命中',
  RULE_MATCHED: '规则命中',
  MODEL_PROPOSED: '模型提议',
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
  BLOCKED: '单次规范化读链未通过',
  CANONICAL_CHAIN_OBSERVED: '单次规范化读链通过（待 T7 字段验收）',
}

const GUANCE_SPINE_PREVIEW_LABEL: Record<GuanceSpinePreviewStage, string> = {
  BLOCKED: '真实 Evidence Spine 未形成',
  CORE_CHAIN_OBSERVED: '核心调用链已观测，成功样本对照缺失',
  FULL_SPINE_OBSERVED: '完整 Evidence Spine 已观测（待 T7/T8 验收）',
}

const GUANCE_OWNER_BLOCKER_LABEL: Record<string, string> = {
  'the current Guance binding has not been explicitly accepted by an owner':
    '当前 Guance 绑定尚未由 Workspace owner 明确验收。',
}

export type GuanceAcceptanceState = 'BLOCKED' | 'READY' | 'OWNER_EVIDENCE_REQUIRED'

export function canStartGuanceValidation(value: GuanceReadinessStatus) {
  return value === 'READY_FOR_VALIDATION' || value === 'CANONICAL_SIGNALS_OBSERVED'
}

export function guanceAcceptanceStateLabel(value: GuanceAcceptanceState) {
  if (value === 'READY') return '就绪'
  if (value === 'OWNER_EVIDENCE_REQUIRED') return '待 owner 证据'
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

export function investigationLabel(mode: InvestigationMode, authority: RouteAuthority) {
  return `${INVESTIGATION_LABEL[mode]} · ${AUTHORITY_LABEL[authority]}`
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
 * Projects the architecture acceptance ladder without pretending that one
 * process-local observation proves owner acceptance or the historical baseline.
 */
export function guanceAcceptanceProgress(
  readiness: GuanceAcceptanceInput,
  ownerAcceptance: GuanceEvidenceAcceptanceView | null = null,
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

  const stages: GuanceAcceptanceStage[] = [
    {
      code: 'T6',
      state: sourceAuthorized ? 'READY' : 'BLOCKED',
      title: sourceAuthorized ? '资产授权与核心绑定已就绪' : '授权接缝未就绪',
      detail: sourceAuthorized
        ? '当前 Workspace 资产与 log_search、log_trace_bundle 已通过秘密无关授权检查。'
        : '必须先建立唯一资产授权，并显式绑定 log_search 与 log_trace_bundle。',
    },
    {
      code: 'T7',
      state: ownerAccepted
        ? 'READY'
        : ownerAcceptanceStale || coreSignalsObserved
          ? 'OWNER_EVIDENCE_REQUIRED'
          : sourceReady ? 'READY' : 'BLOCKED',
      title: ownerAccepted
        ? '当前绑定已完成 owner 验收'
        : ownerAcceptanceStale
          ? '绑定已变更，旧验收已过期'
          : coreSignalsObserved
            ? '核心信号已观测，真链路待验收'
            : sourceReady
              ? '首条真实读链待执行'
              : sourceAuthorized ? '真源运行条件未就绪' : '被 T6 阻断',
      detail: ownerAccepted
        ? `配置指纹已由 ${ownerAcceptance?.acceptance?.acceptedBy || 'owner'} 于 ${ownerAcceptance?.acceptance?.acceptedAt || '已记录时间'} 核对；验收不会关闭 fixtureMode。`
        : ownerAcceptanceStale
          ? '查询模板、字段映射、路由或端点发生变化，必须重新执行真实同 PS ID 链并完成 owner 清单。'
          : coreSignalsObserved
            ? '当前进程已分别观测两个核心信号；该状态不证明同一 PS ID，仍需验证报告核实 measurement、字段、索引、时间单位/窗、DQL 延迟与 903001 冲突。'
            : sourceReady
              ? '用会议案例执行 Guance-only 的 log_search → log_trace_bundle。'
              : sourceAuthorized
                ? '端点、运行时凭据或适配器尚未就绪，不得发起真实查询。'
                : 'T6 未就绪前不得查询真实观测资产。',
    },
    {
      code: 'T8',
      state: ownerAccepted && sourceReady ? 'READY' : 'BLOCKED',
      title: ownerAccepted && sourceReady
        ? '真实历史样本采集已解锁'
        : '历史样本基线未开始',
      detail: ownerAccepted && sourceReady
        ? '可开始积累 20–30 条真实样本；当前只是具备采集条件，不代表 T8 已通过。'
        : ownerAccepted
          ? 'T7 验收仍保留，但当前真源运行条件未就绪，不能采集 T8 样本。'
          : '等待当前绑定完成 T7 owner 验收，再建立 20–30 条真实样本。',
    },
  ]

  const nextAction = (() => {
    if (ownerAccepted && sourceReady) {
      return '当前绑定已完成 T7 owner 验收；从关闭 Diagnosis 积累 20–30 条真实样本、冻结参考解并运行单 Agent 基线。'
    }
    if (ownerAcceptanceStale) {
      return 'Guance 配置指纹已变化；重新执行同 PS ID 两步读链并完成 T7 owner 清单。'
    }
    switch (status) {
      case 'DISABLED':
        return '由 owner 启用 Guance 适配器；启用本身不会授予任何 Workspace 资产。'
      case 'UNAUTHORIZED':
        return '为当前 Workspace / system / service 配置唯一资产授权与 log_search、log_trace_bundle 绑定。'
      case 'CONFIGURATION_INCOMPLETE':
        return '在精确资产授权后补齐 Guance 端点与运行时凭据；凭据不得进入页面、日志或领域表。'
      case 'READY_FOR_VALIDATION':
        return '由管理员用会议案例执行一次只读真源链路，核对同一 PS ID 与每步 Guance 取证耗时。'
      case 'CANONICAL_SIGNALS_OBSERVED':
        return '由 owner 用验证报告确认同一 PS ID 并完成 T7 字段验收，再进入 T8 样本基线；fixtureMode 继续保持开启。'
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
