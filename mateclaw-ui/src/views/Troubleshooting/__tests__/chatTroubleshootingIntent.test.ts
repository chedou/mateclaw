import { describe, expect, it } from 'vitest'
import {
  classifyChatTroubleshootingIntent,
  isTroubleshootingReadOnlyTriageAgent,
  shouldAutoStartTroubleshootingIntake,
  shouldOfferTroubleshootingIntake,
  troubleshootingDiagnosisResultMessage,
} from '../chatTroubleshootingIntent'

describe('chatTroubleshootingIntent', () => {
  it('keeps ordinary chat with the generic assistant', () => {
    expect(classifyChatTroubleshootingIntent('帮我写一首诗')).toBe('LOW')
    expect(classifyChatTroubleshootingIntent('今天天气怎么样')).toBe('LOW')
    expect(classifyChatTroubleshootingIntent('/approve')).toBe('LOW')
  })

  it('offers a soft confirm for medium-confidence ops language', () => {
    expect(classifyChatTroubleshootingIntent('CSDP 消息发不出去了')).toBe('MEDIUM')
    expect(shouldOfferTroubleshootingIntake('CSDP 消息发不出去了', {
      canOperate: true,
      suppressed: false,
      intakeActive: false,
    })).toBe(true)
    expect(shouldAutoStartTroubleshootingIntake('CSDP 消息发不出去了', {
      canOperate: true,
      suppressed: false,
      intakeActive: false,
    })).toBe(false)
  })

  it('routes a medium-confidence failure straight to Intake when the selected Agent is the troubleshooting robot', () => {
    const text = JSON.stringify({
      url: 'https://example.invalid/icare/channel/accept',
      error: '工单渠道与当前登录用户渠道不一致',
    })

    expect(classifyChatTroubleshootingIntent(text)).toBe('MEDIUM')
    expect(isTroubleshootingReadOnlyTriageAgent({
      name: 'troubleshooting-readonly-triage',
    })).toBe(true)
    expect(shouldAutoStartTroubleshootingIntake(text, {
      canOperate: true,
      suppressed: false,
      intakeActive: false,
      preferIntakeForTroubleshootingAgent: true,
    })).toBe(true)
  })

  it('keeps ordinary questions in normal chat even when the troubleshooting robot is selected', () => {
    expect(isTroubleshootingReadOnlyTriageAgent({ name: '通用助手' })).toBe(false)
    expect(shouldAutoStartTroubleshootingIntake('什么是排障方案', {
      canOperate: true,
      suppressed: false,
      intakeActive: false,
      preferIntakeForTroubleshootingAgent: true,
    })).toBe(false)
  })

  it('does not treat a bare JSON error example as an operational incident', () => {
    const text = JSON.stringify({ error: 'expected validation error in sample code' })

    expect(classifyChatTroubleshootingIntent(text)).toBe('LOW')
    expect(shouldAutoStartTroubleshootingIntake(text, {
      canOperate: true,
      suppressed: false,
      intakeActive: false,
      preferIntakeForTroubleshootingAgent: true,
    })).toBe(false)
  })

  it('auto-starts when structured alert fields are present', () => {
    const text = `系统: CSDP
服务: csdp-wechat
现象: ITGW访问失败【904003】
发生时间: 2026-08-07 17:12:00
客户ID: 未知`
    expect(classifyChatTroubleshootingIntent(text)).toBe('HIGH')
    expect(shouldAutoStartTroubleshootingIntake(text, {
      canOperate: true,
      suppressed: false,
      intakeActive: false,
    })).toBe(true)
  })

  it('auto-starts for the full monitoring alert format used by on-call staff', () => {
    const text = `客服数字化(WECHAT)-【ITGW访问失败】-事件
■【紧急】2026-08-07 17:12:00 (r/e4d3f5)
集群：sz3-s-k8s
服务：csdp-wechat
数量：6
异常：ITGW访问失败【904003】
说明：异常事件`

    expect(classifyChatTroubleshootingIntent(text)).toBe('HIGH')
    expect(shouldAutoStartTroubleshootingIntake(text, {
      canOperate: true,
      suppressed: false,
      intakeActive: false,
    })).toBe(true)
  })

  it('auto-starts for a latency alert that carries no error code', () => {
    const text = `客服数字化(WECHAT)-【URL慢请求】-事件
■【紧急】2026-08-06 12:00:00 (r/0009b2)
集群：sz3-s-k8s
服务：csdp-wechat
数量：110
说明：异常事件`

    expect(classifyChatTroubleshootingIntent(text)).toBe('HIGH')
    expect(shouldAutoStartTroubleshootingIntake(text, {
      canOperate: true,
      suppressed: false,
      intakeActive: false,
    })).toBe(true)
  })

  it('auto-starts for a four-digit CSDP business-code alert', () => {
    const text = `客服数字化(WECHAT)-【客户-搜索用户名超限制】-事件
■【紧急】2026-08-14 13:06:00 (r/93bf1d)
集群：sz3-s-k8s
服务：csdp-wechat
数量：4
异常：客户-搜索用户名超限制【1009】
说明：异常事件`

    expect(classifyChatTroubleshootingIntent(text)).toBe('HIGH')
    expect(shouldAutoStartTroubleshootingIntake(text, {
      canOperate: true,
      suppressed: false,
      intakeActive: false,
    })).toBe(true)
  })

  it('respects suppress and permission gates', () => {
    const text = '告警：会话创建失败'
    expect(shouldOfferTroubleshootingIntake(text, {
      canOperate: false,
      suppressed: false,
      intakeActive: false,
    })).toBe(false)
    expect(shouldOfferTroubleshootingIntake(text, {
      canOperate: true,
      suppressed: true,
      intakeActive: false,
    })).toBe(false)
  })

  it('returns a same-origin detail action after a real diagnosis is ready', () => {
    expect(troubleshootingDiagnosisResultMessage('diag-a/b', true, false))
      .toBe('已生成正式排障单，结论和证据都保存在详情里。\n\n'
        + '[打开排障详情](/troubleshooting?view=detail&diagnosisId=diag-a%2Fb)')
    expect(troubleshootingDiagnosisResultMessage('diag-existing', false, true))
      .toContain('已找到同一故障的既有排障单')
    expect(troubleshootingDiagnosisResultMessage('   ', true, true)).toBe('')
  })
})
