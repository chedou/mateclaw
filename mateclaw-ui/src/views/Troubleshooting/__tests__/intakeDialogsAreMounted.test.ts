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
    expect(conversationDialogSource).toContain('演练模式（当前对话入口仅支持演练）')
    expect(conversationDialogSource).toContain('disabled')
    expect(conversationDialogSource).toContain('正式通用调查请使用「新建排障单」')
    expect(conversationDialogSource).not.toContain('生成正式排障单，适用于真实值班告警')
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

  it('routes alert-like prompts through Intake when the troubleshooting robot is selected', () => {
    expect(chatConsoleSource).toContain('isTroubleshootingReadOnlyTriageAgent')
    expect(chatConsoleSource).toContain(
      'preferIntakeForTroubleshootingAgent: isTroubleshootingReadOnlyTriageAgent(currentAgent.value)',
    )
    expect(chatConsoleSource).toContain('await runTroubleshootingIntakeTurn(content)')
  })

  it('keeps the diagnosis context after READY and exits only on an explicit end result', () => {
    const selectConversationHandler = chatConsoleSource.match(
      /async function selectConversation[\s\S]*?(?=\nfunction newConversation)/,
    )?.[0]
    const newConversationHandler = chatConsoleSource.match(
      /function newConversation[\s\S]*?(?=\nfunction onConversationsDeleted)/,
    )?.[0]

    expect(chatConsoleSource).toContain('tsActiveDiagnosisId')
    expect(chatConsoleSource).toContain('troubleshootingApi.diagnosisFollowUp')
    expect(chatConsoleSource).toContain('saveDiagnosisFollowUpContext')
    expect(chatConsoleSource).toContain("data.status === 'ENDED'")
    expect(chatConsoleSource).toContain('结束排障')
    expect(conversationDialogSource).not.toContain(':disabled="loading || !!diagnosisId"')
    expect(conversationDialogSource).toContain('troubleshootingApi.diagnosisFollowUp')
    expect(conversationDialogSource).toContain("emit('ended'")
    expect(conversationDialogSource).toContain('requestGeneration')
    expect(conversationDialogSource).toContain('originChatConversationId')
    expect(conversationDialogSource).toContain('requestGeneration !== generation')
    expect(conversationDialogSource).toContain('watch(() => props.originChatConversationId')
    expect(chatConsoleSource).toContain(':origin-chat-conversation-id="currentConversationId"')
    expect(chatConsoleSource).toContain('@ended="onConversationIntakeEnded"')
    expect(chatConsoleSource).toContain('applyDiagnosisFollowUpContextOutcome(')
    expect(chatConsoleSource).toContain('ensureLocalConversationId()\n  conversationIntakeOpen.value = true')
    expect(chatConsoleSource).not.toContain('payload.originChatConversationId\n    || currentConversationId.value')
    expect(selectConversationHandler).toContain('resetTroubleshootingIntakeUi()')
    expect(selectConversationHandler).not.toContain('exitTroubleshootingIntakeMode()')
    expect(newConversationHandler).toContain('resetTroubleshootingIntakeUi()')
    expect(newConversationHandler).not.toContain('exitTroubleshootingIntakeMode()')
  })
})
