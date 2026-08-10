import { describe, expect, it, vi } from 'vitest'
import { computed, createApp, defineComponent, h, inject, nextTick, provide } from 'vue'
import type { EvidenceQueryCatalog, ObservabilityAssetCatalog } from '@/api'

const evidenceCatalog = vi.fn()
const observabilityAssets = vi.fn()
const routeState = {
  path: '/troubleshooting/observability-assets',
  query: { section: 'modules' },
  fullPath: '/troubleshooting/observability-assets?section=modules',
}

vi.mock('@/api', () => ({
  troubleshootingApi: {
    evidenceCatalog: () => evidenceCatalog(),
    observabilityAssets: () => observabilityAssets(),
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
  useRouter: () => ({ push: vi.fn(), resolve: () => ({ fullPath: '/troubleshooting' }) }),
}))

describe('observability asset create flow', () => {
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
    app.component('ElTable', tableStub)
    app.component('ElTableColumn', tableColumnStub)
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

  it('adds a module under the selected system without reusing the selected service', async () => {
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
    app.component('ElTable', tableStub)
    app.component('ElTableColumn', tableColumnStub)
    app.mount(host)
    await settle()

    const createButton = [...host.querySelectorAll('button')]
      .find(button => button.textContent?.trim() === '新增模块')
    expect(createButton).toBeTruthy()
    createButton!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await nextTick()

    const systemChoice = [...host.querySelectorAll('button')]
      .find(button => button.textContent?.includes('CSDP'))
    expect(systemChoice).toBeTruthy()
    systemChoice!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await nextTick()

    const systemInput = host.querySelector<HTMLInputElement>('input[placeholder="例如 CSDP"]')
    const serviceInput = host.querySelector<HTMLInputElement>('input[placeholder="例如 csdp-session-service"]')
    expect(systemInput?.value).toBe('CSDP')
    expect(systemInput?.disabled).toBe(true)
    expect(serviceInput?.value).toBe('')
    expect(serviceInput?.disabled).toBe(false)

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
      app.component('ElTable', tableStub)
      app.component('ElTableColumn', tableColumnStub)
      app.mount(host)
      await settle()

      expect(host.querySelector(`.${section === 'tools' ? 'tool' : 'source'}-list-workspace`)).toBeTruthy()
      expect(host.querySelector('.assets-workspace')).toBeNull()

      app.unmount()
      host.remove()
    }
  })
})

const buttonStub = defineComponent({
  name: 'ElButton',
  emits: ['click'],
  setup(_, { emit, slots }) {
    return () => h('button', { type: 'button', onClick: () => emit('click') }, slots.default?.())
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
