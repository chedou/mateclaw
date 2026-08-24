import { createApp, nextTick } from 'vue'
import { describe, expect, it } from 'vitest'
import type { BusinessSummary, IncidentContext } from '@/api'

describe('BusinessSummaryCard developer actions', () => {
  it.each([
    ['HYPOTHESIS', 'READY_FOR_HUMAN'],
    ['INSUFFICIENT_EVIDENCE', 'READY_FOR_HUMAN'],
  ] as const)(
    'never offers root-cause confirmation for a %s conclusion',
    async (conclusionType, status) => {
      const { text, unmount } = await render({ conclusionType, status })

      expect(text()).not.toContain('复核后确认定位')
      expect(text()).toContain('转给负责人继续查')
      unmount()
    },
  )

  it('keeps confirmation available after the cause is actually located', async () => {
    const { text, unmount } = await render({
      conclusionType: 'LOCATED',
      status: 'READY_FOR_HUMAN',
    })

    expect(text()).toContain('复核后确认定位')
    unmount()
  })
})

async function render(
  value: Pick<BusinessSummary, 'conclusionType' | 'status'>,
) {
  const Card = (await import('../BusinessSummaryCard.vue')).default
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp(Card, {
    business: business(value),
    incident: incident(),
    closure: null,
    canOperate: true,
    canTransfer: true,
    canClose: false,
    canEvaluate: false,
    rehearsal: false,
    actionLoading: false,
    status: value.status,
    perspective: 'developer',
    failureBreakdown: null,
  })
  app.mount(host)
  await nextTick()
  return {
    text: () => host.textContent ?? '',
    unmount: () => {
      app.unmount()
      host.remove()
    },
  }
}

function business(
  value: Pick<BusinessSummary, 'conclusionType' | 'status'>,
): BusinessSummary {
  return {
    diagnosisId: 'diag-1',
    ...value,
    headline: value.conclusionType === 'LOCATED' ? '已定位连接池耗尽' : '连接池是当前最可能方向',
    rootCause: value.conclusionType === 'LOCATED' ? '连接池耗尽' : null,
    narrative: '本次调查记录了可复核的事实。',
    keyEvidence: '失败请求中记录到相同特征。',
    confidence: 'MEDIUM',
    problem: '会话创建失败',
    impact: {
      functionScope: '会话创建',
      affectedCustomers: null,
      affectedUsers: null,
      blastRadius: 'UNKNOWN',
      evidenceRefs: [],
      observedAt: null,
      note: '影响待确认',
    },
    nextStep: {
      label: '继续调查',
      text: '请负责人继续补充证据。',
      capabilityBoundary: null,
    },
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
    evidenceBasis: 'OBSERVED',
  }
}

function incident(): IncidentContext {
  return {
    incidentId: 'incident-1',
    system: 'CSDP',
    service: 'csdp-task',
    severity: 'P2',
    title: '会话创建失败',
    errorCode: null,
    traceId: null,
    occurredAt: '2026-08-24T08:00:00Z',
    impact: '',
    slaRemaining: null,
    intakeSource: 'web:formal-workbench',
    completeness: 'SYMPTOM',
    rawInput: null,
  }
}
