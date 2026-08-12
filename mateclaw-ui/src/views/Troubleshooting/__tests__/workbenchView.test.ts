import { describe, expect, it } from 'vitest'
import {
  DEFAULT_WORKBENCH_VIEW,
  TROUBLESHOOTING_UI_LABELS,
  WORKBENCH_CAPABILITY_ACTIONS,
  WORKBENCH_TROUBLESHOOTING_SCENARIOS,
  diagnosisSelectionMode,
  isDiagnosisViewMode,
  resolveWorkbenchView,
  shouldShowQueuePanel,
  workbenchSecondaryScenarios,
  workbenchViewQuery,
} from '../workbenchView'

describe('troubleshooting workbench view mode', () => {
  it('uses the traditional list as the default view', () => {
    expect(DEFAULT_WORKBENCH_VIEW).toBe('LIST')
    expect(resolveWorkbenchView(undefined, undefined)).toBe('LIST')
  })

  it('keeps diagnosis deep links opening the queue detail view', () => {
    expect(resolveWorkbenchView(undefined, 'diag-123')).toBe('QUEUE')
    expect(resolveWorkbenchView('queue', undefined)).toBe('QUEUE')
  })

  it('opens list records in a full-width detail view without the queue panel', () => {
    expect(resolveWorkbenchView('detail', 'diag-123')).toBe('DETAIL')
    expect(workbenchViewQuery('DETAIL', 'diag-123')).toEqual({
      view: 'detail',
      diagnosisId: 'diag-123',
    })
    expect(shouldShowQueuePanel('DETAIL')).toBe(false)
    expect(shouldShowQueuePanel('QUEUE')).toBe(true)
  })

  it('falls back to the list when a detail route has no diagnosis', () => {
    expect(resolveWorkbenchView('detail', undefined)).toBe('LIST')
    expect(workbenchViewQuery('DETAIL')).toEqual({ view: 'list' })
  })

  it('centralizes which modes own a diagnosis detail', () => {
    expect(isDiagnosisViewMode('LIST')).toBe(false)
    expect(isDiagnosisViewMode('QUEUE')).toBe(true)
    expect(isDiagnosisViewMode('DETAIL')).toBe(true)
    expect(diagnosisSelectionMode('LIST')).toBe('DETAIL')
    expect(diagnosisSelectionMode('QUEUE')).toBe('QUEUE')
    expect(diagnosisSelectionMode('DETAIL')).toBe('DETAIL')
  })

  it('lets an explicit list view override a retained diagnosis id', () => {
    expect(resolveWorkbenchView('list', 'diag-123')).toBe('LIST')
  })

  it('builds stable route query values for both modes', () => {
    expect(workbenchViewQuery('LIST')).toEqual({ view: 'list' })
    expect(workbenchViewQuery('QUEUE', 'diag-123')).toEqual({
      view: 'queue',
      diagnosisId: 'diag-123',
    })
  })

  it('keeps the compact capability menu in one ordered registry', () => {
    const commands = WORKBENCH_CAPABILITY_ACTIONS.map(action => action.command)
    const labels = WORKBENCH_CAPABILITY_ACTIONS.map(action => action.label)

    expect(commands).toEqual([
      'playbooks',
      'observability-assets',
      'ledger',
      'case-knowledge',
    ])
    expect(labels).toEqual([
      '排障规则库',
      '接入系统',
      '诊断效果评估',
      '历史案例入库',
    ])
    expect(new Set(commands).size).toBe(commands.length)
  })

  it('puts paste-alert first and keeps known scenarios secondary', () => {
    expect(WORKBENCH_TROUBLESHOOTING_SCENARIOS).toEqual([
      expect.objectContaining({
        command: 'incident',
        label: '粘贴告警发起',
        group: 'daily',
        outcome: '最常用',
      }),
      expect.objectContaining({
        command: 'cti-create-conversation-failed',
        label: '创建会话失败',
        group: 'known',
        outcome: '已有标准方法',
      }),
      expect.objectContaining({
        command: 'message-send-failed',
        label: '消息发送失败',
        group: 'known',
        outcome: '已有标准方法',
      }),
      expect.objectContaining({
        command: 'deployment',
        label: '部署拓扑拨测',
        group: 'admin',
        outcome: '管理员',
      }),
    ])
    expect(workbenchSecondaryScenarios(true, true).map(item => item.command)).toEqual([
      'cti-create-conversation-failed',
      'message-send-failed',
      'deployment',
    ])
    expect(workbenchSecondaryScenarios(true, false).map(item => item.command)).toEqual([
      'cti-create-conversation-failed',
      'message-send-failed',
    ])
  })

  it('keeps user-facing troubleshooting names in one canonical label table', () => {
    expect(TROUBLESHOOTING_UI_LABELS).toMatchObject({
      launch: '发起排障',
      firstUse: '第一次使用？',
      firstUseTitle: '第一次使用智能排障',
      startRehearsal: '开始演练',
      scenarioPicker: '选择已登记场景',
      incident: '粘贴告警发起',
      conversation: '对话发起排障',
      ctiCreateConversationFailed: '创建会话失败',
      messageSendFailed: '消息发送失败',
      rules: '排障规则库',
      evidenceCatalog: '查询规则说明书',
      observabilityAssets: '接入系统',
      historyReplay: '历史样本回放',
      guanceOnboarding: '数据连接检查',
      guanceSourceStatus: '观测云真实数据源状态',
      guanceValidation: '真实数据验证',
      deploymentTopology: '部署拓扑拨测',
      evaluation: '诊断效果评估',
      caseKnowledge: '历史案例入库',
    })
  })
})
