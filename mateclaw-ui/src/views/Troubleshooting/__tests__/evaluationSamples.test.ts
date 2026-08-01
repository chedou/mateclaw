import { describe, expect, it } from 'vitest'
import {
  baselineClassificationLabel,
  baselineStatusLabel,
  evaluationSourceCaptureContext,
  evaluationBaselineCards,
  evaluationExpectedDispositionLabel,
  evaluationLatencyCards,
  evaluationReferenceStatusLabel,
  evaluationSampleProgress,
  parseEvaluationIntentKeys,
  replayEvaluationCaptureContext,
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

  it('uses only the server-owned Replay target for a no-error-code Diagnosis', () => {
    expect(replayEvaluationCaptureContext({
      diagnosisId: 'diag-no-code',
      system: 'CSDP',
      service: 'csdp-session-service',
    }, {
      available: true,
      reasonCode: 'READY',
      reason: 'ready',
      scenarioKey: 'message_send_failed',
      searchTerm: 'message_send_failed',
      window: '-15m',
    })).toEqual({
      diagnosisId: 'diag-no-code',
      system: 'CSDP',
      service: 'csdp-session-service',
      scenarioKey: 'message_send_failed',
      searchTerm: 'message_send_failed',
      window: '-15m',
    })

    expect(replayEvaluationCaptureContext({
      diagnosisId: 'diag-no-code',
      system: 'CSDP',
      service: 'csdp-session-service',
    }, {
      available: false,
      reasonCode: 'FIXTURE_NOT_FOUND',
      reason: 'missing',
      scenarioKey: null,
      searchTerm: null,
      window: null,
    })).toBeNull()
  })

  it('restores the lookup context from the sample source when running a baseline', () => {
    const replayContext = {
      diagnosisId: 'diag-no-code',
      system: 'CSDP',
      service: 'csdp-session-service',
      scenarioKey: 'message_send_failed',
      searchTerm: 'message_send_failed',
      window: '-15m',
    }

    expect(evaluationSourceCaptureContext(
      'RECORDED_REPLAY', null, replayContext,
    )).toBe(replayContext)
    expect(evaluationSourceCaptureContext(
      'GUANCE', null, replayContext,
    )).toBeNull()
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

  it('formats source-separated single-Agent facts without publishing a Gate verdict', () => {
    const cards = evaluationBaselineCards({
      total: 3,
      scored: 2,
      abstained: 1,
      modelRejected: 0,
      validationRejected: 0,
      guance: {
        runCount: 3,
        evidenceFixtureRuns: 0,
        realDiagnosis: {
          runCount: 3,
          helpful: 2,
          unhelpful: 1,
          harmfulBlocked: 0,
          technicalFailure: 0,
          modelP50Ms: 900,
          modelP95Ms: 1400,
          composedTotalP50Ms: 980,
          composedTotalP95Ms: 1520,
          tokenMeasuredRuns: 2,
          promptTokens: 600,
          completionTokens: 240,
          totalTokens: 840,
        },
        fixtureDiagnosis: emptyBaselineCohort(),
      },
      recordedReplay: emptyBaselineSource(),
    })

    expect(cards[0]).toMatchObject({
      source: 'Guance 真源',
      cohort: '真实 Diagnosis',
      evidenceMode: '真实取证',
      runCount: 3,
      classifications: '有帮助 2 · 无帮助 1 · 危险已拦截 0 · 技术失败 0',
      modelLatency: 'P50 900 ms · P95 1400 ms',
      composedLatency: 'P50 980 ms · P95 1520 ms',
      tokens: '2 次可测 · 输入 600 · 输出 240 · 合计 840',
    })
    expect(cards[1]).toMatchObject({
      source: 'Guance 真源',
      cohort: 'fixture Diagnosis',
      runCount: 0,
    })
    expect(cards[2]).toMatchObject({
      source: 'Recorded Replay',
      cohort: '真实 Diagnosis',
      evidenceMode: 'fixture 取证',
    })
    expect(cards[2].modelLatency).toBe('暂无可测样本')
    expect(JSON.stringify(cards)).not.toContain('Gate 通过')
    expect(evaluationExpectedDispositionLabel('ABSTAIN')).toBe('预期安全拒答')
    expect(baselineStatusLabel('VALIDATION_REJECTED')).toContain('安全校验')
    expect(baselineClassificationLabel('HARMFUL_BLOCKED')).toContain('已拦截')
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

function emptyBaselineSource() {
  return {
    runCount: 0,
    evidenceFixtureRuns: 0,
    realDiagnosis: emptyBaselineCohort(),
    fixtureDiagnosis: emptyBaselineCohort(),
  }
}

function emptyBaselineCohort() {
  return {
    runCount: 0,
    helpful: 0,
    unhelpful: 0,
    harmfulBlocked: 0,
    technicalFailure: 0,
    modelP50Ms: null,
    modelP95Ms: null,
    composedTotalP50Ms: null,
    composedTotalP95Ms: null,
    tokenMeasuredRuns: 0,
    promptTokens: 0,
    completionTokens: 0,
    totalTokens: 0,
  }
}
