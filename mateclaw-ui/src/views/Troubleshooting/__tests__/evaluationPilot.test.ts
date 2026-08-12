import { describe, expect, it } from 'vitest'
import type {
  BaselineEvaluationRun,
  DiagnosisSummary,
  EvidenceEvaluationSample,
} from '@/api'
import { buildEvaluationPilotQueue } from '../evaluationPilot'

describe('evaluation pilot hand-off queue', () => {
  it('turns persisted formal diagnoses into one truthful next action', () => {
    const diagnoses = [
      diagnosis('diag-open', 'READY_FOR_HUMAN', false, '2026-08-13T08:00:00Z'),
      diagnosis('diag-sample', 'CLOSED', false, '2026-08-13T07:00:00Z'),
      diagnosis('diag-reference', 'CLOSED', false, '2026-08-13T06:00:00Z'),
      diagnosis('diag-baseline', 'CLOSED', false, '2026-08-13T05:00:00Z'),
      diagnosis('diag-ready', 'CLOSED', false, '2026-08-13T04:00:00Z'),
      diagnosis('diag-rehearsal', 'CLOSED', true, '2026-08-13T09:00:00Z'),
    ]
    const samples = [
      sample('sample-reference', 'diag-reference', 'EVIDENCE_CAPTURED', true),
      sample('sample-baseline', 'diag-baseline', 'READY_FOR_EVALUATION', true),
      sample('sample-ready', 'diag-ready', 'READY_FOR_EVALUATION', true),
      sample('sample-rehearsal', 'diag-rehearsal', 'READY_FOR_EVALUATION', true),
    ]
    const runs = [run('sample-ready', 'SCORED', 'HELPFUL')]

    expect(buildEvaluationPilotQueue(diagnoses, samples, runs).map(row => [
      row.diagnosisId,
      row.stage,
      row.ownerLabel,
    ])).toEqual([
      ['diag-open', 'NEEDS_CLOSURE', '二线 / 三线'],
      ['diag-sample', 'NEEDS_REAL_SAMPLE', '系统 / Guance 负责人'],
      ['diag-reference', 'NEEDS_REFERENCE', '三线复核人'],
      ['diag-baseline', 'NEEDS_BASELINE', '试点管理员'],
      ['diag-ready', 'READY_FOR_REVIEW', '二线 + 三线 + Owner'],
    ])
  })

  it('excludes Replay and fixture samples from formal pilot progress', () => {
    const formal = diagnosis('diag-formal', 'CLOSED', false, '2026-08-13T04:00:00Z')
    const replay = sample('sample-replay', 'diag-formal', 'READY_FOR_EVALUATION', true)
    replay.sourcePlatform = 'RECORDED_REPLAY'
    const fixture = sample('sample-fixture', 'diag-formal', 'READY_FOR_EVALUATION', true)
    fixture.diagnosisFixtureMode = true

    expect(buildEvaluationPilotQueue([formal], [replay, fixture], []).at(0)?.stage)
      .toBe('NEEDS_REAL_SAMPLE')
  })

  it('does not turn a frozen answer without human time into a savings sample', () => {
    const formal = diagnosis('diag-formal', 'CLOSED', false, '2026-08-13T04:00:00Z')
    const accuracyOnly = sample('sample-accuracy', 'diag-formal', 'READY_FOR_EVALUATION', false)

    const [row] = buildEvaluationPilotQueue(
      [formal],
      [accuracyOnly],
      [run('sample-accuracy', 'SCORED', 'HELPFUL')],
    )

    expect(row.stage).toBe('ACCURACY_ONLY')
    expect(row.nextAction).toContain('不进入省时对照')
  })

  it('keeps failed shadow runs visible instead of counting them as ready', () => {
    const formal = diagnosis('diag-formal', 'CLOSED', false, '2026-08-13T04:00:00Z')
    const ready = sample('sample-ready', 'diag-formal', 'READY_FOR_EVALUATION', true)

    expect(buildEvaluationPilotQueue(
      [formal],
      [ready],
      [run('sample-ready', 'MODEL_REJECTED', 'TECHNICAL_FAILURE')],
    ).at(0)?.stage).toBe('BASELINE_BLOCKED')
  })
})

function diagnosis(
  diagnosisId: string,
  status: DiagnosisSummary['status'],
  rehearsal: boolean,
  updateTime: string,
): DiagnosisSummary {
  return {
    diagnosisId,
    caseId: `case-${diagnosisId}`,
    system: 'CSDP',
    errorCode: '904003',
    service: 'csdp-wechat',
    status,
    investigationMode: 'ERROR_CODE_PLAYBOOK',
    routeAuthority: 'RULE_MATCHED',
    routeSemanticsProvenance: 'PERSISTED',
    rehearsal,
    version: 1,
    createTime: updateTime,
    updateTime,
  }
}

function sample(
  sampleId: string,
  diagnosisId: string,
  referenceStatus: EvidenceEvaluationSample['referenceStatus'],
  withHumanBaseline: boolean,
): EvidenceEvaluationSample {
  return {
    sampleId,
    sampleKey: `key-${sampleId}`,
    captureIdentityKey: `identity-${sampleId}`,
    captureRevision: 1,
    diagnosisId,
    system: 'CSDP',
    service: 'csdp-wechat',
    scenarioKey: 'error_904003',
    sourcePlatform: 'GUANCE',
    evidence: {} as EvidenceEvaluationSample['evidence'],
    modelInputHash: 'hash',
    evidenceOccurredAt: '2026-08-07T09:12:00Z',
    diagnosisFixtureMode: false,
    referenceStatus,
    referenceSolution: referenceStatus === 'READY_FOR_EVALUATION'
      ? {} as EvidenceEvaluationSample['referenceSolution']
      : null,
    expectedDisposition: referenceStatus === 'READY_FOR_EVALUATION' ? 'DRAFT' : null,
    humanBaseline: withHumanBaseline
      ? { minutesToLocate: 30, basis: 'MEASURED', note: '工单时间戳' }
      : null,
    outcome: null,
    version: 1,
    capturedBy: 'owner',
    finalizedBy: null,
    capturedAt: '2026-08-13T04:00:00Z',
    finalizedAt: null,
  }
}

function run(
  sampleId: string,
  status: BaselineEvaluationRun['status'],
  classification: BaselineEvaluationRun['quality']['classification'],
): BaselineEvaluationRun {
  return {
    runId: `run-${sampleId}`,
    runKey: `key-${sampleId}`,
    sampleId,
    diagnosisId: 'diag-formal',
    sampleVersion: 1,
    sourcePlatform: 'GUANCE',
    evidenceFixtureMode: false,
    diagnosisFixtureMode: false,
    evidenceStage: 'FULL_SPINE_OBSERVED',
    modelInputHash: 'hash',
    status,
    modelErrorCodes: [],
    validation: {} as BaselineEvaluationRun['validation'],
    quality: { classification } as BaselineEvaluationRun['quality'],
    model: {} as BaselineEvaluationRun['model'],
    evidenceDurationMs: 100,
    modelDurationMs: 200,
    composedTotalDurationMs: 300,
    executedBy: 'owner',
    executedAt: '2026-08-13T05:00:00Z',
  }
}
