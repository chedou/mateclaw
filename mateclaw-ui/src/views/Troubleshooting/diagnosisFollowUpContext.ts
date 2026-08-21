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

export interface TranscriptContextProjection {
  foundTranscript: boolean
  context: DiagnosisFollowUpContext | null
  intakeConversationId: string | null
}

export interface RetryableTroubleshootingTurn {
  clientTurnId: string
}

const STORAGE_PREFIX = 'mateclaw.troubleshooting.follow-up.'
const SAFE_ID = /^[A-Za-z0-9._:-]{1,128}$/

/** Restores the active Diagnosis from the MySQL-backed message metadata. */
export function projectDiagnosisFollowUpFromTranscript(
  messages: Array<{ conversationId?: string; role?: string; metadata?: any }>,
  chatConversationId: string,
): TranscriptContextProjection {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index]
    if (message.conversationId !== chatConversationId || message.role !== 'assistant') continue
    const metadata = message.metadata
    if (metadata?.type !== 'troubleshooting_transcript') continue
    if (metadata.followUpIntent === 'END') {
      return { foundTranscript: true, context: null, intakeConversationId: null }
    }
    const diagnosisId = typeof metadata.diagnosisId === 'string'
      ? metadata.diagnosisId.trim()
      : ''
    if (SAFE_ID.test(diagnosisId)) {
      const intakeConversationId = typeof metadata.intakeConversationId === 'string'
        && SAFE_ID.test(metadata.intakeConversationId.trim())
        ? metadata.intakeConversationId.trim()
        : null
      return {
        foundTranscript: true,
        context: { diagnosisId, intakeConversationId },
        intakeConversationId,
      }
    }
    const intakeConversationId = typeof metadata.intakeConversationId === 'string'
      && SAFE_ID.test(metadata.intakeConversationId.trim())
      ? metadata.intakeConversationId.trim()
      : null
    if (intakeConversationId) {
      return { foundTranscript: true, context: null, intakeConversationId }
    }
  }
  return { foundTranscript: false, context: null, intakeConversationId: null }
}

/** Recovers the durable retry identity without ever persisting the raw question. */
export function projectRetryableTroubleshootingTurn(
  messages: Array<{ conversationId?: string; role?: string; metadata?: any }>,
  chatConversationId: string,
): RetryableTroubleshootingTurn | null {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index]
    if (message.conversationId !== chatConversationId) continue
    const metadata = message.metadata
    if (metadata?.type !== 'troubleshooting_transcript') continue
    if (metadata.transcriptStatus !== 'PENDING'
      && metadata.transcriptStatus !== 'FAILED_RETRYABLE') return null
    const clientTurnId = typeof metadata.clientTurnId === 'string'
      ? metadata.clientTurnId.trim()
      : ''
    return SAFE_ID.test(clientTurnId) ? { clientTurnId } : null
  }
  return null
}

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
