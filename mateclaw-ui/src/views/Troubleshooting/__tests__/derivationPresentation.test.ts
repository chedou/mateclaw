import { describe, expect, it } from 'vitest'
import {
  canRenderDeterministicDerivation,
  supportsDeterministicDerivation,
} from '../derivationPresentation'

describe('derivation presentation boundary', () => {
  it('uses the v4 investigation mode instead of the legacy route mode', () => {
    expect(supportsDeterministicDerivation('ERROR_CODE_PLAYBOOK')).toBe(true)
    expect(supportsDeterministicDerivation('SCENARIO_PLAYBOOK')).toBe(true)
    expect(supportsDeterministicDerivation('OPEN_DISCOVERY')).toBe(false)
  })

  it('does not render empty criteria or rules when reconstruction failed', () => {
    expect(canRenderDeterministicDerivation('ERROR_CODE_PLAYBOOK', false)).toBe(false)
    expect(canRenderDeterministicDerivation('SCENARIO_PLAYBOOK', true)).toBe(true)
    expect(canRenderDeterministicDerivation('OPEN_DISCOVERY', true)).toBe(false)
  })
})
