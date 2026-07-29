/** A deterministic rule chain exists only after exact-version reconstruction succeeds. */
export function canRenderDeterministicDerivation(
  isDeterministic: boolean,
  derivationAvailable: boolean,
) {
  return isDeterministic && derivationAvailable
}
