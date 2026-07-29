import type {
  KnowledgeApprovalEligibility,
  KnowledgeCandidate,
  KnowledgeOrigin,
  KnowledgeReviewInbox,
  KnowledgeReviewState,
  KnowledgeReviewStatus,
  KnowledgeValidationStatus,
  PlaybookKnowledgeRecord,
  ReferenceSolutionComparison,
  SopSummary,
} from '@/api'

export type KnowledgeReviewSource =
  | { kind: 'EVIDENCE_DERIVED'; record: PlaybookKnowledgeRecord }
  | { kind: 'OUTCOME_BACKED'; candidate: KnowledgeCandidate }
  | { kind: 'MANUAL'; summary: SopSummary }

export interface KnowledgeReviewRow {
  key: string
  recordId: string
  origin: KnowledgeOrigin
  reviewStatus: KnowledgeReviewStatus
  reviewVersion: number
  reviewer: string
  reviewReason: string
  validationStatus: KnowledgeValidationStatus
  approvalEligibility: KnowledgeApprovalEligibility
  eligibilityReasons: string[]
  system: string
  service: string | null
  selector: string
  title: string
  summary: string
  sourceRef: string
  evidenceRefs: string[]
  createdAt: string
  fixtureMode: boolean | null
  reviewStatePersisted: boolean
  reviewState: KnowledgeReviewState | null
  source: KnowledgeReviewSource
}

export interface ReferenceComparisonIssue {
  code: string
  label: string
  items: string[]
  danger: boolean
}

const REASON_LABELS: Record<string, string> = {
  P1_CALIBRATION_PERIOD: '当前工作区仍处于 P1–P2 校准期，需要人工参考解法和固定回放。',
  CONTRAST_UNAVAILABLE: '成功样本对照尚不可用，不能进入运行期资格档。',
  REFERENCE_SOLUTION_DELTA: '草稿与人工参考解法仍有必需意图或证据差异。',
  OUTCOME_ELIGIBILITY_GATE_NOT_IMPLEMENTED: '关闭结果候选的 outcome、恢复验证与回放资格门禁尚未实现。',
  OUTCOME_VERIFICATION_NOT_PROJECTED: '当前候选合同不足以独立证明 outcome 与恢复验证条件。',
  POSITIVE_REPLAY_REQUIRED: '尚缺固定正例回放结果。',
  OWNER_REQUIRED: '尚未指定对该 selector 负责的 owner。',
  CITATIONS_REQUIRED: '候选没有可审计的证据引用。',
  MANUAL_ELIGIBILITY_GATE_NOT_IMPLEMENTED: '人工候选的 owner、selector 唯一性、合同校验与回放资格门禁尚未实现。',
  POSITIVE_AND_NEGATIVE_REPLAY_REQUIRED: '人工候选尚缺固定正例与负例回放。',
  OWNER_AND_CONTRACT_VALIDATION_REQUIRED: '需要核对 owner 与完整合同校验结果。',
  REVIEW_START_AND_REJECT_ONLY: '当前只开放开始审阅和拒绝；批准决策仍保持关闭。',
  APPROVAL_REQUIRES_ELIGIBILITY_GATE: '批准必须通过来源对应的资格门禁，不能由按钮绕过。',
  PROMOTION_MUST_CREATE_NEW_VERSION: '晋升必须创建新版本，并显式替代旧的 approved 版本。',
}

export function buildKnowledgeReviewRows(inbox: KnowledgeReviewInbox): KnowledgeReviewRow[] {
  const reviewStates = new Map(
    (inbox.reviewStates ?? []).map((state) => [
      `${state.origin}:${state.sourceRecordId}`,
      state,
    ]),
  )
  const evidenceDerived = inbox.evidenceDerived.map((record): KnowledgeReviewRow => {
    const selectorValue = record.draft.proposedSelector.errorCode
      ?? record.draft.proposedSelector.scenarioKey
      ?? '未绑定 selector'
    return {
      key: `EVIDENCE_DERIVED:${record.recordId}`,
      recordId: record.recordId,
      origin: 'EVIDENCE_DERIVED',
      reviewStatus: 'CANDIDATE',
      reviewVersion: 0,
      reviewer: '',
      reviewReason: '',
      validationStatus: record.validationStatus,
      approvalEligibility: record.approvalEligibility,
      eligibilityReasons: [...record.eligibilityReasons],
      system: record.draft.proposedSelector.system,
      service: record.service,
      selector: `${record.draft.proposedSelector.system}:${selectorValue}`,
      title: record.draft.title,
      summary: record.draft.diagnosisHypotheses[0]?.summary ?? '尚未形成根因假设',
      sourceRef: record.draft.sourceIncident ?? record.evidenceBundleId,
      evidenceRefs: [...record.draft.evidenceCitations],
      createdAt: record.createdAt,
      fixtureMode: record.fixtureMode,
      reviewStatePersisted: false,
      reviewState: null,
      source: { kind: 'EVIDENCE_DERIVED', record },
    }
  })

  const outcomeBacked = inbox.outcomeBacked.map((candidate): KnowledgeReviewRow => {
    const reasons = [
      'OUTCOME_ELIGIBILITY_GATE_NOT_IMPLEMENTED',
      'OUTCOME_VERIFICATION_NOT_PROJECTED',
      'POSITIVE_REPLAY_REQUIRED',
      'OWNER_REQUIRED',
    ]
    if (!candidate.evidenceIds.length) reasons.push('CITATIONS_REQUIRED')
    return {
      key: `OUTCOME_BACKED:${candidate.candidateId}`,
      recordId: candidate.candidateId,
      origin: 'OUTCOME_BACKED',
      reviewStatus: 'CANDIDATE',
      reviewVersion: 0,
      reviewer: '',
      reviewReason: '',
      validationStatus: 'NOT_EVALUATED',
      approvalEligibility: 'NOT_ELIGIBLE',
      eligibilityReasons: reasons,
      system: candidate.system,
      service: null,
      selector: candidate.errorCode
        ? `${candidate.system}:${candidate.errorCode}`
        : `${candidate.system}:未绑定 selector`,
      title: candidate.rootCause,
      summary: candidate.resolutionSummary,
      sourceRef: candidate.sourceDiagnosisId,
      evidenceRefs: [...candidate.evidenceIds],
      createdAt: candidate.createdAt,
      fixtureMode: null,
      reviewStatePersisted: false,
      reviewState: null,
      source: { kind: 'OUTCOME_BACKED', candidate },
    }
  })

  const manual = inbox.manual.map((summary): KnowledgeReviewRow => ({
    key: `MANUAL:${summary.sopId}`,
    recordId: summary.sopId,
    origin: 'MANUAL',
    reviewStatus: 'CANDIDATE',
    reviewVersion: 0,
    reviewer: '',
    reviewReason: '',
    validationStatus: 'NOT_EVALUATED',
    approvalEligibility: 'NOT_ELIGIBLE',
    eligibilityReasons: [
      'MANUAL_ELIGIBILITY_GATE_NOT_IMPLEMENTED',
      'POSITIVE_AND_NEGATIVE_REPLAY_REQUIRED',
      'OWNER_AND_CONTRACT_VALIDATION_REQUIRED',
    ],
    system: summary.system,
    service: summary.service,
    selector: `${summary.system}:${summary.errorCode}`,
    title: `人工候选 ${summary.system}:${summary.errorCode}`,
    summary: '完整合同按需读取；在资格与版本门禁落地前不进入命中路。',
    sourceRef: summary.sopId,
    evidenceRefs: [],
    createdAt: summary.createTime,
    fixtureMode: null,
    reviewStatePersisted: false,
    reviewState: null,
    source: { kind: 'MANUAL', summary },
  }))

  return [...evidenceDerived, ...outcomeBacked, ...manual]
    .map((row): KnowledgeReviewRow => {
      const state = reviewStates.get(row.key)
      if (!state) return row
      return {
        ...row,
        reviewStatus: state.status,
        reviewVersion: state.version,
        reviewer: state.reviewer,
        reviewReason: state.reason,
        reviewStatePersisted: true,
        reviewState: state,
      }
    })
    .sort((left, right) => timestamp(right.createdAt) - timestamp(left.createdAt))
}

export function filterKnowledgeReviewRows(
  rows: KnowledgeReviewRow[],
  origin: '' | KnowledgeOrigin,
  query: string,
) {
  const needle = query.trim().toLowerCase()
  return rows.filter((row) => {
    if (origin && row.origin !== origin) return false
    if (!needle) return true
    return [
      row.recordId, row.selector, row.title, row.summary, row.sourceRef,
      row.service ?? '', ...row.evidenceRefs,
    ].some((value) => value.toLowerCase().includes(needle))
  })
}

export function reviewReasonLabel(reason: string) {
  return REASON_LABELS[reason] ?? reason
}

/** Complete, deterministic reference delta; coverage alone is not a verdict. */
export function referenceComparisonIssues(
  comparison: ReferenceSolutionComparison,
): ReferenceComparisonIssue[] {
  return [
    {
      code: 'MISSING_STEP_INTENTS',
      label: '缺失必需步骤',
      items: comparison.missingStepIntents,
      danger: false,
    },
    {
      code: 'FORBIDDEN_STEP_INTENTS',
      label: '包含禁止动作',
      items: comparison.forbiddenStepIntentsPresent,
      danger: true,
    },
    {
      code: 'ORDERING_VIOLATIONS',
      label: '步骤顺序违规',
      items: comparison.orderingViolations,
      danger: true,
    },
    {
      code: 'MISSING_EVIDENCE_KINDS',
      label: '缺失证据类型',
      items: comparison.missingEvidenceKinds,
      danger: false,
    },
  ].filter((issue) => issue.items.length > 0)
}

function timestamp(value: string) {
  const parsed = Date.parse(value)
  return Number.isFinite(parsed) ? parsed : 0
}
