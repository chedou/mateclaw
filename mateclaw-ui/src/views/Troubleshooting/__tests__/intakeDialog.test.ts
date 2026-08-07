import { describe, expect, it } from 'vitest'
import { INCIDENT_SEVERITY_OPTIONS } from '../intakeDialog'

describe('shared troubleshooting intake fields', () => {
  it('offers the same four severity levels to every intake dialog', () => {
    expect(INCIDENT_SEVERITY_OPTIONS).toEqual([
      { value: 'P0', label: 'P0 · 全局阻断' },
      { value: 'P1', label: 'P1 · 核心故障' },
      { value: 'P2', label: 'P2 · 一般故障' },
      { value: 'P3', label: 'P3 · 低优先级' },
    ])
  })
})
