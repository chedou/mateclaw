import type { DiagnosisStatus, InvestigationMode } from '@/api'

export type WorkbenchViewSwitchMode = 'LIST' | 'QUEUE'
export type WorkbenchViewMode = WorkbenchViewSwitchMode | 'DETAIL'
export type WorkbenchDiagnosisViewMode = Exclude<WorkbenchViewMode, 'LIST'>
export type WorkbenchCapabilityCommand =
  | 'playbooks'
  | 'observability-assets'
  | 'guance'
  | 'ledger'
  | 'case-knowledge'
export type TroubleshootingScenarioCommand =
  | 'cti-create-conversation-failed'
  | 'message-send-failed'
  | 'incident'
  | 'deployment'
export type TroubleshootingScenarioDefinition = {
  command: TroubleshootingScenarioCommand
  label: string
  description: string
  outcome: string
  manageOnly: boolean
}

export const TROUBLESHOOTING_UI_LABELS = {
  launch: '发起排障',
  scenarioPicker: '选择排障场景',
  incident: '通用事件排障',
  ctiCreateConversationFailed: 'CTI 创建会话失败',
  messageSendFailed: '会话消息发送失败',
  rules: '排障规则库',
  evidenceCatalog: '查询规则说明书',
  observabilityAssets: '取证接入',
  historyReplay: '历史样本回放',
  guanceOnboarding: '数据源联调',
  guanceSourceStatus: '观测云真实数据源状态',
  guanceValidation: '真实数据验证',
  deploymentTopology: '部署拓扑拨测分析',
  evaluation: '诊断效果评估',
  caseKnowledge: '历史案例入库',
} as const

export const WORKBENCH_DIAGNOSIS_STATUSES: DiagnosisStatus[] = [
  'READY_FOR_HUMAN',
  'NEEDS_INVESTIGATION',
  'CONFIRMED',
  'TRANSFERRED',
  'CLOSED',
]

export const WORKBENCH_INVESTIGATION_MODES: InvestigationMode[] = [
  'ERROR_CODE_PLAYBOOK',
  'SCENARIO_PLAYBOOK',
  'OPEN_DISCOVERY',
]

export const WORKBENCH_CAPABILITY_ACTIONS: ReadonlyArray<{
  command: WorkbenchCapabilityCommand
  label: string
}> = [
  { command: 'playbooks', label: TROUBLESHOOTING_UI_LABELS.rules },
  { command: 'observability-assets', label: TROUBLESHOOTING_UI_LABELS.observabilityAssets },
  { command: 'ledger', label: TROUBLESHOOTING_UI_LABELS.evaluation },
  { command: 'case-knowledge', label: TROUBLESHOOTING_UI_LABELS.caseKnowledge },
]

export const WORKBENCH_TROUBLESHOOTING_SCENARIOS: ReadonlyArray<TroubleshootingScenarioDefinition> = [
  {
    command: 'cti-create-conversation-failed',
    label: TROUBLESHOOTING_UI_LABELS.ctiCreateConversationFailed,
    description: '真实 csdp-task 场景：查失败日志、还原关联调用链，再用成功样本排除背景噪声。',
    outcome: '三次只读取证',
    manageOnly: false,
  },
  {
    command: 'message-send-failed',
    label: TROUBLESHOOTING_UI_LABELS.messageSendFailed,
    description: '首条完整竖线：先查失败请求，再沿 PS ID 还原调用链，最后对比成功与失败样本。',
    outcome: '三次只读取证',
    manageOnly: false,
  },
  {
    command: 'incident',
    label: TROUBLESHOOTING_UI_LABELS.incident,
    description: '按系统、服务、故障现象与可选错误码发起调查，进入 Diagnosis 处置主链。',
    outcome: '创建 Diagnosis',
    manageOnly: false,
  },
  {
    command: 'deployment',
    label: TROUBLESHOOTING_UI_LABELS.deploymentTopology,
    description: '显式创建受控 Scenario Diagnosis，再选择 Workspace 拓扑资产并将安全结果写入详情。',
    outcome: '创建场景 Diagnosis',
    manageOnly: true,
  },
]

const STATUS_LABEL: Record<DiagnosisStatus, string> = {
  READY_FOR_HUMAN: '待确认',
  NEEDS_INVESTIGATION: '需人工深查',
  CONFIRMED: '已确认',
  TRANSFERRED: '已转派',
  CLOSED: '已关闭',
}

type WorkbenchViewPolicy = {
  queryValue: Lowercase<WorkbenchViewMode>
  diagnosisRoute: 'omit' | 'optional' | 'required'
  selectionMode: WorkbenchDiagnosisViewMode
  showQueuePanel: boolean
}

const WORKBENCH_VIEW_POLICY: Record<WorkbenchViewMode, WorkbenchViewPolicy> = {
  LIST: { queryValue: 'list', diagnosisRoute: 'omit', selectionMode: 'DETAIL', showQueuePanel: false },
  QUEUE: { queryValue: 'queue', diagnosisRoute: 'optional', selectionMode: 'QUEUE', showQueuePanel: true },
  DETAIL: { queryValue: 'detail', diagnosisRoute: 'required', selectionMode: 'DETAIL', showQueuePanel: false },
}

export const DEFAULT_WORKBENCH_VIEW: WorkbenchViewMode = 'LIST'

function hasDiagnosisId(diagnosisId: unknown): diagnosisId is string {
  return typeof diagnosisId === 'string' && Boolean(diagnosisId.trim())
}

function modeFromQuery(queryView: unknown): WorkbenchViewMode | null {
  if (typeof queryView !== 'string') return null
  const entry = (Object.entries(WORKBENCH_VIEW_POLICY) as Array<[WorkbenchViewMode, WorkbenchViewPolicy]>)
    .find(([, policy]) => policy.queryValue === queryView)
  return entry?.[0] ?? null
}

export function resolveWorkbenchView(
  queryView: unknown,
  diagnosisId: unknown,
): WorkbenchViewMode {
  const requestedMode = modeFromQuery(queryView)
  if (requestedMode) {
    const policy = WORKBENCH_VIEW_POLICY[requestedMode]
    return policy.diagnosisRoute === 'required' && !hasDiagnosisId(diagnosisId)
      ? DEFAULT_WORKBENCH_VIEW
      : requestedMode
  }
  return hasDiagnosisId(diagnosisId) ? 'QUEUE' : DEFAULT_WORKBENCH_VIEW
}

export function workbenchViewQuery(
  mode: WorkbenchViewMode,
  diagnosisId?: string | null,
): Record<string, string> {
  const policy = WORKBENCH_VIEW_POLICY[mode]
  if (policy.diagnosisRoute === 'omit') return { view: policy.queryValue }
  if (policy.diagnosisRoute === 'required' && !hasDiagnosisId(diagnosisId)) {
    return { view: WORKBENCH_VIEW_POLICY[DEFAULT_WORKBENCH_VIEW].queryValue }
  }
  return hasDiagnosisId(diagnosisId)
    ? { view: policy.queryValue, diagnosisId }
    : { view: policy.queryValue }
}

export function shouldShowQueuePanel(mode: WorkbenchViewMode): boolean {
  return WORKBENCH_VIEW_POLICY[mode].showQueuePanel
}

export function isDiagnosisViewMode(mode: WorkbenchViewMode): mode is WorkbenchDiagnosisViewMode {
  return WORKBENCH_VIEW_POLICY[mode].diagnosisRoute !== 'omit'
}

export function diagnosisSelectionMode(mode: WorkbenchViewMode): WorkbenchDiagnosisViewMode {
  return WORKBENCH_VIEW_POLICY[mode].selectionMode
}

export function diagnosisStatusLabel(status: DiagnosisStatus): string {
  return STATUS_LABEL[status]
}

export function diagnosisStatusTone(status: DiagnosisStatus): 'active' | 'warning' | 'success' | 'muted' {
  if (status === 'NEEDS_INVESTIGATION') return 'warning'
  if (status === 'CLOSED') return 'muted'
  if (status === 'CONFIRMED' || status === 'TRANSFERRED') return 'success'
  return 'active'
}

export function formatWorkbenchTime(value?: string | null): string {
  return value ? value.replace('T', ' ').replace(/\.\d+Z?$/, '').slice(0, 19) : '—'
}
