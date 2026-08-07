import { describe, expect, it } from 'vitest'
import type { Diagnosis } from '@/api'
import {
  CTI_CREATE_CONVERSATION_SCENARIO,
  buildCtiCreateConversationScenarioRequest,
  canRunCtiCreateConversationEvidence,
} from '../ctiCreateConversationScenario'

describe('CTI create-conversation real scenario vertical', () => {
  it('owns the exact CSDP csdp-task scenario without accepting a browser query', () => {
    expect(CTI_CREATE_CONVERSATION_SCENARIO).toMatchObject({
      scenarioKey: 'cti_create_conversation_failed',
      selector: 'csdp:scenario:cti_create_conversation_failed',
      system: 'CSDP',
      service: 'csdp-task',
    })
    expect(buildCtiCreateConversationScenarioRequest({
      title: 'CTI创建会话失败',
      severity: 'P1',
      traceId: '',
      customerRef: '集群 sz4-s-zaibei、告警数 3',
      occurredAt: '2026-08-07T17:24:00+08:00',
      rehearsal: false,
    })).toEqual({
      system: 'CSDP',
      service: 'csdp-task',
      title: 'CTI创建会话失败',
      severity: 'P1',
      customerRef: '集群 sz4-s-zaibei、告警数 3',
      occurredAt: '2026-08-07T17:24:00+08:00',
      rehearsal: false,
    })
  })

  it('offers the evidence action only for the frozen waiting CTI scenario', () => {
    const waiting = {
      investigationMode: 'SCENARIO_PLAYBOOK',
      status: 'NEEDS_INVESTIGATION',
      sopKey: 'csdp:scenario:cti_create_conversation_failed',
      sourcePlaybookVersionRef: { playbookId: 'cti-playbook', playbookVersion: 1 },
    } as Diagnosis
    expect(canRunCtiCreateConversationEvidence(waiting)).toBe(true)
    expect(canRunCtiCreateConversationEvidence({
      ...waiting,
      sopKey: 'csdp:scenario:message_send_failed',
    })).toBe(false)
  })
})
