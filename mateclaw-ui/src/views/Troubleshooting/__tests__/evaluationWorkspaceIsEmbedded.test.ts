import { describe, expect, it } from 'vitest'
import formalWorkbenchSource from '../FormalWorkbench.vue?raw'
import evaluationWorkspaceSource from '../EvaluationSampleLedgerWorkspace.vue?raw'

describe('diagnosis evaluation workspace', () => {
  it('renders inside the troubleshooting work area instead of a dialog', () => {
    expect(formalWorkbenchSource)
      .toContain("import EvaluationSampleLedgerWorkspace from './EvaluationSampleLedgerWorkspace.vue'")
    expect(formalWorkbenchSource).toMatch(/<EvaluationSampleLedgerWorkspace[\s>]/)
    expect(formalWorkbenchSource).toContain('v-if="evaluationWorkspaceActive"')
    expect(formalWorkbenchSource).not.toContain('EvaluationSampleLedgerDialog')
    expect(evaluationWorkspaceSource).toContain('class="evaluation-ledger-workspace"')
    expect(evaluationWorkspaceSource).not.toContain('<el-dialog')
  })

  it('keeps a visible return action and the historical replay entry', () => {
    expect(evaluationWorkspaceSource).toContain("$emit('back')")
    expect(evaluationWorkspaceSource).toContain('回放一条历史样本')
    expect(evaluationWorkspaceSource).toContain("openHistoryReplay: []")
  })
})
