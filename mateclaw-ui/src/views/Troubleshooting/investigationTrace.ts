import type {
  DeveloperEvidenceView,
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

export function traceDisplay(value: string | null | undefined) {
  return value?.trim() || '未记录'
}

export function investigationStageStatusLabel(status: InvestigationStageStatus) {
  return STATUS_LABELS[status]
}

export function investigationRouteLabel(
  developer: Pick<
    DeveloperEvidenceView,
    'investigationMode' | 'routeAuthority' | 'routeSemanticsProvenance'
  >,
) {
  if (developer.routeSemanticsProvenance === 'LEGACY_DERIVED') {
    return '旧合同推导 · 调查模式与路由权威未记录'
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
