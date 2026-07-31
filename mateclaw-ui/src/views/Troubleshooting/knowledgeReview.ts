import type {
  KnowledgeApprovalEligibility,
  KnowledgeCandidate,
  KnowledgeOrigin,
  KnowledgeReviewInbox,
  KnowledgeReviewSnapshot,
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
  qualificationSnapshot: KnowledgeReviewSnapshot
  source: KnowledgeReviewSource
}

export interface ReferenceComparisonIssue {
  code: string
  label: string
  items: string[]
  danger: boolean
}

const LEGACY_OUTCOME_CONTRACT = 'knowledge-candidate.v1'

export function missingKnowledgeOwnerLabel(candidate: KnowledgeCandidate): string {
  return candidate.contractVersion === LEGACY_OUTCOME_CONTRACT
    ? '未冻结（历史 v1 候选）'
    : '未冻结（当前合同缺口）'
}

export function missingOutcomeProofLabel(candidate: KnowledgeCandidate): string {
  return candidate.contractVersion === LEGACY_OUTCOME_CONTRACT
    ? 'LEGACY_NOT_PROJECTED'
    : 'CURRENT_CONTRACT_INVALID'
}

const REASON_LABELS: Record<string, string> = {
  P1_CALIBRATION_PERIOD: '当前工作区仍处于 P1–P2 校准期，需要人工参考解法和固定回放。',
  CONTRAST_UNAVAILABLE: '成功样本对照尚不可用，不能进入运行期资格档。',
  REFERENCE_SOLUTION_DELTA: '草稿与人工参考解法仍有必需意图或证据差异。',
  OUTCOME_ELIGIBILITY_GATE_NOT_IMPLEMENTED: '关闭结果候选的 outcome、恢复验证与回放资格门禁尚未实现。',
  OUTCOME_VERIFICATION_NOT_PROJECTED: '历史 v1 关闭结果候选没有冻结服务端 outcome 与恢复验证证明。',
  POSITIVE_REPLAY_REQUIRED: '尚缺固定正例回放结果。',
  OWNER_REQUIRED: '尚未指定对该 selector 负责的 owner。',
  VERSIONED_SELECTOR_UNIQUENESS_REQUIRED: '尚未建立可版本替换的 selector 单 active-approved 唯一性证明。',
  CITATIONS_REQUIRED: '候选没有可审计的证据引用。',
  MANUAL_ELIGIBILITY_GATE_NOT_IMPLEMENTED: '该历史响应尚未提供人工候选的完整服务端资格门禁，请保持不可晋升。',
  POSITIVE_AND_NEGATIVE_REPLAY_REQUIRED: '人工候选尚缺固定正例与负例回放。',
  POSITIVE_AND_NEGATIVE_REPLAY_FAILED: '服务端固定正例、负例或弃权回放未通过；当前候选不能晋升。',
  REPLAY_SUITE_UNAVAILABLE: '该 selector 尚未注册服务端回放套件，不能生成晋升证明。',
  REPLAY_PROOF_STALE: '已有回放证明与当前候选或套件指纹不一致，证明已失效。',
  OWNER_AND_CONTRACT_VALIDATION_REQUIRED: '需要核对 owner 与完整合同校验结果。',
  CONTRACT_VALIDATION_FAILED: '候选合同存在确定性校验错误，必须修正后生成新的来源记录。',
  NEGATIVE_OR_ABSTAIN_REPLAY_REQUIRED: '尚缺与当前候选关联的负例或弃权回放。',
  FIXTURE_ONLY: '当前事实只来自 Recorded Replay，不能冒充生产数据晋升。',
  SELECTOR_REQUIRED: '候选没有可用于版本替换的明确 selector。',
  SOURCE_QUALIFICATION_MISSING: '服务端没有返回该来源的资格投影，当前保持不可晋升。',
  REVIEW_START_AND_REJECT_ONLY: '当前只开放开始审阅和拒绝；批准决策仍保持关闭。',
  APPROVAL_IS_SERVER_GATED: '批准命令已接入，但只有服务端当前资格全部通过时才会创建新版本。',
  APPROVAL_REQUIRES_ELIGIBILITY_GATE: '批准必须通过来源对应的资格门禁，不能由按钮绕过。',
  PROMOTION_MUST_CREATE_NEW_VERSION: '晋升必须创建新版本，并显式替代旧的 approved 版本。',
}

export function buildKnowledgeReviewRows(inbox: KnowledgeReviewInbox): KnowledgeReviewRow[] {
  const sourceStates = new Map(
    (inbox.sourceStates ?? []).map((state) => [
      `${state.origin}:${state.sourceRecordId}`,
      state,
    ]),
  )
  const reviewStates = new Map(
    (inbox.reviewStates ?? []).map((state) => [
      `${state.origin}:${state.sourceRecordId}`,
      state,
    ]),
  )
  const evidenceDerived = inbox.evidenceDerived.map((record): KnowledgeReviewRow => {
    const sourceState = sourceStates.get(`EVIDENCE_DERIVED:${record.recordId}`)
    const qualification = sourceState?.snapshot
      ?? missingQualification(record.fixtureMode)
    return {
      key: `EVIDENCE_DERIVED:${record.recordId}`,
      recordId: record.recordId,
      origin: 'EVIDENCE_DERIVED',
      reviewStatus: 'CANDIDATE',
      reviewVersion: 0,
      reviewer: '',
      reviewReason: '',
      validationStatus: qualification.validationStatus,
      approvalEligibility: qualification.approvalEligibility,
      eligibilityReasons: [...qualification.eligibilityReasons],
      system: record.draft.proposedSelector.system,
      service: record.service,
      selector: sourceState?.selectorKey ?? '服务端未返回 selector',
      title: record.draft.title,
      summary: record.draft.diagnosisHypotheses[0]?.summary ?? '尚未形成根因假设',
      sourceRef: record.draft.sourceIncident ?? record.evidenceBundleId,
      evidenceRefs: [...record.draft.evidenceCitations],
      createdAt: record.createdAt,
      fixtureMode: qualification.fixtureMode ?? record.fixtureMode,
      reviewStatePersisted: false,
      reviewState: null,
      qualificationSnapshot: qualification,
      source: { kind: 'EVIDENCE_DERIVED', record },
    }
  })

  const outcomeBacked = inbox.outcomeBacked.map((candidate): KnowledgeReviewRow => {
    const sourceState = sourceStates.get(`OUTCOME_BACKED:${candidate.candidateId}`)
    const qualification = sourceState?.snapshot
      ?? missingQualification(null)
    return {
      key: `OUTCOME_BACKED:${candidate.candidateId}`,
      recordId: candidate.candidateId,
      origin: 'OUTCOME_BACKED',
      reviewStatus: 'CANDIDATE',
      reviewVersion: 0,
      reviewer: '',
      reviewReason: '',
      validationStatus: qualification.validationStatus,
      approvalEligibility: qualification.approvalEligibility,
      eligibilityReasons: [...qualification.eligibilityReasons],
      system: candidate.system,
      service: null,
      selector: sourceState?.selectorKey ?? '服务端未返回 selector',
      title: candidate.rootCause,
      summary: candidate.resolutionSummary,
      sourceRef: candidate.sourceDiagnosisId,
      evidenceRefs: [...candidate.evidenceIds],
      createdAt: candidate.createdAt,
      fixtureMode: qualification.fixtureMode,
      reviewStatePersisted: false,
      reviewState: null,
      qualificationSnapshot: qualification,
      source: { kind: 'OUTCOME_BACKED', candidate },
    }
  })

  const manual = inbox.manual.map((summary): KnowledgeReviewRow => {
    const sourceState = sourceStates.get(`MANUAL:${summary.sopId}`)
    const qualification = sourceState?.snapshot
      ?? missingQualification(null)
    return {
      key: `MANUAL:${summary.sopId}`,
      recordId: summary.sopId,
      origin: 'MANUAL',
      reviewStatus: 'CANDIDATE',
      reviewVersion: 0,
      reviewer: '',
      reviewReason: '',
      validationStatus: qualification.validationStatus,
      approvalEligibility: qualification.approvalEligibility,
      eligibilityReasons: [...qualification.eligibilityReasons],
      system: summary.system,
      service: summary.service,
      selector: sourceState?.selectorKey ?? '服务端未返回 selector',
      title: `人工候选 ${summary.system}:${summary.errorCode}`,
      summary: '完整合同按需读取；只有服务端资格通过并经人工批准后才能创建命中权威。',
      sourceRef: summary.sopId,
      evidenceRefs: [],
      createdAt: summary.createTime,
      fixtureMode: qualification.fixtureMode,
      reviewStatePersisted: false,
      reviewState: null,
      qualificationSnapshot: qualification,
      source: { kind: 'MANUAL', summary },
    }
  })

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

function missingQualification(fixtureMode: boolean | null): KnowledgeReviewSnapshot {
  return {
    validationStatus: 'NOT_EVALUATED',
    qualificationPhase: 'UNKNOWN',
    validationErrors: [],
    referenceComparison: null,
    modelConfigVersion: null,
    approvalEligibility: 'NOT_ELIGIBLE',
    eligibilityReasons: ['SOURCE_QUALIFICATION_MISSING'],
    fixtureMode,
  }
}
