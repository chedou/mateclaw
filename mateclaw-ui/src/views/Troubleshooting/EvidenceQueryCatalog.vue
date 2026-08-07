<template>
  <CapabilityWorkspaceShell
    eyebrow="配置与接入"
    :title="TROUBLESHOOTING_UI_LABELS.evidenceCatalog"
    description="只读查看各系统模块已经审核的查询规则：能查什么、要传什么、返回什么、哪里受阻。接入资产、改路由和真源联调不在本页完成。"
    :refresh-loading="loading"
    @back="returnToWorkbench"
    @refresh="loadCatalog"
  >
    <div class="catalog-page" v-loading="loading">
      <el-alert
        v-if="error"
        type="error"
        :closable="false"
        show-icon
        :title="error"
        class="page-alert"
      />

      <div class="toolbar">
        <el-input
          v-model="query"
          clearable
          placeholder="搜索系统、模块、场景或信号"
          class="search-input"
        />
        <el-button @click="openAssets">取证接入</el-button>
        <el-button type="primary" plain @click="openGuanceValidation">数据源联调</el-button>
      </div>

      <section class="summary-strip" aria-label="目录概览">
        <article><span>系统</span><strong>{{ summary.systems }}</strong></article>
        <article><span>模块</span><strong>{{ summary.modules }}</strong></article>
        <article><span>查询规则</span><strong>{{ summary.contracts }}</strong></article>
        <article class="emphasis"><span>可运行</span><strong>{{ summary.runnable }}</strong></article>
      </section>

      <div v-if="filteredModules.length" class="module-workspace">
        <aside class="module-rail" aria-label="系统模块">
          <template v-for="system in filteredModuleTree" :key="system.system">
            <div class="tree-system">{{ system.system }}</div>
            <button
              v-for="module in system.modules"
              :key="`${system.system}/${module.service}`"
              type="button"
              class="module-item"
              :class="{ active: selectedModuleKey === moduleKey(system.system, module.service) }"
              @click="selectModule(system.system, module.service)"
            >
              <span>{{ module.service }}</span>
              <el-tag
                size="small"
                :type="module.status === 'READY' ? 'success' : module.status === 'PARTIAL' ? 'warning' : 'danger'"
              >{{ module.runnableContracts }}/{{ module.contracts.length }}</el-tag>
            </button>
          </template>
        </aside>

        <section v-if="selectedModule" class="module-panel">
          <header class="module-panel-head">
            <div>
              <p class="scope-line">{{ selectedModule.system }}</p>
              <h2>{{ selectedModule.module.service }}</h2>
              <p>
                {{ selectedModule.module.runnableContracts }}/{{ selectedModule.module.contracts.length }}
                条查询规则当前可运行
              </p>
            </div>
          </header>

          <el-alert
            v-if="selectedNextAction.code !== 'READY'"
            :type="selectedNextAction.code === 'FIX_BLOCKERS' || selectedNextAction.code === 'SOURCE_DOWN' ? 'warning' : 'info'"
            :closable="false"
            show-icon
            class="next-alert"
            :title="selectedNextAction.title"
          >
            <div class="next-alert-body">
              <p>{{ selectedNextAction.detail }}</p>
              <div class="next-alert-actions">
                <el-button
                  v-if="selectedNextAction.primaryCta === 'asset' || selectedNextAction.code === 'FIX_BLOCKERS'"
                  type="primary"
                  size="small"
                  @click="openAssetsForModule"
                >去取证接入</el-button>
                <el-button
                  v-if="selectedNextAction.primaryCta === 'acceptance' || selectedNextAction.code === 'SOURCE_DOWN'"
                  type="primary"
                  size="small"
                  :plain="selectedNextAction.primaryCta !== 'acceptance'"
                  @click="openGuanceValidation"
                >去数据源联调</el-button>
              </div>
            </div>
          </el-alert>

          <section class="rules-section">
            <div class="section-heading">
              <h3>查询规则</h3>
              <small>只读核对；试跑与改路由请到取证接入</small>
            </div>
            <div class="rule-list">
              <article
                v-for="row in selectedModuleRows"
                :key="contractKey(row.system, row.service, row.contract)"
                class="rule-card"
                :class="{ active: selectedKey === contractKey(row.system, row.service, row.contract) }"
              >
                <button
                  type="button"
                  class="rule-card-main"
                  @click="selectedKey = contractKey(row.system, row.service, row.contract)"
                >
                  <div>
                    <b>{{ row.contract.scenario }}</b>
                    <p>{{ row.contract.question }}</p>
                    <code>{{ row.contract.signalKind }}</code>
                  </div>
                  <el-tag :type="row.contract.runnable ? 'success' : 'danger'" size="small">
                    {{ row.contract.runnable ? '可运行' : '受阻' }}
                  </el-tag>
                </button>

                <div
                  v-if="selectedKey === contractKey(row.system, row.service, row.contract)"
                  class="rule-detail"
                >
                  <div class="axis-grid">
                    <article>
                      <span>路由</span>
                      <strong>{{ routeOriginLabel(row.contract.route.origin) }}</strong>
                      <small>{{ row.contract.route.platforms.join(' → ') || '未选择适配器' }}</small>
                    </article>
                    <article>
                      <span>绑定</span>
                      <strong>{{ bindingStatusLabel(row.contract.binding.status) }}</strong>
                      <small>{{ row.contract.binding.bindingRef || '未绑定' }}</small>
                    </article>
                    <article>
                      <span>预算</span>
                      <strong>{{ row.contract.budget.queryCount }} 查询 · {{ row.contract.budget.maxRows }} 行</strong>
                      <small>超时 {{ formatDuration(row.contract.budget.timeoutMs) }}</small>
                    </article>
                  </div>

                  <div v-if="row.contract.blockers.length" class="blocker-box">
                    <b>阻断点</b>
                    <ul>
                      <li v-for="blocker in row.contract.blockers" :key="blocker">{{ blocker }}</li>
                    </ul>
                  </div>

                  <div class="detail-grid">
                    <section class="detail-card parameter-card">
                      <div class="card-title"><small>INPUT</small><h3>需要传什么</h3></div>
                      <el-table :data="row.contract.parameters" size="small">
                        <el-table-column prop="name" label="参数" min-width="120" />
                        <el-table-column label="来源" min-width="140">
                          <template #default="scope">{{ parameterSourceLabel(scope.row.source) }}</template>
                        </el-table-column>
                        <el-table-column label="要求" width="72">
                          <template #default="scope">{{ scope.row.required ? '必填' : '可兜底' }}</template>
                        </el-table-column>
                        <el-table-column prop="description" label="说明" min-width="180" />
                      </el-table>
                    </section>
                    <section class="detail-card">
                      <div class="card-title"><small>OUTPUT</small><h3>规范返回</h3></div>
                      <div class="tag-list">
                        <el-tag
                          v-for="field in row.contract.canonicalOutputs"
                          :key="field"
                          effect="plain"
                        >{{ field }}</el-tag>
                      </div>
                    </section>
                    <section class="detail-card">
                      <div class="card-title"><small>FIXED</small><h3>服务端固定条件</h3></div>
                      <div class="tag-list">
                        <el-tag
                          v-for="condition in row.contract.fixedConditions"
                          :key="condition"
                          type="info"
                          effect="plain"
                        >{{ condition }}</el-tag>
                        <span v-if="!row.contract.fixedConditions.length" class="empty-inline">未记录</span>
                      </div>
                    </section>
                  </div>
                </div>
              </article>
            </div>
          </section>

          <el-alert
            class="boundary-note"
            type="info"
            :closable="false"
            title="目录只展示规则摘要和脱敏轮廓；API Key、端点主机、原始 DQL 和原始日志仍由服务端保管。"
          />
        </section>
      </div>
      <el-empty v-else description="当前 Workspace 没有匹配的系统模块">
        <el-button type="primary" @click="openAssets">去取证接入</el-button>
      </el-empty>
    </div>
  </CapabilityWorkspaceShell>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { vLoading } from 'element-plus/es/components/loading/index'
import {
  troubleshootingApi,
  type EvidenceCatalogModule,
  type EvidenceQueryCatalog,
  type EvidenceQueryContract,
} from '@/api'
import CapabilityWorkspaceShell from './CapabilityWorkspaceShell.vue'
import {
  bindingStatusLabel,
  catalogSummary,
  contractMatches,
  moduleNextAction,
  parameterSourceLabel,
  routeOriginLabel,
} from './evidenceCatalog'
import {
  normalizeEvidenceCatalogTab,
  observabilityAssetsLocation,
  safeTroubleshootingReturnPath,
  workbenchOverlayLocation,
} from './workbenchCapabilityMenu'
import { TROUBLESHOOTING_UI_LABELS } from './workbenchView'

type ContractRow = {
  system: string
  service: string
  module: EvidenceCatalogModule
  contract: EvidenceQueryContract
}

const route = useRoute()
const router = useRouter()
const catalog = ref<EvidenceQueryCatalog | null>(null)
const assetOrigins = ref<Map<string, 'WORKSPACE' | 'DEPLOYMENT' | null>>(new Map())
const loading = ref(false)
const error = ref('')
const query = ref('')
const selectedModuleKey = ref('')
const selectedKey = ref('')

const allRows = computed<ContractRow[]>(() => (catalog.value?.systems || []).flatMap(system =>
  system.modules.flatMap(module => module.contracts.map(contract => ({
    system: system.system,
    service: module.service,
    module,
    contract,
  })))))

const filteredRows = computed(() => allRows.value.filter(
  row => contractMatches(row.contract, query.value)
    || row.system.toLowerCase().includes(query.value.trim().toLowerCase())
    || row.service.toLowerCase().includes(query.value.trim().toLowerCase()),
))

const filteredModules = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  return (catalog.value?.systems || []).flatMap(system =>
    system.modules
      .filter(module => {
        if (!keyword) return true
        if (system.system.toLowerCase().includes(keyword)
          || module.service.toLowerCase().includes(keyword)) return true
        return module.contracts.some(contract => contractMatches(contract, query.value))
      })
      .map(module => ({ system: system.system, module })),
  )
})

const filteredModuleTree = computed(() => {
  const grouped = new Map<string, EvidenceCatalogModule[]>()
  for (const row of filteredModules.value) {
    const modules = grouped.get(row.system) || []
    modules.push(row.module)
    grouped.set(row.system, modules)
  }
  return [...grouped.entries()].map(([system, modules]) => ({ system, modules }))
})

const selectedModule = computed(() => filteredModules.value.find(row =>
  moduleKey(row.system, row.module.service) === selectedModuleKey.value)
  || filteredModules.value[0]
  || null)

const selectedModuleRows = computed(() => {
  const current = selectedModule.value
  if (!current) return []
  return filteredRows.value.filter(row =>
    row.system === current.system && row.service === current.module.service)
})

const summary = computed(() => catalogSummary(catalog.value))
const sourceReady = computed(() => (catalog.value?.sources || []).some(source =>
  source.status === 'READY'))

const selectedModuleAsset = computed(() => {
  const current = selectedModule.value
  if (!current) return null
  const key = moduleKey(current.system, current.module.service).toLowerCase()
  const origin = assetOrigins.value.get(key)
  if (!origin) return null
  return {
    origin,
    enabled: true,
    system: current.system,
    service: current.module.service,
  }
})

const selectedNextAction = computed(() => {
  const current = selectedModule.value
  if (!current) {
    return moduleNextAction(
      {
        service: '',
        status: 'BLOCKED',
        runnableContracts: 0,
        blockers: [],
        acceptance: {
          status: 'UNAVAILABLE',
          currentBindingFingerprint: null,
          acceptedBy: null,
          acceptedAt: null,
          blockers: [],
        },
        contracts: [],
      },
      null,
      false,
    )
  }
  const asset = selectedModuleAsset.value
  return moduleNextAction(
    current.module,
    asset ? {
      assetId: null,
      origin: asset.origin,
      workspaceId: catalog.value?.workspaceId || 0,
      system: asset.system,
      service: asset.service,
      displayName: asset.service,
      platform: 'guance',
      environment: null,
      region: null,
      cluster: null,
      namespace: null,
      enabled: true,
      signalBindings: {},
      parameters: {},
      version: asset.origin === 'WORKSPACE' ? 1 : 0,
      changedBy: null,
      reason: '',
      changedAt: null,
    } : null,
    sourceReady.value,
  )
})

function moduleKey(system: string, service: string) {
  return `${system}/${service}`
}

function contractKey(system: string, service: string, contract: EvidenceQueryContract) {
  return `${system}/${service}/${contract.signalKind}/${contract.contractRef}`
}

function selectModule(system: string, service: string) {
  selectedModuleKey.value = moduleKey(system, service)
  const first = filteredRows.value.find(row => row.system === system && row.service === service)
  if (first) selectedKey.value = contractKey(first.system, first.service, first.contract)
}

function formatDuration(milliseconds: number) {
  if (milliseconds >= 1000) return `${milliseconds / 1000} 秒`
  return `${milliseconds} 毫秒`
}

function returnToWorkbench() {
  void router.push(safeTroubleshootingReturnPath(route.query.returnTo) || '/troubleshooting')
}

function openAssets() {
  void router.push(observabilityAssetsLocation(undefined, route.fullPath))
}

function openAssetsForModule() {
  const current = selectedModule.value
  void router.push(observabilityAssetsLocation(
    current ? { system: current.system, service: current.module.service } : undefined,
    route.fullPath,
  ))
}

function openGuanceValidation() {
  const returnTo = safeTroubleshootingReturnPath(route.query.returnTo)
    || '/troubleshooting?view=list'
  void router.push(workbenchOverlayLocation('guance', returnTo))
}

async function loadCatalog() {
  loading.value = true
  error.value = ''
  try {
    const [catalogResponse, assetResponse] = await Promise.all([
      troubleshootingApi.evidenceCatalog(),
      troubleshootingApi.observabilityAssets(),
    ])
    catalog.value = catalogResponse.data
    const origins = new Map<string, 'WORKSPACE' | 'DEPLOYMENT' | null>()
    for (const asset of assetResponse.data.assets || []) {
      origins.set(moduleKey(asset.system, asset.service).toLowerCase(), asset.origin)
    }
    assetOrigins.value = origins
    if (filteredModules.value.length) {
      const stillVisible = filteredModules.value.some(row =>
        moduleKey(row.system, row.module.service) === selectedModuleKey.value)
      if (!stillVisible) {
        const first = filteredModules.value[0]
        selectModule(first.system, first.module.service)
      } else if (selectedModuleRows.value.length && !selectedModuleRows.value.some(row =>
        contractKey(row.system, row.service, row.contract) === selectedKey.value)) {
        const firstRule = selectedModuleRows.value[0]
        selectedKey.value = contractKey(firstRule.system, firstRule.service, firstRule.contract)
      }
    }
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '查询规则说明书加载失败'
  } finally {
    loading.value = false
  }
}

watch(() => route.query.tab, value => {
  const tab = normalizeEvidenceCatalogTab(value)
  if (tab === 'assets' || tab === 'routes') {
    void router.replace(observabilityAssetsLocation(
      undefined,
      safeTroubleshootingReturnPath(route.query.returnTo) || undefined,
    ))
    return
  }
  if (tab === 'acceptance') {
    openGuanceValidation()
  }
}, { immediate: true })

onMounted(loadCatalog)
</script>

<style scoped>
.catalog-page { display: grid; gap: 16px; }
.page-alert { margin-bottom: 2px; }
.toolbar { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
.search-input { flex: 1; min-width: min(100%, 280px); }
.summary-strip { display: grid; grid-template-columns: repeat(4, minmax(120px, 1fr)); gap: 10px; }
.summary-strip article {
  display: flex; align-items: baseline; justify-content: space-between;
  padding: 14px 16px; border: 1px solid var(--mc-border-light); border-radius: 12px; background: var(--mc-bg-elevated);
}
.summary-strip span { color: var(--mc-text-secondary); font-size: 12px; }
.summary-strip strong { font-size: 22px; font-variant-numeric: tabular-nums; }
.summary-strip .emphasis { border-color: color-mix(in srgb, var(--mc-primary) 35%, var(--mc-border)); }
.summary-strip .emphasis strong { color: var(--mc-primary); }
.module-workspace {
  display: grid; grid-template-columns: minmax(220px, 280px) minmax(0, 1fr); min-height: 560px;
  border: 1px solid var(--mc-border-light); border-radius: 14px; overflow: hidden; background: var(--mc-bg-elevated);
}
.module-rail { padding: 14px 10px; border-right: 1px solid var(--mc-border-light); background: var(--mc-bg-muted); overflow: auto; }
.tree-system { padding: 8px 10px; font-size: 12px; font-weight: 800; letter-spacing: .04em; }
.module-item {
  display: flex; align-items: center; justify-content: space-between; gap: 10px; width: 100%;
  margin: 2px 0; padding: 11px 12px; border: 0; border-radius: 10px; color: inherit; background: transparent;
  text-align: left; cursor: pointer;
}
.module-item:hover { background: var(--mc-bg); }
.module-item.active {
  color: var(--mc-primary); background: var(--mc-primary-bg);
  box-shadow: inset 3px 0 var(--mc-primary);
}
.module-item span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; font-weight: 650; }
.module-panel { min-width: 0; padding: 20px; overflow: auto; }
.module-panel-head { margin-bottom: 14px; }
.module-panel-head h2 { margin: 4px 0 6px; font-size: 22px; }
.module-panel-head p { margin: 0; color: var(--mc-text-secondary); }
.scope-line { margin: 0; color: var(--mc-primary); font: 12px ui-monospace, SFMono-Regular, Menlo, monospace; }
.next-alert { margin-bottom: 16px; }
.next-alert-body { display: grid; gap: 10px; }
.next-alert-body p { margin: 0; color: var(--mc-text-secondary); font-size: 13px; line-height: 1.55; }
.next-alert-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.section-heading { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.section-heading h3 { margin: 0; font-size: 15px; }
.section-heading small { color: var(--mc-text-secondary); }
.rule-list { display: grid; gap: 10px; }
.rule-card { border: 1px solid var(--mc-border-light); border-radius: 12px; overflow: hidden; }
.rule-card.active { border-color: color-mix(in srgb, var(--mc-primary) 45%, var(--mc-border)); }
.rule-card-main {
  display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; width: 100%;
  padding: 14px 16px; border: 0; color: inherit; background: transparent; text-align: left; cursor: pointer;
}
.rule-card-main b { display: block; margin-bottom: 4px; }
.rule-card-main p { margin: 0 0 8px; color: var(--mc-text-secondary); font-size: 12px; line-height: 1.5; }
.rule-card-main code { color: var(--mc-primary); font-size: 11px; }
.rule-detail { padding: 0 16px 16px; border-top: 1px solid var(--mc-border-light); }
.axis-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin: 14px 0; }
.axis-grid article, .detail-card {
  display: flex; flex-direction: column; min-width: 0; padding: 14px; border-radius: 10px; background: var(--mc-bg-muted);
}
.axis-grid span, .card-title small { color: var(--mc-text-secondary); font-size: 11px; }
.axis-grid strong { margin-top: 6px; font-size: 14px; }
.axis-grid small, .empty-inline { margin-top: 5px; color: var(--mc-text-secondary); overflow-wrap: anywhere; }
.blocker-box {
  padding: 12px 14px; border: 1px solid color-mix(in srgb, #b93838 30%, var(--mc-border));
  border-radius: 10px; color: #b93838; background: color-mix(in srgb, #b93838 6%, var(--mc-bg));
}
.blocker-box ul { margin: 8px 0 0; padding-left: 18px; line-height: 1.6; }
.detail-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; margin-top: 12px; }
.parameter-card { grid-column: 1 / -1; }
.card-title { margin-bottom: 10px; }
.card-title h3 { margin: 3px 0 0; font-size: 14px; }
.tag-list { display: flex; flex-wrap: wrap; gap: 8px; }
.boundary-note { margin-top: 16px; }
@media (max-width: 960px) {
  .summary-strip, .module-workspace, .axis-grid, .detail-grid { grid-template-columns: 1fr; }
  .module-rail { max-height: 240px; border-right: 0; border-bottom: 1px solid var(--mc-border-light); }
  .parameter-card { grid-column: auto; overflow-x: auto; }
}
</style>
