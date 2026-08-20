import { describe, expect, it } from 'vitest'
import {
  diagnosisConfirmedCandidateGuidance,
  diagnosisDeveloperExplanation,
  diagnosisDecisionStatusLabel,
  diagnosisPerspectiveHero,
  diagnosisPerspectiveLabel,
  diagnosisProblemBrief,
  diagnosisRootCauseAnswer,
  diagnosisSupportAction,
  normalizeDiagnosisPerspective,
} from '../diagnosisPerspective'

describe('diagnosis perspective', () => {
  it('defaults every missing or unknown value to the third-line developer view', () => {
    expect(normalizeDiagnosisPerspective(undefined)).toBe('developer')
    expect(normalizeDiagnosisPerspective('')).toBe('developer')
    expect(normalizeDiagnosisPerspective('admin')).toBe('developer')
  })

  it('allows an explicit second-line support view without changing diagnosis facts', () => {
    expect(normalizeDiagnosisPerspective('support')).toBe('support')
    expect(diagnosisPerspectiveLabel('developer')).toBe('三线开发视角')
    expect(diagnosisPerspectiveLabel('support')).toBe('二线保障视角')
  })

  it('summarizes what broke, where and when before showing any diagnosis conclusion', () => {
    expect(diagnosisProblemBrief({
      system: 'CSDP',
      service: 'csdp-task',
      title: 'CTI创建会话失败',
      severity: 'P1',
      errorCode: '701018',
      occurredAt: '2026-08-17T15:44:00',
    }, '候选文案')).toEqual({
      title: 'CTI创建会话失败',
      scope: 'CSDP / csdp-task',
      occurredAt: '2026-08-17 15:44:00',
      alertSignal: 'P1 · 错误码 701018',
    })
  })

  it('shows missing incident facts instead of guessing them from the conclusion', () => {
    expect(diagnosisProblemBrief({
      system: '',
      service: '',
      title: '',
      severity: '',
      errorCode: null,
      occurredAt: '',
    }, '')).toEqual({
      title: '故障现象未记录',
      scope: '系统未记录 / 服务未记录',
      occurredAt: '未记录',
      alertSignal: '无明确错误码',
    })
  })

  it('puts the developer decision boundary first instead of presenting a hypothesis as the root cause', () => {
    expect(diagnosisPerspectiveHero({
      perspective: 'developer',
      conclusionType: 'HYPOTHESIS',
      rootCause: '直接失败点：iCare 产品映射外部接口返回 HTTP 502（上游为何返回 502 尚未定位）',
      headline: '已形成候选方向',
      narrative: '这是告警已明确的失败点，不是最终根因。',
      candidateCount: 0,
    })).toEqual({
      title: '已确认故障发生点，尚未找到根因',
      summary: '这是告警已明确的失败点，不是最终根因。',
    })
  })

  it('answers the developer root-cause question directly before showing context', () => {
    expect(diagnosisRootCauseAnswer({
      conclusionType: 'LOCATED',
      rootCause: '回访结果字段为空，iCare 业务规则拒绝完结。',
      headline: '已定位',
    })).toBe('回访结果字段为空，iCare 业务规则拒绝完结。')
    expect(diagnosisRootCauseAnswer({
      conclusionType: 'HYPOTHESIS',
      rootCause: '直接失败点：上游返回 502。',
      headline: '已形成候选方向',
    })).toBe('尚未查明')
  })

  it('uses the located root cause as the primary known fact instead of a supporting count', () => {
    expect(diagnosisDeveloperExplanation({
      conclusionType: 'LOCATED',
      evidenceBasis: 'OBSERVED',
      keyEvidence: '2 个失败样本均命中。',
      rootCause: '回访结果字段为空。',
      candidateCount: 0,
    }).known).toBe('回访结果字段为空。')
  })

  it('explains known facts, unknown causes and evidence provenance in plain language', () => {
    expect(diagnosisDeveloperExplanation({
      conclusionType: 'HYPOTHESIS',
      evidenceBasis: 'REPORTED',
      keyEvidence: '告警明确记录 iCare 产品映射接口返回 HTTP 502。',
      rootCause: '直接失败点：iCare 产品映射接口返回 HTTP 502。',
      candidateCount: 0,
    })).toEqual({
      state: '尚未找到根因',
      known: '告警明确记录 iCare 产品映射接口返回 HTTP 502。',
      unknown: '当前证据只能说明故障在哪里发生，还不能解释为什么发生。',
      reason: '这条判断来自告警原文。告警能说明直接失败点，但不能证明更上游的原因。',
    })
  })

  it('gives second-line operators an escalation action instead of developer investigation instructions', () => {
    expect(diagnosisSupportAction('HYPOTHESIS')).toBe(
      '把告警、影响范围和现有线索一起升级三线；不要根据候选方向直接处置。',
    )
    expect(diagnosisSupportAction('INSUFFICIENT_EVIDENCE')).toContain('确认告警时间、服务和影响范围')
  })

  it('does not present an accepted hypothesis as a confirmed root cause', () => {
    expect(diagnosisDecisionStatusLabel('CONFIRMED', 'HYPOTHESIS', '已确认')).toBe(
      '候选方向已人工确认',
    )
    expect(diagnosisDecisionStatusLabel('CONFIRMED', 'LOCATED', '已确认')).toBe(
      '根因已人工确认',
    )
    expect(diagnosisConfirmedCandidateGuidance({
      status: 'CONFIRMED',
      conclusionType: 'HYPOTHESIS',
      nextStep: '继续查询上游返回 502 的原因。',
    })).toEqual({
      title: '继续核实未确认的根因，再登记真实结果',
      detail: '当前只是人工接受了候选方向，不等于根因定位完成。继续查询上游返回 502 的原因。',
    })
    expect(diagnosisConfirmedCandidateGuidance({
      status: 'CONFIRMED',
      conclusionType: 'LOCATED',
      nextStep: '复核后处置。',
    })).toBeNull()
  })
})
