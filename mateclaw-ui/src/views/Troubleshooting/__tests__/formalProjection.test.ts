import { describe, expect, it } from 'vitest'
import type {
  GuanceEvidenceAcceptanceStatus,
  GuanceEvidenceAcceptanceView,
  GuanceReadinessStatus,
  GuanceSignalStatus,
} from '@/api'
import {
  closureOutcomeLabel,
  conclusionLabel,
  formatDuration,
  guanceAcceptanceProgress,
  guanceReadinessLabel,
  guanceSignalLabel,
  guanceSpinePreviewLabel,
  guanceValidationLabel,
  impactMetrics,
  investigationLabel,
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

describe('formal troubleshooting projection formatting', () => {
  it('keeps conclusion semantics explicit', () => {
    expect(conclusionLabel('LOCATED')).toBe('已定位')
    expect(conclusionLabel('EXCLUDED')).toBe('已排除（非定位）')
    expect(conclusionLabel('HYPOTHESIS')).toBe('根因假设')
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
      .toBe('错误码 Playbook · 显式命中')
    expect(investigationLabel('OPEN_DISCOVERY', 'MODEL_PROPOSED'))
      .toBe('开放调查 · 模型提议')
  })

  it('keeps the real-source gate distinct from T7 acceptance', () => {
    expect(guanceReadinessLabel('READY_FOR_VALIDATION')).toBe('可执行单次验证')
    expect(guanceReadinessLabel('CANONICAL_SIGNALS_OBSERVED'))
      .toBe('核心规范化信号已分别观测')
    expect(guanceSignalLabel('NOT_ROUTED')).toBe('未路由到 Guance')
    expect(guanceSignalLabel('INVALID_BINDING')).toBe('绑定无效')
    expect(guanceValidationLabel('CANONICAL_CHAIN_OBSERVED'))
      .toBe('单次规范化读链通过（待 T7 字段验收）')
    expect(guanceSpinePreviewLabel('FULL_SPINE_OBSERVED'))
      .toBe('完整 Evidence Spine 已观测（待 T7/T8 验收）')
    expect(guanceSpinePreviewLabel('CORE_CHAIN_OBSERVED')).toContain('成功样本对照缺失')
  })

  it('keeps T6 authorization, T7 field verification, and T8 samples as separate gates', () => {
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
    ))
    expect(ready.stages).toEqual([
      expect.objectContaining({ code: 'T6', state: 'READY' }),
      expect.objectContaining({ code: 'T7', state: 'READY' }),
      expect.objectContaining({ code: 'T8', state: 'BLOCKED' }),
    ])
    expect(ready.nextAction).toContain('会议案例')

    const observed = guanceAcceptanceProgress(readiness(
      'CANONICAL_SIGNALS_OBSERVED', 'CANONICAL_RESULT_OBSERVED', true,
    ))
    expect(observed.stages).toEqual([
      expect.objectContaining({ code: 'T6', state: 'READY' }),
      expect.objectContaining({ code: 'T7', state: 'OWNER_EVIDENCE_REQUIRED' }),
      expect.objectContaining({ code: 'T8', state: 'BLOCKED' }),
    ])
    expect(observed.stages[1].detail).toContain('measurement')
    expect(observed.stages[2].detail).toContain('20–30')
    expect(observed.nextAction).toContain('fixtureMode')

    const missingRuntime = guanceAcceptanceProgress(readiness(
      'CONFIGURATION_INCOMPLETE', 'READY_FOR_VALIDATION', true,
    ))
    expect(missingRuntime.stages).toEqual([
      expect.objectContaining({ code: 'T6', state: 'READY' }),
      expect.objectContaining({ code: 'T7', state: 'BLOCKED' }),
      expect.objectContaining({ code: 'T8', state: 'BLOCKED' }),
    ])
    expect(missingRuntime.stages[1].title).toBe('真源运行条件未就绪')
  })

  it('unlocks T8 collection only for the current owner-accepted binding', () => {
    const accepted = guanceAcceptanceProgress(
      readiness('READY_FOR_VALIDATION', 'READY_FOR_VALIDATION', true),
      acceptance('ACCEPTED'),
    )

    expect(accepted.stages).toEqual([
      expect.objectContaining({ code: 'T6', state: 'READY' }),
      expect.objectContaining({
        code: 'T7',
        state: 'READY',
        title: '当前绑定已完成 owner 验收',
      }),
      expect.objectContaining({
        code: 'T8',
        state: 'READY',
        title: '真实历史样本采集已解锁',
      }),
    ])
    expect(accepted.stages[2].detail).toContain('不代表 T8 已通过')
    expect(accepted.nextAction).toContain('20–30')

    const stale = guanceAcceptanceProgress(
      readiness(
        'CANONICAL_SIGNALS_OBSERVED',
        'CANONICAL_RESULT_OBSERVED',
        true,
      ),
      acceptance('STALE'),
    )
    expect(stale.stages[1]).toEqual(expect.objectContaining({
      state: 'OWNER_EVIDENCE_REQUIRED',
      title: '绑定已变更，旧验收已过期',
    }))
    expect(stale.stages[2].state).toBe('BLOCKED')
    expect(stale.nextAction).toContain('配置指纹已变化')
  })
})
