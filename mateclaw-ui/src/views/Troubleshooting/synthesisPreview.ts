import type {
  EvidenceStatus,
  SopSynthesisPreview,
  SopSynthesisPreviewRequest,
  SynthesisEvidenceReference,
} from '@/api'

export interface SynthesisEvidenceStep {
  signalKind: 'log_search' | 'log_trace_bundle' | 'contrast_sample'
  label: string
  queryId: string | null
  status: EvidenceStatus
  source: string | null
  collectedAt: string | null
}

export const EVIDENCE_WINDOW_OPTIONS = [
  { label: '前 5 分钟', value: '-5m' },
  { label: '前 15 分钟', value: '-15m' },
  { label: '前 30 分钟', value: '-30m' },
  { label: '前 1 小时', value: '-1h' },
] as const

/** Keeps the architecture's fixed Evidence Spine order visible in the formal UI. */
export function buildSynthesisEvidenceSteps(
  preview: SopSynthesisPreview,
): SynthesisEvidenceStep[] {
  return [
    evidenceStep('log_search', '搜索故障日志并提取 PS ID', preview.searchEvidence),
    evidenceStep('log_trace_bundle', '按 PS ID 拉取有界全链路', preview.traceEvidence),
    evidenceStep('contrast_sample', '取得同窗口成功样本对照', preview.contrastEvidence),
  ]
}

function evidenceStep(
  signalKind: SynthesisEvidenceStep['signalKind'],
  label: string,
  evidence: SynthesisEvidenceReference | null,
): SynthesisEvidenceStep {
  return {
    signalKind,
    label,
    queryId: evidence?.queryId ?? null,
    status: evidence?.status ?? 'MISSING',
    source: evidence?.source ?? null,
    collectedAt: evidence?.collectedAt ?? null,
  }
}

export function normalizeSynthesisPreviewRequest(
  request: SopSynthesisPreviewRequest,
): SopSynthesisPreviewRequest {
  return {
    system: request.system.trim(),
    service: request.service.trim(),
    searchTerm: request.searchTerm.trim(),
    window: request.window.trim(),
    occurredAt: request.occurredAt?.trim() || null,
  }
}

export function formatSynthesisRate(value: number): string {
  return `${formatNumber(value * 100)}%`
}

export function formatSynthesisRateDelta(value: number): string {
  const percentagePoints = value * 100
  const prefix = percentagePoints > 0 ? '+' : ''
  return `${prefix}${formatNumber(percentagePoints)} 个百分点`
}

function formatNumber(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(1).replace(/\.0$/, '')
}
