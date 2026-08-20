import { describe, expect, it } from 'vitest'
import formalWorkbenchSource from '../FormalWorkbench.vue?raw'
import scenarioDialogSource from '../TroubleshootingScenarioDialog.vue?raw'
import incidentDialogSource from '../IncidentReportDialog.vue?raw'
import messageSendDialogSource from '../MessageSendScenarioDialog.vue?raw'
import ctiDialogSource from '../CtiCreateConversationScenarioDialog.vue?raw'
import deploymentDialogSource from '../DeploymentTopologyScenarioDialog.vue?raw'
import conversationDialogSource from '../ConversationIntakeDialog.vue?raw'
import firstUseGuideSource from '../FirstUseGuideDrawer.vue?raw'
import chatConsoleSource from '../../ChatConsole.vue?raw'

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
    expect(formalWorkbenchSource).not.toContain('<FiveQuestionRail')
    expect(formalWorkbenchSource).not.toContain('class="question-progress-fold"')
    expect(formalWorkbenchSource).toContain('查看方式')
    expect(formalWorkbenchSource).toContain('FirstUseGuideDrawer')
    expect(formalWorkbenchSource).toContain('@guide="openFirstUseGuide"')
    expect(formalWorkbenchSource).toContain('@start="startFirstUseRehearsal"')
    expect(firstUseGuideSource).toContain('<el-drawer')
    expect(firstUseGuideSource).toContain('日常排障不从配置页开始')
    expect(firstUseGuideSource).toContain('TROUBLESHOOTING_UI_LABELS.startRehearsal')
    expect(formalWorkbenchSource).toContain('openIncidentIntake')
    expect(formalWorkbenchSource).toContain('@pick-scenario="openKnownScenarioPicker"')
    expect(formalWorkbenchSource).toContain('ConversationIntakeDialog')
    expect(formalWorkbenchSource).toContain('@pick-conversation="openConversationIntake"')
    expect(incidentDialogSource).toContain('这是已登记场景？')
    expect(incidentDialogSource).toContain('改用对话补问')
    expect(incidentDialogSource).toContain('故障发生时间（有就填）')
    expect(incidentDialogSource).toContain('v-model="form.occurredAt"')
    expect(conversationDialogSource).toContain('演练模式（推荐首次使用）')
    expect(conversationDialogSource).toContain('rehearsal: rehearsal.value')
    expect(conversationDialogSource).toContain('直接粘贴完整告警')
    expect(conversationDialogSource).toContain('查看排障详情')
    expect(conversationDialogSource).toContain("query: { view: 'detail', diagnosisId: diagnosisId.value }")
    expect(scenarioDialogSource).toContain('返回粘贴告警')
  })

  it('keeps the completed analysis visible in Chat instead of closing the result drawer', () => {
    const handler = chatConsoleSource.match(
      /async function onConversationIntakeReady[\s\S]*?\n}\n\nfunction exitTroubleshootingIntakeMode/,
    )?.[0]

    expect(handler).toBeTruthy()
    expect(handler).not.toContain('conversationIntakeOpen.value = false')
    expect(handler).toContain('分析结果已显示在排障对话')
  })
})
