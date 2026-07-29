import type {
  ClosureOutcome,
  ConclusionType,
  GuanceReadinessStatus,
  GuanceSignalStatus,
  GuanceValidationStage,
  InvestigationMode,
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

const GUANCE_READINESS_LABEL: Record<GuanceReadinessStatus, string> = {
  DISABLED: '适配器未启用',
  CONFIGURATION_INCOMPLETE: '运行时配置不完整',
  UNAUTHORIZED: 'Workspace 资产未授权',
  READY_FOR_VALIDATION: '可执行单次验证',
  CANONICAL_SIGNALS_OBSERVED: '已观测规范化读链',
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
  CANONICAL_CHAIN_OBSERVED: '单次规范化读链通过（非 T7 验收）',
}

export function conclusionLabel(value: ConclusionType) {
  return CONCLUSION_LABEL[value]
}

export function closureOutcomeLabel(value: ClosureOutcome) {
  return CLOSURE_OUTCOME_LABEL[value]
}

export function investigationLabel(mode: InvestigationMode, authority: RouteAuthority) {
  return `${INVESTIGATION_LABEL[mode]} · ${AUTHORITY_LABEL[authority]}`
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
