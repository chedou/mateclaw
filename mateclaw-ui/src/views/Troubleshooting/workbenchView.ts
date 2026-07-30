import type { DiagnosisStatus } from '@/api'

export type WorkbenchViewSwitchMode = 'LIST' | 'QUEUE'
export type WorkbenchViewMode = WorkbenchViewSwitchMode | 'DETAIL'
export type WorkbenchDiagnosisViewMode = Exclude<WorkbenchViewMode, 'LIST'>
export type WorkbenchCapabilityCommand = 'playbooks' | 'synthesis' | 'guance' | 'deployment' | 'ledger'

export const WORKBENCH_DIAGNOSIS_STATUSES: DiagnosisStatus[] = [
  'READY_FOR_HUMAN',
  'NEEDS_INVESTIGATION',
  'CONFIRMED',
  'TRANSFERRED',
  'CLOSED',
]

export const WORKBENCH_CAPABILITY_ACTIONS: ReadonlyArray<{
  command: WorkbenchCapabilityCommand
  label: string
}> = [
  { command: 'playbooks', label: 'Playbook 管理' },
  { command: 'synthesis', label: '无码证据预览' },
  { command: 'guance', label: 'P2 真源接入' },
  { command: 'deployment', label: '部署图拨测 SOP' },
  { command: 'ledger', label: 'T8 样本台账' },
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
