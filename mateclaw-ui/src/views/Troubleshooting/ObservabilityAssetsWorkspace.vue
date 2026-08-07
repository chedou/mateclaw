<template>
  <CapabilityWorkspaceShell
    eyebrow="配置与接入"
    :title="TROUBLESHOOTING_UI_LABELS.observabilityAssets"
    description="按系统与系统模块配置取证：能用哪些工具、每项要填什么；在这里登记范围、绑定规则、改路由并做管理员只读试跑。"
    :refresh-loading="loading"
    @back="returnToWorkbench"
    @refresh="loadCatalog"
  >
    <div class="assets-page" v-loading="loading">
      <el-alert
        v-if="error"
        type="error"
        :closable="false"
        show-icon
        :title="error"
        class="page-alert"
      />

      <!-- 搜索只在真有东西可搜时出现。空态里的搜索框是在假装这页已经有内容。 -->
      <div v-if="hasModules" class="toolbar">
        <el-input
          v-model="query"
          clearable
          placeholder="搜索系统、模块或工具"
          class="search-input"
        />
        <el-button type="primary" plain @click="openGuanceValidation">数据源联调</el-button>
        <el-button type="primary" @click="openNewAsset">接入模块</el-button>
      </div>

      <!--
        源没通时，这是本页唯一要紧的事。原来它是三步流程条里的第 3 步，
        排在「选模块」「看工具」后面——而它其实是前两步的前提。
      -->
      <section v-if="!realSourceReady" class="source-gate">
        <div class="source-gate-head">
          <div>
            <h2>
              {{ replayOnly
                ? '当前只有受控回放可用，还没有真实数据源'
                : '还不能取到真实证据：没有可用的数据源' }}
            </h2>
            <p v-if="replayOnly">
              回放能让工具跑起来、让你核对规则形状，但
              <b>它不是真实生产观测</b>——真实故障要等真源接通。
            </p>
            <p v-else>
              模块和绑定可以先离线配好，但
              <b>绑定的工具跑不起来，也做不了只读试跑</b>——这一步要等数据源接通。
            </p>
          </div>
          <!-- 同一个动作一页只出现一次：没有模块时空态已经在带路，这里就只陈述事实。 -->
          <el-button
            v-if="hasModules"
            type="primary"
            @click="openGuanceValidation"
          >去数据源联调</el-button>
        </div>
        <ul class="source-gate-list">
          <li v-for="source in blockedSources" :key="source.platform">
            <b>{{ source.platform }}</b>
            <span v-if="source.missing" class="missing">缺 {{ source.missing }}</span>
            <small>{{ source.detail }}</small>
          </li>
        </ul>
      </section>

      <div v-if="filteredSetupModules.length" class="assets-workspace">
        <aside class="asset-rail" aria-label="系统与模块">
          <template v-for="system in filteredModuleTree" :key="system.system">
            <div class="tree-system">{{ system.system }}</div>
            <button
              v-for="entry in system.modules"
              :key="moduleKey(entry.system, entry.service)"
              type="button"
              class="asset-item"
              :class="{ active: selectedModuleKey === moduleKey(entry.system, entry.service) }"
              @click="selectSetupModule(entry)"
            >
              <div>
                <b>{{ entry.service }}</b>
                <small>{{ entry.displayName }}</small>
              </div>
              <el-tag size="small" effect="plain" :type="moduleRailTagType(entry)">
                {{ moduleRailLabel(entry) }}
              </el-tag>
            </button>
          </template>
        </aside>

        <section v-if="selectedSetupModule" class="asset-panel">
          <header class="asset-panel-head">
            <div>
              <p class="scope-line">系统 · {{ selectedSetupModule.system }}</p>
              <h2>{{ selectedSetupModule.service }}</h2>
              <p>{{ selectedSetupModule.displayName }} · 系统模块</p>
            </div>
            <div class="asset-panel-actions">
              <el-button
                v-if="selectedSetupModule.asset"
                text
                @click="openAssetDetail(selectedSetupModule.asset)"
              >查看详情</el-button>
              <el-button type="primary" @click="openModuleConfig">
                {{ selectedSetupModule.asset?.origin === 'WORKSPACE' ? '修改模块配置' : '登记 / 接管模块' }}
              </el-button>
            </div>
          </header>

          <div class="setup-grid">
            <article>
              <span>1. 系统模块范围</span>
              <strong>{{ selectedSetupModule.asset?.environment || '未登记环境' }}</strong>
              <small>
                {{ selectedSetupModule.asset
                  ? ([selectedSetupModule.asset.region, selectedSetupModule.asset.cluster, selectedSetupModule.asset.namespace].filter(Boolean).join(' / ')
                    || '可补充区域、集群、命名空间')
                  : '先登记这个系统模块，才能绑定工具' }}
              </small>
            </article>
            <article>
              <span>2. 平台数据源</span>
              <strong>{{ sourceReady ? '观测云已就绪' : '观测云待联调' }}</strong>
              <small>{{ sourceReady ? '端点与凭据已配置' : '工具启用后仍需完成数据源联调' }}</small>
            </article>
            <article>
              <span>3. 可用取证工具</span>
              <strong>{{ readyToolCount }}/{{ selectedToolSetups.length }} 可运行</strong>
              <small>{{ enabledToolCount }} 已启用 · {{ selectedToolSetups.length - enabledToolCount }} 未启用</small>
            </article>
          </div>

          <section class="rules-section">
            <div class="section-heading">
              <h3>可用取证工具</h3>
              <small>每项工具列明还要配置什么；点开可改路由或试跑</small>
            </div>
            <div v-if="selectedToolSetups.length" class="rule-list">
              <article
                v-for="tool in selectedToolSetups"
                :key="tool.contractRef"
                class="rule-card"
                :class="{ active: selectedToolRef === tool.contractRef }"
              >
                <button
                  type="button"
                  class="rule-card-main"
                  @click="selectTool(tool)"
                >
                  <div>
                    <b>{{ signalKindLabel(tool.signalKind) }} · {{ tool.scenario }}</b>
                    <p>{{ tool.question }}</p>
                    <code>{{ tool.signalKind }} · {{ tool.contractRef }}</code>
                  </div>
                  <el-tag
                    :type="tool.status === 'READY' ? 'success' : tool.status === 'BLOCKED' ? 'warning' : 'info'"
                    size="small"
                  >{{ tool.statusLabel }}</el-tag>
                </button>

                <div v-if="selectedToolRef === tool.contractRef" class="rule-detail">
                  <div class="checklist">
                    <div
                      v-for="item in tool.checklist"
                      :key="item.key"
                      class="checklist-item"
                      :class="{ done: item.done }"
                    >
                      <span class="check-mark">{{ item.done ? '✓' : '○' }}</span>
                      <div>
                        <b>{{ item.label }}</b>
                        <small>{{ item.detail }}</small>
                      </div>
                    </div>
                  </div>

                  <div v-if="tool.contract?.blockers?.length" class="blocker-box">
                    <b>阻断点</b>
                    <ul>
                      <li v-for="blocker in tool.contract.blockers" :key="blocker">{{ blocker }}</li>
                    </ul>
                  </div>

                  <!--
                    规则规格原来是另一个页面（查询规则说明书）的全部内容。两个页面调的是
                    同一组接口、渲染的是同一份数据，只是一个能改、一个只能看，于是配一条
                    规则要在两页之间来回跳。规格是**配这条规则时要对照的东西**，放这里。
                    默认折叠：核对是偶尔需要，不该每次展开工具都占半屏。
                  -->
                  <details v-if="tool.contract" class="contract-spec">
                    <summary>规则明细：要传什么 · 返回什么 · 预算</summary>
                    <div class="axis-grid">
                      <article>
                        <span>路由</span>
                        <strong>{{ routeOriginLabel(tool.contract.route.origin) }}</strong>
                        <small>{{ tool.contract.route.platforms.join(' → ') || '未选择适配器' }}</small>
                      </article>
                      <article>
                        <span>绑定</span>
                        <strong>{{ bindingStatusLabel(tool.contract.binding.status) }}</strong>
                        <small>{{ tool.contract.binding.bindingRef || '未绑定' }}</small>
                      </article>
                      <article>
                        <span>预算</span>
                        <strong>{{ tool.contract.budget.queryCount }} 查询 · {{ tool.contract.budget.maxRows }} 行</strong>
                        <small>超时 {{ formatTrialTimeout(tool.contract.budget.timeoutMs) }}</small>
                      </article>
                    </div>

                    <div class="detail-grid">
                      <section class="detail-card">
                        <div class="card-title"><small>INPUT</small><h3>需要传什么</h3></div>
                        <el-table :data="tool.contract.parameters" size="small">
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
                            v-for="field in tool.contract.canonicalOutputs"
                            :key="field"
                            effect="plain"
                          >{{ field }}</el-tag>
                        </div>
                      </section>
                      <section class="detail-card">
                        <div class="card-title"><small>FIXED</small><h3>服务端固定条件</h3></div>
                        <div class="tag-list">
                          <el-tag
                            v-for="condition in tool.contract.fixedConditions"
                            :key="condition"
                            type="info"
                            effect="plain"
                          >{{ condition }}</el-tag>
                          <span v-if="!tool.contract.fixedConditions.length" class="empty-inline">未记录</span>
                        </div>
                      </section>
                    </div>
                  </details>

                  <div class="rule-actions">
                    <el-button
                      v-if="toolNeedsModuleConfig(tool)"
                      type="primary"
                      plain
                      @click="openModuleConfig"
                    >去配置模块与绑定</el-button>
                    <el-button
                      v-if="tool.contract"
                      text
                      type="primary"
                      @click="openRouteEditor(toolRow(tool)!)"
                    >修改路由</el-button>
                    <el-button
                      v-if="tool.contract?.route.origin === 'WORKSPACE'"
                      text
                      @click="withdrawRoute(toolRow(tool)!)"
                    >恢复默认路由</el-button>
                    <el-button
                      v-if="tool.checklist.some(item => item.key === 'source' && !item.done)"
                      text
                      @click="openGuanceValidation"
                    >去数据源联调</el-button>
                    <el-tooltip
                      :content="toolRow(tool) ? rowTrialBlocker(toolRow(tool)!) : '先启用并绑定这条工具'"
                      :disabled="Boolean(toolRow(tool)) && !rowTrialBlocker(toolRow(tool)!)"
                    >
                      <span>
                        <el-button
                          type="primary"
                          :disabled="!toolRow(tool) || Boolean(rowTrialBlocker(toolRow(tool)!))"
                          @click="openTrialForRow(toolRow(tool)!)"
                        >管理员只读试跑</el-button>
                      </span>
                    </el-tooltip>
                    <small
                      v-if="toolRow(tool) && rowTrialBlocker(toolRow(tool)!)"
                      class="trial-blocker"
                    >{{ rowTrialBlocker(toolRow(tool)!) }}</small>
                  </div>

                  <section v-if="tool.contract" class="trial-history">
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
                      <el-table-column label="资产版本" width="100">
                        <template #default="scope">v{{ scope.row.assetVersion }}</template>
                      </el-table-column>
                      <el-table-column label="返回字段" min-width="180">
                        <template #default="scope">{{ scope.row.canonicalFields.join('、') || '无' }}</template>
                      </el-table-column>
                      <el-table-column prop="durationMs" label="耗时" width="90" />
                      <el-table-column prop="actor" label="操作人" width="120" />
                      <el-table-column prop="completedAt" label="完成时间" min-width="160" />
                    </el-table>
                    <el-empty v-else :image-size="40" description="还没有只读试跑记录" />
                  </section>
                </div>
              </article>
            </div>
            <!--
              这一格不是用户能在页面里解决的：可绑定的工具来自部署侧发布的已审核查询
              规则。原来这里放了个「查看查询规则说明书」的按钮，跳过去也是同一句话的
              另一种说法——它不会让人离答案更近，所以只留下要找谁办。
            -->
            <el-empty
              v-else
              description="部署侧还没有为这个模块发布可绑定的取证工具；需要先让已审核的查询规则随部署发布。"
            />
          </section>
        </section>
      </div>
      <!--
        空态分两种，原来只有一句话概括了它们：源没通时先去联调，源通了就登记模块。
        「登记模块」在源没通时仍然可做（离线准备），所以它留着，只是不抢主位。
      -->
      <section v-else-if="!hasModules" class="setup-empty">
        <h2>{{ setupGate === 'NEEDS_SOURCE' ? '先接通一个真实数据源' : '接入第一个系统模块' }}</h2>
        <p v-if="setupGate === 'NEEDS_SOURCE'">
          还没有任何模块，也还没有真实数据源。两件事都要做，但先做数据源——
          {{ replayOnly
            ? '现在只有受控回放，模块登记完也仍然取不到真实证据。'
            : '没有源的话，模块登记完也仍然取不到证据。' }}
        </p>
        <p v-else>
          真实数据源已就绪。登记你要排障的第一个系统模块，然后给它绑定取证工具。
        </p>
        <div class="setup-empty-actions">
          <el-button
            v-if="setupGate === 'NEEDS_SOURCE'"
            type="primary"
            @click="openGuanceValidation"
          >去数据源联调</el-button>
          <el-button
            :type="setupGate === 'NEEDS_SOURCE' ? 'default' : 'primary'"
            @click="openNewAsset"
          >{{ setupGate === 'NEEDS_SOURCE' ? '仍然先登记模块' : '接入第一个模块' }}</el-button>
        </div>
      </section>
      <el-empty v-else description="没有匹配当前搜索的系统模块" />
    </div>

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
          title="只验证这条查询规则能否返回规范证据，不会创建排障单，也不代表真源已验收。"
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
      v-model="assetDetailOpen"
      title="模块取证配置详情"
      width="720px"
      destroy-on-close
    >
      <template v-if="assetDetail">
        <div class="asset-detail-heading">
          <div>
            <small>{{ assetDetail.origin === 'WORKSPACE' ? `Workspace v${assetDetail.version}` : '部署默认' }}</small>
            <h3>{{ assetDetail.displayName || assetDetail.service }}</h3>
            <p>{{ assetDetail.system }} / {{ assetDetail.service }}</p>
          </div>
          <el-tag :type="assetDetail.enabled ? 'success' : 'info'">
            {{ assetDetail.enabled ? '已启用' : '已停用' }}
          </el-tag>
        </div>

        <dl class="asset-detail-grid">
          <div><dt>观测平台</dt><dd>{{ assetDetail.platform }}</dd></div>
          <div><dt>环境</dt><dd>{{ assetDetail.environment || '未记录' }}</dd></div>
          <div><dt>区域</dt><dd>{{ assetDetail.region || '未记录' }}</dd></div>
          <div><dt>集群</dt><dd>{{ assetDetail.cluster || '未记录' }}</dd></div>
          <div><dt>命名空间</dt><dd>{{ assetDetail.namespace || '未记录' }}</dd></div>
          <div><dt>最后变更</dt><dd>{{ assetDetail.changedAt || '未记录' }}</dd></div>
          <div><dt>变更人</dt><dd>{{ assetDetail.changedBy || '未记录' }}</dd></div>
          <div class="wide"><dt>变更原因</dt><dd>{{ assetDetail.reason || '未记录' }}</dd></div>
        </dl>

        <section class="asset-detail-section">
          <h4>已绑定查询规则</h4>
          <div class="asset-contracts">
            <el-tag
              v-for="(contractRef, signalKind) in assetDetail.signalBindings"
              :key="`${signalKind}/${contractRef}`"
              effect="plain"
            >{{ signalKind }} · {{ contractRef }}</el-tag>
            <span v-if="!Object.keys(assetDetail.signalBindings).length" class="empty-inline">未绑定</span>
          </div>
        </section>

        <section class="asset-detail-section">
          <h4>查询使用的资源标识</h4>
          <dl v-if="Object.keys(assetDetail.parameters).length" class="asset-parameter-list">
            <div v-for="(value, name) in assetDetail.parameters" :key="name">
              <dt>{{ assetParameterLabel(name) }}</dt><dd>{{ value }}</dd>
            </div>
          </dl>
          <p v-else class="empty-inline">当前查询规则不需要额外资源标识。</p>
        </section>

        <el-alert
          type="info"
          :closable="false"
          title="修改会保存为新版本，不会覆盖原来的生产审计记录。"
        />
      </template>
      <template #footer>
        <el-button @click="assetDetailOpen = false">关闭</el-button>
        <el-button v-if="assetDetail" type="primary" @click="editAssetFromDetail">
          {{ assetDetail.origin === 'WORKSPACE' ? '修改模块配置' : '接管模块配置' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="assetDialogOpen"
      :title="assetForm.expectedVersion ? '修改系统模块取证配置' : '登记系统模块取证配置'"
      width="760px"
      destroy-on-close
    >
      <!--
        这条边界原来印在页面顶部，对着一个还没开始配的人说「你填的东西不含密钥」——
        那时他还没填任何东西。它回答的是「我在这个表单里填的会被存成什么」，
        所以贴在表单上。
      -->
      <el-alert
        type="info"
        :closable="false"
        class="asset-dialog-alert"
        title="这里只保存系统、模块范围和查询规则引用。API Key、端点主机、原始 DQL 与原始日志不进入配置，由服务端保管。"
      />
      <el-alert
        type="warning"
        :closable="false"
        class="asset-dialog-alert"
        title="修改会保存为新版本，不会覆盖原来的生产审计记录；已停用的 Workspace 资产也会阻止回落到部署默认。"
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
  </CapabilityWorkspaceShell>
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
import CapabilityWorkspaceShell from './CapabilityWorkspaceShell.vue'
import {
  assetParameterLabel,
  bindingStatusLabel,
  buildModuleToolSetups,
  directTrialBlockReason,
  listSetupModules,
  mergeObservabilityAssetContractOptions,
  observabilityAssetDraftReadiness,
  parameterSourceLabel,
  routeOriginLabel,
  signalKindLabel,
  moveOrderedItem,
  type ModuleToolSetup,
  type SetupModuleEntry,
} from './evidenceCatalog'
import {
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
const selectedModuleKey = ref('')
const selectedToolRef = ref('')
const selectedKey = ref('')
const routeDialogOpen = ref(false)
const routeSaving = ref(false)
const routeTarget = ref<ContractRow | null>(null)
const routePlatforms = ref<string[]>([])
const routeReason = ref('')
const assetDialogOpen = ref(false)
const assetSaving = ref(false)
const assetForm = ref<AssetForm>(emptyAssetForm())
const assetDetailOpen = ref(false)
const assetDetail = ref<ObservabilityAsset | null>(null)
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

const sourceReady = computed(() => (catalog.value?.sources || []).some(source =>
  source.status === 'READY'))

/**
 * 受控回放**不是真源**。`formalProjection` 早就定了这条规矩（`recorded-replay*`
 * 单独归成 `RECORDED_REPLAY`，并明写「页面不会把回放证据描述成真实生产观测」）。
 * 本页必须守同一条：夹具适配器在 demo profile 下是 READY 的，只看
 * `some(status === 'READY')` 会让页面对着一台三个真源全 DISABLED 的机器说
 * 「数据源已就绪」——那正是投产清单里要挡的「系统假装能取证」。
 *
 * 不要因此去改上面那个 `sourceReady`：它喂给 `buildModuleToolSetups`，回答的是
 * 「这条工具能不能跑起来」，在那个问题上回放算数。两个问题不同，别并成一个。
 */
function isRecordedReplay(platform: string) {
  return platform.startsWith('recorded-replay')
}

const realSourceReady = computed(() => (catalog.value?.sources || []).some(source =>
  source.status === 'READY' && !isRecordedReplay(source.platform)))

/** 只有回放可用：能跑，但跑出来的不是真实生产观测。这是最容易被说成「就绪」的一格。 */
const replayOnly = computed(() => !realSourceReady.value && sourceReady.value)

/**
 * 每个平台**具体缺什么**。页面原来只说「观测云待联调」，那句话对着一个
 * `endpointStatus=MISSING、credentialStatus=MISSING` 的适配器和一个只是没开开关的
 * 适配器说的是同一句话，而这两件事要找的人完全不同。
 */
const blockedSources = computed(() => (catalog.value?.sources || [])
  .filter(source => source.status !== 'READY' && !isRecordedReplay(source.platform))
  .map(source => ({
    platform: source.platform,
    detail: source.detail,
    missing: [
      source.endpointStatus === 'MISSING' ? '端点' : '',
      source.credentialStatus === 'MISSING' ? '凭据' : '',
    ].filter(Boolean).join(' · '),
  })))

const setupModules = computed(() => listSetupModules(
  catalog.value,
  assetCatalog.value?.assets || [],
))

const hasModules = computed(() => setupModules.value.length > 0)

/**
 * 真实依赖顺序是「先有可用的源 → 再登记模块 → 再给模块绑工具 → 才谈得上试跑」。
 * 页面原先把「数据源联调」写成第 3 步，于是可以把前两步全填完，再发现根本没有源
 * 可用。这里让页面按你**实际站在哪一格**说话，而不是永远摆出同一张流程图。
 *
 * 注意闸门只决定强调什么，不决定隐藏什么：已登记的模块在源没通时照常列出——
 * 补范围、改绑定都是离线能做的准备，藏起来等于把人已经做过的工作抹掉。
 */
const setupGate = computed<'NEEDS_SOURCE' | 'NEEDS_MODULE' | 'SOURCE_BLOCKED' | 'READY'>(() => {
  if (!hasModules.value) return realSourceReady.value ? 'NEEDS_MODULE' : 'NEEDS_SOURCE'
  return realSourceReady.value ? 'READY' : 'SOURCE_BLOCKED'
})

const filteredSetupModules = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  if (!keyword) return setupModules.value
  return setupModules.value.filter(entry => {
    const tools = buildModuleToolSetups({
      options: assetCatalog.value?.contracts || [],
      module: entry.module,
      asset: entry.asset,
      sourceReady: sourceReady.value,
    })
    return [
      entry.system,
      entry.service,
      entry.displayName,
      entry.asset?.environment || '',
      ...tools.flatMap(tool => [tool.signalKind, tool.contractRef, tool.scenario, signalKindLabel(tool.signalKind)]),
    ].some(value => value.toLowerCase().includes(keyword))
  })
})

const filteredModuleTree = computed(() => {
  const systems = new Map<string, SetupModuleEntry[]>()
  for (const entry of filteredSetupModules.value) {
    const modules = systems.get(entry.system) || []
    modules.push(entry)
    systems.set(entry.system, modules)
  }
  return [...systems.entries()].map(([system, modules]) => ({ system, modules }))
})

const selectedSetupModule = computed(() => filteredSetupModules.value.find(entry =>
  moduleKey(entry.system, entry.service) === selectedModuleKey.value)
  || filteredSetupModules.value[0]
  || null)

const selectedAsset = computed(() => selectedSetupModule.value?.asset || null)

const selectedToolSetups = computed(() => {
  const entry = selectedSetupModule.value
  if (!entry) return [] as ModuleToolSetup[]
  return buildModuleToolSetups({
    options: assetCatalog.value?.contracts || [],
    module: entry.module,
    asset: entry.asset,
    sourceReady: sourceReady.value,
  })
})

const enabledToolCount = computed(() => selectedToolSetups.value.filter(tool => tool.enabled).length)
const readyToolCount = computed(() => selectedToolSetups.value.filter(tool => tool.status === 'READY').length)

const selectedAssetRows = computed(() => {
  const entry = selectedSetupModule.value
  if (!entry) return []
  return allRows.value.filter(row =>
    row.system.trim().toLowerCase() === entry.system.trim().toLowerCase()
      && row.service.trim().toLowerCase() === entry.service.trim().toLowerCase())
})

const selectedRow = computed(() => {
  const tool = selectedToolSetups.value.find(item => item.contractRef === selectedToolRef.value)
  if (tool?.contract && selectedSetupModule.value) {
    return {
      system: selectedSetupModule.value.system,
      service: selectedSetupModule.value.service,
      module: selectedSetupModule.value.module || {
        service: selectedSetupModule.value.service,
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
      contract: tool.contract,
    } satisfies ContractRow
  }
  return selectedAssetRows.value.find(row =>
    contractKey(row.system, row.service, row.contract) === selectedKey.value)
    || selectedAssetRows.value[0]
    || null
})

const assetContractOptions = computed(() => mergeObservabilityAssetContractOptions(
  assetCatalog.value?.contracts || [],
  allRows.value.map(row => row.contract),
))
const contractGroups = computed(() => {
  const grouped = new Map<string, ObservabilityAssetContractOption[]>()
  for (const option of assetContractOptions.value) {
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
  return assetContractOptions.value.filter(option => selected.has(option.contractRef))
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

function moduleKey(system: string, service: string) {
  return `${system}/${service}`
}

function rowTrialBlocker(row: ContractRow) {
  return directTrialBlockReason(row.contract, selectedAsset.value)
}

function toolRow(tool: ModuleToolSetup): ContractRow | null {
  const entry = selectedSetupModule.value
  if (!entry || !tool.contract) return null
  return {
    system: entry.system,
    service: entry.service,
    module: entry.module || {
      service: entry.service,
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
    contract: tool.contract,
  }
}

function moduleRailLabel(entry: SetupModuleEntry) {
  if (!entry.asset) return '未登记'
  if (entry.asset.origin !== 'WORKSPACE') return '部署默认'
  const tools = buildModuleToolSetups({
    options: assetCatalog.value?.contracts || [],
    module: entry.module,
    asset: entry.asset,
    sourceReady: sourceReady.value,
  })
  const ready = tools.filter(tool => tool.status === 'READY').length
  return `${ready}/${tools.length || entry.module?.contracts.length || 0}`
}

function moduleRailTagType(entry: SetupModuleEntry): 'success' | 'warning' | 'info' | 'danger' {
  if (!entry.asset || entry.asset.origin !== 'WORKSPACE') return 'info'
  const tools = buildModuleToolSetups({
    options: assetCatalog.value?.contracts || [],
    module: entry.module,
    asset: entry.asset,
    sourceReady: sourceReady.value,
  })
  if (!tools.length) return 'info'
  if (tools.every(tool => tool.status === 'READY')) return 'success'
  if (tools.some(tool => tool.enabled)) return 'warning'
  return 'info'
}

function selectSetupModule(entry: SetupModuleEntry) {
  selectedModuleKey.value = moduleKey(entry.system, entry.service)
  const tools = buildModuleToolSetups({
    options: assetCatalog.value?.contracts || [],
    module: entry.module,
    asset: entry.asset,
    sourceReady: sourceReady.value,
  })
  const first = tools.find(tool => tool.status === 'BLOCKED')
    || tools.find(tool => tool.status === 'READY')
    || tools[0]
  selectedToolRef.value = first?.contractRef || ''
  selectedKey.value = first?.contract
    ? contractKey(entry.system, entry.service, first.contract)
    : ''
}

function selectTool(tool: ModuleToolSetup) {
  selectedToolRef.value = tool.contractRef
  const entry = selectedSetupModule.value
  if (entry && tool.contract) {
    selectedKey.value = contractKey(entry.system, entry.service, tool.contract)
  }
}

function toolNeedsModuleConfig(tool: ModuleToolSetup) {
  return tool.checklist.some(item =>
    (item.key === 'enable' || item.key === 'workspace' || item.key === 'params') && !item.done)
}

function openModuleConfig() {
  const entry = selectedSetupModule.value
  if (entry?.asset) {
    openAssetEditor(entry.asset)
    return
  }
  openNewAsset()
}

function contractKey(system: string, service: string, contract: EvidenceQueryContract) {
  return `${system}/${service}/${contract.signalKind}/${contract.contractRef}`
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

function openTrialForRow(row: ContractRow) {
  selectedKey.value = contractKey(row.system, row.service, row.contract)
  if (rowTrialBlocker(row)) return
  trialTarget.value = row
  trialParameterValues.value = Object.fromEntries(
    row.contract.parameters
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
    const preferredSystem = typeof route.query.system === 'string' ? route.query.system : ''
    const preferredService = typeof route.query.service === 'string' ? route.query.service : ''
    const preferred = setupModules.value.find(entry =>
      (!preferredSystem || entry.system === preferredSystem)
        && (!preferredService || entry.service === preferredService))
      || setupModules.value.find(entry =>
        moduleKey(entry.system, entry.service) === selectedModuleKey.value)
      || setupModules.value[0]
    if (preferred) selectSetupModule(preferred)
    else {
      selectedModuleKey.value = ''
      selectedToolRef.value = ''
      selectedKey.value = ''
    }
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '取证接入配置加载失败'
  } finally {
    loading.value = false
  }
}

function openNewAsset() {
  const current = selectedSetupModule.value
  assetForm.value = emptyAssetForm()
  if (current) {
    assetForm.value.system = current.system
    assetForm.value.service = current.service
    assetForm.value.displayName = current.displayName || current.service
    assetForm.value.scopeLocked = Boolean(current.module)
    if (current.asset) {
      assetForm.value.environment = current.asset.environment || ''
      assetForm.value.region = current.asset.region || ''
      assetForm.value.cluster = current.asset.cluster || ''
      assetForm.value.namespace = current.asset.namespace || ''
      assetForm.value.contractRefs = { ...current.asset.signalBindings }
      assetForm.value.parameters = { ...current.asset.parameters }
      assetForm.value.enabled = current.asset.enabled
      assetForm.value.expectedVersion = current.asset.origin === 'WORKSPACE'
        ? current.asset.version
        : undefined
    }
  }
  if (typeof route.query.system === 'string' && route.query.system) {
    assetForm.value.system = route.query.system
    assetForm.value.scopeLocked = true
  }
  if (typeof route.query.service === 'string' && route.query.service) {
    assetForm.value.service = route.query.service
    assetForm.value.displayName = route.query.service
    assetForm.value.scopeLocked = true
  }
  assetDialogOpen.value = true
}

function openAssetDetail(asset: ObservabilityAsset) {
  assetDetail.value = asset
  assetDetailOpen.value = true
}

function editAssetFromDetail() {
  if (!assetDetail.value) return
  const asset = assetDetail.value
  assetDetailOpen.value = false
  openAssetEditor(asset)
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
    ElMessage.success(form.expectedVersion ? '模块取证配置已追加新版本' : '模块取证配置已登记')
    await loadCatalog()
  } catch (failure) {
    ElMessage.error(failure instanceof Error ? failure.message : '模块取证配置保存失败')
  } finally {
    assetSaving.value = false
  }
}

function returnToWorkbench() {
  void router.push(safeTroubleshootingReturnPath(route.query.returnTo) || '/troubleshooting')
}

/**
 * 规则预算里的超时是毫秒数。原来这个格式化函数在查询规则说明书那页各写了一份，
 * 与 formalProjection 里那个同名函数不是一回事（那个吃 ISO duration 字符串）。
 */
function formatTrialTimeout(milliseconds: number) {
  if (milliseconds >= 1000) return `${milliseconds / 1000} 秒`
  return `${milliseconds} 毫秒`
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

watch(selectedKey, () => {
  void loadTrialHistory()
})

onMounted(loadCatalog)
</script>

<style scoped>
.assets-page { display: grid; gap: 14px; }
.page-alert { margin-bottom: 2px; }
.toolbar { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
.search-input { flex: 1; min-width: min(100%, 240px); }
/* 源没通时页面顶部那块闸门。它替掉了原来常驻的三步流程条。 */
.source-gate {
  padding: 16px 18px;
  border: 1px solid var(--mc-warning-border, var(--mc-border-light));
  border-radius: 12px;
  background: var(--mc-warning-bg, var(--mc-bg-muted));
}
.source-gate-head { display: flex; flex-wrap: wrap; gap: 14px; align-items: flex-start; justify-content: space-between; }
.source-gate-head h2 { margin: 0 0 6px; color: var(--mc-text-primary); font-size: 16px; letter-spacing: -.01em; }
.source-gate-head p { margin: 0; max-width: 62ch; color: var(--mc-text-secondary); font-size: 13px; line-height: 1.6; }
.source-gate-list { display: grid; gap: 6px; margin: 14px 0 0; padding: 0; list-style: none; }
.source-gate-list li { display: flex; flex-wrap: wrap; gap: 8px; align-items: baseline; font-size: 12px; }
.source-gate-list b { color: var(--mc-text-primary); font-family: var(--mc-font-mono, monospace); }
.source-gate-list .missing { color: var(--mc-danger, #c0392b); font-weight: 600; }
.source-gate-list small { color: var(--mc-text-tertiary); }

/* 首次进入的空态。唯一能做的动作是主按钮，不再和三个跳转按钮抢注意力。 */
.setup-empty { display: grid; justify-items: center; gap: 10px; padding: 56px 24px; text-align: center; }
.setup-empty h2 { margin: 0; color: var(--mc-text-primary); font-size: 18px; letter-spacing: -.02em; }
.setup-empty p { margin: 0; max-width: 52ch; color: var(--mc-text-secondary); font-size: 13px; line-height: 1.7; }
.setup-empty-actions { display: flex; flex-wrap: wrap; gap: 10px; justify-content: center; margin-top: 6px; }

/* 折进工具详情的规则规格（原「查询规则说明书」整页内容）。 */
.contract-spec { margin-top: 12px; border-top: 1px dashed var(--mc-border-light); padding-top: 10px; }
.contract-spec > summary { color: var(--mc-text-secondary); font-size: 12px; cursor: pointer; user-select: none; }
.contract-spec > summary:hover { color: var(--mc-primary); }
.contract-spec .axis-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 10px;
  margin-top: 12px;
}
.contract-spec .axis-grid article { padding: 10px 12px; border-radius: 10px; background: var(--mc-bg-muted); }
.contract-spec .axis-grid span { display: block; color: var(--mc-text-tertiary); font-size: 11px; }
.contract-spec .axis-grid strong { display: block; margin: 2px 0; color: var(--mc-text-primary); font-size: 13px; }
.contract-spec .axis-grid small { color: var(--mc-text-secondary); font-size: 11px; word-break: break-all; }
.contract-spec .detail-grid { display: grid; gap: 12px; margin-top: 12px; }
.contract-spec .detail-card { padding: 12px 14px; border: 1px solid var(--mc-border-light); border-radius: 10px; }
.contract-spec .card-title { display: flex; gap: 8px; align-items: baseline; margin-bottom: 8px; }
.contract-spec .card-title small { color: var(--mc-primary); font-size: 10px; font-weight: 800; letter-spacing: .1em; }
.contract-spec .card-title h3 { margin: 0; color: var(--mc-text-primary); font-size: 13px; }
.contract-spec .empty-inline { color: var(--mc-text-tertiary); font-size: 12px; }
.assets-workspace {
  display: grid;
  grid-template-columns: minmax(240px, 300px) minmax(0, 1fr);
  min-height: 560px;
  border: 1px solid var(--mc-border-light);
  border-radius: 14px;
  overflow: hidden;
  background: var(--mc-bg-elevated);
}
.asset-rail { padding: 12px; border-right: 1px solid var(--mc-border-light); background: var(--mc-bg-muted); overflow: auto; }
.tree-system {
  margin: 10px 8px 6px;
  color: var(--mc-text-secondary);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: .04em;
  text-transform: uppercase;
}
.tree-system:first-child { margin-top: 2px; }
.asset-item {
  display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; width: 100%;
  margin: 0 0 6px; padding: 12px; border: 0; border-radius: 10px; color: inherit; background: transparent;
  text-align: left; cursor: pointer;
}
.asset-item:hover { background: var(--mc-bg); }
.asset-item.active { color: var(--mc-primary); background: var(--mc-primary-bg); box-shadow: inset 3px 0 var(--mc-primary); }
.asset-item b { display: block; font-size: 13px; }
.asset-item small { display: block; margin-top: 4px; color: var(--mc-text-secondary); font-size: 11px; }
.asset-panel { min-width: 0; padding: 18px; overflow: auto; }
.asset-panel-head { display: flex; justify-content: space-between; gap: 16px; margin-bottom: 14px; }
.asset-panel-head h2 { margin: 4px 0 6px; font-size: 22px; }
.asset-panel-head p { margin: 0; color: var(--mc-text-secondary); }
.asset-panel-actions { display: flex; gap: 8px; flex: 0 0 auto; }
.scope-line { margin: 0; color: var(--mc-primary); font: 12px ui-monospace, SFMono-Regular, Menlo, monospace; }
.setup-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin-bottom: 16px; }
.setup-grid article { display: flex; flex-direction: column; min-width: 0; padding: 14px; border-radius: 10px; background: var(--mc-bg-muted); }
.setup-grid span { color: var(--mc-text-secondary); font-size: 11px; }
.setup-grid strong { margin-top: 6px; font-size: 14px; }
.setup-grid small { margin-top: 5px; color: var(--mc-text-secondary); overflow-wrap: anywhere; }
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
.checklist { display: grid; gap: 8px; margin: 14px 0; }
.checklist-item {
  display: grid;
  grid-template-columns: 22px minmax(0, 1fr);
  gap: 10px;
  padding: 12px 14px;
  border-radius: 10px;
  background: var(--mc-bg-muted);
}
.checklist-item.done { background: color-mix(in srgb, #17724a 8%, var(--mc-bg)); }
.check-mark {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  color: var(--mc-text-secondary);
  background: var(--mc-bg);
  font-size: 12px;
  font-weight: 700;
}
.checklist-item.done .check-mark { color: #17724a; background: color-mix(in srgb, #17724a 14%, var(--mc-bg)); }
.checklist-item b { display: block; font-size: 13px; }
.checklist-item small { display: block; margin-top: 4px; color: var(--mc-text-secondary); line-height: 1.45; }
.blocker-box {
  padding: 12px 14px; border: 1px solid color-mix(in srgb, #b93838 30%, var(--mc-border));
  border-radius: 10px; color: #b93838; background: color-mix(in srgb, #b93838 6%, var(--mc-bg));
}
.blocker-box ul { margin: 8px 0 0; padding-left: 18px; line-height: 1.6; }
.rule-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 12px; }
.trial-blocker { max-width: 320px; color: var(--mc-text-secondary); font-size: 11px; line-height: 1.45; }
.trial-history { margin-top: 14px; padding: 14px; border: 1px solid var(--mc-border-light); border-radius: 10px; }
.trial-history-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.trial-history-heading small { color: var(--mc-primary); font-weight: 700; letter-spacing: .12em; }
.trial-history-heading h3 { margin: 3px 0 0; font-size: 14px; }
.trial-time-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.trial-time-grid :deep(.el-select) { width: 100%; }
.trial-result { display: grid; gap: 12px; padding: 16px; border: 1px solid color-mix(in srgb, #17724a 30%, var(--mc-border)); border-radius: 10px; background: color-mix(in srgb, #17724a 6%, var(--mc-bg)); }
.trial-result small { display: block; margin-top: 5px; color: var(--mc-text-secondary); }
.trial-result p { margin: 0; color: var(--mc-text-secondary); font-size: 12px; line-height: 1.6; }
.tag-list { display: flex; flex-wrap: wrap; gap: 8px; }
.route-scope { display: flex; flex-direction: column; margin-bottom: 18px; padding: 14px; border-radius: 10px; background: var(--mc-bg-muted); }
.route-scope span, .route-scope small { color: var(--mc-text-secondary); font-size: 12px; }
.route-scope b { margin: 5px 0; }
.source-checkboxes { display: flex; flex-direction: column; align-items: flex-start; }
.source-checkboxes small { margin-left: 8px; color: var(--mc-text-secondary); }
.form-help { margin: 8px 0 0; color: var(--mc-text-secondary); font-size: 12px; line-height: 1.6; }
.route-priority { margin-top: 14px; padding: 12px; border: 1px solid var(--mc-border-light); border-radius: 9px; }
.route-priority>p { margin: 0 0 8px; color: var(--mc-text-secondary); font-size: 12px; }
.priority-row { display: grid; grid-template-columns: 26px minmax(0, 1fr) auto auto; align-items: center; gap: 6px; min-height: 34px; }
.priority-row em { display: grid; place-items: center; width: 22px; height: 22px; border-radius: 50%; color: var(--mc-primary); background: var(--mc-primary-bg); font-style: normal; font-size: 11px; font-weight: 700; }
.asset-dialog-alert { margin-bottom: 18px; }
.asset-form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 14px; width: 100%; }
.contract-binding-grid { display: grid; gap: 8px; width: 100%; }
.contract-binding-row { display: grid; grid-template-columns: minmax(180px, .65fr) minmax(280px, 1.35fr); align-items: center; gap: 16px; padding: 12px; border: 1px solid var(--mc-border-light); border-radius: 9px; }
.contract-binding-row small { display: block; margin-top: 4px; color: var(--mc-text-secondary); }
.contract-binding-row :deep(.el-select) { width: 100%; }
.asset-parameter-section { margin: 2px 0 18px; padding: 16px; border: 1px solid color-mix(in srgb, var(--mc-primary) 30%, var(--mc-border)); border-radius: 10px; background: color-mix(in srgb, var(--mc-primary) 5%, var(--mc-bg)); }
.asset-parameter-heading { display: flex; flex-direction: column; margin-bottom: 12px; }
.asset-parameter-heading small { margin-top: 5px; color: var(--mc-text-secondary); }
.asset-enabled-row { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin-bottom: 18px; padding: 14px; border-radius: 10px; background: var(--mc-bg-muted); }
.asset-enabled-row small { display: block; margin-top: 4px; color: var(--mc-text-secondary); }
.asset-detail-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; padding-bottom: 18px; border-bottom: 1px solid var(--mc-border-light); }
.asset-detail-heading small { color: var(--mc-primary); font-weight: 700; }
.asset-detail-heading h3 { margin: 5px 0; font-size: 22px; }
.asset-detail-heading p { margin: 0; color: var(--mc-text-secondary); }
.asset-detail-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0; margin: 18px 0; border-top: 1px solid var(--mc-border-light); border-left: 1px solid var(--mc-border-light); }
.asset-detail-grid>div { min-width: 0; padding: 13px 14px; border-right: 1px solid var(--mc-border-light); border-bottom: 1px solid var(--mc-border-light); }
.asset-detail-grid>div.wide { grid-column: 1 / -1; }
.asset-detail-grid dt, .asset-parameter-list dt { color: var(--mc-text-secondary); font-size: 11px; }
.asset-detail-grid dd, .asset-parameter-list dd { margin: 5px 0 0; overflow-wrap: anywhere; }
.asset-detail-section { margin: 18px 0; }
.asset-detail-section h4 { margin: 0 0 10px; font-size: 14px; }
.asset-parameter-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; margin: 0; }
.asset-parameter-list>div { padding: 12px; border-radius: 8px; background: var(--mc-bg-muted); }
.asset-contracts { display: flex; flex-wrap: wrap; gap: 6px; }
@media (max-width: 960px) {
  .assets-workspace, .setup-grid, .setup-workflow, .asset-detail-grid, .asset-parameter-list, .asset-form-grid, .contract-binding-row, .trial-time-grid { grid-template-columns: 1fr; }
  .asset-rail { max-height: 240px; border-right: 0; border-bottom: 1px solid var(--mc-border-light); }
  .asset-panel-head { flex-direction: column; }
}
</style>


