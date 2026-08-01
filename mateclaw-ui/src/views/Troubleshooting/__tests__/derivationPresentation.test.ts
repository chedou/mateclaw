import { describe, expect, it } from 'vitest'
import { canRenderDeterministicDerivation } from '../derivationPresentation'

describe('derivation presentation boundary', () => {
  it('does not render empty criteria or rules when reconstruction failed', () => {
    expect(canRenderDeterministicDerivation(true, false)).toBe(false)
    expect(canRenderDeterministicDerivation(true, true)).toBe(true)
    expect(canRenderDeterministicDerivation(false, true)).toBe(false)
  })
})
