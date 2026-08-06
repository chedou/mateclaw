import type {
  DeveloperEvidenceView,
  InvestigationStageKey,
  InvestigationStageStatus,
  InvestigationStageView,
  RelationEdge,
  RelationType,
} from '@/api'
import { investigationLabel } from './formalProjection'

const STATUS_LABELS: Record<InvestigationStageStatus, string> = {
  COMPLETED: '已记录',
  PARTIAL: '部分记录',
  STOPPED: '已停止',
  UNRECORDED: '未记录',
}

const STAGE_PRESENTATIONS: Record<
  InvestigationStageKey,
  Readonly<{ title: string; description: string }>
> = {
  INCIDENT: {
    title: '先看发生了什么',
    description: '确认哪个系统、哪个服务，在什么时间出现了什么问题。',
  },
  PLAYBOOK_ROUTE: {
    title: '决定怎么排查',
    description: '根据已知信息选择排障方案，并确认这套方案是否经过审核。',
  },
  EVIDENCE_CONTRACT: {
    title: '列出要查的数据',
    description: '固定本次要查询的数据、范围和时间窗口，并标明哪些必查、哪些可选。',
  },
  ADAPTER_SELECTION: {
    title: '选择查询工具',
    description: '为每项数据选择可用的只读数据源和查询方式。',
  },
  EVIDENCE_COLLECTION: {
    title: '查询并拿回结果',
    description: '执行只读查询，展示系统已经记录的结果和耗时。',
  },
  CRITERION_EVALUATION: {
    title: '按规则判断结果',
    description: '把查询结果代入固定规则，判断哪些条件成立。',
  },
  CONCLUSION: {
    title: '给出结论，或明确不判断',
    description: '证据足够就给出可复核结论；证据不足就明确停止，不猜答案。',
  },
}

export function traceDisplay(value: string | null | undefined) {
  return value?.trim() || '未记录'
}

/** A provenance read is tied to an aggregate version, not just its stable id. */
export function investigationProvenanceRefreshKey(
  diagnosisId: string,
  aggregateVersion: number,
) {
  return `${diagnosisId}@${aggregateVersion}`
}

export function investigationStageStatusLabel(status: InvestigationStageStatus) {
  return STATUS_LABELS[status]
}

export function investigationStagePresentation(key: InvestigationStageKey) {
  return STAGE_PRESENTATIONS[key]
}

export function investigationStageSummaryLabel(
  key: InvestigationStageKey,
  value: string,
) {
  if (key !== 'PLAYBOOK_ROUTE') return value
  return value
    .replaceAll('未命中已审核 Playbook', '未命中已审核的排障方案')
    .replaceAll('错误码 Playbook', '错误码排障方案')
    .replaceAll('场景 Playbook', '场景排障方案')
}

export function investigationRouteLabel(
  developer: Pick<
    DeveloperEvidenceView,
    'investigationMode' | 'routeAuthority' | 'routeSemanticsProvenance'
  >,
) {
  if (developer.routeSemanticsProvenance === 'LEGACY_DERIVED') {
    return '旧版记录推导 · 调查模式与路由权威未记录'
  }
  return investigationLabel(developer.investigationMode, developer.routeAuthority)
}

export function defaultInvestigationStage(stages: InvestigationStageView[]) {
  return stages.find(stage => stage.status === 'STOPPED')
    ?? stages.find(stage => stage.status === 'PARTIAL')
    ?? stages.find(stage => stage.status === 'UNRECORDED')
    ?? stages.find(stage => stage.key === 'CONCLUSION')
    ?? stages[0]
}

export function relationTypeLabel(relation: RelationType) {
  return {
    SUPPORTS: '支持',
    REFUTES: '反证',
    BLOCKS: '阻断',
    CITES: '引用',
  }[relation]
}

/** Returns the exact persisted lineage reachable by walking incoming edges. */
export function relationUpstreamPath(edges: RelationEdge[], targetNodeId: string) {
  const nodeIds = new Set<string>([targetNodeId])
  const edgeIds = new Set<string>()
  const queue = [targetNodeId]

  while (queue.length) {
    const target = queue.shift()!
    for (const edge of edges) {
      if (edge.toNodeId !== target || edgeIds.has(edge.edgeId)) continue
      edgeIds.add(edge.edgeId)
      if (!nodeIds.has(edge.fromNodeId)) {
        nodeIds.add(edge.fromNodeId)
        queue.push(edge.fromNodeId)
      }
    }
  }

  return { nodeIds, edgeIds }
}
