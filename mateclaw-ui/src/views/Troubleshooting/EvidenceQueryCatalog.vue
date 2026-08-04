<template>
  <div class="catalog-page">
    <div class="catalog-layout">
      <aside class="catalog-subnav" :class="{ collapsed: catalogNavCompact }">
        <div v-if="!catalogNavCompact" class="subnav-intro">
          <p>取证能力</p>
          <h2>取证查询目录</h2>
        </div>
        <nav aria-label="取证查询目录工作区">
          <el-tooltip
            v-for="destination in EVIDENCE_CATALOG_DESTINATIONS"
            :key="destination.tab"
            :content="destination.label"
            placement="right"
            :disabled="!catalogNavCompact"
          >
            <button
              type="button"
              class="subnav-item"
              :class="{ active: activeTab === destination.tab }"
              :aria-current="activeTab === destination.tab ? 'page' : undefined"
              @click="activeTab = destination.tab"
            >
              <el-icon class="subnav-icon">
                <component :is="DESTINATION_ICONS[destination.tab]" />
              </el-icon>
              <span v-if="!catalogNavCompact" class="subnav-label">{{ destination.label }}</span>
              <small v-if="!catalogNavCompact && destination.badge">{{ destination.badge }}</small>
            </button>
          </el-tooltip>
        </nav>
        <button
          type="button"
          class="subnav-collapse"
          :class="{ 'mobile-hidden': forcedRailViewport }"
          :title="navCollapsed ? '展开二级菜单' : '折叠二级菜单'"
          :aria-label="navCollapsed ? '展开二级菜单' : '折叠二级菜单'"
          @click="toggleCatalogNav"
        >
          <svg v-if="!navCollapsed" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6" /></svg>
          <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6" /></svg>
        </button>
      </aside>

      <section class="catalog-content">
    <header class="catalog-header">
      <div class="header-copy">
        <el-button text class="back-button" @click="returnToWorkbench">
          ← 返回智能排障
        </el-button>
        <div class="title-row">
          <div>
            <p class="eyebrow">运行与治理</p>
            <h1>取证查询目录</h1>
          </div>
          <el-tag effect="plain" type="info">服务端审核合同</el-tag>
        </div>
        <p class="subtitle">
          按系统和模块查看“要查什么、传什么、从哪里来、返回什么”，
          并维护 Workspace 层的只读取证路由。
        </p>
      </div>
      <div class="header-actions">
        <el-input
          v-model="query"
          clearable
          placeholder="搜索场景、信号、合同或输出字段"
          class="search-input"
        />
        <el-button :loading="loading" @click="loadCatalog">刷新目录</el-button>
      </div>
    </header>

    <main class="catalog-main" v-loading="loading">
      <el-alert
        v-if="error"
        type="error"
        :closable="false"
        show-icon
        :title="error"
      />

      <section class="summary-strip" aria-label="目录概览">
        <article class="summary-card">
          <span>系统</span><strong>{{ summary.systems }}</strong>
        </article>
        <article class="summary-card">
          <span>模块</span><strong>{{ summary.modules }}</strong>
        </article>
        <article class="summary-card">
          <span>查询合同</span><strong>{{ summary.contracts }}</strong>
        </article>
        <article class="summary-card emphasis">
          <span>当前可运行</span><strong>{{ summary.runnable }}</strong>
        </article>
      </section>

      <el-tabs v-model="activeTab" class="catalog-tabs sidebar-controlled-tabs">
        <el-tab-pane label="系统与模块" name="systems">
          <div v-if="filteredRows.length" class="system-workspace">
            <aside class="catalog-tree">
              <template v-for="system in filteredTree" :key="system.system">
                <div class="tree-system">{{ system.system }}</div>
                <template v-for="module in system.modules" :key="`${system.system}/${module.service}`">
                  <div class="tree-module">
                    <span>{{ module.service }}</span>
                    <el-tag
                      size="small"
                      :type="module.status === 'READY' ? 'success' : module.status === 'PARTIAL' ? 'warning' : 'danger'"
                    >{{ module.runnableContracts }}/{{ module.contracts.length }}</el-tag>
                  </div>
                  <button
                    v-for="contract in module.contracts"
                    :key="contractKey(system.system, module.service, contract)"
                    type="button"
                    class="tree-contract"
                    :class="{ active: selectedKey === contractKey(system.system, module.service, contract) }"
                    @click="selectedKey = contractKey(system.system, module.service, contract)"
                  >
                    <span>{{ contract.scenario }}</span>
                    <small>{{ contract.signalKind }}</small>
                  </button>
                </template>
              </template>
            </aside>

            <section v-if="selectedRow" class="contract-inspector">
              <div class="inspector-heading">
                <div>
                  <p class="scope-line">
                    {{ selectedRow.system }} / {{ selectedRow.service }} / {{ selectedRow.contract.signalKind }}
                  </p>
                  <h2>{{ selectedRow.contract.scenario }}</h2>
                  <p>{{ selectedRow.contract.question }}</p>
                </div>
                <el-tag :type="selectedRow.contract.runnable ? 'success' : 'danger'">
                  {{ selectedRow.contract.runnable ? '可运行' : '暂不可运行' }}
                </el-tag>
              </div>

              <div class="axis-grid">
                <article>
                  <span>路由来源</span>
                  <strong>{{ routeOriginLabel(selectedRow.contract.route.origin) }}</strong>
                  <small>{{ selectedRow.contract.route.platforms.join(' → ') || '未选择适配器' }}</small>
                </article>
                <article>
                  <span>查询绑定</span>
                  <strong>{{ bindingStatusLabel(selectedRow.contract.binding.status) }}</strong>
                  <small>{{ selectedRow.contract.binding.bindingRef || '未绑定' }}</small>
                </article>
                <article>
                  <span>调用方式</span>
                  <strong>{{ selectedRow.contract.endpoint.method }} · {{ selectedRow.contract.endpoint.qtype }}</strong>
                  <small>{{ selectedRow.contract.endpoint.path }}</small>
                </article>
                <article>
                  <span>请求预算</span>
                  <strong>{{ selectedRow.contract.budget.queryCount }} 个查询 · {{ selectedRow.contract.budget.maxRows }} 行</strong>
                  <small>超时 {{ formatDuration(selectedRow.contract.budget.timeoutMs) }}</small>
                </article>
              </div>

              <div v-if="selectedRow.contract.blockers.length" class="blocker-box">
                <b>当前阻断点</b>
                <ul>
                  <li v-for="blocker in selectedRow.contract.blockers" :key="blocker">{{ blocker }}</li>
                </ul>
              </div>

              <div class="detail-grid">
                <section class="detail-card parameter-card">
                  <div class="card-title">
                    <div><small>INPUT</small><h3>需要传什么</h3></div>
                    <el-tag size="small" effect="plain">{{ selectedRow.contract.parameters.length }} 项</el-tag>
                  </div>
                  <el-table :data="selectedRow.contract.parameters" size="small">
                    <el-table-column prop="name" label="参数" min-width="130" />
                    <el-table-column label="来源" min-width="150">
                      <template #default="scope">{{ parameterSourceLabel(scope.row.source) }}</template>
                    </el-table-column>
                    <el-table-column label="要求" width="72">
                      <template #default="scope">{{ scope.row.required ? '必填' : '可兜底' }}</template>
                    </el-table-column>
                    <el-table-column prop="description" label="说明" min-width="220" />
                  </el-table>
                </section>

                <section class="detail-card">
                  <div class="card-title"><div><small>FIXED</small><h3>服务端固定条件</h3></div></div>
                  <div class="tag-list">
                    <el-tag
                      v-for="condition in selectedRow.contract.fixedConditions"
                      :key="condition"
                      type="info"
                      effect="plain"
                    >{{ condition }}</el-tag>
                    <span v-if="!selectedRow.contract.fixedConditions.length" class="empty-inline">未记录展示摘要</span>
                  </div>
                </section>

                <section class="detail-card">
                  <div class="card-title"><div><small>OUTPUT</small><h3>返回的规范证据</h3></div></div>
                  <div class="tag-list output-tags">
                    <el-tag
                      v-for="field in selectedRow.contract.canonicalOutputs"
                      :key="field"
                      effect="plain"
                    >{{ field }}</el-tag>
                  </div>
                </section>
              </div>

              <el-alert
                class="boundary-note"
                type="info"
                :closable="false"
                title="目录展示合同元数据和脱敏请求轮廓；API Key、端点主机、原始 DQL 和原始日志仍由服务端保管。"
              />
            </section>
          </div>
          <el-empty v-else description="当前 Workspace 没有匹配的取证查询合同" />
        </el-tab-pane>

        <el-tab-pane label="查询合同" name="contracts">
          <el-table :data="filteredRows" class="catalog-table" stripe>
            <el-table-column prop="system" label="系统" min-width="120" />
            <el-table-column prop="service" label="模块" min-width="170" />
            <el-table-column label="场景 / 要回答的问题" min-width="310">
              <template #default="scope">
                <b>{{ scope.row.contract.scenario }}</b>
                <small class="table-note">{{ scope.row.contract.question }}</small>
              </template>
            </el-table-column>
            <el-table-column label="证据维度" min-width="150">
              <template #default="scope"><code>{{ scope.row.contract.signalKind }}</code></template>
            </el-table-column>
            <el-table-column label="接口" min-width="210">
              <template #default="scope">
                {{ scope.row.contract.endpoint.method }} {{ scope.row.contract.endpoint.path }}
                <small class="table-note">{{ scope.row.contract.endpoint.operationKind }}</small>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="105">
              <template #default="scope">
                <el-tag :type="scope.row.contract.runnable ? 'success' : 'danger'">
                  {{ scope.row.contract.runnable ? '可运行' : '受阻' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90" fixed="right">
              <template #default="scope">
                <el-button text type="primary" @click="inspectRow(scope.row)">查看</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="路由与绑定" name="routes">
          <el-alert
            type="warning"
            :closable="false"
            title="路由以“系统 + 证据维度”生效；修改会影响该系统下使用同一维度的所有模块。"
            class="tab-alert"
          />
          <el-table :data="filteredRows" class="catalog-table" stripe>
            <el-table-column prop="system" label="系统" min-width="120" />
            <el-table-column prop="service" label="模块" min-width="170" />
            <el-table-column label="证据维度" min-width="150">
              <template #default="scope"><code>{{ scope.row.contract.signalKind }}</code></template>
            </el-table-column>
            <el-table-column label="有效路由" min-width="210">
              <template #default="scope">
                <el-tag size="small" effect="plain">
                  {{ routeOriginLabel(scope.row.contract.route.origin) }}
                </el-tag>
                <span class="route-platforms">
                  {{ scope.row.contract.route.platforms.join(' → ') || '明确不取证' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="查询绑定" min-width="240">
              <template #default="scope">
                <b>{{ scope.row.contract.binding.bindingRef || '未绑定' }}</b>
                <small class="table-note">{{ bindingStatusLabel(scope.row.contract.binding.status) }}</small>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="190" fixed="right">
              <template #default="scope">
                <el-button text type="primary" @click="openRouteEditor(scope.row)">修改路由</el-button>
                <el-button
                  v-if="scope.row.contract.route.origin === 'WORKSPACE'"
                  text
                  @click="withdrawRoute(scope.row)"
                >恢复默认</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="联调与验收" name="acceptance">
          <section class="acceptance-layout">
            <div class="source-panel">
              <h3>运行时适配器</h3>
              <article v-for="source in catalog?.sources || []" :key="source.platform" class="source-card">
                <div>
                  <b>{{ source.platform }}</b>
                  <p>{{ source.detail }}</p>
                  <dl class="source-runtime-grid">
                    <div><dt>查询端点</dt><dd>{{ runtimeStateLabel(source.endpointStatus) }}</dd></div>
                    <div><dt>运行凭据</dt><dd>{{ runtimeStateLabel(source.credentialStatus) }}</dd></div>
                    <div class="source-signals">
                      <dt>支持证据</dt>
                      <dd>{{ source.supportedSignals.join('、') || '未报告' }}</dd>
                    </div>
                  </dl>
                </div>
                <el-tag :type="source.status === 'READY' ? 'success' : source.status === 'DISABLED' ? 'info' : 'warning'">
                  {{ source.status }}
                </el-tag>
              </article>
            </div>
            <div class="acceptance-panel">
              <h3>模块验收状态</h3>
              <article v-for="row in moduleRows" :key="`${row.system}/${row.module.service}`" class="acceptance-card">
                <div class="acceptance-title">
                  <div><b>{{ row.system }} / {{ row.module.service }}</b><small>{{ row.module.runnableContracts }}/{{ row.module.contracts.length }} 个合同可运行</small></div>
                  <el-tag :type="row.module.acceptance.status === 'ACCEPTED' ? 'success' : 'warning'">
                    {{ acceptanceStatusLabel(row.module.acceptance.status) }}
                  </el-tag>
                </div>
                <ul v-if="row.module.acceptance.blockers.length">
                  <li v-for="blocker in row.module.acceptance.blockers" :key="blocker">{{ blocker }}</li>
                </ul>
                <small v-if="row.module.acceptance.acceptedBy">
                  {{ row.module.acceptance.acceptedBy }} · {{ row.module.acceptance.acceptedAt || '未记录时间' }}
                </small>
              </article>
              <el-button type="primary" plain @click="returnToWorkbench">
                返回排障台执行只读联调
              </el-button>
            </div>
          </section>
        </el-tab-pane>
      </el-tabs>
    </main>
      </section>
    </div>

    <el-dialog v-model="routeDialogOpen" title="修改 Workspace 取证路由" width="560px">
      <template v-if="routeTarget">
        <div class="route-scope">
          <span>作用域</span>
          <b>{{ routeTarget.system }} / {{ routeTarget.contract.signalKind }}</b>
          <small>该声明优先于部署默认路由</small>
        </div>
        <el-form label-position="top">
          <el-form-item label="按顺序选择适配器">
            <el-checkbox-group v-model="routePlatforms" class="source-checkboxes">
              <el-checkbox
                v-for="source in catalog?.sources || []"
                :key="source.platform"
                :value="source.platform"
              >
                {{ source.platform }}
                <small>{{ source.status }}</small>
              </el-checkbox>
            </el-checkbox-group>
            <p class="form-help">
              这里只会出现当前部署已装配的适配器；可以预先声明暂不可用来源，
              但目录会继续标记为不可运行。全部不选表示“该维度明确不取证”，不会回落到部署默认。
            </p>
            <div v-if="routePlatforms.length" class="route-priority">
              <p>调用顺序（前面的来源优先）</p>
              <div v-for="(platform, index) in routePlatforms" :key="platform" class="priority-row">
                <em>{{ index + 1 }}</em>
                <b>{{ platform }}</b>
                <el-button
                  text
                  :disabled="index === 0"
                  @click="moveRoutePlatform(index, -1)"
                >上移</el-button>
                <el-button
                  text
                  :disabled="index === routePlatforms.length - 1"
                  @click="moveRoutePlatform(index, 1)"
                >下移</el-button>
              </div>
            </div>
          </el-form-item>
          <el-form-item label="变更原因">
            <el-input
              v-model="routeReason"
              type="textarea"
              :rows="3"
              maxlength="500"
              show-word-limit
              placeholder="说明为什么要修改生产取证路由"
            />
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="routeDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="routeSaving" @click="saveRoute">保存声明</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { vLoading } from 'element-plus/es/components/loading/index'
import { Connection, DataLine, Document, Select } from '@element-plus/icons-vue'
import { BREAKPOINTS, useMediaQuery } from '@/composables/useBreakpoint'
import {
  troubleshootingApi,
  type EvidenceCatalogModule,
  type EvidenceQueryCatalog,
  type EvidenceQueryContract,
} from '@/api'
import {
  acceptanceStatusLabel,
  bindingStatusLabel,
  catalogSummary,
  contractMatches,
  parameterSourceLabel,
  routeOriginLabel,
  runtimeStateLabel,
  moveOrderedItem,
} from './evidenceCatalog'
import {
  EVIDENCE_CATALOG_DESTINATIONS,
  normalizeEvidenceCatalogTab,
  safeTroubleshootingReturnPath,
  type EvidenceCatalogTab,
} from './workbenchCapabilityMenu'

type ContractRow = {
  system: string
  service: string
  module: EvidenceCatalogModule
  contract: EvidenceQueryContract
}

const route = useRoute()
const router = useRouter()
const storedNavPreference = localStorage.getItem('mc-evidence-catalog-nav-collapsed')
const navCollapsed = ref(storedNavPreference === 'true')
const navUserExplicit = ref(storedNavPreference !== null)
const compactViewport = useMediaQuery(BREAKPOINTS.compact)
const forcedRailViewport = useMediaQuery('(max-width: 900px)')
const catalogNavCompact = computed(() => navCollapsed.value || forcedRailViewport.value)
const DESTINATION_ICONS = {
  systems: DataLine,
  contracts: Document,
  routes: Connection,
  acceptance: Select,
} as const
const catalog = ref<EvidenceQueryCatalog | null>(null)
const loading = ref(false)
const error = ref('')
const query = ref('')
const activeTab = ref<EvidenceCatalogTab>(normalizeEvidenceCatalogTab(route.query.tab))
const selectedKey = ref('')
const routeDialogOpen = ref(false)
const routeSaving = ref(false)
const routeTarget = ref<ContractRow | null>(null)
const routePlatforms = ref<string[]>([])
const routeReason = ref('')

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
const filteredTree = computed(() => {
  const allowed = new Set(filteredRows.value.map(row => contractKey(
    row.system, row.service, row.contract,
  )))
  return (catalog.value?.systems || []).map(system => ({
    ...system,
    modules: system.modules.map(module => ({
      ...module,
      contracts: module.contracts.filter(contract => allowed.has(
        contractKey(system.system, module.service, contract),
      )),
    })).filter(module => module.contracts.length),
  })).filter(system => system.modules.length)
})
const selectedRow = computed(() => filteredRows.value.find(row =>
  contractKey(row.system, row.service, row.contract) === selectedKey.value)
  || filteredRows.value[0]
  || null)
const summary = computed(() => catalogSummary(catalog.value))
const moduleRows = computed(() => (catalog.value?.systems || []).flatMap(system =>
  system.modules.map(module => ({ system: system.system, module }))))

function contractKey(system: string, service: string, contract: EvidenceQueryContract) {
  return `${system}/${service}/${contract.signalKind}/${contract.contractRef}`
}

function formatDuration(milliseconds: number) {
  if (milliseconds >= 1000) return `${milliseconds / 1000} 秒`
  return `${milliseconds} 毫秒`
}

async function loadCatalog() {
  loading.value = true
  error.value = ''
  try {
    const response = await troubleshootingApi.evidenceCatalog()
    catalog.value = response.data
    if (allRows.value.length && !allRows.value.some(row =>
      contractKey(row.system, row.service, row.contract) === selectedKey.value)) {
      const first = allRows.value[0]
      selectedKey.value = contractKey(first.system, first.service, first.contract)
    }
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '取证查询目录加载失败'
  } finally {
    loading.value = false
  }
}

function inspectRow(row: ContractRow) {
  selectedKey.value = contractKey(row.system, row.service, row.contract)
  activeTab.value = 'systems'
}

function returnToWorkbench() {
  void router.push(safeTroubleshootingReturnPath(route.query.returnTo) || '/troubleshooting')
}

function toggleCatalogNav() {
  navCollapsed.value = !navCollapsed.value
  navUserExplicit.value = true
  localStorage.setItem('mc-evidence-catalog-nav-collapsed', String(navCollapsed.value))
}

function recomputeAutoNav() {
  if (navUserExplicit.value) return
  navCollapsed.value = compactViewport.value
}

function openRouteEditor(row: ContractRow) {
  routeTarget.value = row
  routePlatforms.value = [...row.contract.route.platforms]
  routeReason.value = ''
  routeDialogOpen.value = true
}

function moveRoutePlatform(index: number, offset: -1 | 1) {
  routePlatforms.value = moveOrderedItem(routePlatforms.value, index, offset)
}

async function saveRoute() {
  if (!routeTarget.value || !routeReason.value.trim()) {
    ElMessage.warning('请填写路由变更原因')
    return
  }
  routeSaving.value = true
  try {
    await troubleshootingApi.declareEvidenceRoute({
      system: routeTarget.value.system,
      signalKind: routeTarget.value.contract.signalKind,
      platforms: routePlatforms.value,
      reason: routeReason.value.trim(),
    })
    routeDialogOpen.value = false
    ElMessage.success(routePlatforms.value.length
      ? 'Workspace 取证路由已更新'
      : '已明确停用该证据路由')
    await loadCatalog()
  } catch (failure) {
    ElMessage.error(failure instanceof Error ? failure.message : '路由保存失败')
  } finally {
    routeSaving.value = false
  }
}

async function withdrawRoute(row: ContractRow) {
  try {
    await ElMessageBox.confirm(
      `撤销 ${row.system} / ${row.contract.signalKind} 的 Workspace 声明后，将恢复部署默认路由。`,
      '恢复部署默认',
      { type: 'warning', confirmButtonText: '确认恢复', cancelButtonText: '取消' },
    )
    await troubleshootingApi.withdrawEvidenceRoute(row.system, row.contract.signalKind)
    ElMessage.success('已恢复部署默认路由')
    await loadCatalog()
  } catch (failure) {
    if (failure === 'cancel' || failure === 'close') return
    ElMessage.error(failure instanceof Error ? failure.message : '路由恢复失败')
  }
}

watch(() => route.query.tab, value => {
  const nextTab = normalizeEvidenceCatalogTab(value)
  if (activeTab.value !== nextTab) activeTab.value = nextTab
})

watch(activeTab, tab => {
  if (route.query.tab === tab) return
  if (route.query.tab == null && tab === 'systems') return
  void router.replace({
    query: {
      ...route.query,
      tab,
    },
  })
})

watch(compactViewport, recomputeAutoNav, { immediate: true })

onMounted(loadCatalog)
</script>

<style scoped>
.catalog-page {
  --catalog-accent: var(--mc-primary, #d86f45);
  height: 100%;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  padding: 28px clamp(20px, 3vw, 48px);
  color: var(--el-text-color-primary);
  background: color-mix(in srgb, var(--el-bg-color-page) 88%, #f5eee7);
}

.catalog-layout { display:flex; gap:18px; width:min(1420px,100%); height:100%; min-height:0; margin:0 auto; }
.catalog-subnav { display:flex; align-self:stretch; flex-direction:column; width:210px; min-width:210px; padding:14px 10px; overflow-y:auto; border:1px solid var(--mc-border); border-radius:var(--mc-radius-md); background:var(--mc-bg-elevated); box-shadow:var(--mc-shadow-soft); transition:width .25s ease,min-width .25s ease; }
.catalog-subnav.collapsed { width:56px; min-width:56px; padding:12px 8px; }
.subnav-intro { padding:4px 8px 12px; margin-bottom:8px; border-bottom:1px solid var(--mc-border-light); }
.subnav-intro p { margin:0 0 5px; color:var(--catalog-accent); font-size:10px; font-weight:800; letter-spacing:.12em; text-transform:uppercase; }
.subnav-intro h2 { margin:0; color:var(--mc-text-primary); font-size:20px; letter-spacing:-.03em; }
.catalog-subnav nav { display:flex; flex-direction:column; gap:2px; }
.subnav-item { display:flex; align-items:center; gap:8px; width:100%; min-height:38px; padding:8px 10px; border:0; border-radius:10px; color:var(--mc-text-secondary); background:transparent; font:inherit; font-size:13px; font-weight:500; text-align:left; cursor:pointer; transition:background .15s ease,color .15s ease; }
.subnav-item:hover,.subnav-item:focus-visible { color:var(--mc-text-primary); background:var(--mc-bg-muted); outline:none; }
.subnav-item.active { color:var(--catalog-accent); background:var(--mc-primary-bg); font-weight:650; box-shadow:inset 0 0 0 1px rgba(217,109,70,.08); }
.subnav-icon { display:grid; place-items:center; flex:0 0 18px; width:18px; height:18px; font-size:17px; }
.subnav-label { min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.subnav-item small { margin-left:auto; padding:1px 5px; border-radius:999px; color:var(--catalog-accent); background:var(--mc-primary-bg); font-size:9px; white-space:nowrap; }
.catalog-subnav.collapsed .subnav-item { justify-content:center; padding:10px 8px; }
.subnav-collapse { display:flex; align-items:center; justify-content:center; position:sticky; bottom:8px; flex:0 0 auto; align-self:center; width:32px; height:32px; padding:0; margin-top:auto; margin-bottom:8px; border:1px solid rgba(217,109,70,.22); border-radius:50%; color:var(--catalog-accent); background:var(--mc-primary-bg); box-shadow:0 6px 16px rgba(217,109,70,.18); cursor:pointer; transition:background .18s ease,color .18s ease,transform .18s ease; }
.subnav-collapse:hover,.subnav-collapse:focus-visible { color:#fff; background:var(--catalog-accent); outline:2px solid color-mix(in srgb,var(--catalog-accent) 30%,transparent); outline-offset:2px; transform:scale(1.05); }
.catalog-content { flex:1; min-width:0; min-height:0; overflow-y:auto; border:1px solid var(--mc-border); border-radius:var(--mc-radius-md); background:var(--el-bg-color); box-shadow:var(--mc-shadow-soft); }

.catalog-header {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 32px;
  padding: 28px clamp(24px, 4vw, 64px) 22px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
}

.header-copy { flex: 1 1 520px; min-width: min(100%, 480px); max-width: 820px; }
.back-button { margin: 0 0 14px -12px; color: var(--el-text-color-secondary); }
.title-row { display: flex; align-items: center; gap: 14px; }
.title-row h1 { margin: 2px 0 0; font-size: clamp(26px, 3vw, 38px); letter-spacing: -.04em; white-space: nowrap; }
.eyebrow { margin: 0; color: var(--catalog-accent); font-size: 12px; font-weight: 700; letter-spacing: .16em; }
.subtitle { margin: 12px 0 0; color: var(--el-text-color-secondary); line-height: 1.7; }
.header-actions { display: flex; align-items: flex-end; flex: 1 1 360px; gap: 10px; min-width: min(100%, 360px); }
.search-input { flex: 1; }

.catalog-main { padding: 22px clamp(24px, 4vw, 64px) 48px; }
.summary-strip { display: grid; grid-template-columns: repeat(4, minmax(140px, 1fr)); gap: 12px; margin-bottom: 18px; }
.summary-card { display: flex; align-items: baseline; justify-content: space-between; padding: 17px 18px; border: 1px solid var(--el-border-color-lighter); border-radius: 12px; background: var(--el-bg-color); }
.summary-card span { color: var(--el-text-color-secondary); font-size: 13px; }
.summary-card strong { font-size: 26px; font-variant-numeric: tabular-nums; }
.summary-card.emphasis { border-color: color-mix(in srgb, var(--catalog-accent) 40%, var(--el-border-color)); background: color-mix(in srgb, var(--catalog-accent) 7%, var(--el-bg-color)); }
.summary-card.emphasis strong { color: var(--catalog-accent); }

.catalog-tabs { padding: 0 20px 24px; border: 1px solid var(--el-border-color-lighter); border-radius: 14px; background: var(--el-bg-color); }
.sidebar-controlled-tabs :deep(.el-tabs__header) { display:none; }
.sidebar-controlled-tabs :deep(.el-tabs__content) { padding-top:20px; }
.system-workspace { display: grid; grid-template-columns: minmax(240px, 300px) minmax(0, 1fr); min-height: 620px; border: 1px solid var(--el-border-color-lighter); border-radius: 12px; overflow: hidden; }
.catalog-tree { padding: 16px 12px; border-right: 1px solid var(--el-border-color-lighter); background: var(--el-fill-color-extra-light); overflow: auto; }
.tree-system { padding: 8px 10px; color: var(--el-text-color-primary); font-size: 13px; font-weight: 800; letter-spacing: .04em; }
.tree-module { display: flex; justify-content: space-between; align-items: center; padding: 8px 10px 5px 18px; color: var(--el-text-color-secondary); font-size: 12px; }
.tree-contract { display: flex; flex-direction: column; width: 100%; margin: 2px 0; padding: 10px 12px 10px 28px; border: 0; border-radius: 8px; color: inherit; background: transparent; text-align: left; cursor: pointer; }
.tree-contract:hover { background: var(--el-fill-color); }
.tree-contract.active { color: var(--catalog-accent); background: color-mix(in srgb, var(--catalog-accent) 10%, var(--el-bg-color)); box-shadow: inset 3px 0 var(--catalog-accent); }
.tree-contract span { font-size: 13px; font-weight: 650; }
.tree-contract small { margin-top: 4px; opacity: .72; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }

.contract-inspector { min-width: 0; padding: clamp(20px, 3vw, 34px); overflow: auto; }
.inspector-heading { display: flex; justify-content: space-between; gap: 24px; }
.inspector-heading h2 { margin: 5px 0 8px; font-size: 24px; }
.inspector-heading p { margin: 0; color: var(--el-text-color-secondary); line-height: 1.6; }
.scope-line { color: var(--catalog-accent) !important; font: 12px ui-monospace, SFMono-Regular, Menlo, monospace; }
.axis-grid { display: grid; grid-template-columns: repeat(4, minmax(150px, 1fr)); gap: 10px; margin: 24px 0; }
.axis-grid article { display: flex; flex-direction: column; min-width: 0; padding: 14px; border-radius: 10px; background: var(--el-fill-color-extra-light); }
.axis-grid span { color: var(--el-text-color-secondary); font-size: 11px; }
.axis-grid strong { margin-top: 7px; font-size: 14px; }
.axis-grid small { margin-top: 5px; color: var(--el-text-color-secondary); overflow-wrap: anywhere; }
.blocker-box { padding: 14px 16px; border: 1px solid var(--el-color-danger-light-7); border-radius: 10px; color: var(--el-color-danger); background: var(--el-color-danger-light-9); }
.blocker-box ul, .acceptance-card ul { margin: 8px 0 0; padding-left: 20px; line-height: 1.7; }
.detail-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin-top: 18px; }
.detail-card { padding: 18px; border: 1px solid var(--el-border-color-lighter); border-radius: 10px; }
.detail-card.parameter-card { grid-column: 1 / -1; }
.card-title { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.card-title small { color: var(--catalog-accent); font-weight: 700; letter-spacing: .14em; }
.card-title h3 { margin: 3px 0 0; font-size: 15px; }
.tag-list { display: flex; flex-wrap: wrap; gap: 8px; }
.output-tags :deep(.el-tag) { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.empty-inline, .table-note { display: block; margin-top: 5px; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.5; }
.boundary-note { margin-top: 16px; }
.catalog-table { width: 100%; }
.catalog-table code { color: var(--catalog-accent); }
.route-platforms { margin-left: 8px; }
.tab-alert { margin-bottom: 14px; }

.acceptance-layout { display: grid; grid-template-columns: minmax(260px, .7fr) minmax(420px, 1.3fr); gap: 18px; }
.source-panel, .acceptance-panel { padding: 18px; border: 1px solid var(--el-border-color-lighter); border-radius: 12px; }
.source-panel h3, .acceptance-panel h3 { margin: 0 0 14px; }
.source-card, .acceptance-card { padding: 14px; border-radius: 10px; background: var(--el-fill-color-extra-light); }
.source-card { display: flex; justify-content: space-between; gap: 14px; margin-bottom: 10px; }
.source-card p { margin: 6px 0 0; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.5; }
.source-runtime-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px 16px; margin: 12px 0 0; }
.source-runtime-grid div { min-width: 0; }
.source-runtime-grid dt { color: var(--el-text-color-secondary); font-size: 11px; }
.source-runtime-grid dd { margin: 3px 0 0; font-size: 12px; overflow-wrap: anywhere; }
.source-runtime-grid .source-signals { grid-column: 1 / -1; }
.acceptance-card { margin-bottom: 10px; }
.acceptance-title { display: flex; align-items: center; justify-content: space-between; gap: 14px; }
.acceptance-title small { display: block; margin-top: 5px; color: var(--el-text-color-secondary); }
.route-scope { display: flex; flex-direction: column; margin-bottom: 18px; padding: 14px; border-radius: 10px; background: var(--el-fill-color-extra-light); }
.route-scope span, .route-scope small { color: var(--el-text-color-secondary); font-size: 12px; }
.route-scope b { margin: 5px 0; }
.source-checkboxes { display: flex; flex-direction: column; align-items: flex-start; }
.source-checkboxes small { margin-left: 8px; color: var(--el-text-color-secondary); }
.form-help { margin: 8px 0 0; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.6; }
.route-priority { margin-top: 14px; padding: 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 9px; }
.route-priority>p { margin: 0 0 8px; color: var(--el-text-color-secondary); font-size: 12px; }
.priority-row { display: grid; grid-template-columns: 26px minmax(0, 1fr) auto auto; align-items: center; gap: 6px; min-height: 34px; }
.priority-row em { display: grid; place-items: center; width: 22px; height: 22px; border-radius: 50%; color: var(--catalog-accent); background: color-mix(in srgb, var(--catalog-accent) 10%, var(--el-bg-color)); font-style: normal; font-size: 11px; font-weight: 700; }

@media (max-width: 1100px) {
  .catalog-header { flex-direction: column; flex-wrap: nowrap; }
  .header-copy, .header-actions { flex: 0 0 auto; min-width: 0; width: 100%; max-width: none; }
  .axis-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .summary-strip { grid-template-columns: repeat(2, minmax(140px, 1fr)); }
}

@media (max-width: 900px) {
  .catalog-page { height: auto; min-height: 100%; overflow: auto; padding: 14px; }
  .catalog-layout { height: auto; min-height: calc(100vh - 28px); align-items: stretch; }
  .catalog-subnav { align-self: auto; width: 56px; min-width: 56px; max-height: none; padding: 12px 8px; overflow: visible; }
  .catalog-subnav .subnav-intro,
  .catalog-subnav .subnav-label,
  .catalog-subnav .subnav-item small { display: none; }
  .catalog-subnav .subnav-item { justify-content: center; padding: 10px 8px; }
  .subnav-collapse.mobile-hidden { display: none; }
  .catalog-content { min-height: 0; overflow: visible; }
}

@media (max-width: 760px) {
  .catalog-page { padding: 10px; }
  .catalog-layout { gap: 10px; }
  .catalog-header, .catalog-main { padding-left: 16px; padding-right: 16px; }
  .header-actions { align-items: stretch; flex-direction: column; }
  .system-workspace, .acceptance-layout { grid-template-columns: 1fr; }
  .catalog-tree { max-height: 300px; border-right: 0; border-bottom: 1px solid var(--el-border-color-lighter); }
  .axis-grid, .detail-grid { grid-template-columns: 1fr; }
  .detail-card.parameter-card { grid-column: auto; overflow-x: auto; }
  .title-row { align-items: flex-start; flex-direction: column; }
}
</style>
