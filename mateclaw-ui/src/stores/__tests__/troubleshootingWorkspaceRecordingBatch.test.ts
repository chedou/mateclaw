import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useTroubleshootingStore } from '../useTroubleshootingStore'

const apiMocks = vi.hoisted(() => ({
  evidenceReadiness: vi.fn(),
  guanceEvidenceAcceptance: vi.fn(),
  guanceRecordingTargets: vi.fn(),
  currentGuanceRecordingBatch: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}))

vi.mock('@/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api')>()
  return {
    ...actual,
    troubleshootingApi: {
      ...actual.troubleshootingApi,
      ...apiMocks,
    },
  }
})

function target(service: string, index: number) {
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
}

const workspaceBatch = {
  contractVersion: 't7-guance-recording-batch-readiness.v2' as const,
  batchId: `t7-first-${'a'.repeat(24)}`,
  workspaceId: '1',
  catalogContractVersion: 't7-guance-recording-target-catalog.v1',
  catalogFingerprint: 'a'.repeat(64),
  frozenTargetCount: 20,
  executableTargetCount: 20,
  readyForOwnerAcceptance: true,
  targets: [
    ...Array.from({ length: 10 }, (_, index) => target('csdp-task', index)),
    ...Array.from({ length: 10 }, (_, index) => target('csdp-wechat', index)),
  ],
  asOfEpochSeconds: '1787068800',
  blockers: [],
}

describe('troubleshooting workspace recording batch', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    apiMocks.evidenceReadiness.mockResolvedValue({
      data: {
        status: 'READY_FOR_VALIDATION',
        uniqueAssetAuthorized: true,
        signals: [],
      },
    })
    apiMocks.guanceEvidenceAcceptance.mockResolvedValue({
      data: {
        status: 'NOT_ACCEPTED',
        system: 'CSDP',
        service: 'csdp-task',
        currentBindingFingerprint: null,
        acceptance: null,
        blockers: [],
      },
    })
    apiMocks.guanceRecordingTargets.mockResolvedValue({
      data: {
        executableTargetCount: 10,
        frozenTargetCount: 10,
        blockers: ['module scope contains only 10 targets'],
      },
    })
    apiMocks.currentGuanceRecordingBatch.mockResolvedValue({ data: workspaceBatch })
  })

  it('uses 10 plus 10 workspace targets instead of the selected module scope as the T7 denominator', async () => {
    const store = useTroubleshootingStore()

    await store.loadGuanceReadiness('CSDP', 'csdp-task')

    expect(apiMocks.currentGuanceRecordingBatch).toHaveBeenCalledWith()
    expect(apiMocks.guanceRecordingTargets).not.toHaveBeenCalled()
    expect(store.guanceRecordingTargets).toMatchObject({
      executableTargetCount: 20,
      readyForOwnerAcceptance: true,
    })
  })
})
