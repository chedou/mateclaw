import { describe, expect, it } from 'vitest'
import type {
  BaselineEvaluationRun,
  DiagnosisSummary,
  EvidenceEvaluationSample,
  TroubleshootingPilotPlan,
} from '@/api'
import {
  buildEvaluationPilotQueue,
  buildPilotScopeSuggestions,
  buildPilotTeamRepairPlan,
  buildPilotTeamReadiness,
  buildPilotWorkbenchPrompt,
  pilotMemberCanOwnResponsibility,
} from '../evaluationPilot'

describe('pilot scope suggestions', () => {
  it('suggests deduplicated saveable scopes from formal diagnoses only', () => {
    const olderWechat = diagnosis('diag-wechat-old', 'CLOSED', false, '2026-08-13T08:00:00Z')
    const latestWechat = {
      ...diagnosis('diag-wechat-latest', 'CLOSED', false, '2026-08-13T10:00:00Z'),
      system: ' csdp ',
      service: ' CSDP-WECHAT ',
    }
    const task = {
      ...diagnosis('diag-task', 'CLOSED', false, '2026-08-13T09:00:00Z'),
      service: 'csdp-task',
    }
    const rehearsal = diagnosis('diag-rehearsal', 'CLOSED', true, '2026-08-13T11:00:00Z')
    const blank = {
      ...diagnosis('diag-blank', 'CLOSED', false, '2026-08-13T12:00:00Z'),
      service: ' ',
    }
    const displayNameOnly = {
      ...diagnosis('diag-display-name', 'CLOSED', false, '2026-08-13T13:00:00Z'),
      system: '深信服新ICare系统-邹汶达',
      service: 'sf-icare-app',
    }

    expect(buildPilotScopeSuggestions([
      task,
      rehearsal,
      latestWechat,
      blank,
      displayNameOnly,
      olderWechat,
    ])).toEqual([
      {
        system: 'csdp',
        service: 'CSDP-WECHAT',
        formalCount: 2,
        latestAt: '2026-08-13T10:00:00Z',
      },
      {
        system: 'CSDP',
        service: 'csdp-task',
        formalCount: 1,
        latestAt: '2026-08-13T09:00:00Z',
      },
    ])
  })
})

describe('evaluation pilot hand-off queue', () => {
  it('requires three operators including two administrators for the three pilot duties', () => {
    expect(buildPilotTeamReadiness([{ role: 'owner', active: true }])).toEqual({
      memberCount: 1,
      operatorCount: 1,
      adminCount: 1,
      missingOperatorCount: 2,
      missingAdminCount: 1,
      ready: false,
    })
    expect(buildPilotTeamReadiness([
      { role: 'owner', active: true },
      { role: 'admin', active: true },
      { role: 'member', active: true },
    ])).toEqual({
      memberCount: 3,
      operatorCount: 3,
      adminCount: 2,
      missingOperatorCount: 0,
      missingAdminCount: 0,
      ready: true,
    })
    expect(buildPilotTeamReadiness([
      { role: 'owner', active: true },
      { role: 'member', active: true },
      { role: 'member', active: true },
    ]).ready).toBe(false)
    expect(buildPilotTeamReadiness([
      { role: 'owner', active: true },
      { role: 'admin', active: true },
      { role: 'viewer', active: true },
    ]).ready).toBe(false)
  })

  it('only offers people who can perform the selected pilot duty', () => {
    expect(pilotMemberCanOwnResponsibility('SECOND_LINE', { role: 'member', active: true })).toBe(true)
    expect(pilotMemberCanOwnResponsibility('SECOND_LINE', { role: 'viewer', active: true })).toBe(false)
    expect(pilotMemberCanOwnResponsibility('THIRD_LINE', { role: 'admin', active: true })).toBe(true)
    expect(pilotMemberCanOwnResponsibility('THIRD_LINE', { role: 'member', active: true })).toBe(false)
    expect(pilotMemberCanOwnResponsibility('SOURCE_OWNER', { role: 'owner', active: true })).toBe(true)
    expect(pilotMemberCanOwnResponsibility('SOURCE_OWNER', { role: 'member', active: true })).toBe(false)
  })

  it('does not count disabled, orphaned or legacy-unknown accounts as ready', () => {
    const members = [
      { role: 'owner', active: true },
      { role: 'admin', active: false },
      { role: 'admin', active: null },
      { role: 'member' },
    ]

    expect(buildPilotTeamReadiness(members)).toEqual({
      memberCount: 4,
      operatorCount: 1,
      adminCount: 1,
      missingOperatorCount: 2,
      missingAdminCount: 1,
      ready: false,
    })
    expect(pilotMemberCanOwnResponsibility(
      'THIRD_LINE', { role: 'admin', active: false },
    )).toBe(false)
  })

  it('turns the overlapping operator and administrator gaps into exact setup actions', () => {
    expect(buildPilotTeamRepairPlan({
      memberCount: 1,
      operatorCount: 1,
      adminCount: 1,
      missingOperatorCount: 2,
      missingAdminCount: 1,
      ready: false,
    })).toEqual({
      addAdminCount: 1,
      addMemberCount: 1,
      promoteAdminCount: 0,
    })

    expect(buildPilotTeamRepairPlan({
      memberCount: 3,
      operatorCount: 3,
      adminCount: 1,
      missingOperatorCount: 0,
      missingAdminCount: 1,
      ready: false,
    })).toEqual({
      addAdminCount: 0,
      addMemberCount: 0,
      promoteAdminCount: 1,
    })

    expect(buildPilotTeamRepairPlan({
      memberCount: 3,
      operatorCount: 3,
      adminCount: 2,
      missingOperatorCount: 0,
      missingAdminCount: 0,
      ready: true,
    })).toEqual({
      addAdminCount: 0,
      addMemberCount: 0,
      promoteAdminCount: 0,
    })
  })

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

    expect(buildEvaluationPilotQueue(diagnoses, samples, runs, pilotPlan()).map(row => [
      row.diagnosisId,
      row.stage,
      row.ownerLabel,
    ])).toEqual([
      ['diag-open', 'NEEDS_CLOSURE', '二线小周'],
      ['diag-sample', 'NEEDS_REAL_SAMPLE', '观测负责人'],
      ['diag-reference', 'NEEDS_REFERENCE', '三线小陈'],
      ['diag-baseline', 'NEEDS_BASELINE', '三线小陈'],
      ['diag-ready', 'READY_FOR_REVIEW', '二线小周、三线小陈、观测负责人'],
    ])
  })

  it('shows no pilot queue until an exact scope and three owners are configured', () => {
    const unconfigured = { ...pilotPlan(), configured: false, modules: [] }

    expect(buildEvaluationPilotQueue(
      [diagnosis('diag-formal', 'CLOSED', false, '2026-08-13T04:00:00Z')],
      [],
      [],
      unconfigured,
    )).toEqual([])
  })

  it('does not retroactively turn a historical matching diagnosis into a pilot record', () => {
    const historical = {
      ...diagnosis('diag-before-pilot', 'CLOSED', false, '2026-08-13T04:00:00Z'),
      pilotPlanVersion: null,
    }

    expect(buildEvaluationPilotQueue(
      [historical], [], [], pilotPlan(),
    )).toEqual([])
    expect(buildPilotWorkbenchPrompt([historical], pilotPlan())).toMatchObject({
      kind: 'CREATE_FORMAL',
      step: 2,
      actionLabel: '发起首张正式排障',
    })
  })

  it('keeps a previous pilot revision out of the current cohort', () => {
    const previousCohort = diagnosis(
      'diag-v1', 'CLOSED', false, '2026-08-13T04:00:00Z',
    )
    const currentPlan = { ...pilotPlan(), version: 2 }

    expect(buildEvaluationPilotQueue(
      [previousCohort], [], [], currentPlan,
    )).toEqual([])
    expect(buildPilotWorkbenchPrompt([previousCohort], currentPlan)).toMatchObject({
      kind: 'CREATE_FORMAL',
      step: 2,
    })
  })

  it('admits only diagnoses inside the declared system and service scope', () => {
    const inScope = diagnosis('diag-wechat', 'READY_FOR_HUMAN', false, '2026-08-13T04:00:00Z')
    const wrongService = {
      ...diagnosis('diag-other', 'READY_FOR_HUMAN', false, '2026-08-13T05:00:00Z'),
      service: 'csdp-customer',
    }
    const wrongSystem = {
      ...diagnosis('diag-other-system', 'READY_FOR_HUMAN', false, '2026-08-13T06:00:00Z'),
      system: 'ICARE',
    }

    expect(buildEvaluationPilotQueue(
      [wrongService, wrongSystem, inScope], [], [], pilotPlan(),
    ).map(row => row.diagnosisId)).toEqual(['diag-wechat'])
  })

  it('excludes Replay and fixture samples from formal pilot progress', () => {
    const formal = diagnosis('diag-formal', 'CLOSED', false, '2026-08-13T04:00:00Z')
    const replay = sample('sample-replay', 'diag-formal', 'READY_FOR_EVALUATION', true)
    replay.sourcePlatform = 'RECORDED_REPLAY'
    const fixture = sample('sample-fixture', 'diag-formal', 'READY_FOR_EVALUATION', true)
    fixture.diagnosisFixtureMode = true

    expect(buildEvaluationPilotQueue([formal], [replay, fixture], [], pilotPlan()).at(0)?.stage)
      .toBe('NEEDS_REAL_SAMPLE')
  })

  it('does not turn a frozen answer without human time into a savings sample', () => {
    const formal = diagnosis('diag-formal', 'CLOSED', false, '2026-08-13T04:00:00Z')
    const accuracyOnly = sample('sample-accuracy', 'diag-formal', 'READY_FOR_EVALUATION', false)

    const [row] = buildEvaluationPilotQueue(
      [formal],
      [accuracyOnly],
      [run('sample-accuracy', 'SCORED', 'HELPFUL')],
      pilotPlan(),
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
      pilotPlan(),
    ).at(0)?.stage).toBe('BASELINE_BLOCKED')
  })
})

describe('pilot workbench start prompt', () => {
  it('asks an administrator to configure the pilot before showing any case', () => {
    const unconfigured = { ...pilotPlan(), configured: false, modules: [] }

    expect(buildPilotWorkbenchPrompt([], unconfigured)).toMatchObject({
      kind: 'SETUP',
      step: 1,
      ownerLabel: '工作区管理员',
      diagnosisId: null,
      actionLabel: '配置试点',
    })
  })

  it('distinguishes an unavailable saved plan from a plan that was never configured', () => {
    const disabled = { ...pilotPlan(), enabled: false }

    expect(buildPilotWorkbenchPrompt([], disabled)).toMatchObject({
      kind: 'SETUP',
      title: '试点配置需要管理员处理',
      detail: '已有试点配置暂未就绪。管理员检查是否启用、调查范围和三位负责人。',
    })
  })

  it('starts the first formal incident inside the declared scope', () => {
    expect(buildPilotWorkbenchPrompt([], pilotPlan())).toMatchObject({
      kind: 'CREATE_FORMAL',
      step: 2,
      ownerLabel: '二线小周',
      diagnosisId: null,
      actionLabel: '发起首张正式排障',
      scope: { system: 'csdp', service: 'csdp-wechat' },
    })
  })

  it('points the team at the latest pending formal diagnosis only', () => {
    const rehearsal = diagnosis('diag-rehearsal', 'READY_FOR_HUMAN', true, '2026-08-13T10:00:00Z')
    const outOfScope = {
      ...diagnosis('diag-other', 'READY_FOR_HUMAN', false, '2026-08-13T11:00:00Z'),
      service: 'csdp-customer',
    }
    const older = diagnosis('diag-older', 'READY_FOR_HUMAN', false, '2026-08-13T08:00:00Z')
    const latest = diagnosis('diag-latest', 'CONFIRMED', false, '2026-08-13T09:00:00Z')

    expect(buildPilotWorkbenchPrompt(
      [rehearsal, outOfScope, older, latest],
      pilotPlan(),
    )).toMatchObject({
      kind: 'CONTINUE_DIAGNOSIS',
      step: 2,
      ownerLabel: '二线小周',
      diagnosisId: 'diag-latest',
      actionLabel: '打开这张排障单',
    })
    expect(buildPilotWorkbenchPrompt([latest], pilotPlan()).detail).toContain('排障单 diag-latest')
  })

  it('hands a closed formal diagnosis to the evidence owner', () => {
    const closed = diagnosis('diag-closed', 'CLOSED', false, '2026-08-13T09:00:00Z')

    expect(buildPilotWorkbenchPrompt([closed], pilotPlan())).toMatchObject({
      kind: 'HANDOFF_EVALUATION',
      step: 3,
      ownerLabel: '观测负责人、三线小陈',
      diagnosisId: 'diag-closed',
      actionLabel: '进入试点评估',
    })
    expect(buildPilotWorkbenchPrompt([closed], pilotPlan()).detail).toContain('排障单 diag-closed')
  })
})

function pilotPlan(): TroubleshootingPilotPlan {
  return {
    workspaceId: '7',
    configured: true,
    enabled: true,
    version: 1,
    name: 'CSDP 首批试点',
    modules: [{ system: 'csdp', service: 'csdp-wechat' }],
    secondLine: member('11', '二线小周'),
    thirdLine: member('12', '三线小陈', 'admin'),
    sourceOwner: member('13', '观测负责人', 'admin'),
    changedBy: 'admin',
    changedAt: '2026-08-13T03:00:00Z',
    changeReason: '固定首批范围与负责人',
    blockers: [],
  }
}

function member(userId: string, displayName: string, workspaceRole = 'member') {
  return {
    userId,
    username: `user-${userId}`,
    nickname: displayName,
    displayName,
    workspaceRole,
  }
}

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
    pilotPlanVersion: 1,
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
