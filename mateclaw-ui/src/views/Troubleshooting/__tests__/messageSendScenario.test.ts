import { describe, expect, it } from 'vitest'
import type { Diagnosis } from '@/api'
import {
  EMPTY_MESSAGE_SEND_SCENARIO,
  MESSAGE_SEND_SCENARIO_KEY,
  MESSAGE_SEND_SCENARIO_SELECTOR,
  buildMessageSendScenarioRequest,
  canRunMessageSendEvidence,
  messageSendScenarioFormErrors,
} from '../messageSendScenario'

describe('message-send-failed scenario vertical', () => {
  it('builds the exact no-error-code scenario request owned by the server', () => {
    expect(messageSendScenarioFormErrors(EMPTY_MESSAGE_SEND_SCENARIO)).toEqual([])
    expect(buildMessageSendScenarioRequest({
      ...EMPTY_MESSAGE_SEND_SCENARIO,
      traceId: ' ps-safe-1 ',
      customerRef: ' customer-a ',
    })).toEqual({
      system: 'CSDP',
      service: 'csdp-session-service',
      title: '会话消息发送失败，页面未返回错误码',
      severity: 'P2',
      traceId: 'ps-safe-1',
      customerRef: 'customer-a',
      rehearsal: true,
    })
    expect(MESSAGE_SEND_SCENARIO_KEY).toBe('message_send_failed')
    expect(MESSAGE_SEND_SCENARIO_SELECTOR).toBe('csdp:scenario:message_send_failed')
  })

  it('rejects an unsafe identifier before it reaches the scenario API', () => {
    const errors = messageSendScenarioFormErrors({
      ...EMPTY_MESSAGE_SEND_SCENARIO,
      traceId: 'trace id with spaces',
    })
    expect(errors).toContain('Trace / PS 线索只能包含字母、数字和 . _ : / -')
  })

  it('offers evidence execution only to the waiting frozen scenario diagnosis', () => {
    const waiting = {
      investigationMode: 'SCENARIO_PLAYBOOK',
      status: 'NEEDS_INVESTIGATION',
      sopKey: MESSAGE_SEND_SCENARIO_SELECTOR,
      sourcePlaybookVersionRef: { playbookId: 'playbook-1', playbookVersion: 3 },
    } as Diagnosis
    expect(canRunMessageSendEvidence(waiting)).toBe(true)
    expect(canRunMessageSendEvidence({ ...waiting, status: 'READY_FOR_HUMAN' })).toBe(false)
    expect(canRunMessageSendEvidence({ ...waiting, sopKey: 'csdp:scenario:other' })).toBe(false)
  })
})
