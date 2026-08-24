import { describe, expect, it } from 'vitest'
import {
  buildFormalIncidentReport,
  formalOpenDiscoveryReadinessScope,
  formalIncidentFormErrors,
  formalIncidentRoutePreview,
  type FormalIncidentForm,
} from '../incidentReport'

const baseForm: FormalIncidentForm = {
  system: ' CSDP ',
  service: ' csdp-session-service ',
  title: ' 会话消息发送失败 ',
  severity: 'P2',
  errorCode: ' 903001 ',
  traceId: ' trace-safe-001 ',
  occurredAt: '2026-08-07T17:12:00+08:00',
  rehearsal: true,
}

describe('formal workbench incident report boundary', () => {
  it('normalizes a deterministic report without exposing unsafe intake fields', () => {
    const request = buildFormalIncidentReport(baseForm)

    expect(request).toEqual({
      system: 'CSDP',
      service: 'csdp-session-service',
      title: '会话消息发送失败',
      severity: 'P2',
      errorCode: '903001',
      traceId: 'trace-safe-001',
      occurredAt: '2026-08-07T17:12:00+08:00',
      intakeSource: 'web:formal-workbench',
      completeness: 'STRUCTURED',
      rehearsal: true,
    })
    for (const forbidden of [
      'incidentId', 'impact', 'rawInput', 'evidence', 'slaRemaining',
    ]) {
      expect(request).not.toHaveProperty(forbidden)
    }
  })

  it('classifies a trace-only report as LOG without inventing an error code', () => {
    const request = buildFormalIncidentReport({
      ...baseForm,
      errorCode: '   ',
    })

    expect(request.completeness).toBe('LOG')
    expect(request).not.toHaveProperty('errorCode')
    expect(request.traceId).toBe('trace-safe-001')
  })

  it('classifies a symptom-only report as SYMPTOM and omits blank optional fields', () => {
    const request = buildFormalIncidentReport({
      ...baseForm,
      errorCode: '',
      traceId: '',
      occurredAt: '',
      rehearsal: false,
    })

    expect(request.completeness).toBe('SYMPTOM')
    expect(request.rehearsal).toBe(false)
    expect(request).not.toHaveProperty('errorCode')
    expect(request).not.toHaveProperty('traceId')
    expect(request).not.toHaveProperty('occurredAt')
  })

  it('rejects an invalid or future incident time instead of querying the wrong window', () => {
    expect(formalIncidentFormErrors({
      ...baseForm,
      occurredAt: '2026-08-07 17:12:00',
    })).toContain('故障发生时间必须是带时区的有效时间')

    expect(formalIncidentFormErrors({
      ...baseForm,
      occurredAt: '2999-01-01T00:00:00Z',
    })).toContain('故障发生时间不能晚于当前时间')
  })

  it('requires the three fields needed for a useful workbench incident', () => {
    const invalid = {
      ...baseForm,
      system: ' ',
      service: '',
      title: '  ',
    }

    expect(formalIncidentFormErrors(invalid)).toEqual([
      '请选择或填写故障系统',
      '请填写故障服务',
      '请填写可复核的故障现象',
    ])
    expect(() => buildFormalIncidentReport(invalid)).toThrow('请选择或填写故障系统')
  })

  it('keeps the route preview honest about standard plans and bounded discovery', () => {
    expect(formalIncidentRoutePreview(baseForm)).toEqual({
      tone: 'DETERMINISTIC',
      title: '自动使用标准排障方法',
      detail: expect.stringContaining('匹配不上会转入通用只读调查'),
    })

    const discovery = formalIncidentRoutePreview({
      ...baseForm,
      errorCode: '',
      traceId: '',
    })
    expect(discovery.tone).toBe('BOUNDED_DISCOVERY')
    expect(discovery.title).toBe('通用只读调查')
    expect(discovery.detail).toContain('证据不够就停')

    const withTrace = formalIncidentRoutePreview({
      ...baseForm,
      errorCode: '',
      traceId: 'ps-abc123',
    })
    expect(withTrace.detail).toContain('已批准的只读查询范围')
  })

  it('scopes readiness to the exact system and service selected by the user', () => {
    expect(formalOpenDiscoveryReadinessScope(baseForm)).toEqual({
      system: 'CSDP',
      service: 'csdp-session-service',
    })
    expect(formalOpenDiscoveryReadinessScope({
      ...baseForm,
      system: ' ',
      service: ' ',
    })).toEqual({})
  })

  it('rejects DQL and raw log text before it can leave the browser form', () => {
    const dql = {
      ...baseForm,
      title: "L::logs:(message) {service='order-svc'} [-15m]",
    }
    const rawLog = {
      ...baseForm,
      errorCode: '',
      title: '会话消息发送失败',
      traceId: '2026-07-25 09:12:03 ERROR request failed\nat OrderService.java:42',
    }
    const dqlTrace = {
      ...baseForm,
      traceId: 'L::logs:message',
    }
    const jsonLog = {
      ...baseForm,
      title: '{"timestamp":"2026-07-25T09:12:03Z","level":"ERROR"}',
    }
    const prettyJsonLog = {
      ...baseForm,
      title: '{\n  "timestamp": "2026-07-25T09:12:03Z",\n  "level": "ERROR",\n  "message": "failed"\n}',
    }
    const oversizedJsonLog = {
      ...baseForm,
      title: `{"payload":"${'x'.repeat(1025)}","level":"ERROR"}`,
    }
    const pythonTraceback = {
      ...baseForm,
      title: 'Traceback (most recent call last):\n  File "/app/order.py", line 42',
    }
    const goPanic = {
      ...baseForm,
      title: 'panic: runtime error: index out of range\ngoroutine 18 [running]:',
    }
    const nodeStack = {
      ...baseForm,
      title: 'TypeError: request failed\n    at async submitReport (/app/index.js:42:17)',
    }
    const browserStack = {
      ...baseForm,
      title: '会话消息发送失败\n    at /app/bootstrap.js:3:9',
    }
    const safariStack = {
      ...baseForm,
      title: 'Error: request failed\nsubmit@https://app.example.com/main.js:42:17',
    }
    const accessLog = {
      ...baseForm,
      title: '127.0.0.1 - - [29/Jul/2026:12:00:00 +0800] "GET /orders HTTP/1.1" 500 612',
    }
    const safeBusinessText = {
      ...baseForm,
      title: 'Error: order submit failed；用户打开 `/orders/{id}` 返回 404，Windows 路径 C:\\data\\orders 不可用',
    }

    expect(formalIncidentFormErrors(dql)).toContain('故障现象不能包含 DQL、原始日志或堆栈正文')
    expect(formalIncidentFormErrors(rawLog)).toContain('Trace / PS 线索不能包含 DQL、原始日志或堆栈正文')
    expect(formalIncidentFormErrors(dqlTrace)).toContain('Trace / PS 线索不能包含 DQL、原始日志或堆栈正文')
    for (const unsafe of [
      jsonLog, prettyJsonLog, pythonTraceback, goPanic, nodeStack, browserStack, safariStack,
      accessLog,
    ]) {
      expect(formalIncidentFormErrors(unsafe)).toContain('故障现象不能包含 DQL、原始日志或堆栈正文')
    }
    expect(formalIncidentFormErrors(oversizedJsonLog)).toContain('故障现象最多 500 个字符')
    expect(formalIncidentFormErrors(safeBusinessText)).toEqual([])
    expect(buildFormalIncidentReport(safeBusinessText).title).toContain('/orders/{id}')
    expect(() => buildFormalIncidentReport(dql)).toThrow('故障现象不能包含 DQL、原始日志或堆栈正文')
    expect(() => buildFormalIncidentReport(rawLog)).toThrow('Trace / PS 线索不能包含 DQL、原始日志或堆栈正文')
  })
})
