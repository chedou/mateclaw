import { describe, expect, it } from 'vitest'
import {
  applyDiagnosisFollowUpContextOutcome,
  clearDiagnosisFollowUpContext,
  loadDiagnosisFollowUpContext,
  saveDiagnosisFollowUpContext,
} from '../diagnosisFollowUpContext'

class MemoryStorage implements Storage {
  private readonly values = new Map<string, string>()
  get length() { return this.values.size }
  clear() { this.values.clear() }
  getItem(key: string) { return this.values.get(key) ?? null }
  key(index: number) { return [...this.values.keys()][index] ?? null }
  removeItem(key: string) { this.values.delete(key) }
  setItem(key: string, value: string) { this.values.set(key, value) }
}

describe('diagnosisFollowUpContext', () => {
  it('keeps one diagnosis bound to the originating chat until explicitly cleared', () => {
    const storage = new MemoryStorage()

    saveDiagnosisFollowUpContext(storage, 'chat-1', {
      diagnosisId: 'diag-1',
      intakeConversationId: 'web-conv-1',
    })

    expect(loadDiagnosisFollowUpContext(storage, 'chat-1')).toEqual({
      diagnosisId: 'diag-1',
      intakeConversationId: 'web-conv-1',
    })
    expect(loadDiagnosisFollowUpContext(storage, 'chat-2')).toBeNull()

    clearDiagnosisFollowUpContext(storage, 'chat-1')
    expect(loadDiagnosisFollowUpContext(storage, 'chat-1')).toBeNull()
  })

  it('fails closed for malformed or oversized stored context', () => {
    const storage = new MemoryStorage()
    storage.setItem('mateclaw.troubleshooting.follow-up.chat-1', '{bad json')
    expect(loadDiagnosisFollowUpContext(storage, 'chat-1')).toBeNull()

    storage.setItem('mateclaw.troubleshooting.follow-up.chat-1', JSON.stringify({
      diagnosisId: 'x'.repeat(129),
      intakeConversationId: 'web-conv-1',
    }))
    expect(loadDiagnosisFollowUpContext(storage, 'chat-1')).toBeNull()
  })

  it('routes a delayed A response back to A without changing the active B context', () => {
    const storage = new MemoryStorage()
    saveDiagnosisFollowUpContext(storage, 'chat-b', {
      diagnosisId: 'diag-b',
      intakeConversationId: 'web-b',
    })

    const ready = applyDiagnosisFollowUpContextOutcome(storage, 'chat-a', 'chat-b', {
      type: 'ATTACHED',
      context: { diagnosisId: 'diag-a', intakeConversationId: 'web-a' },
    })
    expect(ready).toEqual({ appliesToCurrentConversation: false, contextChanged: true })
    expect(loadDiagnosisFollowUpContext(storage, 'chat-a')?.diagnosisId).toBe('diag-a')
    expect(loadDiagnosisFollowUpContext(storage, 'chat-b')?.diagnosisId).toBe('diag-b')

    const ended = applyDiagnosisFollowUpContextOutcome(storage, 'chat-a', 'chat-b', {
      type: 'ENDED',
      expectedDiagnosisId: 'diag-a',
    })
    expect(ended).toEqual({ appliesToCurrentConversation: false, contextChanged: true })
    expect(loadDiagnosisFollowUpContext(storage, 'chat-a')).toBeNull()
    expect(loadDiagnosisFollowUpContext(storage, 'chat-b')?.diagnosisId).toBe('diag-b')
  })

  it('does not let a stale end response clear a newer diagnosis in the same chat', () => {
    const storage = new MemoryStorage()
    saveDiagnosisFollowUpContext(storage, 'chat-a', {
      diagnosisId: 'diag-new',
      intakeConversationId: 'web-new',
    })

    expect(applyDiagnosisFollowUpContextOutcome(storage, 'chat-a', 'chat-a', {
      type: 'ENDED',
      expectedDiagnosisId: 'diag-old',
    })).toEqual({ appliesToCurrentConversation: true, contextChanged: false })
    expect(loadDiagnosisFollowUpContext(storage, 'chat-a')?.diagnosisId).toBe('diag-new')
  })
})
