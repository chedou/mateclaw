import type { DiagnosisStatus, InvestigationMode } from '@/api'

export type WorkbenchViewSwitchMode = 'LIST' | 'QUEUE'
export type WorkbenchViewMode = WorkbenchViewSwitchMode | 'DETAIL'
export type WorkbenchDiagnosisViewMode = Exclude<WorkbenchViewMode, 'LIST'>
export type WorkbenchCapabilityCommand =
  | 'playbooks'
  | 'observability-assets'
  | 't7-owner-contract'
  | 'guance'
  | 'ledger'
  | 'case-knowledge'
export type TroubleshootingScenarioCommand =
  | 'cti-create-conversation-failed'
  | 'message-send-failed'
  | 'incident'
  | 'deployment'
export type TroubleshootingScenarioGroup = 'daily' | 'known' | 'admin'
export type TroubleshootingScenarioDefinition = {
  command: TroubleshootingScenarioCommand
  label: string
  description: string
  outcome: string
  /** daily = 粘贴告警主路径；known = 已登记场景；admin = 管理员专项 */
  group: TroubleshootingScenarioGroup
  manageOnly: boolean
}

export const TROUBLESHOOTING_UI_LABELS = {
  launch: '发起排障',
  firstUse: '第一次使用？',
  firstUseTitle: '第一次使用智能排障',
  startRehearsal: '开始演练',
  scenarioPicker: '选择已登记场景',
  incident: '粘贴告警发起',
  conversation: '对话发起排障',
  ctiCreateConversationFailed: '创建会话失败',
  messageSendFailed: '消息发送失败',
  rules: '排障规则库',
  evidenceCatalog: '查询规则说明书',
  observabilityAssets: '接入系统',
  historyReplay: '历史样本回放',
  guanceOnboarding: '数据连接检查',
  guanceSourceStatus: '观测云真实数据源状态',
  guanceValidation: '真实数据验证',
  deploymentTopology: '部署拓扑拨测',
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
    command: 'incident',
    label: TROUBLESHOOTING_UI_LABELS.incident,
    description: '把告警里的系统、服务、现象填进来即可；有错误码优先走标准方案。',
    outcome: '最常用',
    group: 'daily',
    manageOnly: false,
  },
  {
    command: 'cti-create-conversation-failed',
    label: TROUBLESHOOTING_UI_LABELS.ctiCreateConversationFailed,
    description: 'CSDP 已登记场景。系统和服务已锁定，按标准步骤查失败与对照。',
    outcome: '已有标准方法',
    group: 'known',
    manageOnly: false,
  },
  {
    command: 'message-send-failed',
    label: TROUBLESHOOTING_UI_LABELS.messageSendFailed,
    description: '会话消息发送失败的已登记场景。按固定三步只读取证。',
    outcome: '已有标准方法',
    group: 'known',
    manageOnly: false,
  },
  {
    command: 'deployment',
    label: TROUBLESHOOTING_UI_LABELS.deploymentTopology,
    description: '先建排障单，再选拓扑做只读拨测。适合管理员专项。',
    outcome: '管理员',
    group: 'admin',
    manageOnly: true,
  },
]

/** 日常主路径以外的已登记 / 管理员场景，用于次要入口。 */
export function workbenchSecondaryScenarios(
  canOperate: boolean,
  canManage: boolean,
): TroubleshootingScenarioDefinition[] {
  return WORKBENCH_TROUBLESHOOTING_SCENARIOS.filter((scenario) => {
    if (scenario.group === 'daily') return false
    if (scenario.manageOnly) return canManage
    return canOperate
  })
}

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
