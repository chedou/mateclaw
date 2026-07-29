import type {
  EvaluationSampleReferenceStatus,
  EvaluationLatencySummary,
  EvaluationSampleSummary,
  EvaluationSampleSourcePlatform,
} from '@/api'

const STRUCTURED_KEY = /^[a-z][a-z0-9_:-]{1,63}$/

export interface ParsedIntentKeys {
  values: string[]
  invalid: string[]
}

export interface EvaluationSampleCaptureContext {
  diagnosisId: string
  system: string
  service: string
  scenarioKey: string
  searchTerm: string
  window: string
}

export interface EvaluationLatencyCard {
  key: EvaluationSampleSourcePlatform
  source: string
  sampleCount: number
  evidence: string
  compression: string
  total: string
}

export function parseEvaluationIntentKeys(input: string): ParsedIntentKeys {
  const values: string[] = []
  const invalid: string[] = []
  const seen = new Set<string>()
  for (const raw of input.split(/\r?\n/)) {
    const value = raw.trim()
    if (!value || seen.has(value)) continue
    seen.add(value)
    if (STRUCTURED_KEY.test(value)) values.push(value)
    else invalid.push(value)
  }
  return { values, invalid }
}

/**
 * Only an explicit error code is safe to turn into a default selector.
 * Guance search identifiers and Diagnosis ids are evidence lookup material,
 * not semantic scenario keys, so no-error-code cases stay human-curated.
 */
export function suggestedEvaluationScenarioKey(errorCode: string | null) {
  const normalizedError = safeFragment(errorCode || '')
  if (normalizedError) return `error_${normalizedError}`.slice(0, 64)
  return ''
}

export function evaluationReferenceStatusLabel(value: EvaluationSampleReferenceStatus) {
  return value === 'READY_FOR_EVALUATION' ? '可进入评估集' : '待人工参考解'
}

export function evaluationSourceLabel(value: EvaluationSampleSourcePlatform) {
  return value === 'GUANCE' ? 'Guance 真源' : 'Recorded Replay'
}

export function evaluationSampleProgress(summary: EvaluationSampleSummary) {
  const minimum = Math.max(1, summary.minimumEvaluationTarget)
  return {
    label: `${summary.readyForEvaluation} / ${minimum} 条可评估样本`,
    percent: Math.min(100, Math.round((summary.readyForEvaluation / minimum) * 100)),
    note: `20–${summary.targetRangeMax} 条只是固定评估集的数量目标，不代表 T8 已通过；仍需 owner 核对字段、覆盖与结果质量。`,
  }
}

export function evaluationLatencyCards(
  summary: EvaluationSampleSummary,
): EvaluationLatencyCard[] {
  return [
    latencyCard('GUANCE', summary.guanceLatency),
    latencyCard('RECORDED_REPLAY', summary.recordedReplayLatency),
  ]
}

function latencyCard(
  key: EvaluationSampleSourcePlatform,
  latency: EvaluationLatencySummary,
): EvaluationLatencyCard {
  return {
    key,
    source: evaluationSourceLabel(key),
    sampleCount: latency.sampleCount,
    evidence: latencyPair(latency.sampleCount, latency.evidenceP50Ms, latency.evidenceP95Ms),
    compression: latencyPair(
      latency.sampleCount,
      latency.compressionP50Ms,
      latency.compressionP95Ms,
    ),
    total: latencyPair(latency.sampleCount, latency.totalP50Ms, latency.totalP95Ms),
  }
}

function latencyPair(sampleCount: number, p50: number | null, p95: number | null) {
  if (sampleCount <= 0 || p50 === null || p95 === null) return '暂无可测样本'
  return `P50 ${p50} ms · P95 ${p95} ms`
}

function safeFragment(value: string) {
  return value
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9:-]+/g, '_')
    .replace(/^[_:-]+|[_:-]+$/g, '')
}
