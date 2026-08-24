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
    expect(firstUseGuideSource).toContain('有标准排障方法就直接复用')
    expect(firstUseGuideSource).toContain('没有时进入通用只读调查')
    expect(firstUseGuideSource).toContain('TROUBLESHOOTING_UI_LABELS.startRehearsal')
    expect(formalWorkbenchSource).toContain('openIncidentIntake')
    expect(formalWorkbenchSource).toContain('@pick-scenario="openKnownScenarioPicker"')
    expect(formalWorkbenchSource).toContain('ConversationIntakeDialog')
    expect(formalWorkbenchSource).toContain('@pick-conversation="openConversationIntake"')
    expect(incidentDialogSource).toContain('这是已登记场景？')
    expect(incidentDialogSource).toContain('改用对话补问')
    expect(incidentDialogSource).toContain('故障发生时间（有就填）')
    expect(incidentDialogSource).toContain('v-model="form.occurredAt"')
    expect(incidentDialogSource).toContain('真实告警 · 正式只读调查')
    expect(incidentDialogSource).toContain('试用演练')
    expect(incidentDialogSource).not.toContain('排障员工：')
    expect(incidentDialogSource).not.toContain('当前系统可见计划')
    expect(incidentDialogSource).not.toContain('openDiscoveryReadiness.nextAction')
    expect(incidentDialogSource).not.toContain('openDiscoveryReadiness.blockers')
    expect(incidentDialogSource).toContain('openDiscoveryReadinessPresentation')
    expect(incidentDialogSource).toContain("form.rehearsal ? '生成演练单' : '开始正式只读调查'")
    expect(incidentDialogSource).not.toContain('标记为演练（推荐试用；不占用生产去重窗口）')
    expect(conversationDialogSource).toContain('真实告警 · 正式只读调查')
    expect(conversationDialogSource).toContain('试用演练')
    expect(conversationDialogSource).toContain('v-model="rehearsal"')
    expect(conversationDialogSource).toContain(':value="false"')
    expect(conversationDialogSource).toContain(':value="true"')
    expect(conversationDialogSource).toContain(':disabled="modeLoading || loading || Boolean(conversationId) || Boolean(diagnosisId)"')
    expect(conversationDialogSource).toContain('对话开始后会锁定本次模式')
    expect(conversationDialogSource).toContain('rehearsal: rehearsal.value')
    expect(conversationDialogSource).toContain('直接粘贴完整告警')
    expect(conversationDialogSource).toContain('查看排障详情')
    expect(conversationDialogSource).toContain("query: { view: 'detail', diagnosisId: diagnosisId.value }")
    expect(scenarioDialogSource).toContain('返回粘贴告警')
  })

  it('starts ordinary and first-use entries in rehearsal without overwriting the formal pilot entry', () => {
    const ordinaryEntry = formalWorkbenchSource.match(
      /function openTroubleshootingScenario\(\)[\s\S]*?\n}/,
    )?.[0]
    const firstUseEntry = formalWorkbenchSource.match(
      /function startFirstUseRehearsal\(\)[\s\S]*?\n}/,
    )?.[0]
    const formalPilotEntry = formalWorkbenchSource.match(
      /function launchFormalPilotIncident[\s\S]*?\n}/,
    )?.[0]

    expect(ordinaryEntry).toContain('resetIncidentReportForm()')
    expect(firstUseEntry).toContain('openTroubleshootingScenario()')
    expect(formalPilotEntry).toContain('incidentReportForm.rehearsal = false')
    expect(formalPilotEntry).not.toContain('openTroubleshootingScenario()')
  })

  it('refreshes bounded-discovery readiness when either system or service changes', () => {
    expect(formalWorkbenchSource).toContain('() => incidentReportForm.system')
    expect(formalWorkbenchSource).toContain('() => incidentReportForm.service')
    expect(formalWorkbenchSource).toContain('formalOpenDiscoveryReadinessScope({ system, service })')
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
    expect(chatConsoleSource).toContain('troubleshootingAgentMode')
    expect(chatConsoleSource).toContain(
      "preferIntakeForTroubleshootingAgent: troubleshootingAgentMode(currentAgent.value) !== null",
    )
    expect(chatConsoleSource).toContain('await runTroubleshootingIntakeTurn(content)')
    expect(chatConsoleSource).toContain('正式只读排障已启用')
    expect(chatConsoleSource).toContain('troubleshootingTurnRehearsal(')
    expect(chatConsoleSource).toContain('originEmployeeName')
    expect(chatConsoleSource).not.toContain('rehearsal: true')
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
    expect(chatConsoleSource).toContain(':agent-id="String(selectedAgentId)"')
    expect(chatConsoleSource).toContain('@ended="onConversationIntakeEnded"')
    expect(chatConsoleSource).toContain('applyDiagnosisFollowUpContextOutcome(')
    expect(chatConsoleSource).toContain('ensureLocalConversationId()\n  conversationIntakeOpen.value = true')
    expect(chatConsoleSource).not.toContain('payload.originChatConversationId\n    || currentConversationId.value')
    expect(selectConversationHandler).toContain('resetTroubleshootingIntakeUi()')
    expect(selectConversationHandler).not.toContain('exitTroubleshootingIntakeMode()')
    expect(newConversationHandler).toContain('resetTroubleshootingIntakeUi()')
    expect(newConversationHandler).not.toContain('exitTroubleshootingIntakeMode()')
  })

  it('persists troubleshooting turns through the normal conversation history', () => {
    expect(chatConsoleSource).not.toContain('ts-local-')
    expect(chatConsoleSource).not.toContain('function appendLocalChatMessage')
    expect(chatConsoleSource).toContain('chatConversationId: originChatConversationId')
    expect(chatConsoleSource).toContain('await onTroubleshootingTranscriptPersisted(originChatConversationId)')
    expect(chatConsoleSource).toContain('refreshCurrentConversationMessages(chatConversationId)')
    expect(chatConsoleSource).toContain('projectRetryableTroubleshootingTurn(messages.value, chatConversationId)')
    expect(chatConsoleSource).toContain('clientTurnId: retryable.clientTurnId')
    expect(conversationDialogSource).toContain("emit('persisted', chatConversationId, resolve)")
    expect(conversationDialogSource).toContain('await refreshPersisted(originChatConversationId)')
    expect(conversationDialogSource).toContain('pendingText.value = text')
    expect(conversationDialogSource).toContain('projectRetryableTroubleshootingTurn(')
    expect(conversationDialogSource).toContain('clientTurnId: recoverable.clientTurnId')
    expect(conversationDialogSource).toContain('retryTurn.text === null || retryTurn.text === text')
    expect(conversationDialogSource).not.toContain("messages.value.push({ role: 'user', text })")
    expect(conversationDialogSource).not.toContain("messages.value.push({ role: 'assistant', text: data.answer })")
    expect(conversationDialogSource).toContain('const transcriptTarget = originChatConversationId && props.agentId')
    expect(conversationDialogSource).toContain('...transcriptTarget')
  })
})
