import type { DiagnosisStatus } from '@/api'

export type WorkbenchViewMode = 'LIST' | 'QUEUE'
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

export const DEFAULT_WORKBENCH_VIEW: WorkbenchViewMode = 'LIST'

export function resolveWorkbenchView(
  queryView: unknown,
  diagnosisId: unknown,
): WorkbenchViewMode {
  if (queryView === 'list') return 'LIST'
  if (queryView === 'queue') return 'QUEUE'
  return typeof diagnosisId === 'string' && diagnosisId.trim() ? 'QUEUE' : DEFAULT_WORKBENCH_VIEW
}

export function workbenchViewQuery(
  mode: WorkbenchViewMode,
  diagnosisId?: string | null,
): Record<string, string> {
  if (mode === 'LIST') return { view: 'list' }
  return diagnosisId
    ? { view: 'queue', diagnosisId }
    : { view: 'queue' }
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
