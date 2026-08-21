export interface DiagnosisFollowUpContext {
  diagnosisId: string
  intakeConversationId: string | null
}

export type DiagnosisFollowUpContextOutcome =
  | { type: 'UNCHANGED' }
  | { type: 'ATTACHED', context: DiagnosisFollowUpContext }
  | { type: 'ENDED', expectedDiagnosisId: string }

export interface DiagnosisFollowUpContextRouting {
  appliesToCurrentConversation: boolean
  contextChanged: boolean
}

const STORAGE_PREFIX = 'mateclaw.troubleshooting.follow-up.'
const SAFE_ID = /^[A-Za-z0-9._:-]{1,128}$/

function storageKey(chatConversationId: string): string | null {
  const normalized = chatConversationId.trim()
  return SAFE_ID.test(normalized) ? `${STORAGE_PREFIX}${normalized}` : null
}

export function saveDiagnosisFollowUpContext(
  storage: Storage,
  chatConversationId: string,
  context: DiagnosisFollowUpContext,
): void {
  const key = storageKey(chatConversationId)
  const diagnosisId = context.diagnosisId.trim()
  const intakeConversationId = context.intakeConversationId?.trim() || null
  if (!key || !SAFE_ID.test(diagnosisId)
    || (intakeConversationId !== null && !SAFE_ID.test(intakeConversationId))) return
  try {
    storage.setItem(key, JSON.stringify({ diagnosisId, intakeConversationId }))
  } catch {
    // Session persistence is optional; the server remains authoritative.
  }
}

export function loadDiagnosisFollowUpContext(
  storage: Storage,
  chatConversationId: string,
): DiagnosisFollowUpContext | null {
  const key = storageKey(chatConversationId)
  if (!key) return null
  try {
    const raw = storage.getItem(key)
    if (!raw || raw.length > 512) return null
    const parsed = JSON.parse(raw) as Record<string, unknown>
    const diagnosisId = typeof parsed.diagnosisId === 'string' ? parsed.diagnosisId.trim() : ''
    const intakeConversationId = typeof parsed.intakeConversationId === 'string'
      ? parsed.intakeConversationId.trim()
      : null
    if (!SAFE_ID.test(diagnosisId)
      || (intakeConversationId !== null && !SAFE_ID.test(intakeConversationId))) return null
    return { diagnosisId, intakeConversationId }
  } catch {
    return null
  }
}

export function clearDiagnosisFollowUpContext(
  storage: Storage,
  chatConversationId: string,
): void {
  const key = storageKey(chatConversationId)
  if (!key) return
  try {
    storage.removeItem(key)
  } catch {
    // Ending locally must not depend on browser storage availability.
  }
}

/**
 * Commits a delayed response to the chat where the request originated.
 * A response from chat A can never attach to, or end, chat B.
 */
export function applyDiagnosisFollowUpContextOutcome(
  storage: Storage,
  originChatConversationId: string,
  currentChatConversationId: string,
  outcome: DiagnosisFollowUpContextOutcome,
): DiagnosisFollowUpContextRouting {
  let contextChanged = false
  if (outcome.type === 'ATTACHED') {
    saveDiagnosisFollowUpContext(storage, originChatConversationId, outcome.context)
    contextChanged = true
  } else if (outcome.type === 'ENDED') {
    const current = loadDiagnosisFollowUpContext(storage, originChatConversationId)
    if (current?.diagnosisId === outcome.expectedDiagnosisId.trim()) {
      clearDiagnosisFollowUpContext(storage, originChatConversationId)
      contextChanged = true
    }
  }
  return {
    appliesToCurrentConversation: originChatConversationId === currentChatConversationId,
    contextChanged,
  }
}
