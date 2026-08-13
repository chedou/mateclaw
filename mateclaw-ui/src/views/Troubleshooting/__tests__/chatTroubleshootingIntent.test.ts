import { describe, expect, it } from 'vitest'
import {
  classifyChatTroubleshootingIntent,
  shouldAutoStartTroubleshootingIntake,
  shouldOfferTroubleshootingIntake,
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
})
