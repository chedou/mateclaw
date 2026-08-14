import { describe, expect, it } from 'vitest'
import type { BusinessSummary, DeveloperEvidenceView } from '@/api'
import { buildFiveQuestionRail } from '../fiveQuestionProgress'

function business(overrides: Partial<BusinessSummary> = {}): BusinessSummary {
  return {
    diagnosisId: 'd-1',
    conclusionType: 'HYPOTHESIS',
    headline: '候选结论',
    rootCause: null,
    narrative: '说明',
    keyEvidence: null,
    confidence: 'MEDIUM',
    problem: 'CSDP / csdp-wechat · ITGW 失败 904003',
    impact: {
      functionScope: '会话创建',
      affectedCustomers: null,
      affectedUsers: null,
      blastRadius: 'UNKNOWN',
      evidenceRefs: [],
      observedAt: null,
      note: '局部影响',
    },
    nextStep: {
      label: '建议下一步',
      text: '人工复核候选结论',
      capabilityBoundary: '不改生产',
    },
    status: 'READY_FOR_HUMAN',
    timings: {
      reportedAt: null,
      readyAt: null,
      conclusionAt: null,
      handoffAt: null,
      intakeCost: null,
      investigateCost: null,
      adoptCost: null,
    },
    fixtureMode: false,
    ...overrides,
  }
}

function developer(overrides: Partial<DeveloperEvidenceView> = {}): DeveloperEvidenceView {
  return {
    diagnosisId: 'd-1',
    investigationMode: 'ERROR_CODE_PLAYBOOK',
    routeAuthority: 'EXPLICIT',
    routeSemanticsProvenance: 'PERSISTED',
    playbookRef: 'CSDP/csdp-wechat/904003@v3',
    knowledgeEvidenceGrade: 'RECORDED_AGGREGATE',
    scenarioAffordances: [],
    callChain: {
      psId: 'ps-1',
      hops: [{ hopId: 'h1', service: 'csdp-wechat', duration: '12ms', anomalous: true }],
      emptyReason: null,
      blastRadius: 'UNKNOWN',
    },
    steps: [{
      kind: 'EVIDENCE',
      at: '2026-08-07T17:12:00Z',
      title: '失败日志',
      detail: '命中 1 条',
      ref: 'e1',
      tone: 'NORMAL',
    }],
    investigationTrace: {
      diagnosisId: 'd-1',
      investigationDuration: null,
      stages: [],
      evidenceContracts: [],
      adapterAttempts: [],
      stopReason: {
        code: 'CONCLUSION_RECORDED',
        message: '',
        stoppedAt: null,
        evidenceRefs: [],
      },
      evidenceRelation: {
        available: false,
        nodes: [],
        edges: [],
        emptyReason: null,
      },
    },
    contrast: {
      available: true,
      featureCode: '904003',
      failedRequests: null,
      normalRequests: null,
      note: '',
      evidenceRefs: [],
    },
    draft: {
      draftId: null,
      title: '',
      steps: [],
      emptyReason: null,
      reviewStatus: 'DRAFT',
      stateNote: '',
    },
    capabilityLimits: [],
    fixtureMode: false,
    ...overrides,
  }
}

describe('buildFiveQuestionRail', () => {
  it('maps a located diagnosis onto the daily five-question spine', () => {
    const items = buildFiveQuestionRail(business(), developer())
    expect(items).toHaveLength(5)
    expect(items[0]).toMatchObject({
      title: '发生了什么？',
      state: 'DONE',
      answer: expect.stringContaining('904003'),
    })
    expect(items[1]).toMatchObject({
      title: '这次怎么查？',
      state: 'DONE',
      answer: expect.stringContaining('标准方案'),
    })
    expect(items[2].state).toBe('DONE')
    expect(items[3].state).toBe('DONE')
    expect(items[4]).toMatchObject({
      title: '接下来怎么办？',
      state: 'ACTIVE',
    })
  })

  it('marks evidence and meaning as stopped when the conclusion is insufficient', () => {
    const items = buildFiveQuestionRail(
      business({ conclusionType: 'INSUFFICIENT_EVIDENCE', status: 'READY_FOR_HUMAN' }),
      developer({
        callChain: { psId: null, hops: [], emptyReason: '无关联日志', blastRadius: 'UNKNOWN' },
        steps: [],
        contrast: {
          available: false,
          featureCode: null,
          failedRequests: null,
          normalRequests: null,
          note: '',
          evidenceRefs: [],
        },
        playbookRef: null,
        investigationMode: 'OPEN_DISCOVERY',
        routeAuthority: 'MODEL_PROPOSED',
      }),
    )
    expect(items[2].state).toBe('STOPPED')
    expect(items[3].state).toBe('STOPPED')
    expect(items[3].answer).toContain('证据不足')
  })
})
