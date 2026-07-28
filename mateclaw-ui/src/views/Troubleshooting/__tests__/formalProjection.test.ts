import { describe, expect, it } from 'vitest'
import {
  conclusionLabel,
  formatDuration,
  impactMetrics,
  investigationLabel,
  timingState,
} from '../formalProjection'

describe('formal troubleshooting projection formatting', () => {
  it('keeps conclusion semantics explicit', () => {
    expect(conclusionLabel('LOCATED')).toBe('已定位')
    expect(conclusionLabel('EXCLUDED')).toBe('已排除（非定位）')
    expect(conclusionLabel('HYPOTHESIS')).toBe('根因假设')
    expect(conclusionLabel('INSUFFICIENT_EVIDENCE')).toBe('证据不足')
  })

  it('does not render unknown impact counts as zero', () => {
    expect(impactMetrics(null, null)).toEqual([])
    expect(impactMetrics(12, null)).toEqual(['12 个客户'])
    expect(impactMetrics(null, 38)).toEqual(['38 名用户'])
  })

  it('distinguishes an unrecorded stage from a stage that has not happened', () => {
    expect(timingState(null, null, 'recorded')).toBe('未记录')
    expect(timingState(null, null, 'pending')).toBe('未发生')
    expect(timingState('2026-07-28T12:43:14Z', 'PT43S', 'recorded')).toBe('43秒')
  })

  it('formats ISO durations and the investigation route without guessing', () => {
    expect(formatDuration('PT1M25S')).toBe('1分25秒')
    expect(formatDuration('PT4M')).toBe('4分钟')
    expect(formatDuration('PT0.031853S')).toBe('<1秒')
    expect(investigationLabel('ERROR_CODE_PLAYBOOK', 'EXPLICIT'))
      .toBe('错误码 Playbook · 显式命中')
    expect(investigationLabel('OPEN_DISCOVERY', 'MODEL_PROPOSED'))
      .toBe('开放调查 · 模型提议')
  })
})
