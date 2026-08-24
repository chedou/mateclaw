import type {
  IncidentCompleteness,
  IncidentReportRequest,
  IncidentSeverity,
} from '@/api'

export interface FormalIncidentForm {
  system: string
  service: string
  title: string
  severity: IncidentSeverity
  errorCode: string
  traceId: string
  occurredAt: string
  rehearsal: boolean
}

export type FormalIncidentRouteTone = 'DETERMINISTIC' | 'BOUNDED_DISCOVERY'

export interface FormalIncidentRoutePreview {
  tone: FormalIncidentRouteTone
  title: string
  detail: string
}

export type FormalOpenDiscoveryReadinessScope = {
  system?: string
  service?: string
}

export const EMPTY_FORMAL_INCIDENT: FormalIncidentForm = {
  system: '',
  service: '',
  title: '',
  severity: 'P2',
  errorCode: '',
  traceId: '',
  occurredAt: '',
  rehearsal: true,
}

const DEVELOPER_EVIDENCE = /(?:\b[LMTO]::|\bdql\b|raw[ _-]?logs?|原始日志|全量日志包)/i
const RAW_LOG_BODY = /(?:^|\n)\s*\d{4}-\d{2}-\d{2}[T\s]\d{2}:\d{2}:\d{2}.*\b(?:TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\b|(?:^|\n)\s*(?:at\s+[A-Za-z_$][\w.$]*\([^\n)]*:\d+\)|Caused by:|Exception in thread|Traceback \(most recent call last\):|File\s+["'][^"'\n]+["'],\s*line\s+\d+|panic:|goroutine\s+\d+\s+\[)|(?:^|\s)(?:timestamp|time|level|logger|thread|message|stack|exception|traceback|goroutine|panic)\s*[:=]/im
const STRUCTURED_LOG_BODY = /\{[\s\S]{0,1024}"(?:timestamp|time|level|logger|thread|message|stack|exception|traceback|goroutine|panic)"\s*:/i
const SCRIPT_STACK_BODY = /^(?:\s*(?:TypeError|ReferenceError|RangeError|SyntaxError|URIError|EvalError|AggregateError):(?:\s|$)|\s*at\s+(?:(?:async\s+)?[A-Za-z_$][\w.$<>]*\s+)?\(?[^\n()]+\.(?:js|mjs|cjs|ts|tsx|jsx|vue):\d+(?::\d+)?\)?\s*$|\s*[^@\n]{0,200}@(?:https?|file):\/\/[^\n]+\.(?:js|mjs|cjs|ts|tsx|jsx|vue):\d+(?::\d+)?\s*$)/im
const ACCESS_LOG_BODY = /^\s*\S+\s+\S+\s+\S+\s+\[[^\n]{1,128}\]\s+"(?:GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE|CONNECT)\s+\S+\s+HTTP\/\d(?:\.\d)?"\s+\d{3}\s+(?:\d+|-)(?:\s+"[^"\n]*"\s+"[^"\n]*")?\s*$/im
const SAFE_TRACE_ID = /^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$/
const MAX_IDENTIFIER_LENGTH = 128
const MAX_TITLE_LENGTH = 500
const ISO_OCCURRED_AT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?(?:Z|[+-]\d{2}:\d{2})$/

function clean(value: string) {
  return value.trim()
}

function containsDeveloperEvidence(value: string) {
  return DEVELOPER_EVIDENCE.test(value)
    || RAW_LOG_BODY.test(value)
    || STRUCTURED_LOG_BODY.test(value)
    || SCRIPT_STACK_BODY.test(value)
    || ACCESS_LOG_BODY.test(value)
}

export function formalIncidentFormErrors(form: FormalIncidentForm) {
  const errors: string[] = []
  if (!clean(form.system)) errors.push('请选择或填写故障系统')
  if (!clean(form.service)) errors.push('请填写故障服务')
  if (!clean(form.title)) errors.push('请填写可复核的故障现象')
  if (clean(form.system).length > MAX_IDENTIFIER_LENGTH) errors.push('故障系统最多 128 个字符')
  if (clean(form.service).length > MAX_IDENTIFIER_LENGTH) errors.push('故障服务最多 128 个字符')
  if (clean(form.title).length > MAX_TITLE_LENGTH) errors.push('故障现象最多 500 个字符')
  if (clean(form.errorCode).length > MAX_IDENTIFIER_LENGTH) errors.push('错误码最多 128 个字符')
  const occurredAt = clean(form.occurredAt)
  if (occurredAt) {
    const parsed = Date.parse(occurredAt)
    if (!ISO_OCCURRED_AT.test(occurredAt) || !Number.isFinite(parsed)) {
      errors.push('故障发生时间必须是带时区的有效时间')
    } else if (parsed > Date.now() + 5 * 60 * 1000) {
      errors.push('故障发生时间不能晚于当前时间')
    }
  }
  if (containsDeveloperEvidence(form.system)) errors.push('故障系统不能包含 DQL、原始日志或堆栈正文')
  if (containsDeveloperEvidence(form.service)) errors.push('故障服务不能包含 DQL、原始日志或堆栈正文')
  if (containsDeveloperEvidence(form.title)) errors.push('故障现象不能包含 DQL、原始日志或堆栈正文')
  if (clean(form.errorCode) && containsDeveloperEvidence(form.errorCode)) {
    errors.push('错误码不能包含 DQL、原始日志或堆栈正文')
  }
  if (clean(form.traceId)) {
    if (containsDeveloperEvidence(form.traceId)) {
      errors.push('Trace / PS 线索不能包含 DQL、原始日志或堆栈正文')
    } else if (!SAFE_TRACE_ID.test(clean(form.traceId))) {
      errors.push('Trace / PS 线索只能填写安全标识符')
    }
  }
  return errors
}

function completeness(errorCode: string, traceId: string): IncidentCompleteness {
  if (errorCode) return 'STRUCTURED'
  return traceId ? 'LOG' : 'SYMPTOM'
}

/**
 * Converts the intentionally small console form into the existing Incident API
 * contract. Optional keys are omitted rather than serialized as empty strings.
 */
export function buildFormalIncidentReport(form: FormalIncidentForm): IncidentReportRequest {
  const errors = formalIncidentFormErrors(form)
  if (errors.length) throw new Error(errors[0])

  const system = clean(form.system)
  const service = clean(form.service)
  const title = clean(form.title)
  const errorCode = clean(form.errorCode)
  const traceId = clean(form.traceId)
  const occurredAt = clean(form.occurredAt)

  return {
    system,
    service,
    title,
    severity: form.severity,
    ...(errorCode ? { errorCode } : {}),
    ...(traceId ? { traceId } : {}),
    ...(occurredAt ? { occurredAt } : {}),
    intakeSource: 'web:formal-workbench',
    completeness: completeness(errorCode, traceId),
    rehearsal: form.rehearsal,
  }
}

/** Readiness belongs to one exact service asset, never to a system alone. */
export function formalOpenDiscoveryReadinessScope(
  form: Pick<FormalIncidentForm, 'system' | 'service'>,
): FormalOpenDiscoveryReadinessScope {
  const system = clean(form.system)
  const service = clean(form.service)
  return {
    ...(system ? { system } : {}),
    ...(service ? { service } : {}),
  }
}

/** Keeps the UI honest about the route that the server will actually attempt. */
export function formalIncidentRoutePreview(
  form: Pick<FormalIncidentForm, 'errorCode' | 'traceId'>,
): FormalIncidentRoutePreview {
  if (clean(form.errorCode)) {
    return {
      tone: 'DETERMINISTIC',
      title: '自动使用标准排障方法',
      detail: '有错误码时先自动匹配已审核的方法；匹配不上会转入通用只读调查。配置不合规会明确停止，不会瞎猜。',
    }
  }
  return {
    tone: 'BOUNDED_DISCOVERY',
    title: '通用只读调查',
    detail: clean(form.traceId)
      ? '按已批准的只读查询范围追踪这条线索；证据不够就停止并转人工，不会编造高把握根因。'
      : '按故障现象查询已接入的只读数据；结论最多按中等把握看待，证据不够就停，不会创造假诊断。',
  }
}
