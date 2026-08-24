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
  COMPLETED: '已完成',
  PARTIAL: '记录不完整',
  STOPPED: '已停止',
  UNRECORDED: '未记录',
}

const STAGE_PRESENTATIONS: Record<
  InvestigationStageKey,
  Readonly<{ title: string; description: string; question: string }>
> = {
  INCIDENT: {
    title: '收到告警',
    description: '先确认故障对象和现象，避免后续查错系统。',
    question: '系统、服务、发生时间和错误码是否明确？',
  },
  PLAYBOOK_ROUTE: {
    title: '选择调查方式',
    description: '有标准排障方法就直接复用；没有时进入通用只读调查。',
    question: '本次使用标准排障方法，还是通用只读调查？',
  },
  EVIDENCE_CONTRACT: {
    title: '明确要查什么',
    description: '列出这次必须查询的数据，并固定查询范围和时间。',
    question: '需要查哪些数据，哪些是得出结论的必需项？',
  },
  ADAPTER_SELECTION: {
    title: '连接只读数据源',
    description: '为每项数据找到可用的只读查询来源。',
    question: '每项数据是否都有可用的只读查询来源？',
  },
  EVIDENCE_COLLECTION: {
    title: '获取真实证据',
    description: '执行查询，记录实际返回的证据和缺失项。',
    question: '数据是否查到，必需证据有没有缺失？',
  },
  CRITERION_EVALUATION: {
    title: '用规则核对证据',
    description: '把真实证据代入固定规则，判断哪些条件成立。',
    question: '证据满足哪些规则，又排除了哪些判断？',
  },
  CONCLUSION: {
    title: '形成结论',
    description: '汇总证据和规则结果；证据不足就停止，不猜原因。',
    question: '现有证据能否支持明确结论？如果不能，是否应停止判断？',
  },
}

const NEXT_STEP_LABELS: Record<InvestigationStageKey, string> = {
  INCIDENT: '基本信息已确认，下一步选择调查方式。',
  PLAYBOOK_ROUTE: '调查方式已选定，下一步明确要查询的数据。',
  EVIDENCE_CONTRACT: '查询内容已固定，下一步连接只读数据源。',
  ADAPTER_SELECTION: '数据源已连接，下一步执行只读查询。',
  EVIDENCE_COLLECTION: '真实证据已获取，下一步用规则核对。',
  CRITERION_EVALUATION: '规则核对完成，下一步形成结论。',
  CONCLUSION: '排障流程已完成，等待人工确认和处置。',
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

export function investigationStageQuestion(key: InvestigationStageKey) {
  return STAGE_PRESENTATIONS[key].question
}

export function investigationStageContinuationLabel(
  key: InvestigationStageKey,
  status: InvestigationStageStatus,
) {
  if (status === 'STOPPED') return '流程在这里停止，不再继续猜测。'
  if (status === 'UNRECORDED') return '系统没有这一步的记录，不能把它当作已经执行。'
  if (status === 'PARTIAL') return '只记录了部分过程；后续判断只使用已记录事实。'
  return NEXT_STEP_LABELS[key]
}

export function investigationStageSummaryLabel(
  key: InvestigationStageKey,
  value: string,
) {
  if (key !== 'PLAYBOOK_ROUTE') return value
  return value
    .replaceAll('未命中已审核 Playbook', '未命中已审核的标准排障方法')
    .replaceAll('错误码 Playbook', '标准排障方法（按错误码）')
    .replaceAll('场景 Playbook', '标准排障方法（按场景）')
    .replaceAll('开放调查', '通用只读调查')
    .replaceAll('显式命中', '直接命中')
    .replaceAll('规则命中', '自动匹配')
    .replaceAll('模型提议', 'AI 规划')
    .replaceAll('受限调查提议', '受限规划')
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
    ?? stages.find(stage => stage.key === 'CONCLUSION' && stage.status === 'COMPLETED')
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
