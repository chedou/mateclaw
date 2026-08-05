import { describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import type { InvestigationProvenance } from '@/api'

const provenanceApi = vi.fn()

vi.mock('@/api', () => ({
  troubleshootingApi: { provenance: (id: string) => provenanceApi(id) },
}))

// element-plus 的 v-loading 在测试里换成空指令：这条用例要看的是文案，
// 不是加载动画，而真去加载整个组件库会让一条断言的成本高一个量级。
vi.mock('element-plus/es/components/loading/index', () => ({
  vLoading: { mounted() {}, updated() {} },
}))

/**
 * 前端里唯一一处 `null` 和 `false` 必须显示成不同话的地方。
 *
 * <p>`cited === null` 是「本路径不维护引用清单」——错误码路根本不记引用；
 * `cited === false` 是「这条证据取到了，但没支撑结论」。把前者显示成后者，等于
 * 告诉读者「系统查过了，发现它不支持结论」，而事实是系统压根没记这件事。</p>
 *
 * <p>这类缺陷**后端一条测试都拦不住**：接口把两种值都如实发出来了，错的是渲染。
 * 本仓库此前没有任何组件渲染测试，所以这一整类缺陷没有守卫。</p>
 */
describe('InvestigationProvenancePanel', () => {
  it('renders null, true and false citation states as three different sentences', async () => {
    provenanceApi.mockResolvedValue({ data: provenance() })
    const { text, unmount } = await render()

    expect(text()).toContain('本路径不维护引用清单')
    expect(text()).toContain('已引用')
    expect(text()).toContain('未被引用')
    unmount()
  })

  it('never shows the no-citation-list wording for evidence that simply was not cited', async () => {
    provenanceApi.mockResolvedValue({
      data: provenance([collector('EV-1', false)]),
    })
    const { text, unmount } = await render()

    expect(text()).toContain('未被引用')
    expect(text())
      .not.toContain('本路径不维护引用清单')
    unmount()
  })

  /** 反过来同样要成立，否则「两个都显示成同一句」也能让上一条通过。 */
  it('never shows "not cited" for a lane that keeps no citation list at all', async () => {
    provenanceApi.mockResolvedValue({
      data: provenance([collector('EV-1', null)]),
    })
    const { text, unmount } = await render()

    expect(text()).toContain('本路径不维护引用清单')
    expect(text()).not.toContain('未被引用')
    unmount()
  })

  it('says nothing rather than guessing when the provenance cannot be read', async () => {
    provenanceApi.mockRejectedValue(new Error('boom'))
    const { text, unmount } = await render()

    expect(text()).toContain('宁可空着，也不猜谁参与过')
    expect(text()).not.toContain('已引用')
    unmount()
  })

  async function render() {
    const Panel = (await import('../InvestigationProvenancePanel.vue')).default
    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp(Panel, { diagnosisId: 'diag-1', diagnosisVersion: 1 })
    app.mount(host)
    // watch(immediate) 触发的加载是异步的，等两拍让 then 和重渲染都落地。
    await nextTick()
    await Promise.resolve()
    await nextTick()
    return {
      text: () => host.textContent ?? '',
      unmount: () => {
        app.unmount()
        host.remove()
      },
    }
  }
})

function collector(
  requestId: string,
  cited: boolean | null,
): InvestigationProvenance['collectors'][number] {
  return {
    requestId,
    signalKind: 'log_search',
    adapter: 'recorded-replay',
    status: 'ANOMALY',
    answered: true,
    cited,
    collectedAt: '2026-08-03T10:00:00Z',
  }
}

function provenance(
  collectors: InvestigationProvenance['collectors'] = [
    collector('EV-NULL', null),
    collector('EV-TRUE', true),
    collector('EV-FALSE', false),
  ],
): InvestigationProvenance {
  return {
    knowledge: {
      selectorKey: 'csdp:903001',
      title: '工单库连接池被慢查询占满',
      playbookVersion: 1,
      origin: 'MANUAL',
      ownerTeam: '工单平台组',
      operational: true,
      readable: true,
      note: null,
    },
    collectors,
    reasoning: {
      routeMode: 'DETERMINISTIC',
      investigationMode: 'ERROR_CODE_PLAYBOOK',
      routeAuthority: 'EXPLICIT',
      conclusionType: 'LOCATED',
      modelInvoked: false,
      modelIdentity: null,
      signalsSatisfied: 2,
      derivationRebuildable: true,
    },
    abstentions: [
      { capability: '真实数据校准', reason: '阈值是人手写的，从未用真实历史故障标定过。' },
    ],
  } as InvestigationProvenance
}
