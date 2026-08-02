import type { DiagnosisStatus, InvestigationMode } from '@/api'

export type WorkbenchViewSwitchMode = 'LIST' | 'QUEUE'
export type WorkbenchViewMode = WorkbenchViewSwitchMode | 'DETAIL'
export type WorkbenchDiagnosisViewMode = Exclude<WorkbenchViewMode, 'LIST'>
export type WorkbenchCapabilityCommand = 'playbooks' | 'synthesis' | 'guance' | 'ledger'
export type TroubleshootingScenarioCommand = 'incident' | 'deployment'
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
  rules: '排障规则库',
  noCodePreview: '无码场景预演',
  guanceOnboarding: '观测云接入与验收',
  guanceValidation: '观测云只读验收',
  deploymentTopology: '部署拓扑拨测分析',
  evaluation: '诊断效果评估',
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
  { command: 'synthesis', label: TROUBLESHOOTING_UI_LABELS.noCodePreview },
  { command: 'guance', label: TROUBLESHOOTING_UI_LABELS.guanceOnboarding },
  { command: 'ledger', label: TROUBLESHOOTING_UI_LABELS.evaluation },
]

export const WORKBENCH_TROUBLESHOOTING_SCENARIOS: ReadonlyArray<TroubleshootingScenarioDefinition> = [
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
