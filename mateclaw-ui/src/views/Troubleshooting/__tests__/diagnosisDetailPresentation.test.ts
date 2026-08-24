import { describe, expect, it } from 'vitest'
import {
  diagnosisConclusionCopyViolations,
  diagnosisNextStepPanel,
  diagnosisProvenanceChips,
  diagnosisSupportHandoffCopy,
  diagnosisUnknownImpactCopy,
} from '../diagnosisDetailPresentation'
import { diagnosisPerspectiveHero, diagnosisRootCauseAnswer } from '../diagnosisPerspective'

describe('diagnosis detail presentation', () => {
  it('exposes provenance chips before any fold is opened', () => {
    expect(diagnosisProvenanceChips({
      evidenceBasis: 'REPORTED',
      fixtureMode: false,
      rehearsal: false,
    }).map(chip => chip.label)).toEqual(['告警上报 · 非上游根因证明'])

    expect(diagnosisProvenanceChips({
      evidenceBasis: 'RECORDED_REPLAY',
      fixtureMode: true,
      rehearsal: true,
    }).map(chip => chip.label)).toEqual([
      '录制回放 · 非现场真源',
      '演练单 · 不计入正式验收',
    ])

    expect(diagnosisProvenanceChips({
      evidenceBasis: 'OBSERVED',
      fixtureMode: true,
      rehearsal: false,
    }).map(chip => chip.label)).toEqual([
      '只读数据源观测',
      'Fixture 模式',
    ])
  })

  it('pairs unknown impact with a conservative action', () => {
    const copy = diagnosisUnknownImpactCopy()
    expect(copy.statement).toContain('尚未确认')
    expect(copy.conservativeAction).toMatch(/升级三线|补问/)
  })

  it('tells support to escalate when impact is unknown', () => {
    expect(diagnosisSupportHandoffCopy({
      conclusionType: 'HYPOTHESIS',
      impactKnown: false,
    })).toMatch(/影响未确认/)
    expect(diagnosisSupportHandoffCopy({
      conclusionType: 'EXCLUDED',
      impactKnown: true,
    })).toMatch(/排除不是定位/)
  })

  it('rejects root-cause wording on non-located conclusions', () => {
    const hero = diagnosisPerspectiveHero({
      perspective: 'support',
      conclusionType: 'EXCLUDED',
      rootCause: null,
      headline: '平台侧未见异常',
      narrative: '已排除部分方向',
      candidateCount: 0,
    })
    expect(diagnosisConclusionCopyViolations('EXCLUDED', [
      hero.title,
      diagnosisRootCauseAnswer({
        conclusionType: 'EXCLUDED',
        rootCause: null,
        headline: hero.title,
      }),
    ])).toEqual([])
    expect(diagnosisConclusionCopyViolations('HYPOTHESIS', ['根因已找到'])).toEqual(['根因已找到'])
  })

  it('binds next step to a primary action or an explicit blocker', () => {
    expect(diagnosisNextStepPanel({
      perspective: 'developer',
      status: 'READY_FOR_HUMAN',
      conclusionType: 'LOCATED',
      nextStep: {
        label: '定位结果',
        text: '请责任开发复核定位结果。',
        capabilityBoundary: '平台不执行生产写',
      },
      canOperate: true,
      canTransfer: true,
      canClose: false,
      canEvaluate: false,
    })).toMatchObject({
      title: '定位结果',
      primaryAction: 'confirm',
      blocker: null,
    })

    expect(diagnosisNextStepPanel({
      perspective: 'developer',
      status: 'READY_FOR_HUMAN',
      conclusionType: 'LOCATED',
      nextStep: { label: '定位结果', text: '请复核', capabilityBoundary: null },
      canOperate: false,
      canTransfer: false,
      canClose: false,
      canEvaluate: false,
    }).blocker).toMatch(/没有确认权限|没有转派权限/)

    expect(diagnosisNextStepPanel({
      perspective: 'support',
      status: 'READY_FOR_HUMAN',
      conclusionType: 'HYPOTHESIS',
      nextStep: { label: '升级交接', text: '把线索交给三线', capabilityBoundary: null },
      canOperate: false,
      canTransfer: true,
      canClose: false,
      canEvaluate: false,
    }).primaryAction).toBe('transfer')
  })

  it.each(['HYPOTHESIS', 'INSUFFICIENT_EVIDENCE'] as const)(
    'never tells a developer to confirm an unlocated %s conclusion',
    (conclusionType) => {
      const panel = diagnosisNextStepPanel({
        perspective: 'developer',
        status: 'READY_FOR_HUMAN',
        conclusionType,
        nextStep: null,
        canOperate: true,
        canTransfer: true,
        canClose: false,
        canEvaluate: false,
      })

      expect(panel.primaryAction).toBe('transfer')
      expect(`${panel.title} ${panel.detail}`).not.toMatch(/确认定位|认可就确认/)
      expect(`${panel.title} ${panel.detail}`).toMatch(/继续查|补证据|转给/)
    },
  )
})
