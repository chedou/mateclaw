<template>
  <div class="catalog-page">
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
          <el-tag effect="plain" type="info">服务端已审核规则</el-tag>
        </div>
        <p class="subtitle">
          系统用它确定去哪里查询、需要哪些参数、返回哪些数据，
          并维护 Workspace 层的只读取证路由。
        </p>
      </div>
      <div class="header-actions">
        <el-input
          v-model="query"
          clearable
          placeholder="搜索场景、信号、查询规则或返回字段"
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
          <span>查询规则</span><strong>{{ summary.contracts }}</strong>
        </article>
        <article class="summary-card emphasis">
          <span>完整链路可运行</span><strong>{{ summary.runnable }}</strong>
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
                <div class="inspector-actions">
                  <el-tag :type="selectedRow.contract.runnable ? 'success' : 'danger'">
                    {{ selectedRow.contract.runnable ? '可运行' : '暂不可运行' }}
                  </el-tag>
                  <el-tooltip :content="directTrialBlocker" :disabled="!directTrialBlocker">
                    <span>
                      <el-button
                        type="primary"
                        :disabled="Boolean(directTrialBlocker)"
                        @click="openTrialDialog"
                      >管理员只读试跑</el-button>
                    </span>
                  </el-tooltip>
                  <small v-if="directTrialBlocker" class="trial-blocker">{{ directTrialBlocker }}</small>
                </div>
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
                title="目录只展示查询规则摘要和脱敏请求轮廓；API Key、端点主机、原始 DQL 和原始日志仍由服务端保管。"
              />

              <section class="trial-history">
                <div class="trial-history-heading">
                  <div><small>AUDIT</small><h3>最近只读试跑</h3></div>
                  <el-button text :loading="trialHistoryLoading" @click="loadTrialHistory">刷新</el-button>
                </div>
                <el-table v-if="trialHistory.length" :data="trialHistory" size="small">
                  <el-table-column label="结果" width="110">
                    <template #default="scope">
                      <el-tag :type="scope.row.status === 'OBSERVED' ? 'success' : scope.row.status === 'FAILED' ? 'danger' : 'warning'" size="small">
                        {{ trialStatusLabel(scope.row.status) }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="资产版本" width="120">
                    <template #default="scope">v{{ scope.row.assetVersion }}</template>
                  </el-table-column>
                  <el-table-column label="返回字段" min-width="220">
                    <template #default="scope">{{ scope.row.canonicalFields.join('、') || '无' }}</template>
                  </el-table-column>
                  <el-table-column prop="durationMs" label="耗时（毫秒）" width="120" />
                  <el-table-column prop="actor" label="操作人" width="130" />
                  <el-table-column prop="completedAt" label="完成时间" min-width="180" />
                </el-table>
                <el-empty v-else :image-size="48" description="这条查询规则还没有只读试跑记录" />
              </section>
            </section>
          </div>
          <el-empty v-else description="当前 Workspace 没有匹配的取证查询规则" />
        </el-tab-pane>

        <el-tab-pane label="系统观测资产" name="assets">
          <div class="asset-toolbar">
            <div>
              <h2>系统观测资产</h2>
              <p>把业务系统、运行环境和已审核查询规则精确关联起来。</p>
            </div>
            <el-button type="primary" @click="openNewAsset">新增资产</el-button>
          </div>
          <el-alert
            type="info"
            :closable="false"
            class="tab-alert"
            title="这里只维护资源标识和查询规则引用；API Key、端点主机、原始 DQL 与原始日志不进入资产表。"
          />
          <el-table v-if="filteredAssets.length" :data="filteredAssets" class="catalog-table" stripe>
            <el-table-column label="系统 / 模块" min-width="230">
              <template #default="scope">
                <b>{{ scope.row.system }} / {{ scope.row.service }}</b>
                <small class="table-note">{{ scope.row.displayName }}</small>
              </template>
            </el-table-column>
            <el-table-column label="运行范围" min-width="230">
              <template #default="scope">
                <span>{{ scope.row.environment || '未声明环境' }}</span>
                <small class="table-note">
                  {{ [scope.row.region, scope.row.cluster, scope.row.namespace].filter(Boolean).join(' / ') || '未声明区域、集群或命名空间' }}
                </small>
              </template>
            </el-table-column>
            <el-table-column label="已绑定查询规则" min-width="300">
              <template #default="scope">
                <div class="asset-contracts">
                  <el-tag
                    v-for="(contractRef, signalKind) in scope.row.signalBindings"
                    :key="`${signalKind}/${contractRef}`"
                    size="small"
                    effect="plain"
                  >{{ signalKind }} · {{ contractRef }}</el-tag>
                  <span v-if="!Object.keys(scope.row.signalBindings).length" class="empty-inline">未绑定</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="来源 / 状态" width="150">
              <template #default="scope">
                <el-tag size="small" effect="plain">
                  {{ scope.row.origin === 'WORKSPACE' ? `Workspace v${scope.row.version}` : '部署默认' }}
                </el-tag>
                <small class="table-note">{{ scope.row.enabled ? '已启用' : '已停用' }}</small>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="130" fixed="right">
              <template #default="scope">
                <el-button text type="primary" @click="openAssetEditor(scope.row)">
                  {{ scope.row.origin === 'WORKSPACE' ? '新建版本' : '接管配置' }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-else description="当前 Workspace 还没有系统观测资产">
            <el-button type="primary" @click="openNewAsset">新增第一个资产</el-button>
          </el-empty>
        </el-tab-pane>

        <el-tab-pane label="查询规则" name="contracts">
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

        <el-tab-pane label="数据源联调" name="acceptance">
          <section class="acceptance-layout">
            <div class="source-panel">
              <h3>数据源状态</h3>
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
              <h3>系统联调状态</h3>
              <article v-for="row in moduleRows" :key="`${row.system}/${row.module.service}`" class="acceptance-card">
                <div class="acceptance-title">
                  <div><b>{{ row.system }} / {{ row.module.service }}</b><small>{{ row.module.runnableContracts }}/{{ row.module.contracts.length }} 条查询规则可运行</small></div>
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
              <el-button type="primary" plain @click="openGuanceValidation">
                执行观测云只读联调
              </el-button>
            </div>
          </section>
        </el-tab-pane>
      </el-tabs>
    </main>
    </section>

    <el-dialog v-model="trialDialogOpen" title="管理员只读试跑" width="620px" destroy-on-close>
      <template v-if="trialTarget">
        <div class="route-scope">
          <span>本次只查询</span>
          <b>{{ trialTarget.system }} / {{ trialTarget.service }} / {{ trialTarget.contract.contractRef }}</b>
          <small>系统资产中的资源范围和服务端查询条件不可在此修改。</small>
        </div>
        <el-alert
          type="warning"
          :closable="false"
          title="只验证这条查询规则能否返回规范证据，不会创建排障单，也不代表 T7/T8 已验收。"
          class="asset-dialog-alert"
        />
        <el-alert
          v-if="trialError"
          type="error"
          :closable="false"
          show-icon
          :title="trialError"
          class="asset-dialog-alert"
        />
        <el-form label-position="top">
          <el-form-item
            v-for="parameter in trialParameters"
            :key="parameter.name"
            :label="`${trialParameterLabel(parameter.name)}${parameter.required ? '（必填）' : ''}`"
          >
            <el-input
              v-model="trialParameterValues[parameter.name]"
              maxlength="256"
              :placeholder="parameter.description"
            />
          </el-form-item>
          <div class="trial-time-grid">
            <el-form-item label="查询时间范围">
              <el-select v-model="trialWindow">
                <el-option label="最近 5 分钟" value="-5m" />
                <el-option label="最近 15 分钟" value="-15m" />
                <el-option label="最近 30 分钟" value="-30m" />
                <el-option label="最近 1 小时" value="-1h" />
                <el-option label="最近 24 小时" value="-24h" />
              </el-select>
            </el-form-item>
            <el-form-item label="故障发生时间（不选则用当前时间）">
              <el-date-picker
                v-model="trialOccurredAt"
                type="datetime"
                placeholder="使用当前时间"
                style="width: 100%"
              />
            </el-form-item>
          </div>
        </el-form>
        <section v-if="trialResult" class="trial-result">
          <div>
            <b>{{ trialResult.status === 'OBSERVED'
              ? '已拿到规范证据'
              : trialResult.status === 'FAILED'
                ? '数据源查询失败'
                : '查询成功，但这个时间范围没有完整证据' }}</b>
            <small>资产 v{{ trialResult.assetVersion }} · {{ trialResult.durationMs }} 毫秒 · {{ trialResult.source }}</small>
          </div>
          <div class="tag-list output-tags">
            <el-tag v-for="field in trialResult.canonicalFields" :key="field" effect="plain">{{ field }}</el-tag>
          </div>
          <p>{{ trialResult.warning }}</p>
        </section>
      </template>
      <template #footer>
        <el-button @click="trialDialogOpen = false">关闭</el-button>
        <el-button type="primary" :loading="trialRunning" @click="runTrial">执行只读查询</el-button>
      </template>
    </el-dialog>

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

    <el-dialog
      v-model="assetDialogOpen"
      :title="assetForm.expectedVersion ? '新建系统观测资产版本' : '登记系统观测资产'"
      width="760px"
      destroy-on-close
    >
      <el-alert
        type="warning"
        :closable="false"
        class="asset-dialog-alert"
        title="保存会追加一个不可变版本；已停用的 Workspace 资产也会阻止回落到部署默认。"
      />
      <el-alert
        :type="assetDraftReadiness.ready ? 'success' : 'info'"
        :closable="false"
        show-icon
        class="asset-dialog-alert"
        :title="assetDraftReadiness.ready ? '接管信息已齐全' : `接管前还缺 ${assetDraftReadiness.missing.length} 项`"
        :description="assetDraftReadiness.ready
          ? '保存后会生成 Workspace 不可变资产版本，随后才能执行管理员只读试跑。'
          : `请由系统负责人确认：${assetDraftReadiness.missing.join('、')}`"
      />
      <el-form label-position="top" class="asset-form">
        <div class="asset-form-grid">
          <el-form-item label="系统标识">
            <el-input v-model="assetForm.system" :disabled="assetScopeLocked" placeholder="例如 CSDP" />
          </el-form-item>
          <el-form-item label="模块 / 服务标识">
            <el-input v-model="assetForm.service" :disabled="assetScopeLocked" placeholder="例如 csdp-session-service" />
          </el-form-item>
          <el-form-item label="显示名称">
            <el-input v-model="assetForm.displayName" placeholder="例如 CSDP 会话服务" />
          </el-form-item>
          <el-form-item label="观测平台">
            <el-input v-model="assetForm.platform" disabled />
          </el-form-item>
          <el-form-item label="环境（必填）">
            <el-input v-model="assetForm.environment" placeholder="prod / staging" />
          </el-form-item>
          <el-form-item :label="metadataFieldLabel('region', '区域')">
            <el-input v-model="assetForm.region" placeholder="cn-south-1" />
          </el-form-item>
          <el-form-item :label="metadataFieldLabel('cluster', '集群')">
            <el-input v-model="assetForm.cluster" placeholder="csdp-prod" />
          </el-form-item>
          <el-form-item :label="metadataFieldLabel('namespace', '命名空间')">
            <el-input v-model="assetForm.namespace" placeholder="csdp" />
          </el-form-item>
        </div>

        <el-form-item label="绑定已审核查询规则">
          <div v-if="contractGroups.length" class="contract-binding-grid">
            <div v-for="group in contractGroups" :key="group.signalKind" class="contract-binding-row">
              <div>
                <b>{{ group.signalKind }}</b>
                <small>{{ group.options[0]?.scenario }}</small>
              </div>
              <el-select
                v-model="assetForm.contractRefs[group.signalKind]"
                clearable
                filterable
                placeholder="不绑定该证据维度"
              >
                <el-option
                  v-for="option in group.options"
                  :key="option.contractRef"
                  :label="`${option.contractRef} · ${option.question}`"
                  :value="option.contractRef"
                />
              </el-select>
            </div>
          </div>
          <el-empty v-else :image-size="56" description="当前部署没有可登记的已审核查询规则" />
        </el-form-item>

        <section v-if="editableAssetParameters.length" class="asset-parameter-section">
          <div class="asset-parameter-heading">
            <b>查询规则需要的资源标识</b>
            <small>这些值由资产固定，排障运行时不能改成其他系统资源。</small>
          </div>
          <div class="asset-form-grid">
            <el-form-item
              v-for="parameter in editableAssetParameters"
              :key="parameter"
              :label="assetParameterLabel(parameter)"
            >
              <el-input
                v-model="assetForm.parameters[parameter]"
                :placeholder="`填写 ${parameter} 的精确值`"
              />
            </el-form-item>
          </div>
        </section>

        <div class="asset-enabled-row">
          <div><b>启用资产</b><small>停用后该系统模块不再回落到部署默认授权。</small></div>
          <el-switch v-model="assetForm.enabled" />
        </div>
        <el-form-item label="变更原因">
          <el-input
            v-model="assetForm.reason"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="说明为什么接入、调整或停用这个生产观测资产"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="assetDialogOpen = false">取消</el-button>
        <el-button
          type="primary"
          :loading="assetSaving"
          :disabled="!assetDraftReadiness.ready"
          @click="saveAsset"
        >保存新版本</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { vLoading } from 'element-plus/es/components/loading/index'
import {
  troubleshootingApi,
  type EvidenceCatalogModule,
  type EvidenceContractTrial,
  type EvidenceQueryCatalog,
  type EvidenceQueryContract,
  type ObservabilityAsset,
  type ObservabilityAssetCatalog,
  type ObservabilityAssetContractOption,
} from '@/api'
import {
  acceptanceStatusLabel,
  assetParameterLabel,
  bindingStatusLabel,
  catalogSummary,
  contractMatches,
  directTrialBlockReason,
  observabilityAssetDraftReadiness,
  parameterSourceLabel,
  routeOriginLabel,
  runtimeStateLabel,
  moveOrderedItem,
} from './evidenceCatalog'
import {
  normalizeEvidenceCatalogTab,
  safeTroubleshootingReturnPath,
  workbenchOverlayLocation,
  type EvidenceCatalogTab,
} from './workbenchCapabilityMenu'

type ContractRow = {
  system: string
  service: string
  module: EvidenceCatalogModule
  contract: EvidenceQueryContract
}

type AssetForm = {
  system: string
  service: string
  displayName: string
  platform: 'guance'
  environment: string
  region: string
  cluster: string
  namespace: string
  enabled: boolean
  contractRefs: Record<string, string | undefined>
  parameters: Record<string, string>
  expectedVersion?: number
  reason: string
  scopeLocked: boolean
}

function emptyAssetForm(): AssetForm {
  return {
    system: '',
    service: '',
    displayName: '',
    platform: 'guance',
    environment: '',
    region: '',
    cluster: '',
    namespace: '',
    enabled: true,
    contractRefs: {},
    parameters: {},
    reason: '',
    scopeLocked: false,
  }
}

const route = useRoute()
const router = useRouter()
const catalog = ref<EvidenceQueryCatalog | null>(null)
const assetCatalog = ref<ObservabilityAssetCatalog | null>(null)
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
const assetDialogOpen = ref(false)
const assetSaving = ref(false)
const assetForm = ref<AssetForm>(emptyAssetForm())
const trialDialogOpen = ref(false)
const trialRunning = ref(false)
const trialHistoryLoading = ref(false)
const trialTarget = ref<ContractRow | null>(null)
const trialParameterValues = ref<Record<string, string>>({})
const trialWindow = ref('-15m')
const trialOccurredAt = ref<Date | null>(null)
const trialResult = ref<EvidenceContractTrial | null>(null)
const trialError = ref('')
const trialHistory = ref<EvidenceContractTrial[]>([])

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
const filteredAssets = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  if (!keyword) return assetCatalog.value?.assets || []
  return (assetCatalog.value?.assets || []).filter(asset => [
    asset.system,
    asset.service,
    asset.displayName,
    asset.environment || '',
    asset.region || '',
    asset.cluster || '',
    asset.namespace || '',
    ...Object.keys(asset.signalBindings),
    ...Object.values(asset.signalBindings),
  ].some(value => value.toLowerCase().includes(keyword)))
})
const contractGroups = computed(() => {
  const grouped = new Map<string, ObservabilityAssetContractOption[]>()
  for (const option of assetCatalog.value?.contracts || []) {
    const options = grouped.get(option.signalKind) || []
    options.push(option)
    grouped.set(option.signalKind, options)
  }
  return [...grouped.entries()]
    .map(([signalKind, options]) => ({
      signalKind,
      options: options.sort((left, right) => left.contractRef.localeCompare(right.contractRef)),
    }))
    .sort((left, right) => left.signalKind.localeCompare(right.signalKind))
})
const selectedAssetContracts = computed(() => {
  const selected = new Set(Object.values(assetForm.value.contractRefs).filter(
    (value): value is string => typeof value === 'string' && Boolean(value),
  ))
  return (assetCatalog.value?.contracts || []).filter(option => selected.has(option.contractRef))
})
const requiredAssetParameters = computed(() => [...new Set(
  selectedAssetContracts.value.flatMap(option => option.requiredAssetParameters),
)].sort())
const metadataAssetParameters = new Set(['namespace', 'cluster', 'region', 'environment'])
const editableAssetParameters = computed(() => requiredAssetParameters.value.filter(
  parameter => !metadataAssetParameters.has(parameter),
))
const assetDraftReadiness = computed(() => observabilityAssetDraftReadiness({
  system: assetForm.value.system,
  service: assetForm.value.service,
  displayName: assetForm.value.displayName,
  environment: assetForm.value.environment,
  enabled: assetForm.value.enabled,
  contractRefs: assetForm.value.contractRefs,
  parameterValues: {
    ...assetForm.value.parameters,
    environment: assetForm.value.environment,
    region: assetForm.value.region,
    cluster: assetForm.value.cluster,
    namespace: assetForm.value.namespace,
  },
  requiredAssetParameters: requiredAssetParameters.value,
  reason: assetForm.value.reason,
}))
const assetScopeLocked = computed(() => assetForm.value.scopeLocked)
const trialParameters = computed(() => (trialTarget.value?.contract.parameters || []).filter(
  parameter => (parameter.source === 'EVIDENCE_REQUEST_TARGET'
    && !['monitor_checker', 'deployment', 'namespace', 'cluster', 'region', 'environment']
      .includes(parameter.name))
    || (parameter.source === 'INCIDENT' && ['error_code', 'trace_id'].includes(parameter.name)),
))
const selectedAsset = computed(() => {
  const row = selectedRow.value
  if (!row) return null
  const system = row.system.trim().toLowerCase()
  const service = row.service.trim().toLowerCase()
  return (assetCatalog.value?.assets || []).find(asset =>
    asset.system.trim().toLowerCase() === system
      && asset.service.trim().toLowerCase() === service) || null
})
const directTrialBlocker = computed(() => {
  return directTrialBlockReason(
    selectedRow.value?.contract || null,
    selectedAsset.value,
  )
})

function contractKey(system: string, service: string, contract: EvidenceQueryContract) {
  return `${system}/${service}/${contract.signalKind}/${contract.contractRef}`
}

function formatDuration(milliseconds: number) {
  if (milliseconds >= 1000) return `${milliseconds / 1000} 秒`
  return `${milliseconds} 毫秒`
}

function trialParameterLabel(name: string) {
  return {
    search_term: '安全检索键',
    error_code: '错误码',
    trace_id: '链路标识',
    deployment: 'Deployment 名称',
    namespace: '命名空间',
    monitor_checker: '拨测任务标识',
  }[name] || name
}

function trialStatusLabel(status: EvidenceContractTrial['status']) {
  return {
    OBSERVED: '拿到规范证据',
    NO_EVIDENCE: '未拿到证据',
    FAILED: '查询失败',
  }[status]
}

function openTrialDialog() {
  if (!selectedRow.value || directTrialBlocker.value) return
  trialTarget.value = selectedRow.value
  trialParameterValues.value = Object.fromEntries(
    trialTarget.value.contract.parameters
      .filter(parameter => (parameter.source === 'EVIDENCE_REQUEST_TARGET'
        && !['monitor_checker', 'deployment', 'namespace', 'cluster', 'region', 'environment']
          .includes(parameter.name))
        || (parameter.source === 'INCIDENT' && ['error_code', 'trace_id'].includes(parameter.name)))
      .map(parameter => [parameter.name, '']),
  )
  trialWindow.value = '-15m'
  trialOccurredAt.value = null
  trialResult.value = null
  trialError.value = ''
  trialDialogOpen.value = true
}

async function runTrial() {
  const target = trialTarget.value
  if (!target) return
  const parameters = Object.fromEntries(Object.entries(trialParameterValues.value)
    .map(([name, value]) => [name, value.trim()])
    .filter(([, value]) => Boolean(value)))
  const missing = trialParameters.value.find(parameter =>
    parameter.required && !parameters[parameter.name])
  if (missing) {
    ElMessage.warning(`请填写${trialParameterLabel(missing.name)}`)
    return
  }
  trialRunning.value = true
  trialError.value = ''
  try {
    const response = await troubleshootingApi.runEvidenceContractTrial({
      system: target.system,
      service: target.service,
      contractRef: target.contract.contractRef,
      parameters,
      window: trialWindow.value,
      occurredAt: trialOccurredAt.value?.toISOString(),
    })
    trialResult.value = response.data
    const message = response.data.status === 'OBSERVED'
      ? '只读查询已拿到规范证据'
      : response.data.status === 'FAILED'
        ? '数据源查询失败，请核对运行日志和查询规则'
        : '查询成功，但这个时间范围没有完整证据'
    if (response.data.status === 'FAILED') ElMessage.error(message)
    else ElMessage.success(message)
    await loadTrialHistory()
  } catch (failure) {
    const reason = failure instanceof Error ? failure.message : '只读试跑失败'
    trialError.value = `本次试跑未完成：${reason}`
    ElMessage.error(trialError.value)
  } finally {
    trialRunning.value = false
  }
}

async function loadTrialHistory() {
  const row = selectedRow.value
  if (!row) {
    trialHistory.value = []
    return
  }
  trialHistoryLoading.value = true
  try {
    const response = await troubleshootingApi.evidenceContractTrials({
      system: row.system,
      service: row.service,
      contractRef: row.contract.contractRef,
      limit: 20,
    })
    trialHistory.value = response.data
  } catch {
    trialHistory.value = []
  } finally {
    trialHistoryLoading.value = false
  }
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
    assetCatalog.value = assetResponse.data
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

function openNewAsset() {
  assetForm.value = emptyAssetForm()
  assetDialogOpen.value = true
}

function openAssetEditor(asset: ObservabilityAsset) {
  assetForm.value = {
    system: asset.system,
    service: asset.service,
    displayName: asset.displayName || asset.service,
    platform: 'guance',
    environment: asset.environment || '',
    region: asset.region || '',
    cluster: asset.cluster || '',
    namespace: asset.namespace || '',
    enabled: asset.enabled,
    contractRefs: { ...asset.signalBindings },
    parameters: { ...asset.parameters },
    expectedVersion: asset.origin === 'WORKSPACE' ? asset.version : undefined,
    reason: '',
    scopeLocked: true,
  }
  assetDialogOpen.value = true
}

function metadataFieldLabel(parameter: string, label: string) {
  return requiredAssetParameters.value.includes(parameter)
    ? `${label}（已选规则必填）`
    : `${label}（可选）`
}

function metadataParameter(parameter: string): string {
  return {
    namespace: assetForm.value.namespace,
    cluster: assetForm.value.cluster,
    region: assetForm.value.region,
    environment: assetForm.value.environment,
  }[parameter] || ''
}

async function saveAsset() {
  const form = assetForm.value
  const signalBindings = Object.entries(form.contractRefs).reduce<Record<string, string>>(
    (bindings, [signalKind, contractRef]) => {
      if (typeof contractRef === 'string' && contractRef) bindings[signalKind] = contractRef
      return bindings
    },
    {},
  )
  if (!assetDraftReadiness.value.ready) {
    ElMessage.warning(`请先补齐：${assetDraftReadiness.value.missing.join('、')}`)
    return
  }
  const parameters: Record<string, string> = {}
  for (const parameter of requiredAssetParameters.value) {
    const value = (metadataAssetParameters.has(parameter)
      ? metadataParameter(parameter)
      : form.parameters[parameter] || '').trim()
    parameters[parameter] = value
  }

  assetSaving.value = true
  try {
    await troubleshootingApi.declareObservabilityAsset({
      system: form.system.trim(),
      service: form.service.trim(),
      displayName: form.displayName.trim(),
      platform: 'guance',
      environment: form.environment.trim(),
      region: form.region.trim() || undefined,
      cluster: form.cluster.trim() || undefined,
      namespace: form.namespace.trim() || undefined,
      enabled: form.enabled,
      signalBindings,
      parameters,
      expectedVersion: form.expectedVersion,
      reason: form.reason.trim(),
    })
    assetDialogOpen.value = false
    ElMessage.success(form.expectedVersion ? '系统观测资产已追加新版本' : '系统观测资产已登记')
    await loadCatalog()
  } catch (failure) {
    ElMessage.error(failure instanceof Error ? failure.message : '系统观测资产保存失败')
  } finally {
    assetSaving.value = false
  }
}

function inspectRow(row: ContractRow) {
  selectedKey.value = contractKey(row.system, row.service, row.contract)
  activeTab.value = 'systems'
}

function returnToWorkbench() {
  void router.push(safeTroubleshootingReturnPath(route.query.returnTo) || '/troubleshooting')
}

function openGuanceValidation() {
  const returnTo = safeTroubleshootingReturnPath(route.query.returnTo)
    || '/troubleshooting?view=list'
  void router.push(workbenchOverlayLocation('guance', returnTo))
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

watch(selectedKey, () => {
  void loadTrialHistory()
})

onMounted(loadCatalog)
</script>

<style scoped>
.catalog-page {
  --catalog-accent: var(--mc-primary, #d86f45);
  height: 100%;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  color: var(--el-text-color-primary);
  background: color-mix(in srgb, var(--el-bg-color-page) 88%, #f5eee7);
}

.catalog-content { width:100%; height:100%; min-width:0; min-height:0; overflow-y:auto; border:1px solid var(--mc-border); border-radius:var(--mc-radius-md); background:var(--el-bg-color); box-shadow:var(--mc-shadow-soft); }

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
.inspector-actions { display:flex; flex-direction:column; align-items:flex-end; gap:10px; flex:0 0 auto; }
.trial-blocker { max-width:240px; color:var(--el-text-color-secondary); font-size:11px; line-height:1.45; text-align:right; }
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
.trial-history { margin-top:16px; padding:18px; border:1px solid var(--el-border-color-lighter); border-radius:10px; }
.trial-history-heading { display:flex; align-items:center; justify-content:space-between; margin-bottom:12px; }
.trial-history-heading small { color:var(--catalog-accent); font-weight:700; letter-spacing:.14em; }
.trial-history-heading h3 { margin:3px 0 0; font-size:15px; }
.trial-time-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:14px; }
.trial-time-grid :deep(.el-select) { width:100%; }
.trial-result { display:grid; gap:12px; padding:16px; border:1px solid var(--el-color-success-light-7); border-radius:10px; background:var(--el-color-success-light-9); }
.trial-result small { display:block; margin-top:5px; color:var(--el-text-color-secondary); }
.trial-result p { margin:0; color:var(--el-text-color-secondary); font-size:12px; line-height:1.6; }
.catalog-table { width: 100%; }
.catalog-table code { color: var(--catalog-accent); }
.route-platforms { margin-left: 8px; }
.tab-alert { margin-bottom: 14px; }
.asset-toolbar { display:flex; align-items:flex-start; justify-content:space-between; gap:20px; margin-bottom:14px; }
.asset-toolbar h2 { margin:0; font-size:20px; }
.asset-toolbar p { margin:6px 0 0; color:var(--el-text-color-secondary); font-size:13px; }
.asset-contracts { display:flex; flex-wrap:wrap; gap:6px; }
.asset-dialog-alert { margin-bottom:18px; }
.asset-form-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:0 14px; width:100%; }
.contract-binding-grid { display:grid; gap:8px; width:100%; }
.contract-binding-row { display:grid; grid-template-columns:minmax(180px,.65fr) minmax(280px,1.35fr); align-items:center; gap:16px; padding:12px; border:1px solid var(--el-border-color-lighter); border-radius:9px; }
.contract-binding-row small { display:block; margin-top:4px; color:var(--el-text-color-secondary); }
.contract-binding-row :deep(.el-select) { width:100%; }
.asset-parameter-section { margin:2px 0 18px; padding:16px; border:1px solid color-mix(in srgb,var(--catalog-accent) 30%,var(--el-border-color)); border-radius:10px; background:color-mix(in srgb,var(--catalog-accent) 5%,var(--el-bg-color)); }
.asset-parameter-heading { display:flex; flex-direction:column; margin-bottom:12px; }
.asset-parameter-heading small { margin-top:5px; color:var(--el-text-color-secondary); }
.asset-enabled-row { display:flex; align-items:center; justify-content:space-between; gap:20px; margin-bottom:18px; padding:14px; border-radius:10px; background:var(--el-fill-color-extra-light); }
.asset-enabled-row small { display:block; margin-top:4px; color:var(--el-text-color-secondary); }

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
  .system-workspace, .acceptance-layout { grid-template-columns: 1fr; }
  .catalog-tree { max-height: 300px; border-right: 0; border-bottom: 1px solid var(--el-border-color-lighter); }
}

@media (max-width: 900px) {
  .catalog-page { height: auto; min-height: 100%; overflow: auto; }
  .catalog-content { min-height: 0; overflow: visible; }
}

@media (max-width: 760px) {
  .catalog-header, .catalog-main { padding-left: 16px; padding-right: 16px; }
  .header-actions { align-items: stretch; flex-direction: column; }
  .axis-grid, .detail-grid { grid-template-columns: 1fr; }
  .detail-card.parameter-card { grid-column: auto; overflow-x: auto; }
  .title-row { align-items: flex-start; flex-direction: column; }
  .asset-form-grid, .contract-binding-row, .trial-time-grid { grid-template-columns:1fr; }
  .inspector-heading { flex-direction:column; }
  .inspector-actions { align-items:flex-start; }
  .trial-blocker { text-align:left; }
}
</style>
