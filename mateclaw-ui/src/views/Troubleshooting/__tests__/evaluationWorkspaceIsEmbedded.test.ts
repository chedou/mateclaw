import { describe, expect, it } from 'vitest'
import formalWorkbenchSource from '../FormalWorkbench.vue?raw'
import capabilityWorkspaceSource from '../CapabilityWorkspaceShell.vue?raw'
import caseKnowledgeWorkspaceSource from '../CaseKnowledgeImportWorkspace.vue?raw'
import evaluationPilotPlanPanelSource from '../EvaluationPilotPlanPanel.vue?raw'
import evaluationWorkspaceSource from '../EvaluationSampleLedgerWorkspace.vue?raw'
import membersWorkspaceSource from '../../Security/Members/index.vue?raw'

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

  it('guides one closed diagnosis into the existing pilot evaluation ledger', () => {
    expect(formalWorkbenchSource).toContain(':can-evaluate="canManageTroubleshooting"')
    expect(formalWorkbenchSource).toContain('@evaluate="openEvaluationLedger"')
    expect(formalWorkbenchSource).toContain('@open-validation="openCurrentEvaluationValidation"')
    expect(evaluationWorkspaceSource).toContain('当前排障单怎样进入试点评估')
    expect(evaluationWorkspaceSource).toContain('采集当前排障样本')
    expect(evaluationWorkspaceSource).toContain('返回详情登记结果')
    expect(evaluationWorkspaceSource).toContain('填写人工标准答案')
    expect(evaluationWorkspaceSource).toContain('currentDiagnosisRehearsal')
  })

  it('captures a truthful human baseline before freezing the standard answer', () => {
    expect(evaluationWorkspaceSource).toContain('记录原来人工定位要多久')
    expect(evaluationWorkspaceSource).toContain('工单 / 群聊时间戳（实测）')
    expect(evaluationWorkspaceSource).toContain('处置人回忆（估算）')
    expect(evaluationWorkspaceSource).toContain('humanBaseline: referenceForm.includeHumanBaseline')
    expect(evaluationWorkspaceSource).toContain('evaluationNorthStar')
    expect(evaluationWorkspaceSource).toContain('试点是否真的省时间')
    expect(evaluationWorkspaceSource).toContain('不直接相减，也不省略人的复核成本')
    expect(evaluationWorkspaceSource).toContain("sample.sourcePlatform === 'GUANCE' && !sample.diagnosisFixtureMode")
    expect(evaluationWorkspaceSource).toContain('历史回放和演练样本不登记人工耗时')
    expect(evaluationWorkspaceSource).toContain('只统计真实 Guance、非演练样本')
  })

  it('shows one operational hand-off queue for the next formal pilot record', () => {
    expect(evaluationWorkspaceSource).toContain('试点接力队列')
    expect(evaluationWorkspaceSource).toContain('buildEvaluationPilotQueue')
    expect(evaluationWorkspaceSource).toContain('演练、Recorded Replay 和 fixture 不计入真实效果')
    expect(evaluationWorkspaceSource).toContain('openDiagnosis(row.diagnosisId)')
  })

  it('configures an exact Workspace pilot without creating a second ledger', () => {
    expect(evaluationWorkspaceSource).toContain('<EvaluationPilotPlanPanel')
    expect(evaluationWorkspaceSource).toContain(':start-open="startPilotSetup"')
    expect(evaluationWorkspaceSource).toContain(':scope-suggestions="pilotScopeSuggestions"')
    expect(evaluationWorkspaceSource).toContain('@updated="pilotPlan = $event"')
    expect(evaluationWorkspaceSource).toContain('troubleshootingApi.pilotPlan()')
    expect(evaluationPilotPlanPanelSource).toContain('先固定试点范围和三位负责人')
    expect(evaluationPilotPlanPanelSource).toContain('troubleshootingApi.declarePilotPlan')
    expect(evaluationPilotPlanPanelSource).toContain('workspaceTeamApi.listMembers')
    expect(evaluationPilotPlanPanelSource).toContain("workspaceStore.isAtLeast('admin')")
    expect(evaluationPilotPlanPanelSource).toContain('expectedVersion: props.plan?.version || 0')
    expect(evaluationPilotPlanPanelSource).toContain('三类职责必须由 3 名不同的工作区成员承担')
    expect(evaluationPilotPlanPanelSource).toContain('buildPilotTeamReadiness(members.value)')
    expect(evaluationPilotPlanPanelSource).toContain('pilotMemberCanOwnResponsibility')
    expect(evaluationPilotPlanPanelSource).toContain("member.active === true ? '' : ' · 账号不可用'")
    expect(evaluationPilotPlanPanelSource).toContain('试点需要 3 名能操作排障的成员，其中至少 2 名管理员或所有者')
    expect(evaluationPilotPlanPanelSource).toContain('startOpen?: boolean')
    expect(evaluationPilotPlanPanelSource).toContain('autoOpenConsumed')
    expect(evaluationPilotPlanPanelSource).toContain('去补齐成员与角色')
    expect(evaluationPilotPlanPanelSource)
      .toContain("const canManageMembers = computed(() => workspaceStore.can('manage:settings'))")
    expect(evaluationPilotPlanPanelSource).toContain('pilotMemberSettingsLocation(route.fullPath)')
    expect(evaluationPilotPlanPanelSource).toContain('请联系工作区管理员')
    expect(evaluationPilotPlanPanelSource).toContain('暂时无法读取工作区成员')
    expect(evaluationPilotPlanPanelSource).toContain('工作区成员读取失败，请重试后再保存')
    expect(evaluationPilotPlanPanelSource).toContain('从最近正式排障单选择')
    expect(evaluationPilotPlanPanelSource).toContain('只读取非演练记录')
    expect(evaluationPilotPlanPanelSource).toContain('选择后只会填入范围，不会自动保存')
    expect(evaluationPilotPlanPanelSource).toContain('addScopeSuggestion(suggestion)')
    expect(membersWorkspaceSource).toContain('来自智能排障试点')
    expect(membersWorkspaceSource).toContain('角色已经满足试点要求')
    expect(membersWorkspaceSource).toContain('buildPilotTeamReadiness(members.value)')
    expect(membersWorkspaceSource).toContain('3 名能操作排障的成员，其中至少 2 名具有管理员或所有者角色')
    expect(membersWorkspaceSource).toContain('暂时无法读取成员数量')
    expect(membersWorkspaceSource).toContain('返回试点配置')
    expect(membersWorkspaceSource).toContain('pilotMemberReturnPath(route.query.source, route.query.returnTo)')
    expect(evaluationWorkspaceSource).not.toContain('pilotSampleApi')
  })
})
