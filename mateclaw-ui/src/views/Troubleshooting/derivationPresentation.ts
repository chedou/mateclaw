import type { InvestigationMode } from '@/api'

export function supportsDeterministicDerivation(mode: InvestigationMode) {
  return mode === 'ERROR_CODE_PLAYBOOK' || mode === 'SCENARIO_PLAYBOOK'
}

/** A deterministic rule chain exists only after exact-version reconstruction succeeds. */
export function canRenderDeterministicDerivation(
  mode: InvestigationMode,
  derivationAvailable: boolean,
) {
  return supportsDeterministicDerivation(mode) && derivationAvailable
}
