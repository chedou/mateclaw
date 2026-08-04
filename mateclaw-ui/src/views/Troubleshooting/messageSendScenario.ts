import type {
  Diagnosis,
  IncidentSeverity,
  ScenarioDiagnosisRequest,
} from '@/api'

export const MESSAGE_SEND_SCENARIO_KEY = 'message_send_failed'
export const MESSAGE_SEND_SCENARIO_SELECTOR = 'csdp:scenario:message_send_failed'

export interface MessageSendScenarioForm {
  system: string
  service: string
  title: string
  severity: IncidentSeverity
  traceId: string
  customerRef: string
  occurredAt: string | null
  rehearsal: boolean
}

export const EMPTY_MESSAGE_SEND_SCENARIO: MessageSendScenarioForm = {
  system: 'CSDP',
  service: 'csdp-session-service',
  title: '会话消息发送失败，页面未返回错误码',
  severity: 'P2',
  traceId: '',
  customerRef: '',
  occurredAt: null,
  rehearsal: true,
}

const SAFE_IDENTIFIER = /^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$/

export function messageSendScenarioFormErrors(
  form: MessageSendScenarioForm,
): string[] {
  const errors: string[] = []
  if (!form.system.trim()) errors.push('请填写故障系统')
  if (!form.service.trim()) errors.push('请填写故障服务')
  if (!form.title.trim()) errors.push('请描述故障现象')
  if (form.system.trim().length > 128) errors.push('故障系统不能超过 128 字符')
  if (form.service.trim().length > 128) errors.push('故障服务不能超过 128 字符')
  if (form.title.trim().length > 500) errors.push('故障现象不能超过 500 字符')
  if (form.customerRef.trim().length > 500) errors.push('影响对象不能超过 500 字符')
  if (form.traceId.trim() && !SAFE_IDENTIFIER.test(form.traceId.trim())) {
    errors.push('Trace / PS 线索只能包含字母、数字和 . _ : / -')
  }
  return errors
}

export function buildMessageSendScenarioRequest(
  form: MessageSendScenarioForm,
): ScenarioDiagnosisRequest {
  const errors = messageSendScenarioFormErrors(form)
  if (errors.length) throw new Error(errors[0])
  const traceId = form.traceId.trim()
  const customerRef = form.customerRef.trim()
  return {
    system: form.system.trim(),
    service: form.service.trim(),
    title: form.title.trim(),
    severity: form.severity,
    ...(traceId ? { traceId } : {}),
    ...(customerRef ? { customerRef } : {}),
    ...(form.occurredAt ? { occurredAt: form.occurredAt } : {}),
    rehearsal: form.rehearsal,
  }
}

export function isMessageSendScenarioDiagnosis(diagnosis: Diagnosis | null | undefined) {
  return diagnosis?.investigationMode === 'SCENARIO_PLAYBOOK'
    && diagnosis.sopKey === MESSAGE_SEND_SCENARIO_SELECTOR
}

export function canRunMessageSendEvidence(diagnosis: Diagnosis | null | undefined) {
  return isMessageSendScenarioDiagnosis(diagnosis)
    && diagnosis?.status === 'NEEDS_INVESTIGATION'
    && Boolean(diagnosis.sourcePlaybookVersionRef)
}
