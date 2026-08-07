import { describe, expect, it } from 'vitest'
import { useGuanceValidationDialog } from '../useGuanceValidationDialog'

describe('the Guance validation dialog session', () => {
  it('starts a clean normalized session and captures its immutable request snapshot', () => {
    const session = useGuanceValidationDialog()
    session.validationLoading.value = true
    session.checklist.indexVerified = true

    session.begin({
      system: ' CSDP ',
      service: ' session-service ',
      searchTerm: ' message_send_failed ',
      window: '-15m',
      occurredAt: null,
    }, null, 'ONBOARDING')

    expect(session.open.value).toBe(true)
    expect(session.validationLoading.value).toBe(false)
    expect(session.report.value).toBeNull()
    expect(session.checklist.indexVerified).toBe(false)
    expect(session.capture(7)).toEqual({
      sessionVersion: 7,
      origin: 'ONBOARDING',
      request: {
        system: 'CSDP',
        service: 'session-service',
        searchTerm: 'message_send_failed',
        window: '-15m',
        occurredAt: null,
      },
    })
  })
})
