import { describe, expect, it } from 'vitest'
import { durationSeconds, northStarStages } from '../formalProjection'

const RECORDED = {
  reportedAt: '2026-07-29T12:41:06Z',
  readyAt: '2026-07-29T12:42:31Z',
  conclusionAt: '2026-07-29T12:43:14Z',
  handoffAt: '2026-07-29T12:47:52Z',
  intakeCost: 'PT1M25S',
  investigateCost: 'PT43S',
  adoptCost: 'PT4M38S',
}

describe('north-star stages', () => {
  it('keeps the three costs separate and attributes each to its owner', () => {
    const stages = northStarStages(RECORDED)
    expect(stages.map((s) => s.key)).toEqual(['intake', 'investigate', 'adopt'])
    expect(stages.map((s) => s.label)).toEqual(['补问成本', '系统调查成本', '人的采纳成本'])
    expect(stages.map((s) => s.owner)).toEqual(['报障人 ↔ 助手', '平台', '处置人'])
    expect(stages.every((s) => s.state === 'RECORDED')).toBe(true)
  })

  it('computes share only when every stage is recorded', () => {
    const stages = northStarStages(RECORDED)
    const shares = stages.map((s) => s.share)
    expect(shares.every((share) => share !== null)).toBe(true)
    expect(shares.reduce<number>((sum, share) => sum + (share ?? 0), 0)).toBeCloseTo(1, 6)
  })

  /** A bar drawn over partial data would imply a total the system does not know. */
  it('suppresses share when any stage is missing', () => {
    const stages = northStarStages({ ...RECORDED, handoffAt: null, adoptCost: null })
    expect(stages.map((s) => s.share)).toEqual([null, null, null])
    expect(stages[2].state).toBe('PENDING')
    expect(stages[2].display).toBe('未发生')
  })

  it('separates "not yet happened" from "never recorded"', () => {
    const stages = northStarStages({
      reportedAt: null, readyAt: null, conclusionAt: null, handoffAt: null,
      intakeCost: null, investigateCost: null, adoptCost: null,
    })
    expect(stages.map((s) => s.state)).toEqual(['UNRECORDED', 'UNRECORDED', 'PENDING'])
    expect(stages[0].display).toBe('未记录')
    expect(stages[2].display).toBe('未发生')
  })

  it('never fabricates a value for absent timings', () => {
    const stages = northStarStages(null)
    expect(stages.every((s) => s.seconds === null && s.share === null)).toBe(true)
  })

  it('parses ISO-8601 durations, rejecting anything else', () => {
    expect(durationSeconds('PT1M25S')).toBe(85)
    expect(durationSeconds('PT2H3M4S')).toBe(7384)
    expect(durationSeconds(null)).toBeNull()
    expect(durationSeconds('85')).toBeNull()
  })
})
