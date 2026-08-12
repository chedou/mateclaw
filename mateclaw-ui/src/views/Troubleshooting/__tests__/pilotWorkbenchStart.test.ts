import { describe, expect, it } from 'vitest'
import diagnosisListSource from '../DiagnosisListView.vue?raw'
import formalWorkbenchSource from '../FormalWorkbench.vue?raw'

describe('pilot workbench start', () => {
  it('shows every troubleshooting viewer one current pilot step and one action', () => {
    expect(diagnosisListSource).toContain('class="pilot-workbench-strip"')
    expect(diagnosisListSource).toContain('buildPilotWorkbenchPrompt')
    expect(diagnosisListSource).toContain('现在轮到')
    expect(diagnosisListSource).toContain('{{ pilotPrompt.title }}')
    expect(diagnosisListSource).toContain('{{ pilotActionLabel }}')
    expect(diagnosisListSource).toContain('runPilotAction')
  })

  it('loads the viewer-safe pilot plan without exposing evaluation evidence', () => {
    expect(formalWorkbenchSource).toContain('troubleshootingApi.pilotPlan()')
    expect(formalWorkbenchSource).toContain(':pilot-plan="pilotPlan"')
    expect(formalWorkbenchSource).toContain(':pilot-plan-loading="pilotPlanLoading"')
    expect(formalWorkbenchSource).toContain(':pilot-plan-error="pilotPlanError"')
    expect(diagnosisListSource).not.toContain('evaluationSamples')
    expect(diagnosisListSource).not.toContain('baselineRuns')
  })

  it('opens the exact pending diagnosis or a prefilled non-rehearsal intake', () => {
    expect(formalWorkbenchSource).toContain('@pilot-open-diagnosis="openPilotDiagnosis"')
    expect(formalWorkbenchSource).toContain('@pilot-launch-formal="launchFormalPilotIncident"')
    expect(formalWorkbenchSource).toContain('incidentReportForm.rehearsal = false')
    expect(formalWorkbenchSource).toContain('incidentReportForm.system = scope.system')
    expect(formalWorkbenchSource).toContain('incidentReportForm.service = scope.service')
  })

  it('keeps pilot setup and evaluation curation behind manage permission', () => {
    expect(diagnosisListSource).toContain("pilotPrompt.kind === 'SETUP' && canManage")
    expect(diagnosisListSource).toContain("pilotPrompt.kind === 'HANDOFF_EVALUATION' && canManage")
    expect(formalWorkbenchSource).toContain('@pilot-setup="openPilotSetup"')
    expect(formalWorkbenchSource).toContain('@pilot-open-evaluation="openPilotEvaluation"')
  })

  it('takes an administrator from the list directly into the expanded setup form', () => {
    expect(formalWorkbenchSource).toContain(':start-pilot-setup="route.query.pilotSetup === \'1\'"')
    expect(formalWorkbenchSource).toContain("pilotSetup: '1'")
    expect(formalWorkbenchSource).toContain('delete query.pilotSetup')
  })
})
