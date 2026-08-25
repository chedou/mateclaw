import { beforeEach, describe, expect, it, vi } from 'vitest'
import { computed, createApp, defineComponent, h, inject, nextTick, provide } from 'vue'
import { createPinia } from 'pinia'
import type { EvidenceQueryCatalog, ObservabilityAssetCatalog } from '@/api'

const evidenceCatalog = vi.fn()
const observabilityAssets = vi.fn()
const evidenceContracts = vi.fn()
const listSops = vi.fn()
const routerPush = vi.fn()
const routeState = {
  path: '/troubleshooting/observability-assets',
  query: { section: 'modules' },
  fullPath: '/troubleshooting/observability-assets?section=modules',
}

vi.mock('@/api', () => ({
  troubleshootingApi: {
    evidenceCatalog: () => evidenceCatalog(),
    observabilityAssets: () => observabilityAssets(),
    evidenceContracts: () => evidenceContracts(),
    listSops: () => listSops(),
  },
}))

vi.mock('element-plus/es/components/loading/index', () => ({
  vLoading: { mounted() {}, updated() {} },
}))

vi.mock('element-plus', () => ({
  ElMessage: Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn(), warning: vi.fn() }),
  ElMessageBox: { confirm: vi.fn() },
}))

vi.mock('vue-router', () => ({
  useRoute: () => routeState,
  useRouter: () => ({ push: routerPush, resolve: () => ({ fullPath: '/troubleshooting' }) }),
}))

describe('observability asset create flow', () => {
  beforeEach(() => {
    evidenceCatalog.mockReset()
    observabilityAssets.mockReset()
    evidenceContracts.mockReset()
    evidenceContracts.mockResolvedValue({ data: { workspaceId: '1', contracts: [] } })
    listSops.mockReset()
    listSops.mockResolvedValue({ data: [] })
  })

  it('starts a new system with an empty editable system identifier even when a module is selected', async () => {
    routeState.query.section = 'modules'
    evidenceCatalog.mockResolvedValue({ data: catalogWithSelectedModule() })
    observabilityAssets.mockResolvedValue({ data: emptyAssets() })

    const Page = (await import('../ObservabilityAssetsWorkspace.vue')).default
    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp(Page)
    app.component('ElButton', buttonStub)
    app.component('ElInput', inputStub)
    app.component('ElDialog', dialogStub)
    app.component('ElDrawer', drawerStub)
    app.component('ElTable', tableStub)
    app.component('ElTableColumn', tableColumnStub)
    // 这一页要不要显示管理员设置卡由 manage:troubleshooting 决定。
    // 测试不加载能力集，判定为 false，设置卡不渲染，断言只看列表本身。
    app.use(createPinia())
    app.mount(host)
    await settle()

    expect(host.querySelector('.module-list-workspace')).toBeTruthy()
    expect(host.querySelector('.assets-workspace')).toBeNull()

    const createButton = [...host.querySelectorAll('button')]
      .find(button => button.textContent?.includes('新增系统'))
    expect(createButton).toBeTruthy()
    createButton!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await nextTick()

    const systemInput = host.querySelector<HTMLInputElement>('input[placeholder="例如 CSDP"]')
    expect(systemInput).toBeTruthy()
    expect(systemInput!.disabled).toBe(false)
    expect(systemInput!.value).toBe('')

    app.unmount()
    host.remove()
  })

  it('does not expose module onboarding because a system is configured only once', async () => {
    routeState.query.section = 'modules'
    evidenceCatalog.mockResolvedValue({ data: catalogWithSelectedModule() })
    observabilityAssets.mockResolvedValue({ data: emptyAssets() })

    const Page = (await import('../ObservabilityAssetsWorkspace.vue')).default
    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp(Page)
    app.component('ElButton', buttonStub)
    app.component('ElInput', inputStub)
    app.component('ElDialog', dialogStub)
    app.component('ElDrawer', drawerStub)
    app.component('ElTable', tableStub)
    app.component('ElTableColumn', tableColumnStub)
    // 这一页要不要显示管理员设置卡由 manage:troubleshooting 决定。
    // 测试不加载能力集，判定为 false，设置卡不渲染，断言只看列表本身。
    app.use(createPinia())
    app.mount(host)
    await settle()

    expect([...host.querySelectorAll('button')]
      .some(button => button.textContent?.trim() === '新增模块')).toBe(false)
    expect(host.textContent).toContain('一个系统只配置一次')
    expect(host.textContent).not.toContain('系统模块列表')

    app.unmount()
    host.remove()
  })

  it('uses the same traditional list workspace for tools and data sources', async () => {
    for (const section of ['tools', 'source'] as const) {
      routeState.query.section = section
      evidenceCatalog.mockResolvedValue({ data: catalogWithToolAndSource() })
      observabilityAssets.mockResolvedValue({ data: emptyAssets() })

      const Page = (await import('../ObservabilityAssetsWorkspace.vue')).default
      const host = document.createElement('div')
      document.body.appendChild(host)
      const app = createApp(Page)
      app.component('ElButton', buttonStub)
      app.component('ElInput', inputStub)
      app.component('ElDialog', dialogStub)
      app.component('ElDrawer', drawerStub)
      app.component('ElTable', tableStub)
      app.component('ElTableColumn', tableColumnStub)
      // 这一页要不要显示管理员设置卡由 manage:troubleshooting 决定。
      // 测试不加载能力集，判定为 false，设置卡不渲染，断言只看列表本身。
      app.use(createPinia())
      app.mount(host)
      await settle()

      expect(host.querySelector(`.${section === 'tools' ? 'tool' : 'source'}-list-workspace`)).toBeTruthy()
      expect(host.querySelector('.assets-workspace')).toBeNull()

      app.unmount()
      host.remove()
    }
  })

  it('shows one system row and opens one system-level editor without module onboarding', async () => {
    routeState.query.section = 'modules'
    evidenceCatalog.mockResolvedValue({ data: catalogWithToolAndSource() })
    observabilityAssets.mockResolvedValue({ data: workspaceSystemAsset() })

    const { app, host } = await mountPage()

    expect(host.textContent).toContain('一个系统只配置一次')
    expect(host.textContent).toContain('可对告警中任意服务执行受限只读调查')
    expect(host.textContent).not.toContain('/5')
    const editButton = [...host.querySelectorAll('button')]
      .find(button => button.textContent?.includes('修改接入'))
    expect(editButton).toBeTruthy()
    editButton!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await nextTick()
    expect(host.querySelector('[data-drawer-title="接入系统"]')).toBeTruthy()
    expect(host.textContent).toContain('不用维护模块或服务清单')

    app.unmount()
    host.remove()
  })

  it('does not turn a failed contract catalog read into an empty ready snapshot', async () => {
    routeState.query.section = 'modules'
    evidenceCatalog.mockResolvedValue({ data: catalogWithToolAndSource() })
    observabilityAssets.mockResolvedValue({ data: workspaceAssetsWithTool() })
    evidenceContracts.mockRejectedValue(new Error('取证方法状态读取失败'))

    const { app, host } = await mountPage()

    expect(host.textContent).toContain('取证方法状态读取失败')
    expect(host.querySelector('.onboarding-progress-trigger')).toBeNull()
    expect(host.textContent).not.toContain('下一步：排障方案已生效')

    app.unmount()
    host.remove()
  })

  it('removes stale onboarding actions when a refresh can no longer read the full snapshot', async () => {
    routeState.query.section = 'modules'
    evidenceCatalog.mockResolvedValue({ data: catalogWithToolAndSource() })
    observabilityAssets.mockResolvedValue({ data: workspaceAssetsWithTool() })

    const { app, host } = await mountPage()
    expect(host.querySelector('.module-list-workspace')).toBeTruthy()

    evidenceContracts.mockRejectedValueOnce(new Error('配置快照已失效'))
    refreshButton(host).dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await settle()

    expect(host.textContent).toContain('配置快照已失效')
    expect(host.querySelector('.onboarding-progress-trigger')).toBeNull()

    app.unmount()
    host.remove()
  })

  it('keeps the newest atomic snapshot when an older refresh completes last', async () => {
    routeState.query.section = 'modules'
    const oldAssets = deferred<{ data: ObservabilityAssetCatalog }>()
    const oldContracts = deferred<{ data: { workspaceId: string; contracts: never[] } }>()
    const oldCatalog = deferred<{ data: EvidenceQueryCatalog }>()
    const oldPlaybooks = deferred<{ data: never[] }>()
    observabilityAssets.mockReturnValueOnce(oldAssets.promise)
    evidenceContracts.mockReturnValueOnce(oldContracts.promise)
    evidenceCatalog.mockReturnValueOnce(oldCatalog.promise)
    listSops.mockReturnValueOnce(oldPlaybooks.promise)

    const Page = (await import('../ObservabilityAssetsWorkspace.vue')).default
    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = mountPageComponent(Page, host)
    await nextTick()

    const newestCatalog = catalogWithToolAndSource()
    newestCatalog.systems[0]!.system = 'CSDP-NEWEST'
    const newestAssets = workspaceSystemAsset()
    newestAssets.assets[0]!.system = 'CSDP-NEWEST'
    newestAssets.assets[0]!.displayName = 'csdp-newest'
    observabilityAssets.mockResolvedValueOnce({ data: newestAssets })
    evidenceContracts.mockResolvedValueOnce({ data: { workspaceId: '1', contracts: [] } })
    evidenceCatalog.mockResolvedValueOnce({ data: newestCatalog })
    listSops.mockResolvedValueOnce({ data: [] })

    refreshButton(host).dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await settle()
    expect(host.textContent).toContain('csdp-newest')

    oldAssets.resolve({ data: workspaceSystemAsset() })
    oldContracts.resolve({ data: { workspaceId: '1', contracts: [] } })
    oldCatalog.resolve({ data: catalogWithToolAndSource() })
    oldPlaybooks.resolve({ data: [] })
    await settle()

    expect(host.textContent).toContain('csdp-newest')
    expect(host.textContent).not.toContain('csdp-task')

    app.unmount()
    host.remove()
  })

})

async function mountPage() {
  const Page = (await import('../ObservabilityAssetsWorkspace.vue')).default
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = mountPageComponent(Page, host)
  await settle()
  return { app, host }
}

function mountPageComponent(Page: ReturnType<typeof defineComponent>, host: HTMLElement) {
  const app = createApp(Page)
  app.component('ElButton', buttonStub)
  app.component('ElAlert', alertStub)
  app.component('ElInput', inputStub)
  app.component('ElDialog', dialogStub)
  app.component('ElDrawer', drawerStub)
  app.component('ElTable', tableStub)
  app.component('ElTableColumn', tableColumnStub)
  app.use(createPinia())
  app.mount(host)
  return app
}

function refreshButton(host: HTMLElement) {
  const button = [...host.querySelectorAll('button')]
    .find(candidate => candidate.textContent?.trim() === '刷新')
  if (!button) throw new Error('未找到刷新按钮')
  return button
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

function approvedCtiSops() {
  return [{
    sopId: 'cti-v1',
    routeKey: 'csdp:scenario:cti_create_conversation_failed',
    system: 'CSDP',
    service: 'csdp-task',
    errorCode: 'scenario:cti_create_conversation_failed',
    status: 'approved',
    verified: true,
    operational: true,
    createTime: '2026-08-10T00:00:00Z',
    updateTime: '2026-08-10T00:00:00Z',
    knowledgeEvidenceGrade: 'RECORDED_AGGREGATE',
  }]
}

const buttonStub = defineComponent({
  name: 'ElButton',
  emits: ['click'],
  setup(_, { emit, slots }) {
    return () => h('button', { type: 'button', onClick: () => emit('click') }, slots.default?.())
  },
})

const alertStub = defineComponent({
  name: 'ElAlert',
  props: {
    title: { type: String, default: '' },
  },
  setup(props) {
    return () => h('div', { class: 'alert-stub' }, props.title)
  },
})

const inputStub = defineComponent({
  name: 'ElInput',
  props: {
    modelValue: { type: String, default: '' },
    disabled: { type: Boolean, default: false },
    placeholder: { type: String, default: '' },
  },
  emits: ['update:modelValue'],
  setup(props) {
    return () => h('input', {
      value: props.modelValue,
      disabled: props.disabled,
      placeholder: props.placeholder,
    })
  },
})

const dialogStub = defineComponent({
  name: 'ElDialog',
  props: {
    modelValue: { type: Boolean, default: false },
    title: { type: String, default: '' },
  },
  setup(props, { slots }) {
    return () => props.modelValue
      ? h('section', { 'data-dialog-title': props.title }, [slots.default?.(), slots.footer?.()])
      : null
  },
})

const drawerStub = defineComponent({
  name: 'ElDrawer',
  props: {
    modelValue: { type: Boolean, default: false },
    title: { type: String, default: '' },
  },
  setup(props, { slots }) {
    return () => props.modelValue
      ? h('aside', { 'data-drawer-title': props.title, class: 'drawer-stub' }, [
        slots.default?.(),
        slots.footer?.(),
      ])
      : null
  },
})

const tableRowKey = Symbol('table-row')

const tableStub = defineComponent({
  name: 'ElTable',
  props: {
    data: { type: Array, default: () => [] },
  },
  setup(props, { slots }) {
    provide(tableRowKey, computed(() => props.data[0]))
    return () => h('div', { class: 'module-table-stub' }, slots.default?.())
  },
})

const tableColumnStub = defineComponent({
  name: 'ElTableColumn',
  setup(_, { slots }) {
    const row = inject<ReturnType<typeof computed>>(tableRowKey)
    return () => h('div', {}, slots.default?.({ row: row?.value }))
  },
})

async function settle() {
  await nextTick()
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
  await nextTick()
}

function catalogWithSelectedModule(): EvidenceQueryCatalog {
  return {
    contractVersion: 'evidence-query-catalog.v1',
    workspaceId: '1',
    sources: [],
    systems: [{
      system: 'CSDP',
      modules: [{
        service: 'csdp-task',
        status: 'BLOCKED',
        runnableContracts: 0,
        blockers: [],
        contracts: [],
        acceptance: {
          status: 'UNAVAILABLE',
          currentBindingFingerprint: null,
          acceptedBy: null,
          acceptedAt: null,
          blockers: [],
        },
      }],
    }],
  } as unknown as EvidenceQueryCatalog
}

function emptyAssets(): ObservabilityAssetCatalog {
  return { workspaceId: '1', assets: [], contracts: [] } as unknown as ObservabilityAssetCatalog
}

function workspaceAssetsWithTool(): ObservabilityAssetCatalog {
  return {
    workspaceId: '1',
    assets: [{
      assetId: 'asset-csdp-task',
      origin: 'WORKSPACE',
      workspaceId: '1',
      system: 'CSDP',
      service: 'csdp-task',
      displayName: 'CSDP Task',
      platform: 'guance',
      environment: 'prd',
      region: null,
      cluster: null,
      namespace: null,
      enabled: true,
      signalBindings: { log_search: 'cti-log-search-v1' },
      parameters: {},
      version: 1,
      changedBy: 'owner',
      reason: '接入 CTI 场景',
      changedAt: '2026-08-10T00:00:00Z',
    }],
    contracts: [{
      contractRef: 'cti-log-search-v1',
      signalKind: 'log_search',
      scenario: 'CTI 创建会话失败',
      question: '是否存在失败日志',
      summary: '查询 CTI 失败日志',
      requiredAssetParameters: [],
    }],
  } as unknown as ObservabilityAssetCatalog
}

function workspaceSystemAsset(): ObservabilityAssetCatalog {
  return {
    workspaceId: '1',
    assets: [{
      assetId: 'asset-csdp-system',
      origin: 'WORKSPACE',
      workspaceId: '1',
      system: 'CSDP',
      service: 'system-scope',
      displayName: '客服数字化',
      platform: 'guance',
      environment: 'prd',
      region: null,
      cluster: null,
      namespace: null,
      enabled: true,
      signalBindings: { error_log_scan: 'generic-error-log-scan-v1' },
      parameters: {},
      version: 1,
      changedBy: 'owner',
      reason: '开通系统级通用只读排障',
      changedAt: '2026-08-25T00:00:00Z',
    }],
    contracts: [],
  } as unknown as ObservabilityAssetCatalog
}

function catalogWithToolAndSource(): EvidenceQueryCatalog {
  const catalog = catalogWithSelectedModule()
  catalog.sources = [{
    platform: 'guance',
    status: 'READY',
    verified: true,
    endpointStatus: 'CONFIGURED',
    credentialStatus: 'CONFIGURED',
    supportedSignals: ['log_search'],
    detail: '真实只读数据源',
  }]
  catalog.systems[0]!.modules[0]!.contracts = [{
    contractRef: 'cti-log-search-v1',
    signalKind: 'log_search',
    scenario: 'CTI 创建会话失败',
    question: '是否存在失败日志',
    summary: '查询 CTI 失败日志',
    adapter: 'guance',
    namespace: 'logging',
    fixedConditions: [],
    endpoint: { operationKind: 'query', method: 'POST', path: '/query', qtype: 'dql' },
    parameters: [],
    canonicalOutputs: ['match_count'],
    budget: {
      queryCount: 1,
      maxRows: 20,
      requestLimit: 1,
      timeoutMs: 5000,
      maxPointCount: null,
      intervalSeconds: null,
      seriesLimit: null,
      alignTime: null,
      disableSampling: null,
      timeZone: null,
    },
    route: {
      origin: 'DEPLOYMENT',
      platforms: ['guance'],
      explicitlyDisabled: false,
      updatedBy: null,
      reason: null,
      updatedAt: null,
    },
    binding: { status: 'READY', bindingRef: 'cti-log', lastObservedAt: null, detail: '已绑定' },
    runnable: true,
    blockers: [],
  }]
  return catalog
}
