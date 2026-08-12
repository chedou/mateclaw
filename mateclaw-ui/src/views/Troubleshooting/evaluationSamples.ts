import type {
  BaselineClassification,
  BaselineCohortMetrics,
  BaselineEvaluationStatus,
  BaselineEvaluationSummary,
  EvaluationHumanBaseline,
  EvaluationNorthStarComparison,
  EvaluationExpectedDisposition,
  EvaluationSampleReferenceStatus,
  EvaluationLatencySummary,
  EvaluationSampleSummary,
  EvaluationSampleSourcePlatform,
  RecordedReplayEvaluationCapability,
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

export interface ReplayEvaluationDiagnosisScope {
  diagnosisId: string
  system: string
  service: string
}

export interface EvaluationLatencyCard {
  key: EvaluationSampleSourcePlatform
  source: string
  sampleCount: number
  evidence: string
  compression: string
  total: string
}

export interface BaselineMetricCard {
  key: string
  source: string
  cohort: string
  evidenceMode: string
  runCount: number
  classifications: string
  modelLatency: string
  composedLatency: string
  tokens: string
  systemConfidence: string
}

export interface NorthStarMetricCard {
  key: 'MEASURED' | 'ESTIMATED' | 'MACHINE'
  label: string
  count: number
  p50: string
  p95: string
  note: string
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

/** Builds Replay capture input only from the server-owned capability target. */
export function replayEvaluationCaptureContext(
  scope: ReplayEvaluationDiagnosisScope | null,
  capability: RecordedReplayEvaluationCapability | null,
): EvaluationSampleCaptureContext | null {
  if (!scope || !capability?.available
    || !capability.scenarioKey || !capability.searchTerm || !capability.window) return null
  return {
    diagnosisId: scope.diagnosisId,
    system: scope.system,
    service: scope.service,
    scenarioKey: capability.scenarioKey,
    searchTerm: capability.searchTerm,
    window: capability.window,
  }
}

/** Restores the exact lookup window that belongs to the frozen sample source. */
export function evaluationSourceCaptureContext(
  source: EvaluationSampleSourcePlatform,
  guanceContext: EvaluationSampleCaptureContext | null,
  replayContext: EvaluationSampleCaptureContext | null,
): EvaluationSampleCaptureContext | null {
  return source === 'RECORDED_REPLAY' ? replayContext : guanceContext
}

export function evaluationReferenceStatusLabel(value: EvaluationSampleReferenceStatus) {
  return value === 'READY_FOR_EVALUATION' ? '可进入效果评估' : '待人工标准答案'
}

export function evaluationSourceLabel(value: EvaluationSampleSourcePlatform) {
  return value === 'GUANCE' ? 'Guance 真源' : 'Recorded Replay'
}

export function evaluationExpectedDispositionLabel(value: EvaluationExpectedDisposition) {
  return value === 'DRAFT' ? '预期生成草案' : '预期安全拒答'
}

export function evaluationHumanBaselineLabel(value: EvaluationHumanBaseline) {
  const source = value.basis === 'MEASURED'
    ? '来自工单或聊天时间戳（实测）'
    : '来自处置人回忆（估算）'
  return `${value.minutesToLocate} 分钟 · ${source}`
}

export function evaluationNorthStarCards(
  comparison: EvaluationNorthStarComparison,
): NorthStarMetricCard[] {
  return [
    {
      key: 'MEASURED',
      label: '人工排障 · 时间戳实测',
      count: comparison.measured.count,
      p50: minutePercentile(comparison.measured.count, comparison.measured.p50Minutes, 'P50'),
      p95: minutePercentile(comparison.measured.count, comparison.measured.p95Minutes, 'P95'),
      note: '从工单、群聊或事件系统时间戳取得，可作为正式耗时证据。',
    },
    {
      key: 'ESTIMATED',
      label: '人工排障 · 处置人估算',
      count: comparison.estimated.count,
      p50: minutePercentile(comparison.estimated.count, comparison.estimated.p50Minutes, 'P50'),
      p95: minutePercentile(comparison.estimated.count, comparison.estimated.p95Minutes, 'P95'),
      note: '来自处置人回忆，只单独参考，不能冒充时间戳实测。',
    },
    {
      key: 'MACHINE',
      label: '影子基线 · 机器给出可复核结果',
      count: comparison.machineRunCount,
      p50: millisecondPercentile(comparison.machineRunCount, comparison.machineP50Ms, 'P50'),
      p95: millisecondPercentile(comparison.machineRunCount, comparison.machineP95Ms, 'P95'),
      note: '只包含证据与单模型基线运行，不包含人的复核和生产处置时间。',
    },
  ]
}

export function baselineStatusLabel(value: BaselineEvaluationStatus) {
  const labels: Record<BaselineEvaluationStatus, string> = {
    MODEL_REJECTED: '模型调用失败',
    ABSTAINED: '模型已拒答',
    VALIDATION_REJECTED: '草案被安全校验拦截',
    SCORED: '结构比较已完成',
  }
  return labels[value]
}

export function baselineClassificationLabel(value: BaselineClassification) {
  const labels: Record<BaselineClassification, string> = {
    HELPFUL: '有帮助',
    UNHELPFUL: '无帮助',
    HARMFUL_BLOCKED: '危险提议已拦截',
    TECHNICAL_FAILURE: '技术失败',
  }
  return labels[value]
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

export function evaluationBaselineCards(
  summary: BaselineEvaluationSummary,
): BaselineMetricCard[] {
  return ([
    ['GUANCE', summary.guance],
    ['RECORDED_REPLAY', summary.recordedReplay],
  ] as const).flatMap(([source, metrics]) => [
    baselineCard(source, 'real', metrics.realDiagnosis),
    baselineCard(source, 'fixture', metrics.fixtureDiagnosis),
  ])
}

function baselineCard(
  sourceKey: EvaluationSampleSourcePlatform,
  cohortKey: 'real' | 'fixture',
  metrics: BaselineCohortMetrics,
): BaselineMetricCard {
  return {
    key: `${sourceKey}:${cohortKey}`,
    source: evaluationSourceLabel(sourceKey),
    cohort: cohortKey === 'real' ? '真实 Diagnosis' : 'fixture Diagnosis',
    evidenceMode: sourceKey === 'GUANCE' ? '真实取证' : 'fixture 取证',
    runCount: metrics.runCount,
    classifications: metrics.runCount <= 0
      ? '暂无运行'
      : `有帮助 ${metrics.helpful} · 无帮助 ${metrics.unhelpful} · 危险已拦截 ${metrics.harmfulBlocked} · 技术失败 ${metrics.technicalFailure}`,
    modelLatency: latencyPair(
      metrics.runCount,
      metrics.modelP50Ms,
      metrics.modelP95Ms,
    ),
    composedLatency: latencyPair(
      metrics.runCount,
      metrics.composedTotalP50Ms,
      metrics.composedTotalP95Ms,
    ),
    tokens: metrics.tokenMeasuredRuns <= 0
      ? 'Token 暂不可得'
      : `${metrics.tokenMeasuredRuns} 次可测 · 输入 ${metrics.promptTokens} · 输出 ${metrics.completionTokens} · 合计 ${metrics.totalTokens}`,
    systemConfidence: metrics.quality.confidenceAssessedRuns <= 0
      ? '系统置信度暂未评估'
      : `系统 HIGH ${metrics.quality.highConfidenceRuns}`
        + ` / 已评估 ${metrics.quality.confidenceAssessedRuns}`
        + ` · 高置信错误 ${metrics.quality.highConfidenceErrorRuns}`,
  }
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

function minutePercentile(count: number, value: number | null, label: string) {
  return count <= 0 || value === null ? `${label} 暂无数据` : `${label} ${value} 分钟`
}

function millisecondPercentile(count: number, value: number | null, label: string) {
  if (count <= 0 || value === null) return `${label} 暂无数据`
  if (value < 1000) return `${label} ${value} ms`
  return `${label} ${(value / 1000).toFixed(value % 1000 === 0 ? 0 : 1)} 秒`
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
