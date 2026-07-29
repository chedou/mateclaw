import { describe, expect, it } from 'vitest'
import {
  closureOutcomeLabel,
  conclusionLabel,
  formatDuration,
  guanceReadinessLabel,
  guanceSignalLabel,
  guanceValidationLabel,
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

  it('renders final closure outcomes as business language', () => {
    expect(closureOutcomeLabel('RECOVERED')).toBe('已恢复')
    expect(closureOutcomeLabel('FALSE_POSITIVE')).toBe('误报')
    expect(closureOutcomeLabel('TRANSFERRED_OUT')).toBe('已转出处置')
    expect(closureOutcomeLabel('UNRESOLVED')).toBe('未解决')
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

  it('keeps the real-source gate distinct from T7 acceptance', () => {
    expect(guanceReadinessLabel('READY_FOR_VALIDATION')).toBe('可执行单次验证')
    expect(guanceReadinessLabel('CANONICAL_SIGNALS_OBSERVED')).toBe('已观测规范化读链')
    expect(guanceSignalLabel('NOT_ROUTED')).toBe('未路由到 Guance')
    expect(guanceSignalLabel('INVALID_BINDING')).toBe('绑定无效')
    expect(guanceValidationLabel('CANONICAL_CHAIN_OBSERVED'))
      .toBe('单次规范化读链通过（非 T7 验收）')
  })
})
