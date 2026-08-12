import { describe, expect, it } from 'vitest'
import formalWorkbenchSource from '../FormalWorkbench.vue?raw'
import scenarioDialogSource from '../TroubleshootingScenarioDialog.vue?raw'
import incidentDialogSource from '../IncidentReportDialog.vue?raw'
import messageSendDialogSource from '../MessageSendScenarioDialog.vue?raw'
import ctiDialogSource from '../CtiCreateConversationScenarioDialog.vue?raw'
import deploymentDialogSource from '../DeploymentTopologyScenarioDialog.vue?raw'

const INTAKE_DIALOGS = [
  'IncidentReportDialog',
  'MessageSendScenarioDialog',
  'CtiCreateConversationScenarioDialog',
  'DeploymentTopologyScenarioDialog',
] as const

describe('the troubleshooting intake dialogs', () => {
  it.each(INTAKE_DIALOGS)('%s remains mounted by the formal workbench', (component) => {
    expect(formalWorkbenchSource)
      .toContain(`import ${component} from './${component}.vue'`)
    expect(formalWorkbenchSource).toMatch(new RegExp(`<${component}[\\s>]`))
  })

  it('opens launch and intake surfaces as right-side drawers, not centered dialogs', () => {
    for (const source of [
      scenarioDialogSource,
      incidentDialogSource,
      messageSendDialogSource,
      ctiDialogSource,
      deploymentDialogSource,
    ]) {
      expect(source).toContain('<el-drawer')
      expect(source).toContain('var(--mc-ts-drawer-width)')
      expect(source).not.toContain('<el-dialog')
    }
    expect(formalWorkbenchSource).toContain('FiveQuestionRail')
    expect(formalWorkbenchSource).toContain('openIncidentIntake')
    expect(formalWorkbenchSource).toContain('@pick-scenario="openKnownScenarioPicker"')
    expect(formalWorkbenchSource).toContain('ConversationIntakeDialog')
    expect(formalWorkbenchSource).toContain('@pick-conversation="openConversationIntake"')
    expect(incidentDialogSource).toContain('这是已登记场景？')
    expect(incidentDialogSource).toContain('改用对话补问')
    expect(scenarioDialogSource).toContain('返回粘贴告警')
  })
})
