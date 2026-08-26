/**
 * Soft intent gate for left-nav Chat → troubleshooting Intake.
 *
 * Only HIGH confidence divertable paths should interrupt the generic Agent.
 * MEDIUM shows a confirm banner. LOW leaves the message with the Agent.
 */

export type ChatTroubleshootingIntent = 'HIGH' | 'MEDIUM' | 'LOW'

const FIELD_LINE = /(?:^|\n)\s*(?:系统|服务|集群|错误码|发生时间|客户(?:ID|id)?|现象|问题(?:现象)?|trace(?:id)?|ps(?:id)?)\s*[:：]/im
const ERROR_CODE_BRACKET = /【\s*\d{3,8}\s*】|\berror[_ ]?code\b\s*[=:：]?\s*\d{3,8}/i
// 慢请求 belongs here even though nothing failed: a latency alert is still an
// alert, and it is the one shape that carries no error code to score on.
const ALERT_FAILURE = /告警|报障|排障|故障|报错|不能用|不可用|无响应|崩溃|卡住|卡顿|卡慢|(?:很|这么|特别|突然)卡|响应慢|加载慢|起不来|白屏|接口返回\s*[45]\d{2}|ITGW|访问失败|发送失败|发不出去|创建会话失败|会话创建失败|超时|慢请求|超限制|异常码|失败/
const CONTEXTUAL_ERROR = /(?:接口|服务|请求|页面|数据库|调用|任务).{0,8}错误|错误.{0,8}(?:接口|服务|请求|返回码|日志)/
const WEAK_OPS = /观测云|Guance|调用链|trace[_ ]?id|ps[_ ]?id|生产故障/i
const CLEARLY_GENERAL = /^(写一|帮我写|翻译|总结一下|什么是|如何设计|介绍.{0,12}(?:规范|机制)|解释.{0,12}(?:类型|概念)|讲个笑话|今天天气)|```[\s\S]{40,}/
const STRUCTURED_ERROR_FIELD = /["']error["']\s*:\s*["'][^"'\r\n]{2,}/i
const STRUCTURED_INCIDENT_CONTEXT = /["']url["']\s*:|(?:^|\n)\s*(?:系统|服务|集群|发生时间|现象|异常)\s*[:：]/im

const TROUBLESHOOTING_READONLY_TRIAGE_AGENT = 'troubleshooting-readonly-triage'

export type TroubleshootingAgentMode = 'FORMAL' | 'REHEARSAL'

/**
 * Employee capability is configuration-driven. A formal employee must carry
 * all three tags so an accidental generic `troubleshooting` label cannot
 * silently turn model chat into a production investigation. The built-in
 * robot receives these tags from the audited database migration.
 */
export function troubleshootingAgentMode(
  agent: { id?: string | number | null; name?: string | null; tags?: string | null } | null | undefined,
): TroubleshootingAgentMode | null {
  const tags = new Set((agent?.tags || '')
    .split(',')
    .map(tag => tag.trim().toLowerCase())
    .filter(Boolean))
  if (!tags.has('troubleshooting')) return null
  if (tags.has('readonly') && tags.has('formal')) return 'FORMAL'
  return 'REHEARSAL'
}

/** New intake freezes its mode once; later turns must inherit the server fact. */
export function troubleshootingTurnRehearsal(
  mode: TroubleshootingAgentMode | null,
  existingIntakeConversationId: string | null | undefined,
): boolean | undefined {
  if (existingIntakeConversationId?.trim()) return undefined
  return mode !== 'FORMAL'
}

export function isTroubleshootingReadOnlyTriageAgent(
  agent: { name?: string | null } | null | undefined,
): boolean {
  return agent?.name?.trim().toLowerCase() === TROUBLESHOOTING_READONLY_TRIAGE_AGENT
}

export function classifyChatTroubleshootingIntent(raw: string): ChatTroubleshootingIntent {
  const text = (raw || '').trim()
  if (!text || text.length < 4) return 'LOW'
  if (CLEARLY_GENERAL.test(text)) return 'LOW'
  if (/^\/(approve|deny|help|clear)\b/i.test(text)) return 'LOW'

  let score = 0
  const fieldHits = text.match(new RegExp(FIELD_LINE.source, 'gim'))?.length ?? 0
  if (fieldHits >= 2) score += 3
  else if (fieldHits === 1) score += 2

  if (ERROR_CODE_BRACKET.test(text)) score += 2
  if (ALERT_FAILURE.test(text)) score += 2
  if (CONTEXTUAL_ERROR.test(text)) score += 2
  if (WEAK_OPS.test(text)) score += 1
  if (STRUCTURED_ERROR_FIELD.test(text) && STRUCTURED_INCIDENT_CONTEXT.test(text)) score += 2
  if (/\b\d{5,6}\b/.test(text) && /失败|告警|错误/.test(text)) score += 1

  if (score >= 4) return 'HIGH'
  if (score >= 2) return 'MEDIUM'
  return 'LOW'
}

export function shouldOfferTroubleshootingIntake(
  text: string,
  options: { canOperate: boolean; suppressed: boolean; intakeActive: boolean },
): boolean {
  if (!options.canOperate || options.suppressed) return false
  if (options.intakeActive) return true
  return classifyChatTroubleshootingIntent(text) !== 'LOW'
}

export function shouldAutoStartTroubleshootingIntake(
  text: string,
  options: {
    canOperate: boolean
    suppressed: boolean
    intakeActive: boolean
    preferIntakeForTroubleshootingAgent?: boolean
  },
): boolean {
  if (!options.canOperate || options.suppressed) return false
  if (options.intakeActive) return true
  const intent = classifyChatTroubleshootingIntent(text)
  return intent === 'HIGH'
    || (options.preferIntakeForTroubleshootingAgent === true && intent === 'MEDIUM')
}

/**
 * Keep the completed troubleshooting result inside Chat while giving the
 * operator one obvious next action. The diagnosis id is server-owned; encode
 * it before placing it in a same-origin route.
 */
export function troubleshootingDiagnosisResultMessage(
  diagnosisId: string,
  created: boolean | null,
  rehearsal: boolean,
): string {
  const safeId = diagnosisId.trim()
  if (!safeId) return ''
  const state = created === false
    ? '已找到同一故障的既有排障单，不会重复调查。'
    : `已生成${rehearsal ? '演练' : '正式'}排障单，结论和证据都保存在详情里。`
  return `${state}\n\n[打开排障详情](/troubleshooting?view=detail&diagnosisId=${encodeURIComponent(safeId)})`
}
