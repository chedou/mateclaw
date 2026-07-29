import { describe, expect, it } from 'vitest'
import type { SopSynthesisPreview } from '@/api'
import {
  buildSynthesisEvidenceSteps,
  formatSynthesisRate,
  formatSynthesisRateDelta,
  normalizeSynthesisPreviewRequest,
} from '../synthesisPreview'

const preview: SopSynthesisPreview = {
  stage: 'READY_FOR_MODEL',
  system: 'CSDP',
  service: 'csdp-session-service',
  searchTerm: 'message_send_failed',
  matchCount: 4,
  psId: 'synthetic-ps-message-send-001',
  searchEvidence: {
    queryId: 'SYNTH-LOG-SEARCH',
    status: 'ANOMALY',
    source: 'recorded-replay:message-send-failed',
    collectedAt: '2026-07-20T09:13:01Z',
  },
  traceEvidence: {
    queryId: 'SYNTH-TRACE-BUNDLE',
    status: 'ANOMALY',
    source: 'recorded-replay:message-send-failed',
    collectedAt: '2026-07-20T09:13:02Z',
  },
  contrastEvidence: {
    queryId: 'SYNTH-CONTRAST-SAMPLE',
    status: 'NORMAL',
    source: 'recorded-replay:message-send-failed',
    collectedAt: '2026-07-20T09:13:03Z',
  },
  skeleton: {
    psId: 'synthetic-ps-message-send-001',
    startedAtEpochMs: 1753002781000,
    endedAtEpochMs: 1753002781087,
    elapsedMs: 87,
    serviceSequence: ['session-api', 'session-state', 'session-api'],
    timeline: [],
    anomalySequenceIndexes: [1, 2],
    durationByService: {},
    sourceEntryCount: 3,
    omittedEntryCount: 0,
    contrast: {
      available: true,
      discriminatingFeature: 'session_state_conflict',
      failureSampleCount: 100,
      failureMatchCount: 92,
      successSampleCount: 100,
      successMatchCount: 3,
      failureRate: 0.92,
      successRate: 0.03,
      rateDelta: 0.89,
    },
  },
  fixtureMode: true,
  traceEntries: 3,
  sourceRequestCount: 3,
  totalDurationMs: 40,
  timings: {
    logSearchDurationMs: 10,
    logTraceDurationMs: 20,
    contrastDurationMs: 5,
    compressionDurationMs: 5,
  },
  completedAt: '2026-07-20T09:13:03Z',
  contrastAvailable: true,
  warnings: ['fixture recorded-replay'],
}

describe('formal synthesis preview formatting', () => {
  it('keeps all three Evidence Spine steps visible when contrast is unavailable', () => {
    expect(buildSynthesisEvidenceSteps(preview)).toEqual([
      expect.objectContaining({ signalKind: 'log_search', queryId: 'SYNTH-LOG-SEARCH' }),
      expect.objectContaining({ signalKind: 'log_trace_bundle', queryId: 'SYNTH-TRACE-BUNDLE' }),
      expect.objectContaining({ signalKind: 'contrast_sample', queryId: 'SYNTH-CONTRAST-SAMPLE' }),
    ])

    expect(buildSynthesisEvidenceSteps({
      ...preview,
      contrastEvidence: null,
      contrastAvailable: false,
      skeleton: {
        ...preview.skeleton,
        contrast: {
          available: false,
          discriminatingFeature: '',
          failureSampleCount: 0,
          failureMatchCount: 0,
          successSampleCount: 0,
          successMatchCount: 0,
          failureRate: 0,
          successRate: 0,
          rateDelta: 0,
        },
      },
    })).toEqual([
      expect.objectContaining({ signalKind: 'log_search', status: 'ANOMALY' }),
      expect.objectContaining({ signalKind: 'log_trace_bundle', status: 'ANOMALY' }),
      expect.objectContaining({
        signalKind: 'contrast_sample',
        status: 'MISSING',
        queryId: null,
        source: null,
        collectedAt: null,
      }),
    ])
  })

  it('formats measured rates without turning unavailable values into measurements', () => {
    expect(formatSynthesisRate(0.92)).toBe('92%')
    expect(formatSynthesisRate(0.034)).toBe('3.4%')
    expect(formatSynthesisRateDelta(0.89)).toBe('+89 个百分点')
    expect(formatSynthesisRateDelta(-0.125)).toBe('-12.5 个百分点')
  })

  it('trims request identifiers and preserves an absent event timestamp', () => {
    expect(normalizeSynthesisPreviewRequest({
      system: ' CSDP ',
      service: ' csdp-session-service ',
      searchTerm: ' message_send_failed ',
      window: '-15m',
      occurredAt: '',
    })).toEqual({
      system: 'CSDP',
      service: 'csdp-session-service',
      searchTerm: 'message_send_failed',
      window: '-15m',
      occurredAt: null,
    })
  })
})
