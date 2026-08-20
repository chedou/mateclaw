import { describe, expect, it } from 'vitest'
import developerEvidencePanelSource from '../DeveloperEvidencePanel.vue?raw'
import formalWorkbenchSource from '../FormalWorkbench.vue?raw'

describe('the developer evidence screen keeps only actionable technical records', () => {
  it('does not repeat participant and usage guidance cards', () => {
    expect(developerEvidencePanelSource).not.toContain('InvestigationProvenancePanel')
    expect(developerEvidencePanelSource).not.toContain('调查参与者')
    expect(developerEvidencePanelSource).not.toContain('使用说明')
    expect(developerEvidencePanelSource).not.toContain('developer.capabilityLimits')
  })

  it('also keeps the deterministic derivation chain on the developer screen', () => {
    expect(developerEvidencePanelSource)
      .toContain("import DerivationChain from './DerivationChain.vue'")
    expect(developerEvidencePanelSource).toMatch(/<DerivationChain[\s>]/)
  })

  /** 父组件自己也必须挂在正式工作台上，否则这条链还是断的。 */
  it('reaches the formal workbench through the developer evidence panel', () => {
    expect(formalWorkbenchSource)
      .toContain("import DeveloperEvidencePanel from './DeveloperEvidencePanel.vue'")
    expect(formalWorkbenchSource).toMatch(/<DeveloperEvidencePanel[\s>]/)
  })
})
