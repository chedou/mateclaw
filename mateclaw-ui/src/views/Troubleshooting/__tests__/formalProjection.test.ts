import { describe, expect, it } from 'vitest'
import type {
  GuanceEvidenceAcceptanceStatus,
  GuanceEvidenceAcceptanceView,
  GuanceRecordingBatchReadiness,
  GuanceReadinessStatus,
  GuanceSignalStatus,
  OpenDiscoveryReadiness,
} from '@/api'
import {
  closureOutcomeLabel,
  conclusionLabel,
  diagnosisSummaryRouteLabel,
  diagnosisGuanceUsageLabel,
  diagnosisEvidenceSourcePresentation,
  formalAdmissionErrorMessage,
  formatDuration,
  guanceAcceptanceProgress,
  guanceAcceptanceStateLabel,
  guanceDetailSourceState,
  guanceOwnerBlockerLabel,
  guanceReadinessLabel,
  guanceRecordingBatchLabel,
  guanceRecordingBatchReady,
  guanceSignalLabel,
  guanceSpinePreviewLabel,
  guanceValidationLabel,
  impactMetrics,
  investigationLabel,
  knowledgeEvidenceGradeLabel,
  openDiscoveryReadinessPresentation,
  timingState,
} from '../formalProjection'

function readiness(
  status: GuanceReadinessStatus,
  signalStatus: GuanceSignalStatus = 'UNAUTHORIZED',
  authorized = false,
) {
  return {
    status,
    uniqueAssetAuthorized: authorized,
    signals: ['log_search', 'log_trace_bundle'].map(signalKind => ({
      signalKind,
      routedToGuance: true,
      status: signalStatus,
      bindingRef: `${signalKind}-binding`,
      lastObservedAt: null,
      detail: '',
    })),
  }
}

function acceptance(
  status: GuanceEvidenceAcceptanceStatus,
): GuanceEvidenceAcceptanceView {
  return {
    status,
    system: 'CSDP',
    service: 'session-svc',
    currentBindingFingerprint: 'b'.repeat(64),
    acceptance: status === 'NOT_ACCEPTED' || status === 'BLOCKED' ? null : {
      acceptanceId: 't7-012345678901234567890123',
      system: 'CSDP',
      service: 'session-svc',
      bindingFingerprint: status === 'STALE' ? 'c'.repeat(64) : 'b'.repeat(64),
      checklist: {
        measurementAndFieldsVerified: true,
        indexVerified: true,
        psIdJoinVerified: true,
        timestampUnitVerified: true,
        timeWindowVerified: true,
        dqlLatencyReviewed: true,
        legacyRouteConflictReviewed: true,
      },
      validation: {
        matchCount: 4,
        traceEntries: 3,
        psIdFingerprint: 'd'.repeat(64),
        logSearchDurationMs: 12,
        logTraceDurationMs: 20,
        totalDurationMs: 40,
        observedAt: '2026-07-29T08:00:00Z',
      },
      acceptedBy: 'owner',
      acceptedAt: '2026-07-29T08:00:00Z',
    },
    blockers: [],
  }
}

function recordingBatch(executableTargetCount: number): GuanceRecordingBatchReadiness {
  const targets = Array.from({ length: executableTargetCount }, (_, index) => {
    const service = index < 10 ? 'csdp-task' : 'csdp-wechat'
    return {
      targetId: `${service}-${index}`,
      system: 'CSDP',
      service,
      scenarioKey: index === 0
        ? null
        : service === 'csdp-task' ? 'cti-create-conversation' : 'itgw-access-failed',
      selectorKey: service === 'csdp-task'
        ? 'csdp:scenario:cti_create_conversation_failed'
        : 'csdp:904003',
      bindingFingerprint: index === 0 ? null : 'b'.repeat(64),
      targetBindingFingerprint: index === 0
        ? null
        : `${service}-${index}`.padEnd(64, 'c'),
      executable: true,
      blockers: [],
    }
  })
  return {
    contractVersion: 't7-guance-recording-batch-readiness.v2',
    batchId: `t7-first-${'a'.repeat(24)}`,
    workspaceId: '1',
    catalogContractVersion: 't7-guance-recording-target-catalog.v1',
    catalogFingerprint: 'e'.repeat(64),
    frozenTargetCount: executableTargetCount,
    executableTargetCount,
    readyForOwnerAcceptance: executableTargetCount >= 20,
    targets,
    asOfEpochSeconds: '1785657600',
    blockers: executableTargetCount < 20 ? ['at least 20 targets are required'] : [],
  }
}

describe('formal troubleshooting projection formatting', () => {
  it.each([
    ['formal open discovery requires an enabled pilot plan containing the exact system/service', '还未开通正式只读调查'],
    ['Guance owner acceptance belongs to a different system/service', '数据源尚未完成正式验收'],
    ['当前系统/服务尚未验收通用调查所需的只读能力；no supported accepted read-only capability is available', '还缺少可用的只读查询能力'],
    ['formal open discovery requires structured system and service', '请先填写系统、服务和故障现象'],
    ['formal bounded read-only planner is unavailable', '通用只读调查当前不可用'],
    ['pilot plan changed during formal open discovery', '调查期间配置发生变化'],
  ])('translates a formal-admission failure into a user action: %s', (raw, expected) => {
    const projected = formalAdmissionErrorMessage(new Error(raw))

    expect(projected).toContain(expected)
    expect(projected).not.toMatch(/T7|owner|pilot|contract|Playbook|planner/i)
  })

  it.each([
    ['READY_FOR_BOUNDED_FALLBACK', '通用只读调查已就绪', 'success'],
    ['READY_FOR_REHEARSAL', '通用只读调查当前只能演练', 'warning'],
    ['DISABLED', '通用只读调查未启用', 'error'],
    ['BLOCKED', '通用只读调查尚未就绪', 'error'],
  ] as const)('shows only the user action for readiness %s', (status, title, alertType) => {
    const readiness: OpenDiscoveryReadiness = {
      status,
      agentEnabled: false,
      configuredAgentId: 0,
      agentReady: false,
      configuredPlanCount: 0,
      visiblePlanCount: 0,
      trueSourcePermitted: false,
      plans: [],
      blockers: ['T7 owner contract is missing'],
      nextAction: 'enable Agent and pilot plan',
    }
    const projected = openDiscoveryReadinessPresentation(readiness)

    expect(projected).toMatchObject({ title, alertType })
    expect(projected.detail).toMatch(/管理员|开始/)
    expect(`${projected.title} ${projected.detail}`)
      .not.toMatch(/T7|owner|pilot|contract|Agent|planner|计划/i)
  })

  it('keeps conclusion semantics explicit', () => {
    expect(conclusionLabel('LOCATED')).toBe('已定位原因')
    expect(conclusionLabel('EXCLUDED')).toBe('已排除（非定位）')
    expect(conclusionLabel('HYPOTHESIS')).toBe('最可能方向')
    expect(conclusionLabel('INSUFFICIENT_EVIDENCE')).toBe('证据不足')
  })

  it('renders final closure outcomes as business language', () => {
    expect(closureOutcomeLabel('RECOVERED')).toBe('已恢复')
    expect(closureOutcomeLabel('FALSE_POSITIVE')).toBe('误报')
    expect(closureOutcomeLabel('TRANSFERRED_OUT')).toBe('已转出处置')
    expect(closureOutcomeLabel('UNRESOLVED')).toBe('未解决')
  })

  it('does not render unknown impact counts as zero', () => {
    expect(impactMetrics(null, null)).toEqual([])
    expect(impactMetrics(12, null)).toEqual(['12 个客户'])
    expect(impactMetrics(null, 38)).toEqual(['38 名用户'])
  })

  it('distinguishes an unrecorded stage from a stage that has not happened', () => {
    expect(timingState(null, null, 'recorded')).toBe('未记录')
    expect(timingState(null, null, 'pending')).toBe('未发生')
    expect(timingState('2026-07-28T12:43:14Z', 'PT43S', 'recorded')).toBe('43秒')
  })

  it('formats ISO durations and the investigation route without guessing', () => {
    expect(formatDuration('PT1M25S')).toBe('1分25秒')
    expect(formatDuration('PT4M')).toBe('4分钟')
    expect(formatDuration('PT0.031853S')).toBe('<1秒')
    expect(investigationLabel('ERROR_CODE_PLAYBOOK', 'EXPLICIT'))
      .toBe('标准排障方法（按错误码） · 直接命中')
    expect(investigationLabel('OPEN_DISCOVERY', 'MODEL_PROPOSED'))
      .toBe('通用只读调查 · AI 规划')
    expect(investigationLabel('OPEN_DISCOVERY', 'POLICY_PROPOSED'))
      .toBe('通用只读调查 · 受限规划')
  })

  it('keeps persisted route semantics distinct from legacy reconstruction', () => {
    expect(diagnosisSummaryRouteLabel(null, null, 'LEGACY_DERIVED'))
      .toBe('旧版记录推导 · 详情可见兼容值')
    expect(diagnosisSummaryRouteLabel('SCENARIO_PLAYBOOK', 'RULE_MATCHED', 'PERSISTED'))
      .toBe('标准排障方法（按场景） · 自动匹配')
    expect(diagnosisSummaryRouteLabel(null, 'RULE_MATCHED', 'PERSISTED'))
      .toBe('路由字段缺失')
  })

  it('makes recorded knowledge and authored fixtures impossible to confuse', () => {
    expect(knowledgeEvidenceGradeLabel('RECORDED_AGGREGATE')).toBe('真实录制聚合')
    expect(knowledgeEvidenceGradeLabel('AUTHORED_FIXTURE')).toBe('手写验证夹具')
    expect(knowledgeEvidenceGradeLabel('UNVERIFIED')).toBe('来源未核实')
  })

  it('uses plain language for real-source validation while keeping gate states distinct', () => {
    expect(guanceReadinessLabel('READY_FOR_VALIDATION')).toBe('可执行单次验证')
    expect(guanceReadinessLabel('CANONICAL_SIGNALS_OBSERVED'))
      .toBe('核心规范化信号已分别观测')
    expect(guanceSignalLabel('NOT_ROUTED')).toBe('未路由到 Guance')
    expect(guanceSignalLabel('INVALID_BINDING')).toBe('绑定无效')
    expect(guanceValidationLabel('CANONICAL_CHAIN_OBSERVED'))
      .toBe('日志与调用链验证通过（待负责人确认）')
    expect(guanceSpinePreviewLabel('FULL_SPINE_OBSERVED'))
      .toBe('完整取证流程已验证（待负责人确认）')
    expect(guanceSpinePreviewLabel('CORE_CHAIN_OBSERVED')).toContain('成功样本对照缺失')
    expect(guanceAcceptanceStateLabel('OWNER_EVIDENCE_REQUIRED')).toBe('待负责人确认')
  })

  it('projects Guance as a compact environment status instead of a diagnosis step', () => {
    const blockedProgress = guanceAcceptanceProgress(
      readiness('READY_FOR_VALIDATION', 'READY_FOR_VALIDATION', true),
      acceptance('NOT_ACCEPTED'),
      recordingBatch(0),
    )
    expect(guanceDetailSourceState(
      'READY_FOR_VALIDATION',
      'NOT_ACCEPTED',
      blockedProgress,
    )).toEqual({
      label: '生产验收批次未准备好',
      tone: 'warning',
    })
    expect(guanceDetailSourceState(
      'READY_FOR_VALIDATION',
      'ACCEPTED',
      null,
    )).toEqual({
      label: '当前绑定已验收',
      tone: 'success',
    })
    expect(guanceDetailSourceState(null, null, null)).toEqual({
      label: '状态暂不可用',
      tone: 'muted',
    })
  })

  it('states whether Guance evidence belongs to the current Diagnosis', () => {
    expect(diagnosisGuanceUsageLabel([{ source: 'guance:log_search', status: 'NORMAL' }]))
      .toBe('当前 Diagnosis 包含观测云只读证据。')
    expect(diagnosisGuanceUsageLabel([{ source: 'recorded-replay', status: 'ANOMALY' }]))
      .toContain('当前 Diagnosis 使用 Recorded Replay')
    expect(diagnosisGuanceUsageLabel([{ source: 'router:unavailable', status: 'MISSING' }]))
      .toContain('只记录到缺失结果')
    expect(diagnosisGuanceUsageLabel([]))
      .toContain('尚未记录证据来源')
  })

  it('does not label an all-MISSING real attempt as Recorded Replay', () => {
    const unavailable = diagnosisEvidenceSourcePresentation([
      { source: 'router:unavailable', status: 'MISSING' },
    ])

    expect(unavailable).toMatchObject({
      kind: 'NO_USABLE_EVIDENCE',
      title: '尚未取得可用证据',
      showBanner: true,
    })
    expect(unavailable.detail).not.toContain('Recorded Replay')
    expect(diagnosisEvidenceSourcePresentation([
      { source: 'recorded-replay:csdp', status: 'ANOMALY' },
    ])).toMatchObject({ kind: 'RECORDED_REPLAY' })
    expect(diagnosisEvidenceSourcePresentation([
      { source: 'guance:log_search', status: 'ANOMALY' },
    ])).toMatchObject({ kind: 'GUANCE', showBanner: false })
  })

  it('keeps source authorization, owner confirmation, and real samples as separate gates', () => {
    expect(guanceAcceptanceProgress(readiness('UNAUTHORIZED'))).toEqual({
      stages: [
        expect.objectContaining({ code: 'T6', state: 'BLOCKED' }),
        expect.objectContaining({ code: 'T7', state: 'BLOCKED' }),
        expect.objectContaining({ code: 'T8', state: 'BLOCKED' }),
      ],
      nextAction: '为当前 Workspace / system / service 配置唯一资产授权与 log_search、log_trace_bundle 绑定。',
    })

    const ready = guanceAcceptanceProgress(readiness(
      'READY_FOR_VALIDATION', 'READY_FOR_VALIDATION', true,
    ), null, recordingBatch(20))
    expect(ready.stages).toEqual([
      expect.objectContaining({ code: 'T6', state: 'READY' }),
      expect.objectContaining({ code: 'T7', state: 'READY' }),
      expect.objectContaining({ code: 'T8', state: 'BLOCKED' }),
    ])
    expect(ready.nextAction).toContain('会议案例')

    const observed = guanceAcceptanceProgress(readiness(
      'CANONICAL_SIGNALS_OBSERVED', 'CANONICAL_RESULT_OBSERVED', true,
    ), null, recordingBatch(20))
    expect(observed.stages).toEqual([
      expect.objectContaining({ code: 'T6', state: 'READY' }),
      expect.objectContaining({ code: 'T7', state: 'OWNER_EVIDENCE_REQUIRED' }),
      expect.objectContaining({ code: 'T8', state: 'BLOCKED' }),
    ])
    expect(observed.stages[1].detail).toContain('数据集')
    expect(observed.stages[2].detail).toContain('20–30')
    expect(observed.nextAction).toContain('演示数据状态')

    const missingRuntime = guanceAcceptanceProgress(readiness(
      'CONFIGURATION_INCOMPLETE', 'READY_FOR_VALIDATION', true,
    ), null, recordingBatch(20))
    expect(missingRuntime.stages).toEqual([
      expect.objectContaining({ code: 'T6', state: 'READY' }),
      expect.objectContaining({ code: 'T7', state: 'BLOCKED' }),
      expect.objectContaining({ code: 'T8', state: 'BLOCKED' }),
    ])
    expect(missingRuntime.stages[1].title).toBe('真实数据源运行条件未就绪')
  })

  it('treats two ready 10-target scopes as one ready 20-target workspace batch', () => {
    const batch = recordingBatch(20)
    const selectedModuleCount = batch.targets
      .filter(target => target.service === 'csdp-task').length
    const progress = guanceAcceptanceProgress(
      readiness('READY_FOR_VALIDATION', 'READY_FOR_VALIDATION', true),
      acceptance('NOT_ACCEPTED'),
      batch,
    )
    const presentation = JSON.stringify(progress)

    expect(selectedModuleCount).toBe(10)
    expect(guanceRecordingBatchLabel(batch)).toBe('Workspace 首批录制目标 · 20 / 20')
    expect(guanceRecordingBatchReady(batch)).toBe(true)
    expect(progress.stages[1]).toEqual(expect.objectContaining({
      code: 'T7',
      state: 'READY',
    }))
    expect(presentation).not.toContain(`${selectedModuleCount} / 20`)
    expect(presentation).not.toContain('0 / 20')
  })

  it('keeps an unavailable workspace batch unknown instead of falling back to a module count', () => {
    const progress = guanceAcceptanceProgress(
      readiness('READY_FOR_VALIDATION', 'READY_FOR_VALIDATION', true),
      acceptance('NOT_ACCEPTED'),
      null,
    )
    const presentation = JSON.stringify(progress)

    expect(progress.stages[1].detail).toContain('Workspace 首批录制目标尚未加载')
    expect(guanceRecordingBatchLabel(null)).toBe('Workspace 首批录制目标未加载')
    expect(guanceRecordingBatchReady(null)).toBe(false)
    expect(progress.nextAction).not.toContain('当前可执行 0')
    expect(presentation).not.toContain('0 / 20')
    expect(presentation).not.toContain('10 / 20')
  })

  it('blocks T7 before the window when the server owns fewer than 20 executable targets', () => {
    const progress = guanceAcceptanceProgress(
      readiness('READY_FOR_VALIDATION', 'READY_FOR_VALIDATION', true),
      acceptance('NOT_ACCEPTED'),
      recordingBatch(0),
    )

    expect(progress.stages[0]).toEqual(expect.objectContaining({ code: 'T6', state: 'READY' }))
    expect(progress.stages[1]).toEqual(expect.objectContaining({
      code: 'T7',
      state: 'BLOCKED',
      title: '生产验收批次未准备好',
    }))
    expect(progress.stages[1].detail).toContain('0 / 20')
    expect(progress.stages[1].detail).toContain('不代表当前 Diagnosis 没有真源证据')
    expect(progress.nextAction).toContain('准备至少 20 个')
  })

  it('unlocks real sample collection only for the current owner-confirmed data source', () => {
    const accepted = guanceAcceptanceProgress(
      readiness('READY_FOR_VALIDATION', 'READY_FOR_VALIDATION', true),
      acceptance('ACCEPTED'),
      recordingBatch(0),
    )

    expect(accepted.stages).toEqual([
      expect.objectContaining({ code: 'T6', state: 'READY' }),
      expect.objectContaining({
        code: 'T7',
        state: 'BLOCKED',
        title: '生产验收批次未准备好',
      }),
      expect.objectContaining({
        code: 'T8',
        state: 'BLOCKED',
        title: '真实样本尚未开始',
      }),
    ])
    expect(accepted.stages[1].detail).toContain('0 / 20')
    expect(accepted.nextAction).toContain('准备至少 20 个')

    expect(guanceDetailSourceState(
      'READY_FOR_VALIDATION',
      'ACCEPTED',
      accepted,
    )).toEqual({
      label: '生产验收批次未准备好',
      tone: 'warning',
    })

    const stale = guanceAcceptanceProgress(
      readiness(
        'CANONICAL_SIGNALS_OBSERVED',
        'CANONICAL_RESULT_OBSERVED',
        true,
      ),
      acceptance('STALE'),
      recordingBatch(20),
    )
    expect(stale.stages[1]).toEqual(expect.objectContaining({
      state: 'OWNER_EVIDENCE_REQUIRED',
      title: '数据源配置已变化，原确认失效',
    }))
    expect(stale.stages[2].state).toBe('BLOCKED')
    expect(stale.nextAction).toContain('配置已经变化')
  })

  it('does not let historical owner acceptance override an incomplete batch while Guance is disabled', () => {
    const progress = guanceAcceptanceProgress(
      readiness('DISABLED', 'NOT_ROUTED', false),
      acceptance('ACCEPTED'),
      recordingBatch(0),
    )

    expect(progress.stages[1]).toEqual(expect.objectContaining({
      code: 'T7',
      state: 'BLOCKED',
      title: '生产验收批次未准备好',
    }))
    expect(progress.stages[2]).toEqual(expect.objectContaining({
      code: 'T8',
      state: 'BLOCKED',
    }))
    expect(progress.nextAction).toContain('准备至少 20 个')
  })

  it('localizes known Guance owner blockers without hiding unknown diagnostics', () => {
    expect(guanceOwnerBlockerLabel(
      'the current Guance binding has not been explicitly accepted by an owner',
    )).toBe('当前数据源配置尚未由 Workspace 负责人确认。')
    expect(guanceOwnerBlockerLabel('custom owner diagnostic')).toBe('custom owner diagnostic')
  })
})
