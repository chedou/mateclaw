import type { IncidentSeverity } from '@/api'

export const INCIDENT_SEVERITY_OPTIONS: ReadonlyArray<{
  value: IncidentSeverity
  label: string
}> = [
  { value: 'P0', label: 'P0 · 全局阻断' },
  { value: 'P1', label: 'P1 · 核心故障' },
  { value: 'P2', label: 'P2 · 一般故障' },
  { value: 'P3', label: 'P3 · 低优先级' },
]
