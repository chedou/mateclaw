import { describe, expect, it } from 'vitest'
import {
  evaluationLatencyCards,
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
      timingMeasuredSamples: 0,
      guanceLatency: emptyLatency(),
      recordedReplayLatency: emptyLatency(),
      minimumEvaluationTarget: 20,
      targetRangeMax: 30,
    })

    expect(progress.label).toBe('17 / 20 条可评估样本')
    expect(progress.percent).toBe(85)
    expect(progress.note).toContain('不代表 T8 已通过')
    expect(JSON.stringify(progress)).not.toContain('通过验收')
    expect(evaluationReferenceStatusLabel('EVIDENCE_CAPTURED')).toBe('待人工参考解')
  })

  it('formats source-separated descriptive latency without inventing missing values', () => {
    const cards = evaluationLatencyCards({
      total: 6,
      guance: 4,
      recordedReplay: 2,
      evidenceCaptured: 6,
      readyForEvaluation: 0,
      fullSpineObserved: 6,
      coreChainObserved: 0,
      linkedFixtureDiagnoses: 0,
      timingMeasuredSamples: 6,
      guanceLatency: {
        sampleCount: 4,
        evidenceP50Ms: 20,
        evidenceP95Ms: 40,
        compressionP50Ms: 8,
        compressionP95Ms: 16,
        totalP50Ms: 30,
        totalP95Ms: 70,
      },
      recordedReplayLatency: {
        sampleCount: 0,
        evidenceP50Ms: null,
        evidenceP95Ms: null,
        compressionP50Ms: null,
        compressionP95Ms: null,
        totalP50Ms: null,
        totalP95Ms: null,
      },
      minimumEvaluationTarget: 20,
      targetRangeMax: 30,
    })

    expect(cards[0]).toMatchObject({
      source: 'Guance 真源',
      sampleCount: 4,
      evidence: 'P50 20 ms · P95 40 ms',
      compression: 'P50 8 ms · P95 16 ms',
      total: 'P50 30 ms · P95 70 ms',
    })
    expect(cards[1]).toMatchObject({
      source: 'Recorded Replay',
      sampleCount: 0,
      evidence: '暂无可测样本',
    })
  })
})

function emptyLatency() {
  return {
    sampleCount: 0,
    evidenceP50Ms: null,
    evidenceP95Ms: null,
    compressionP50Ms: null,
    compressionP95Ms: null,
    totalP50Ms: null,
    totalP95Ms: null,
  }
}
