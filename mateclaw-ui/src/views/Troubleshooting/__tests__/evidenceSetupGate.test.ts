import { describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import type { EvidenceQueryCatalog, ObservabilityAssetCatalog } from '@/api'

const evidenceCatalog = vi.fn()
const observabilityAssets = vi.fn()
const evidenceContracts = vi.fn()

vi.mock('@/api', () => ({
  troubleshootingApi: {
    evidenceCatalog: () => evidenceCatalog(),
    observabilityAssets: () => observabilityAssets(),
    evidenceContracts: () => evidenceContracts(),
  },
}))

vi.mock('element-plus/es/components/loading/index', () => ({
  vLoading: { mounted() {}, updated() {} },
}))

vi.mock('element-plus', () => ({
  ElMessage: Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn() }),
  ElMessageBox: { confirm: vi.fn() },
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {}, fullPath: '/troubleshooting/observability-assets' }),
  useRouter: () => ({ push: vi.fn(), resolve: () => ({ fullPath: '/troubleshooting' }) }),
}))

/**
 * 这一页在内网窗口之前的**每一天**都是空的：没有资产，三个适配器全部 DISABLED。
 * 也就是说空态不是边角情况，它是默认状态。
 *
 * 原来的页面在这个状态下摆出搜索框、三步流程条、四个 0 计数卡和两个跳到另一页的
 * 按钮——全都是「有数据之后才有意义」的东西，而唯一能做的动作被埋在最下面。
 * 更要命的是流程条把「数据源联调」写成第 3 步，可它是前两步的前提：你可以把系统、
 * 模块、绑定全配完，再发现根本没有源可用。
 *
 * 这组用例钉的就是「页面按你实际站在哪一格说话」。
 */
describe('evidence setup gate', () => {
  it('names which platform is missing what instead of one blanket "待联调"', async () => {
    const { text, unmount } = await render()

    expect(text()).toContain('还不能取到真实证据：没有可用的数据源')
    // 缺端点和缺凭据要找的人不一样，所以必须分别说出来。
    expect(text()).toContain('guance')
    expect(text()).toContain('缺 端点 · 凭据')
    unmount()
  })

  it('puts the data source before the module when neither exists', async () => {
    const { text, unmount } = await render()

    expect(text()).toContain('先检查数据连接')
    // 登记模块是离线能做的准备，所以留着——但不占主位。
    expect(text()).toContain('仍然先新增系统')
    unmount()
  })

  it('leads with the module once a source is actually ready', async () => {
    const { text, unmount } = await render({ sourceReady: true })

    expect(text()).toContain('接入第一个系统')
    expect(text()).not.toContain('先检查数据连接')
    expect(text()).not.toContain('还不能取到真实证据')
    unmount()
  })

  /**
   * 这一条是跑真服务跑出来的，不是想出来的：demo profile 下 `recorded-replay`
   * 适配器是 READY 的，于是「有任意 READY 源」为真，页面对着一台三个真源全
   * DISABLED 的机器说了「数据源已就绪」。
   *
   * 受控回放能让工具跑起来，但它不是真实生产观测——`formalProjection` 早就把
   * `recorded-replay*` 单列出来并明写这条。取证配置页把这两件事说成一件，
   * 正是投产清单要挡的「系统假装能取证」。
   */
  it('never calls a replay-only workspace a ready data source', async () => {
    const { text, unmount } = await render({ replayReady: true })

    expect(text()).toContain('当前只有受控回放可用，还没有真实数据源')
    expect(text()).not.toContain('真实数据源已就绪')
    // 回放本身不是「阻断的源」，不该混进缺端点/缺凭据那张清单里。
    expect(text()).not.toContain('recorded-replay')
    unmount()
  })

  it('offers no search box and no cross-page jump while the page is empty', async () => {
    const { html, text, unmount } = await render()

    // 空态里搜索框是在假装这页已经有内容。
    expect(html()).not.toContain('搜索系统、模块或工具')
    // 「查询规则说明书」是被合并掉的那一页，链接不该长回来。
    expect(text()).not.toContain('查询规则说明书')
    unmount()
  })

  async function render(options: { sourceReady?: boolean; replayReady?: boolean } = {}) {
    evidenceCatalog.mockResolvedValue({
      data: catalog(options.sourceReady ?? false, options.replayReady ?? false),
    })
    observabilityAssets.mockResolvedValue({ data: assets() })
    evidenceContracts.mockResolvedValue({ data: { workspaceId: '1', contracts: [] } })

    const Page = (await import('../ObservabilityAssetsWorkspace.vue')).default
    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp(Page)
    app.mount(host)
    await nextTick()
    await Promise.resolve()
    await nextTick()
    await Promise.resolve()
    await nextTick()
    return {
      text: () => host.textContent ?? '',
      html: () => host.innerHTML,
      unmount: () => {
        app.unmount()
        host.remove()
      },
    }
  }
})

function catalog(sourceReady: boolean, replayReady = false): EvidenceQueryCatalog {
  const sources = [
    {
      platform: 'guance',
      status: sourceReady ? 'READY' : 'DISABLED',
      verified: false,
      endpointStatus: sourceReady ? 'CONFIGURED' : 'MISSING',
      credentialStatus: sourceReady ? 'CONFIGURED' : 'MISSING',
      supportedSignals: [],
      detail: sourceReady ? 'adapter ready' : 'adapter disabled',
    },
  ]
  // demo profile 下这一条真的是 READY 的，所以夹具必须能重现那个形状。
  if (replayReady) {
    sources.push({
      platform: 'recorded-replay',
      status: 'READY',
      verified: false,
      endpointStatus: 'NOT_REPORTED',
      credentialStatus: 'NOT_REPORTED',
      supportedSignals: [],
      detail: 'sanitized replay catalog loaded: 14 records',
    })
  }
  return {
    contractVersion: 'evidence-query-catalog.v1',
    workspaceId: '1',
    sources,
    systems: [],
  } as unknown as EvidenceQueryCatalog
}

function assets(): ObservabilityAssetCatalog {
  return { workspaceId: '1', assets: [], contracts: [] } as unknown as ObservabilityAssetCatalog
}
