import { describe, expect, it } from 'vitest'
import type { KnowledgeReviewInbox } from '@/api'
import {
  buildKnowledgeReviewRows,
  filterKnowledgeReviewRows,
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
    createTime: '2026-07-20T09:10:00Z',
    updateTime: '2026-07-20T09:10:00Z',
  }],
  capabilityLimits: [
    'REVIEW_DECISIONS_NOT_IMPLEMENTED',
    'APPROVAL_REQUIRES_ELIGIBILITY_GATE',
  ],
}

describe('knowledge review projection', () => {
  it('merges all three persisted lanes without inventing review state', () => {
    const rows = buildKnowledgeReviewRows(inbox)

    expect(rows.map((row) => row.key)).toEqual([
      'OUTCOME_BACKED:candidate-outcome-001',
      'EVIDENCE_DERIVED:candidate-evidence-001',
      'MANUAL:manual-sop-001',
    ])
    expect(rows[0]).toMatchObject({
      origin: 'OUTCOME_BACKED',
      reviewStatus: null,
      validationStatus: 'NOT_EVALUATED',
      approvalEligibility: 'NOT_ELIGIBLE',
      reviewStatePersisted: false,
      selector: 'CSDP:903001',
    })
    expect(rows[0].eligibilityReasons).toContain('REVIEW_CONTRACT_NOT_MIGRATED')
    expect(rows[1]).toMatchObject({
      origin: 'EVIDENCE_DERIVED',
      validationStatus: 'VALID',
      fixtureMode: true,
      reviewStatePersisted: true,
    })
    expect(rows[1].eligibilityReasons).toEqual([
      'P1_CALIBRATION_PERIOD', 'CONTRAST_UNAVAILABLE',
    ])
    expect(rows[2]).toMatchObject({
      origin: 'MANUAL',
      reviewStatus: null,
      validationStatus: 'NOT_EVALUATED',
      reviewStatePersisted: false,
      selector: 'CSDP:903002',
    })
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
    expect(reviewReasonLabel('REVIEW_CONTRACT_NOT_MIGRATED')).toContain('独立审核状态')
    expect(reviewReasonLabel('UNKNOWN_FUTURE_REASON')).toBe('UNKNOWN_FUTURE_REASON')
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
