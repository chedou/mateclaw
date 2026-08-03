import { describe, expect, it } from 'vitest'
import type { InvestigationStageView, RelationEdge } from '@/api'
import {
  defaultInvestigationStage,
  investigationRouteLabel,
  investigationStagePresentation,
  investigationStageStatusLabel,
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
      '先看发生了什么',
      '决定怎么排查',
      '列出要查的数据',
      '选择查询工具',
      '查询并拿回结果',
      '按规则判断结果',
      '给出结论，或明确不判断',
    ])

    expect(investigationStagePresentation('EVIDENCE_CONTRACT').description)
      .toBe('固定本次要查询的数据、范围和时间窗口，并标明哪些必查、哪些可选。')
    expect(investigationStagePresentation('EVIDENCE_COLLECTION').description)
      .toBe('执行只读查询，展示系统已经记录的结果和耗时。')
    expect(investigationStagePresentation('CONCLUSION').description)
      .toContain('证据不足')
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

  it('falls back to the first incomplete stage, then the conclusion', () => {
    const partial = [
      stage(1, 'INCIDENT', 'COMPLETED'),
      stage(2, 'PLAYBOOK_ROUTE', 'COMPLETED'),
      stage(3, 'EVIDENCE_CONTRACT', 'COMPLETED'),
      stage(4, 'ADAPTER_SELECTION', 'PARTIAL'),
      stage(5, 'EVIDENCE_COLLECTION', 'COMPLETED'),
      stage(6, 'CRITERION_EVALUATION', 'COMPLETED'),
      stage(7, 'CONCLUSION', 'COMPLETED'),
    ]
    const complete = partial.map(item => ({ ...item, status: 'COMPLETED' as const }))

    expect(defaultInvestigationStage(partial)?.key).toBe('ADAPTER_SELECTION')
    expect(defaultInvestigationStage(complete)?.key).toBe('CONCLUSION')
  })

  it('renders every absent value as unrecorded', () => {
    expect(traceDisplay(null)).toBe('未记录')
    expect(traceDisplay(undefined)).toBe('未记录')
    expect(traceDisplay('')).toBe('未记录')
    expect(traceDisplay('guance')).toBe('guance')
    expect(investigationStageStatusLabel('UNRECORDED')).toBe('未记录')
  })

  it('never presents legacy compatibility route values as persisted facts', () => {
    expect(investigationRouteLabel({
      investigationMode: 'ERROR_CODE_PLAYBOOK',
      routeAuthority: 'EXPLICIT',
      routeSemanticsProvenance: 'LEGACY_DERIVED',
    })).toBe('旧合同推导 · 调查模式与路由权威未记录')

    expect(investigationRouteLabel({
      investigationMode: 'ERROR_CODE_PLAYBOOK',
      routeAuthority: 'EXPLICIT',
      routeSemanticsProvenance: 'PERSISTED',
    })).toBe('错误码 Playbook · 显式命中')
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
