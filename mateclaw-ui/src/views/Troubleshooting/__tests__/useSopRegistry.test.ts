import { createApp, defineComponent, h, nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const listSops = vi.fn()
const getSop = vi.fn()

vi.mock('@/api', () => ({
  troubleshootingApi: {
    listSops: (params: unknown) => listSops(params),
    getSop: (system: string, errorCode: string) => getSop(system, errorCode),
    knowledgeEvidenceCoverage: () => Promise.resolve({ data: null }),
  },
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
  ElMessageBox: { confirm: vi.fn() },
}))

vi.mock('@/utils/clipboard', () => ({ copyToClipboard: vi.fn() }))

describe('useSopRegistry onboarding scope', () => {
  beforeEach(() => {
    listSops.mockReset()
    getSop.mockReset()
  })

  it('keeps the detail empty when the requested service has no exact rule', async () => {
    listSops.mockResolvedValue({
      data: [{
        sopId: 'other-v1',
        routeKey: 'csdp:903001',
        system: 'CSDP',
        service: 'order-service',
        errorCode: '903001',
        status: 'approved',
        verified: true,
        operational: true,
        createTime: '2026-08-10T00:00:00Z',
        updateTime: '2026-08-10T00:00:00Z',
        knowledgeEvidenceGrade: 'RECORDED_AGGREGATE',
      }],
    })

    const { useSopRegistry } = await import('../useSopRegistry')
    let registry!: ReturnType<typeof useSopRegistry>
    const host = document.createElement('div')
    const app = createApp(defineComponent({
      setup() {
        registry = useSopRegistry({ initialSystem: 'CSDP', initialService: 'csdp-wechat' })
        return () => h('div')
      },
    }))
    app.mount(host)
    await settle()

    expect(listSops).toHaveBeenCalledWith(expect.objectContaining({ system: 'CSDP' }))
    expect(registry.selectedRouteKey.value).toBeNull()
    expect(registry.selectedSop.value).toBeNull()
    expect(getSop).not.toHaveBeenCalled()

    app.unmount()
  })
})

async function settle() {
  await nextTick()
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
  await nextTick()
}
