import { describe, expect, it } from 'vitest'
import {
  diagnosisPerspectiveHero,
  diagnosisPerspectiveLabel,
  diagnosisSupportAction,
  normalizeDiagnosisPerspective,
} from '../diagnosisPerspective'

describe('diagnosis perspective', () => {
  it('defaults every missing or unknown value to the third-line developer view', () => {
    expect(normalizeDiagnosisPerspective(undefined)).toBe('developer')
    expect(normalizeDiagnosisPerspective('')).toBe('developer')
    expect(normalizeDiagnosisPerspective('admin')).toBe('developer')
  })

  it('allows an explicit second-line support view without changing diagnosis facts', () => {
    expect(normalizeDiagnosisPerspective('support')).toBe('support')
    expect(diagnosisPerspectiveLabel('developer')).toBe('三线开发视角')
    expect(diagnosisPerspectiveLabel('support')).toBe('二线保障视角')
  })

  it('puts the concrete developer hypothesis first while keeping its unconfirmed boundary', () => {
    expect(diagnosisPerspectiveHero({
      perspective: 'developer',
      conclusionType: 'HYPOTHESIS',
      rootCause: '直接失败点：iCare 产品映射外部接口返回 HTTP 502（上游为何返回 502 尚未定位）',
      headline: '已形成候选方向',
      narrative: '这是告警已明确的失败点，不是最终根因。',
      candidateCount: 0,
    })).toEqual({
      title: '直接失败点：iCare 产品映射外部接口返回 HTTP 502（上游为何返回 502 尚未定位）',
      summary: '这是告警已明确的失败点，不是最终根因。',
    })
  })

  it('gives second-line operators an escalation action instead of developer investigation instructions', () => {
    expect(diagnosisSupportAction('HYPOTHESIS')).toBe(
      '把告警、影响范围和现有线索一起升级三线；不要根据候选方向直接处置。',
    )
    expect(diagnosisSupportAction('INSUFFICIENT_EVIDENCE')).toContain('确认告警时间、服务和影响范围')
  })
})
