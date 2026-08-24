import { describe, expect, it } from 'vitest'
import type { InvestigationStageView, RelationEdge } from '@/api'
import {
  defaultInvestigationStage,
  investigationStageContinuationLabel,
  investigationStageQuestion,
  investigationRouteLabel,
  investigationStageSummaryLabel,
  investigationStagePresentation,
  investigationStageStatusLabel,
  investigationProvenanceRefreshKey,
  relationUpstreamPath,
  traceDisplay,
} from '../investigationTrace'

function stage(
  sequence: number,
  key: InvestigationStageView['key'],
  status: InvestigationStageView['status'],
): InvestigationStageView {
  return {
    sequence,
    key,
    title: key,
    status,
    summary: status === 'UNRECORDED' ? '未记录' : '已记录',
    startedAt: null,
    completedAt: null,
    duration: null,
    fields: [],
    evidenceRefs: [],
  }
}

describe('seven-stage investigation trace presentation', () => {
  it('explains all seven stages as plain-language developer actions', () => {
    expect([
      investigationStagePresentation('INCIDENT').title,
      investigationStagePresentation('PLAYBOOK_ROUTE').title,
      investigationStagePresentation('EVIDENCE_CONTRACT').title,
      investigationStagePresentation('ADAPTER_SELECTION').title,
      investigationStagePresentation('EVIDENCE_COLLECTION').title,
      investigationStagePresentation('CRITERION_EVALUATION').title,
      investigationStagePresentation('CONCLUSION').title,
    ]).toEqual([
      '收到告警',
      '选择调查方式',
      '明确要查什么',
      '连接只读数据源',
      '获取真实证据',
      '用规则核对证据',
      '形成结论',
    ])

    expect(investigationStagePresentation('EVIDENCE_CONTRACT').description)
      .toBe('列出这次必须查询的数据，并固定查询范围和时间。')
    expect(investigationStagePresentation('EVIDENCE_COLLECTION').description)
      .toBe('执行查询，记录实际返回的证据和缺失项。')
    expect(investigationStagePresentation('CONCLUSION').description)
      .toContain('证据不足')
    expect(investigationStagePresentation('PLAYBOOK_ROUTE').description)
      .toBe('有标准排障方法就直接复用；没有时进入通用只读调查。')
    expect(investigationStageQuestion('PLAYBOOK_ROUTE'))
      .toBe('本次使用标准排障方法，还是通用只读调查？')
  })

  it('tells the operator what each step answers and why the flow continues', () => {
    expect(investigationStageQuestion('INCIDENT'))
      .toBe('系统、服务、发生时间和错误码是否明确？')
    expect(investigationStageQuestion('EVIDENCE_COLLECTION'))
      .toBe('数据是否查到，必需证据有没有缺失？')
    expect(investigationStageQuestion('CONCLUSION'))
      .toBe('现有证据能否支持明确结论？如果不能，是否应停止判断？')

    expect(investigationStageContinuationLabel('INCIDENT', 'COMPLETED'))
      .toBe('基本信息已确认，下一步选择调查方式。')
    expect(investigationStageContinuationLabel('PLAYBOOK_ROUTE', 'COMPLETED'))
      .toBe('调查方式已选定，下一步明确要查询的数据。')
    expect(investigationStageContinuationLabel('ADAPTER_SELECTION', 'PARTIAL'))
      .toContain('只使用已记录事实')
    expect(investigationStageContinuationLabel('CONCLUSION', 'STOPPED'))
      .toBe('流程在这里停止，不再继续猜测。')
    expect(investigationStageContinuationLabel('CONCLUSION', 'COMPLETED'))
      .toBe('排障流程已完成，等待人工确认和处置。')
    expect(investigationStageContinuationLabel('EVIDENCE_COLLECTION', 'COMPLETED'))
      .toBe('真实证据已获取，下一步用规则核对。')
  })

  it('explains standard methods and generic read-only investigation in user-facing text', () => {
    expect(investigationStageSummaryLabel('PLAYBOOK_ROUTE', '开放调查，未命中已审核 Playbook'))
      .toBe('通用只读调查，未命中已审核的标准排障方法')
    expect(investigationStageSummaryLabel('PLAYBOOK_ROUTE', '错误码 Playbook · 显式命中'))
      .toBe('标准排障方法（按错误码） · 直接命中')
    expect(investigationStageSummaryLabel('PLAYBOOK_ROUTE', '场景 Playbook · 规则命中'))
      .toBe('标准排障方法（按场景） · 自动匹配')
    expect(investigationStageSummaryLabel('INCIDENT', 'Playbook 服务报错'))
      .toBe('Playbook 服务报错')
  })

  it('selects a stopped stage before partial or completed stages', () => {
    const stages = [
      stage(1, 'INCIDENT', 'COMPLETED'),
      stage(2, 'PLAYBOOK_ROUTE', 'COMPLETED'),
      stage(3, 'EVIDENCE_CONTRACT', 'UNRECORDED'),
      stage(4, 'ADAPTER_SELECTION', 'PARTIAL'),
      stage(5, 'EVIDENCE_COLLECTION', 'PARTIAL'),
      stage(6, 'CRITERION_EVALUATION', 'UNRECORDED'),
      stage(7, 'CONCLUSION', 'STOPPED'),
    ]

    expect(defaultInvestigationStage(stages)?.key).toBe('CONCLUSION')
  })

  it('opens a recorded conclusion first, otherwise the first incomplete stage', () => {
    const partial = [
      stage(1, 'INCIDENT', 'COMPLETED'),
      stage(2, 'PLAYBOOK_ROUTE', 'COMPLETED'),
      stage(3, 'EVIDENCE_CONTRACT', 'COMPLETED'),
      stage(4, 'ADAPTER_SELECTION', 'PARTIAL'),
      stage(5, 'EVIDENCE_COLLECTION', 'COMPLETED'),
      stage(6, 'CRITERION_EVALUATION', 'COMPLETED'),
      stage(7, 'CONCLUSION', 'UNRECORDED'),
    ]
    const complete = partial.map(item => item.key === 'CONCLUSION'
      ? { ...item, status: 'COMPLETED' as const }
      : item)

    expect(defaultInvestigationStage(partial)?.key).toBe('ADAPTER_SELECTION')
    expect(defaultInvestigationStage(complete)?.key).toBe('CONCLUSION')
  })

  it('renders every absent value as unrecorded', () => {
    expect(traceDisplay(null)).toBe('未记录')
    expect(traceDisplay(undefined)).toBe('未记录')
    expect(traceDisplay('')).toBe('未记录')
    expect(traceDisplay('guance')).toBe('guance')
    expect(investigationStageStatusLabel('UNRECORDED')).toBe('未记录')
    expect(investigationStageStatusLabel('COMPLETED')).toBe('已完成')
    expect(investigationStageStatusLabel('PARTIAL')).toBe('记录不完整')
  })

  it('refreshes participant provenance when the same diagnosis advances', () => {
    expect(investigationProvenanceRefreshKey('diag-1', 4)).toBe('diag-1@4')
    expect(investigationProvenanceRefreshKey('diag-1', 5))
      .not.toBe(investigationProvenanceRefreshKey('diag-1', 4))
  })

  it('never presents legacy compatibility route values as persisted facts', () => {
    expect(investigationRouteLabel({
      investigationMode: 'ERROR_CODE_PLAYBOOK',
      routeAuthority: 'EXPLICIT',
      routeSemanticsProvenance: 'LEGACY_DERIVED',
    })).toBe('旧版记录推导 · 调查模式与路由权威未记录')

    expect(investigationRouteLabel({
      investigationMode: 'ERROR_CODE_PLAYBOOK',
      routeAuthority: 'EXPLICIT',
      routeSemanticsProvenance: 'PERSISTED',
    })).toBe('标准排障方法（按错误码） · 直接命中')
  })

  it('walks backwards from the conclusion through rules, criteria and evidence', () => {
    const edges: RelationEdge[] = [
      {
        edgeId: 'e1', fromNodeId: 'evidence:EV-1', toNodeId: 'criterion:c1',
        relation: 'SUPPORTS', label: '满足判据',
      },
      {
        edgeId: 'e2', fromNodeId: 'criterion:c1', toNodeId: 'rule:r1',
        relation: 'SUPPORTS', label: '命中规则',
      },
      {
        edgeId: 'e3', fromNodeId: 'rule:r1', toNodeId: 'conclusion:d1',
        relation: 'SUPPORTS', label: '产生结论',
      },
      {
        edgeId: 'e4', fromNodeId: 'evidence:EV-2', toNodeId: 'criterion:c2',
        relation: 'REFUTES', label: '反证',
      },
    ]

    const path = relationUpstreamPath(edges, 'conclusion:d1')
    expect(path.nodeIds).toEqual(new Set([
      'conclusion:d1', 'rule:r1', 'criterion:c1', 'evidence:EV-1',
    ]))
    expect(path.edgeIds).toEqual(new Set(['e1', 'e2', 'e3']))
  })
})
