import { describe, expect, it } from 'vitest'
import formalWorkbenchSource from '../FormalWorkbench.vue?raw'
import capabilityWorkspaceSource from '../CapabilityWorkspaceShell.vue?raw'
import caseKnowledgeWorkspaceSource from '../CaseKnowledgeImportWorkspace.vue?raw'
import evaluationWorkspaceSource from '../EvaluationSampleLedgerWorkspace.vue?raw'

describe('troubleshooting capability workspaces', () => {
  it('renders diagnosis evaluation inside the work area instead of a dialog', () => {
    expect(formalWorkbenchSource)
      .toContain("import EvaluationSampleLedgerWorkspace from './EvaluationSampleLedgerWorkspace.vue'")
    expect(formalWorkbenchSource).toMatch(/<EvaluationSampleLedgerWorkspace[\s>]/)
    expect(formalWorkbenchSource).toContain('v-if="evaluationWorkspaceActive"')
    expect(formalWorkbenchSource).not.toContain('EvaluationSampleLedgerDialog')
    expect(evaluationWorkspaceSource).toContain('class="evaluation-ledger-workspace"')
    expect(evaluationWorkspaceSource).not.toContain('<el-dialog')
  })

  it('renders historical case import in the same workspace shell', () => {
    expect(formalWorkbenchSource)
      .toContain("import CaseKnowledgeImportWorkspace from './CaseKnowledgeImportWorkspace.vue'")
    expect(formalWorkbenchSource).toMatch(/<CaseKnowledgeImportWorkspace[\s>]/)
    expect(formalWorkbenchSource).toContain('v-else-if="caseKnowledgeWorkspaceActive"')
    expect(formalWorkbenchSource).not.toContain('CaseKnowledgeImportDialog')
    expect(caseKnowledgeWorkspaceSource).not.toContain('<el-dialog')
    expect(caseKnowledgeWorkspaceSource).toContain('<CapabilityWorkspaceShell')
    expect(evaluationWorkspaceSource).toContain('<CapabilityWorkspaceShell')
  })

  it('defines one responsive title and scrolling contract for both pages', () => {
    expect(capabilityWorkspaceSource).toContain('class="capability-workspace-shell"')
    expect(capabilityWorkspaceSource).toContain('class="capability-workspace-content"')
    expect(capabilityWorkspaceSource).toContain('overflow-y:auto')
    expect(capabilityWorkspaceSource).toContain('@media(max-width:760px)')
  })

  it('keeps a visible return action and the historical replay entry', () => {
    expect(evaluationWorkspaceSource).toContain("$emit('back')")
    expect(evaluationWorkspaceSource).toContain('回放一条历史样本')
    expect(evaluationWorkspaceSource).toContain('openReplayDrawer')
    expect(evaluationWorkspaceSource).toContain('<SynthesisPreviewDialog embedded')
    expect(evaluationWorkspaceSource).not.toContain("historyReplayOpen")
  })
})
