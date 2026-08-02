import { describe, expect, it } from 'vitest'
import type { KnowledgeReviewInbox } from '@/api'
import {
  buildKnowledgeReviewRows,
  filterKnowledgeReviewRows,
  missingKnowledgeOwnerLabel,
  missingOutcomeProofLabel,
  referenceComparisonIssues,
  reviewReasonLabel,
} from '../knowledgeReview'

const inbox: KnowledgeReviewInbox = {
  evidenceDerived: [{
    recordId: 'candidate-evidence-001',
    draft: {
      draftId: 'draft-001',
      generationKey: 'a'.repeat(64),
      sourceIncident: 'incident-001',
      proposedType: 'SCENARIO',
      proposedSelector: { system: 'CSDP', scenarioKey: 'message_send_failed', errorCode: null },
      title: '消息发送失败排查草稿',
      evidencePlan: [{
        intentKey: 'locate_failed_request', signalKind: 'log_search',
        purpose: '定位失败请求', required: true,
      }],
      criteria: [],
      diagnosisHypotheses: [],
      humanActions: [],
      evidenceCitations: ['SYNTH-LOG-SEARCH'],
      modelProvenance: {
        provider: 'openai', modelName: 'fixed', modelConfigVersion: '7:v1',
        draftContractVersion: 'playbook-draft/v1',
        generatedAt: '2026-07-20T09:13:05Z', invocationCount: 1,
      },
      contrastAvailable: false,
      validationErrors: [],
    },
    origin: 'EVIDENCE_DERIVED',
    reviewStatus: 'CANDIDATE',
    validationStatus: 'VALID',
    reviewer: '',
    reviewReason: '',
    evidenceBundleId: 'bundle-001',
    service: 'session-svc',
    referenceComparison: {
      referenceId: 'reference/v1', passed: false, requiredIntentCoverage: 0.5,
      missingStepIntents: ['compare_success_sample'],
      forbiddenStepIntentsPresent: [], orderingViolations: [], missingEvidenceKinds: [],
    },
    approvalEligibility: 'NOT_ELIGIBLE',
    eligibilityReasons: ['P1_CALIBRATION_PERIOD', 'CONTRAST_UNAVAILABLE'],
    fixtureMode: true,
    timings: {
      reportedAt: null, readyAt: null, conclusionAt: null, handoffAt: null,
      intakeCost: null, investigateCost: null, adoptCost: null,
    },
    createdAt: '2026-07-20T09:13:05Z',
  }],
  outcomeBacked: [{
    candidateId: 'candidate-outcome-001',
    contractVersion: 'knowledge-candidate.v1',
    sourceDiagnosisId: 'diag-001',
    sourceCaseId: 'case-001',
    sourceRunId: 'run-001',
    system: 'CSDP',
    errorCode: '903001',
    sopKey: 'csdp:903001',
    rootCause: 'Mongo 连接池耗尽',
    evidenceIds: ['LOG-SEARCH', 'TRACE-BUNDLE'],
    recommendedActions: [],
    actionOutcomes: [],
    resolutionSummary: '人工恢复后关闭案例',
    feedback: '保留验证步骤',
    createdBy: 'owner-a',
    createdAt: '2026-07-20T09:20:00Z',
  }],
  manual: [{
    sopId: 'manual-sop-001',
    routeKey: 'csdp:903002',
    system: 'CSDP',
    errorCode: '903002',
    service: 'session-svc',
    status: 'candidate',
    verified: false,
    operational: false,
    knowledgeEvidenceGrade: 'UNVERIFIED',
    createTime: '2026-07-20T09:10:00Z',
    updateTime: '2026-07-20T09:10:00Z',
  }],
  reviewStates: [{
    reviewId: 'review-outcome-001',
    origin: 'OUTCOME_BACKED',
    sourceRecordId: 'candidate-outcome-001',
    selectorKey: 'csdp:903001',
    status: 'IN_REVIEW',
    reviewer: 'reviewer-a',
    reason: '核对关闭结果与证据引用',
    snapshot: {
      validationStatus: 'NOT_EVALUATED',
      qualificationPhase: 'UNKNOWN',
      validationErrors: [],
      referenceComparison: null,
      modelConfigVersion: null,
      approvalEligibility: 'NOT_ELIGIBLE',
      eligibilityReasons: ['OUTCOME_ELIGIBILITY_GATE_NOT_IMPLEMENTED'],
      fixtureMode: null,
    },
    version: 1,
    createdAt: '2026-07-20T09:21:00Z',
    updatedAt: '2026-07-20T09:21:00Z',
  }],
  sourceStates: [{
    origin: 'EVIDENCE_DERIVED',
    sourceRecordId: 'candidate-evidence-001',
    selectorKey: 'csdp:scenario:message_send_failed',
    snapshot: {
      validationStatus: 'VALID',
      qualificationPhase: 'CALIBRATION',
      validationErrors: [],
      referenceComparison: {
        referenceId: 'reference/v1', passed: false, requiredIntentCoverage: 0.5,
        missingStepIntents: ['compare_success_sample'],
        forbiddenStepIntentsPresent: [], orderingViolations: [], missingEvidenceKinds: [],
      },
      modelConfigVersion: '7:v1',
      approvalEligibility: 'NOT_ELIGIBLE',
      eligibilityReasons: [
        'REFERENCE_SOLUTION_DELTA',
        'OWNER_REQUIRED',
        'POSITIVE_REPLAY_REQUIRED',
        'NEGATIVE_OR_ABSTAIN_REPLAY_REQUIRED',
        'FIXTURE_ONLY',
      ],
      fixtureMode: true as const,
    },
  }, {
    origin: 'OUTCOME_BACKED',
    sourceRecordId: 'candidate-outcome-001',
    selectorKey: 'csdp:903001',
    snapshot: {
      validationStatus: 'NOT_EVALUATED',
      qualificationPhase: 'NOT_APPLICABLE',
      validationErrors: [],
      referenceComparison: null,
      modelConfigVersion: null,
      approvalEligibility: 'NOT_ELIGIBLE',
      eligibilityReasons: [
        'OUTCOME_VERIFICATION_NOT_PROJECTED',
        'POSITIVE_REPLAY_REQUIRED',
        'OWNER_REQUIRED',
      ],
      fixtureMode: null,
    },
  }, {
    origin: 'MANUAL',
    sourceRecordId: 'manual-sop-001',
    selectorKey: 'csdp:903002',
    snapshot: {
      validationStatus: 'VALID',
      qualificationPhase: 'NOT_APPLICABLE',
      validationErrors: [],
      referenceComparison: null,
      modelConfigVersion: null,
      approvalEligibility: 'NOT_ELIGIBLE',
      eligibilityReasons: [
        'POSITIVE_AND_NEGATIVE_REPLAY_REQUIRED',
      ],
      fixtureMode: null,
    },
  }],
  capabilityLimits: [
    'APPROVAL_IS_SERVER_GATED',
    'APPROVAL_REQUIRES_ELIGIBILITY_GATE',
  ],
}

describe('knowledge review projection', () => {
  it('overlays persisted review decisions and keeps untouched sources at candidate v0', () => {
    const rows = buildKnowledgeReviewRows(inbox)

    expect(rows.map((row) => row.key)).toEqual([
      'OUTCOME_BACKED:candidate-outcome-001',
      'EVIDENCE_DERIVED:candidate-evidence-001',
      'MANUAL:manual-sop-001',
    ])
    expect(rows[0]).toMatchObject({
      origin: 'OUTCOME_BACKED',
      reviewStatus: 'IN_REVIEW',
      reviewVersion: 1,
      reviewer: 'reviewer-a',
      validationStatus: 'NOT_EVALUATED',
      approvalEligibility: 'NOT_ELIGIBLE',
      reviewStatePersisted: true,
      selector: 'csdp:903001',
    })
    expect(rows[0].reviewState?.reason).toContain('核对关闭结果')
    expect(rows[0].eligibilityReasons).toEqual([
      'OUTCOME_VERIFICATION_NOT_PROJECTED',
      'POSITIVE_REPLAY_REQUIRED',
      'OWNER_REQUIRED',
    ])
    expect(rows[1]).toMatchObject({
      origin: 'EVIDENCE_DERIVED',
      reviewStatus: 'CANDIDATE',
      reviewVersion: 0,
      validationStatus: 'VALID',
      fixtureMode: true,
      reviewStatePersisted: false,
      selector: 'csdp:scenario:message_send_failed',
    })
    expect(rows[1].eligibilityReasons).toEqual([
      'REFERENCE_SOLUTION_DELTA',
      'OWNER_REQUIRED',
      'POSITIVE_REPLAY_REQUIRED',
      'NEGATIVE_OR_ABSTAIN_REPLAY_REQUIRED',
      'FIXTURE_ONLY',
    ])
    expect(rows[2]).toMatchObject({
      origin: 'MANUAL',
      reviewStatus: 'CANDIDATE',
      reviewVersion: 0,
      validationStatus: 'VALID',
      reviewStatePersisted: false,
      selector: 'csdp:903002',
    })
  })

  it('fails closed instead of rebuilding a selector when its server state is missing', () => {
    const rows = buildKnowledgeReviewRows({
      ...inbox,
      sourceStates: inbox.sourceStates.filter(
        (state) => state.origin !== 'OUTCOME_BACKED',
      ),
    })
    const outcome = rows.find((row) => row.origin === 'OUTCOME_BACKED')

    expect(outcome?.selector).toBe('服务端未返回 selector')
    expect(outcome?.approvalEligibility).toBe('NOT_ELIGIBLE')
    expect(outcome?.eligibilityReasons).toEqual(['SOURCE_QUALIFICATION_MISSING'])
  })

  it('filters by origin and searches source, selector and title', () => {
    const rows = buildKnowledgeReviewRows(inbox)

    expect(filterKnowledgeReviewRows(rows, 'EVIDENCE_DERIVED', 'message_send'))
      .toHaveLength(1)
    expect(filterKnowledgeReviewRows(rows, '', 'diag-001'))
      .toHaveLength(1)
    expect(filterKnowledgeReviewRows(rows, 'OUTCOME_BACKED', '消息发送'))
      .toHaveLength(0)
    expect(filterKnowledgeReviewRows(rows, 'MANUAL', '903002'))
      .toHaveLength(1)
  })

  it('renders machine reasons as explicit Chinese gate conditions', () => {
    expect(reviewReasonLabel('P1_CALIBRATION_PERIOD')).toContain('校准期')
    expect(reviewReasonLabel('APPROVAL_IS_SERVER_GATED')).toContain('服务端')
    expect(reviewReasonLabel('OUTCOME_VERIFICATION_NOT_PROJECTED')).toContain('关闭结果')
    expect(reviewReasonLabel('VERSIONED_SELECTOR_UNIQUENESS_REQUIRED')).toContain('selector')
    expect(reviewReasonLabel('REPLAY_SUITE_UNAVAILABLE')).toContain('回放套件')
    expect(reviewReasonLabel('REPLAY_PROOF_STALE')).toContain('失效')
    expect(reviewReasonLabel('POSITIVE_AND_NEGATIVE_REPLAY_FAILED')).toContain('未通过')
    expect(reviewReasonLabel('UNKNOWN_FUTURE_REASON')).toBe('UNKNOWN_FUTURE_REASON')
  })

  it('keeps the bounded manual replay attestation in the server qualification snapshot', () => {
    const manualReplay = {
      attestationId: 'manual-replay-1',
      sourceRecordId: 'manual-sop-001',
      selectorKey: 'csdp:903002',
      candidateFingerprint: 'a'.repeat(64),
      suiteId: 'manual-suite/v1',
      suiteVersion: 1,
      suiteFingerprint: 'b'.repeat(64),
      status: 'PASSED' as const,
      positiveTotal: 1,
      positivePassed: 1,
      negativeOrAbstainTotal: 2,
      negativeOrAbstainPassed: 2,
      failureCodes: [],
      fixtureMode: true as const,
      executedBy: 'reviewer-a',
      executedAt: '2026-07-31T03:00:00Z',
    }
    const projected = buildKnowledgeReviewRows({
      ...inbox,
      sourceStates: inbox.sourceStates.map((state) => state.origin === 'MANUAL'
        ? {
            ...state,
            snapshot: {
              ...state.snapshot,
              approvalEligibility: 'ELIGIBLE_FOR_APPROVAL' as const,
              eligibilityReasons: [],
              manualReplay,
            },
          }
        : state),
    }).find((row) => row.origin === 'MANUAL')

    expect(projected?.approvalEligibility).toBe('ELIGIBLE_FOR_APPROVAL')
    expect(projected?.qualificationSnapshot.manualReplay).toEqual(manualReplay)
  })

  it('distinguishes legacy projection gaps from an invalid current candidate', () => {
    const legacy = inbox.outcomeBacked[0]
    const current = {
      ...legacy,
      contractVersion: 'knowledge-candidate.v2',
    }

    expect(missingKnowledgeOwnerLabel(legacy)).toContain('历史 v1')
    expect(missingOutcomeProofLabel(legacy)).toBe('LEGACY_NOT_PROJECTED')
    expect(missingKnowledgeOwnerLabel(current)).toContain('当前合同缺口')
    expect(missingOutcomeProofLabel(current)).toBe('CURRENT_CONTRACT_INVALID')
  })

  it('does not collapse structured reference failures into a coverage score', () => {
    const issues = referenceComparisonIssues({
      ...inbox.evidenceDerived[0].referenceComparison,
      requiredIntentCoverage: 1,
      missingStepIntents: ['verify_recovery'],
      forbiddenStepIntentsPresent: ['restart_production'],
      orderingViolations: ['locate -> verify'],
      missingEvidenceKinds: ['contrast_sample'],
    })

    expect(issues.map((issue) => issue.code)).toEqual([
      'MISSING_STEP_INTENTS',
      'FORBIDDEN_STEP_INTENTS',
      'ORDERING_VIOLATIONS',
      'MISSING_EVIDENCE_KINDS',
    ])
    expect(issues.find((issue) => issue.code === 'FORBIDDEN_STEP_INTENTS')?.danger)
      .toBe(true)
  })
})
