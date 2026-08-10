import { describe, expect, it } from 'vitest'
import {
  evidenceComparisonNarrative,
  evidenceFeatureLabel,
  diagnosisConfidencePresentation,
  isEvidenceCount,
} from '../evidencePlainLanguage'

describe('evidence numbers are translated into operator language', () => {
  it.each([
    ['itgw_content_policy_blocked', '内容拦截'],
    ['session_state_conflict', '会话状态冲突'],
    ['state_conflict', '会话状态冲突'],
    ['message_length_eq_2875', '消息长度为 2875'],
    ['inner_701022_on_failed_trace', '失败请求中的下游错误 701022'],
    ['message_send_failed', '消息发送失败'],
    ['gateway_timeout', '网关超时'],
    ['auth_token_rejected', '鉴权令牌被拒绝'],
  ])('maps registered feature %s to stable operator language', (code, label) => {
    expect(evidenceFeatureLabel(code)).toBe(label)
  })

  it('explains the real ITGW comparison without slash ratios or match jargon', () => {
    const narrative = evidenceComparisonNarrative({
      featureCode: 'itgw_content_policy_blocked',
      failureRequestCount: 2,
      failureWithFeatureCount: 2,
      normalRequestCount: 36,
      normalWithFeatureCount: 0,
    })

    expect(narrative.summary)
      .toBe('2 个失败请求都出现了“内容拦截”；36 个正常请求均未出现。')
    expect(narrative.interpretation)
      .toBe('这个现象只集中在失败请求中，支持它与本次故障有关。')
    expect(narrative.scope)
      .toBe('本次比较基于 2 个失败请求和 36 个正常请求，仍需结合最终处置结果确认。')
    expect(Object.values(narrative).join(' ')).not.toMatch(/\d+\/\d+/)
    expect(Object.values(narrative).join(' ')).not.toContain('命中')
  })

  it('normalizes string counts returned by the long-integer JSON boundary', () => {
    const narrative = evidenceComparisonNarrative({
      featureCode: 'itgw_content_policy_blocked',
      failureRequestCount: '2',
      failureWithFeatureCount: '2',
      normalRequestCount: '36',
      normalWithFeatureCount: '0',
    })

    expect(narrative.summary)
      .toBe('2 个失败请求都出现了“内容拦截”；36 个正常请求均未出现。')
  })

  it('preserves Long values exactly instead of routing them through Number', () => {
    const narrative = evidenceComparisonNarrative({
      featureCode: 'itgw_content_policy_blocked',
      failureRequestCount: '9007199254740993',
      failureWithFeatureCount: '9007199254740993',
      normalRequestCount: '9223372036854775807',
      normalWithFeatureCount: '0',
    })

    expect(narrative.summary).toBe(
      '9007199254740993 个失败请求都出现了“内容拦截”；9223372036854775807 个正常请求均未出现。',
    )
    expect(narrative.scope).toContain('9007199254740993 个失败请求')
    expect(narrative.scope).toContain('9223372036854775807 个正常请求')
  })

  it('states partial and equal observations without overstating causality', () => {
    expect(evidenceComparisonNarrative({
      featureCode: 'session_state_conflict',
      failureRequestCount: 10,
      failureWithFeatureCount: 6,
      normalRequestCount: 10,
      normalWithFeatureCount: 2,
    }).summary).toBe('10 个失败请求中有 6 个出现了“会话状态冲突”；10 个正常请求中有 2 个出现。')

    expect(evidenceComparisonNarrative({
      featureCode: 'unknown_feature',
      failureRequestCount: 4,
      failureWithFeatureCount: 1,
      normalRequestCount: 4,
      normalWithFeatureCount: 1,
    }).interpretation).toBe('失败请求和正常请求的表现相近，暂不能用这个现象解释故障。')
  })

  it('uses safe labels and explains confidence as evidence strength rather than probability', () => {
    expect(evidenceFeatureLabel('itgw_content_policy_blocked')).toBe('内容拦截')
    expect(evidenceFeatureLabel(null)).toBe('异常特征（名称未记录）')
    expect(evidenceFeatureLabel('legacy_feature_not_recorded'))
      .toBe('异常特征（名称未记录）')
    expect(evidenceFeatureLabel('new_safe_feature')).toBe('待确认特征')
    expect(diagnosisConfidencePresentation('HIGH')).toEqual({
      label: '结论依据充分',
      detail: '必需证据完整，并且已审核的判断条件成立；这不是模型概率。',
    })
    expect(diagnosisConfidencePresentation('LOW')).toEqual({
      label: '仅供人工核查',
      detail: '当前证据只能形成线索或明确证据不足，需要人工确认。',
    })
  })

  it('does not explain impossible or missing counts as a real comparison', () => {
    const narrative = evidenceComparisonNarrative({
      featureCode: 'itgw_content_policy_blocked',
      failureRequestCount: 1,
      failureWithFeatureCount: 2,
      normalRequestCount: 0,
      normalWithFeatureCount: 0,
    })

    expect(narrative.summary).toBe('失败请求或正常请求的样本数量不足，暂时无法比较。')
    expect(narrative.scope).toContain('请先补齐')
    expect(isEvidenceCount(null)).toBe(false)
    expect(isEvidenceCount('')).toBe(false)
    expect(isEvidenceCount('01')).toBe(false)
    expect(isEvidenceCount('0')).toBe(true)
  })
})
