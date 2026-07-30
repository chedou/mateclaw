import { describe, expect, it } from 'vitest'
import {
  buildGuanceOnboardingGuide,
  canAttachGuanceResultToDiagnosis,
  guanceOnboardingScopeKey,
  isActiveGuanceValidationSession,
  guanceOnboardingScopeErrors,
  isSafeGuanceSearchTerm,
  sameEvidenceChainLookup,
} from '../guanceOnboarding'
import { canStartGuanceValidation } from '../formalProjection'

describe('formal Guance onboarding', () => {
  it('builds a secret-free external configuration guide without truncating workspace ids', () => {
    const guide = buildGuanceOnboardingGuide({
      workspaceId: '2056993009360007169',
      system: ' CSDP ',
      service: ' csdp-session-service ',
    })

    expect(guide.externalConfig).toContain('workspace-id: 2056993009360007169')
    expect(guide.externalConfig).toContain('system: CSDP')
    expect(guide.externalConfig).toContain('service: csdp-session-service')
    expect(guide.externalConfig).toContain('log_search: <verified-log-search-binding>')
    expect(guide.externalConfig).toContain('log_trace_bundle: <verified-log-trace-bundle-binding>')
    expect(guide.runtimeEnvironment).toContain('MATECLAW_TROUBLESHOOTING_GUANCE_API_KEY=<inject-from-secret-manager>')
    expect(guide.runtimeEnvironment).not.toContain('DF-API-KEY:')
  })

  it('rejects values that could turn the guide into injected YAML or leak a secret-shaped scope', () => {
    expect(guanceOnboardingScopeErrors({
      workspaceId: '1\napi-key: leaked',
      system: 'CSDP',
      service: 'session',
    })).toContain('当前 Workspace ID 不可用')

    expect(guanceOnboardingScopeErrors({
      workspaceId: '1',
      system: 'CSDP',
      service: 'token=sk-example-secret',
    })).toContain('service 必须是安全资源标识符')
  })

  it('opens T7 validation only after the existing readiness gate is ready', () => {
    expect(canStartGuanceValidation('READY_FOR_VALIDATION')).toBe(true)
    expect(canStartGuanceValidation('CANONICAL_SIGNALS_OBSERVED')).toBe(true)
    expect(canStartGuanceValidation('DISABLED')).toBe(false)
    expect(canStartGuanceValidation('CONFIGURATION_INCOMPLETE')).toBe(false)
    expect(canStartGuanceValidation('UNAUTHORIZED')).toBe(false)
  })

  it('accepts only bounded resource identifiers as T7 search keys', () => {
    expect(isSafeGuanceSearchTerm('message_send_failed')).toBe(true)
    expect(isSafeGuanceSearchTerm("error' | leak(secret)")).toBe(false)
    expect(isSafeGuanceSearchTerm('')).toBe(false)
  })

  it('invalidates inspected readiness when the workspace changes', () => {
    expect(guanceOnboardingScopeKey({
      workspaceId: '1',
      system: 'CSDP',
      service: 'session',
    })).not.toBe(guanceOnboardingScopeKey({
      workspaceId: '2',
      system: 'CSDP',
      service: 'session',
    }))
  })

  it('treats a changed search key, window, or occurrence time as a different evidence lookup', () => {
    const lookup = {
      system: 'CSDP',
      service: 'order-svc',
      searchTerm: '903001',
      window: '-15m',
      occurredAt: '2026-07-28T18:59:32Z',
    }

    expect(sameEvidenceChainLookup(lookup, { ...lookup })).toBe(true)
    expect(sameEvidenceChainLookup(lookup, { ...lookup, searchTerm: '903002' })).toBe(false)
    expect(sameEvidenceChainLookup(lookup, { ...lookup, window: '-30m' })).toBe(false)
    expect(sameEvidenceChainLookup(lookup, { ...lookup, occurredAt: null })).toBe(false)
    expect(canAttachGuanceResultToDiagnosis('ONBOARDING', lookup, lookup)).toBe(false)
    expect(canAttachGuanceResultToDiagnosis('DIAGNOSIS', lookup, lookup)).toBe(true)
  })

  it('rejects an old response after the validation dialog is reopened with a new origin', () => {
    const lookup = {
      system: 'CSDP',
      service: 'order-svc',
      searchTerm: '903001',
      window: '-15m',
      occurredAt: null,
    }
    const requested = { sessionVersion: 4, origin: 'ONBOARDING' as const, request: lookup }

    expect(isActiveGuanceValidationSession(requested, {
      sessionVersion: 5,
      origin: 'DIAGNOSIS',
      request: lookup,
    }, true)).toBe(false)
    expect(isActiveGuanceValidationSession(requested, requested, false)).toBe(false)
    expect(isActiveGuanceValidationSession(requested, requested, true)).toBe(true)
  })
})
