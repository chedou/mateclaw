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

    expect(commands).toEqual(['playbooks', 'synthesis', 'guance', 'ledger'])
    expect(labels).toEqual([
      '排障规则库',
      '无码场景预演',
      '观测云接入与验收',
      '诊断效果评估',
    ])
    expect(new Set(commands).size).toBe(commands.length)
  })

  it('treats deployment topology analysis as a troubleshooting scenario', () => {
    expect(WORKBENCH_TROUBLESHOOTING_SCENARIOS).toEqual([
      expect.objectContaining({ command: 'incident', label: '通用事件排障' }),
      expect.objectContaining({
        command: 'deployment',
        label: '部署拓扑拨测分析',
        outcome: '写入 Diagnosis 证据',
      }),
    ])
  })

  it('keeps user-facing troubleshooting names in one canonical label table', () => {
    expect(TROUBLESHOOTING_UI_LABELS).toMatchObject({
      launch: '发起排障',
      scenarioPicker: '选择排障场景',
      incident: '通用事件排障',
      rules: '排障规则库',
      noCodePreview: '无码场景预演',
      guanceOnboarding: '观测云接入与验收',
      deploymentTopology: '部署拓扑拨测分析',
      evaluation: '诊断效果评估',
    })
  })
})
