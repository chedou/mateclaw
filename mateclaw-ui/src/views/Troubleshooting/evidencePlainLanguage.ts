export interface EvidenceComparisonFact {
  featureCode: string | null | undefined
  failureRequestCount: EvidenceCount
  failureWithFeatureCount: EvidenceCount
  normalRequestCount: EvidenceCount
  normalWithFeatureCount: EvidenceCount
}

export type EvidenceCount = number | string | bigint

export interface EvidenceComparisonNarrative {
  summary: string
  interpretation: string
  scope: string
}

export interface ConfidencePresentation {
  label: string
  detail: string
}

const FEATURE_LABELS: Record<string, string> = {
  auth_token_rejected: '鉴权令牌被拒绝',
  gateway_timeout: '网关超时',
  inner_701022_on_failed_trace: '失败请求中的下游错误 701022',
  itgw_content_policy_blocked: '内容拦截',
  message_length_eq_2011: '消息长度为 2011',
  message_length_eq_2875: '消息长度为 2875',
  message_send_failed: '消息发送失败',
  session_state_conflict: '会话状态冲突',
  state_conflict: '会话状态冲突',
}

export function evidenceFeatureLabel(featureCode: string | null | undefined): string {
  if (!featureCode || featureCode.trim().toLowerCase() === 'legacy_feature_not_recorded') {
    return '异常特征（名称未记录）'
  }
  return FEATURE_LABELS[featureCode.trim().toLowerCase()] ?? '待确认特征'
}

function requestObservation(
  total: bigint,
  withFeature: bigint,
  requestKind: '失败' | '正常',
  featureLabel: string,
): string {
  if (withFeature === total) {
    return `${total} 个${requestKind}请求都出现了“${featureLabel}”`
  }
  if (withFeature === 0n) {
    return `${total} 个${requestKind}请求均未出现`
  }
  return `${total} 个${requestKind}请求中有 ${withFeature} 个出现${requestKind === '失败' ? `了“${featureLabel}”` : ''}`
}

export function isEvidenceCount(value: unknown): value is EvidenceCount {
  if (typeof value === 'bigint') return value >= 0n
  if (typeof value === 'number') return Number.isSafeInteger(value) && value >= 0
  return typeof value === 'string' && /^(0|[1-9]\d*)$/.test(value)
}

function parseEvidenceCount(value: EvidenceCount): bigint | null {
  if (!isEvidenceCount(value)) return null
  return typeof value === 'bigint' ? value : BigInt(value)
}

export function evidenceComparisonNarrative(fact: EvidenceComparisonFact): EvidenceComparisonNarrative {
  const featureLabel = evidenceFeatureLabel(fact.featureCode)
  const failureRequestCount = parseEvidenceCount(fact.failureRequestCount)
  const failureWithFeatureCount = parseEvidenceCount(fact.failureWithFeatureCount)
  const normalRequestCount = parseEvidenceCount(fact.normalRequestCount)
  const normalWithFeatureCount = parseEvidenceCount(fact.normalWithFeatureCount)
  if (failureRequestCount === null
    || failureWithFeatureCount === null
    || normalRequestCount === null
    || normalWithFeatureCount === null
    || failureRequestCount <= 0n
    || normalRequestCount <= 0n
    || failureWithFeatureCount > failureRequestCount
    || normalWithFeatureCount > normalRequestCount) {
    return {
      summary: '失败请求或正常请求的样本数量不足，暂时无法比较。',
      interpretation: '当前不能判断这个现象是否只出现在故障请求中。',
      scope: '请先补齐同一时间窗口内的失败请求和正常请求。',
    }
  }
  const failureRateGreater = failureWithFeatureCount * normalRequestCount
    > normalWithFeatureCount * failureRequestCount
  const failureObservation = requestObservation(
    failureRequestCount,
    failureWithFeatureCount,
    '失败',
    featureLabel,
  )
  const normalObservation = requestObservation(
    normalRequestCount,
    normalWithFeatureCount,
    '正常',
    featureLabel,
  )

  let interpretation = '失败请求和正常请求的表现相近，暂不能用这个现象解释故障。'
  if (failureRateGreater && failureWithFeatureCount > 0n) {
    interpretation = normalWithFeatureCount === 0n
      ? '这个现象只集中在失败请求中，支持它与本次故障有关。'
      : '这个现象在失败请求中更常见，支持它与本次故障有关，但仍需结合其他证据确认。'
  }

  return {
    summary: `${failureObservation}；${normalObservation}。`,
    interpretation,
    scope: `本次比较基于 ${failureRequestCount} 个失败请求和 ${normalRequestCount} 个正常请求，仍需结合最终处置结果确认。`,
  }
}

export function diagnosisConfidencePresentation(confidence: string | null | undefined): ConfidencePresentation {
  if (confidence === 'HIGH') {
    return {
      label: '结论依据充分',
      detail: '必需证据完整，并且已审核的判断条件成立；这不是模型概率。',
    }
  }
  if (confidence === 'MEDIUM') {
    return {
      label: '结论依据有限',
      detail: '已有部分证据支持当前判断，但仍有关键事实需要人工确认。',
    }
  }
  return {
    label: '仅供人工核查',
    detail: '当前证据只能形成线索或明确证据不足，需要人工确认。',
  }
}
