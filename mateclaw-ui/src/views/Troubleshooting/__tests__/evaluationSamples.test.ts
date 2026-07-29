import { describe, expect, it } from 'vitest'
import {
  evaluationReferenceStatusLabel,
  evaluationSampleProgress,
  parseEvaluationIntentKeys,
  suggestedEvaluationScenarioKey,
} from '../evaluationSamples'

describe('evaluation sample helpers', () => {
  it('parses, trims and deduplicates structured intent keys in order', () => {
    expect(parseEvaluationIntentKeys(`
      locate_failed_request
      trace_ps_id
      locate_failed_request
      verify_recovery
    `)).toEqual({
      values: ['locate_failed_request', 'trace_ps_id', 'verify_recovery'],
      invalid: [],
    })
  })

  it('reports free text and query-shaped content instead of sending it', () => {
    const parsed = parseEvaluationIntentKeys(`
      locate_failed_request
      请查看原始日志正文
      L::logs:(message=secret)
    `)

    expect(parsed.values).toEqual(['locate_failed_request'])
    expect(parsed.invalid).toEqual(['请查看原始日志正文', 'L::logs:(message=secret)'])
  })

  it('only suggests a scenario key from an explicit error code', () => {
    expect(suggestedEvaluationScenarioKey('903001')).toBe('error_903001')
    expect(suggestedEvaluationScenarioKey('ERR-42')).toBe('error_err-42')
    expect(suggestedEvaluationScenarioKey(null)).toBe('')
  })

  it('renders accumulation progress without turning the count into an acceptance verdict', () => {
    const progress = evaluationSampleProgress({
      total: 24,
      guance: 20,
      recordedReplay: 4,
      evidenceCaptured: 7,
      readyForEvaluation: 17,
      fullSpineObserved: 13,
      coreChainObserved: 7,
      linkedFixtureDiagnoses: 24,
      minimumEvaluationTarget: 20,
      targetRangeMax: 30,
    })

    expect(progress.label).toBe('17 / 20 条可评估样本')
    expect(progress.percent).toBe(85)
    expect(progress.note).toContain('不代表 T8 已通过')
    expect(JSON.stringify(progress)).not.toContain('通过验收')
    expect(evaluationReferenceStatusLabel('EVIDENCE_CAPTURED')).toBe('待人工参考解')
  })
})
