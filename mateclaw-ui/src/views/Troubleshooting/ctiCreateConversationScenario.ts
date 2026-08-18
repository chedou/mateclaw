import type { Diagnosis, IncidentSeverity, ScenarioDiagnosisRequest } from '@/api'

export const CTI_CREATE_CONVERSATION_SCENARIO = {
  scenarioKey: 'cti_create_conversation_failed',
  selector: 'csdp:scenario:cti_create_conversation_failed',
  system: 'CSDP',
  service: 'csdp-task',
  name: 'CTI 创建会话失败',
} as const

export interface CtiCreateConversationScenarioForm {
  system?: string
  service?: string
  title: string
  severity: IncidentSeverity
  traceId: string
  customerRef: string
  occurredAt: string | null
  rehearsal: boolean
}

export const EMPTY_CTI_CREATE_CONVERSATION_SCENARIO: CtiCreateConversationScenarioForm = {
  system: CTI_CREATE_CONVERSATION_SCENARIO.system,
  service: CTI_CREATE_CONVERSATION_SCENARIO.service,
  title: 'CTI创建会话失败',
  severity: 'P1',
  traceId: '',
  customerRef: '',
  occurredAt: null,
  rehearsal: true,
}

const SAFE_IDENTIFIER = /^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$/

export function ctiCreateConversationScenarioFormErrors(
  form: CtiCreateConversationScenarioForm,
): string[] {
  const errors: string[] = []
  if (!form.title.trim()) errors.push('请描述故障现象')
  if (form.title.trim().length > 500) errors.push('故障现象不能超过 500 字符')
  if (form.customerRef.trim().length > 500) errors.push('影响对象不能超过 500 字符')
  if (form.traceId.trim() && !SAFE_IDENTIFIER.test(form.traceId.trim())) {
    errors.push('Trace / 关联 ID 只能包含字母、数字和 . _ : / -')
  }
  return errors
}

export function buildCtiCreateConversationScenarioRequest(
  form: CtiCreateConversationScenarioForm,
): ScenarioDiagnosisRequest {
  const errors = ctiCreateConversationScenarioFormErrors(form)
  if (errors.length) throw new Error(errors[0])
  const traceId = form.traceId.trim()
  const customerRef = form.customerRef.trim()
  return {
    system: CTI_CREATE_CONVERSATION_SCENARIO.system,
    service: CTI_CREATE_CONVERSATION_SCENARIO.service,
    title: form.title.trim(),
    severity: form.severity,
    ...(traceId ? { traceId } : {}),
    ...(customerRef ? { customerRef } : {}),
    ...(form.occurredAt ? { occurredAt: form.occurredAt } : {}),
    // D20 scenario-scoped binding and owner acceptance are not available yet.
    // Keep this browser entry rehearsal-only even if stale form state says false.
    rehearsal: true,
  }
}

export function isCtiCreateConversationDiagnosis(
  diagnosis: Diagnosis | null | undefined,
) {
  return diagnosis?.investigationMode === 'SCENARIO_PLAYBOOK'
    && diagnosis.sopKey === CTI_CREATE_CONVERSATION_SCENARIO.selector
}

export function canRunCtiCreateConversationEvidence(
  diagnosis: Diagnosis | null | undefined,
) {
  return isCtiCreateConversationDiagnosis(diagnosis)
    && diagnosis?.status === 'NEEDS_INVESTIGATION'
    && Boolean(diagnosis.sourcePlaybookVersionRef)
}
