import type {
  BusinessSummary,
  ConclusionType,
  DeveloperEvidenceView,
  DiagnosisStatus,
} from '@/api'
import { investigationLabel } from './formalProjection'

export type FiveQuestionState = 'DONE' | 'ACTIVE' | 'PENDING' | 'STOPPED'

export interface FiveQuestionItem {
  index: number
  title: string
  hint: string
  state: FiveQuestionState
  answer: string
}

function conclusionAnswer(type: ConclusionType): string {
  switch (type) {
    case 'LOCATED':
      return '证据指向可复核的候选定位'
    case 'EXCLUDED':
      return '证据已排除若干方向（非定位）'
    case 'HYPOTHESIS':
      return '形成根因假设，仍需人工复核'
    case 'INSUFFICIENT_EVIDENCE':
      return '证据不足，已停止并保留理由'
    default:
      return '结论待形成'
  }
}

function nextStepState(status: DiagnosisStatus): FiveQuestionState {
  if (status === 'CLOSED' || status === 'TRANSFERRED' || status === 'CONFIRMED') return 'DONE'
  if (status === 'READY_FOR_HUMAN') return 'ACTIVE'
  return 'PENDING'
}

function nextStepAnswer(status: DiagnosisStatus, nextLabel: string, nextText: string): string {
  if (status === 'CLOSED') return '已关闭归档'
  if (status === 'TRANSFERRED') return '已转派，带着证据继续'
  if (status === 'CONFIRMED') return '负责人已确认候选结论'
  return `${nextLabel}：${nextText}`
}

/**
 * Map a diagnosis detail to the daily five-question spine from the demo flow.
 * States are derived from existing projection fields only — no invented evidence.
 */
export function buildFiveQuestionRail(
  business: BusinessSummary,
  developer: DeveloperEvidenceView,
): FiveQuestionItem[] {
  const hopCount = developer.callChain.hops.length
  const stepCount = developer.steps.length
  const hasEvidence = hopCount > 0 || stepCount > 0
  const contrastReady = developer.contrast.available
  const modeLabel = investigationLabel(developer.investigationMode, developer.routeAuthority)
  const howAnswer = developer.playbookRef
    ? `标准方案 · ${developer.playbookRef}`
    : modeLabel

  const foundState: FiveQuestionState = hasEvidence
    ? 'DONE'
    : business.conclusionType === 'INSUFFICIENT_EVIDENCE'
      ? 'STOPPED'
      : 'ACTIVE'

  const meaningState: FiveQuestionState =
    business.conclusionType === 'INSUFFICIENT_EVIDENCE'
      ? 'STOPPED'
      : business.conclusionType === 'LOCATED'
        || business.conclusionType === 'EXCLUDED'
        || business.conclusionType === 'HYPOTHESIS'
        || contrastReady
        ? 'DONE'
        : hasEvidence
          ? 'ACTIVE'
          : 'PENDING'

  return [
    {
      index: 1,
      title: '发生了什么？',
      hint: '确认系统、服务、时间和现象',
      state: 'DONE',
      answer: business.problem,
    },
    {
      index: 2,
      title: '这次怎么查？',
      hint: '标准方案优先；没有则受限只读调查',
      state: 'DONE',
      answer: howAnswer,
    },
    {
      index: 3,
      title: '实际查到了什么？',
      hint: '关联日志、调用链与时间线',
      state: foundState,
      answer: hasEvidence
        ? `${hopCount ? `${hopCount} 条关联日志` : '已记录取证步骤'}${stepCount ? ` · ${stepCount} 步` : ''}`
        : foundState === 'STOPPED'
          ? '证据不够，调查已停'
          : '等待取证或显式开始调查',
    },
    {
      index: 4,
      title: '这些证据说明什么？',
      hint: '对照与候选结论，证据不够就停',
      state: meaningState,
      answer: conclusionAnswer(business.conclusionType),
    },
    {
      index: 5,
      title: '接下来怎么办？',
      hint: '人确认、转派或关闭；平台不改生产',
      state: nextStepState(business.status),
      answer: nextStepAnswer(business.status, business.nextStep.label, business.nextStep.text),
    },
  ]
}
