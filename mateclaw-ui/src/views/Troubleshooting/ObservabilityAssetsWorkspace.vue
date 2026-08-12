<template>
  <CapabilityWorkspaceShell
    :eyebrow="setupSection === 'modules' ? '智能排障' : '更多配置'"
    :title="activeSetupSection.label"
    :description="activeSetupSection.description"
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

      <!-- 三个子菜单共用同一套「搜索 + 列表 + 行操作」管理方式。 -->
      <div v-if="showListToolbar" class="toolbar">
        <el-input
          v-model="query"
          clearable
          :placeholder="listSearchPlaceholder"
          class="search-input"
        />
        <el-select
          v-if="setupSection === 'tools'"
          v-model="contractScopeFilter"
          class="status-filter"
          aria-label="按作用域筛选"
        >
          <el-option label="全部作用域" value="ALL" />
          <el-option label="通用" value="GENERIC" />
          <el-option label="指定系统" value="SYSTEM" />
          <el-option label="指定模块" value="MODULE" />
        </el-select>
        <template v-if="setupSection === 'modules'">
          <el-button @click="moduleChooserOpen = true">新增模块</el-button>
          <el-button type="primary" @click="openNewSystem">新增系统</el-button>
        </template>
        <el-button
          v-if="setupSection === 'tools'"
          type="primary"
          @click="openNewEvidenceContract"
        >新增取证方法</el-button>
        <el-button v-if="setupSection === 'source'" type="primary" :loading="loading" @click="loadCatalog">刷新连接状态</el-button>
      </div>

      <!--
        源没通时，这是本页唯一要紧的事。原来它是三步流程条里的第 3 步，
        排在「选模块」「看工具」后面——而它其实是前两步的前提。
      -->
      <section v-if="setupSection !== 'source' && !realSourceReady" class="source-gate source-gate-compact">
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
              <b>取证方法跑不起来，也做不了只读试跑</b>——这一步要等数据连接可用。
            </p>
          </div>
          <!-- 同一个动作一页只出现一次：没有模块时空态已经在带路，这里就只陈述事实。 -->
          <el-button v-if="hasModules" type="primary" plain @click="selectSetupSection('source')">检查数据连接</el-button>
        </div>
        <ul class="source-gate-list source-gate-list-compact">
          <li v-for="source in blockedSources" :key="source.platform">
            <b>{{ source.platform }}</b>
            <span v-if="source.missing" class="missing">缺 {{ source.missing }}</span>
          </li>
        </ul>
      </section>

      <section v-if="setupSection === 'source'" class="list-workspace source-list-workspace">
        <div class="list-heading">
          <div>
            <h2>数据源列表</h2>
            <p>共 {{ filteredSources.length }} 个数据源；API Key 由部署环境注入，不在页面保存。</p>
          </div>
        </div>
        <el-table v-if="filteredSources.length" :data="filteredSources" class="management-table" stripe>
          <el-table-column prop="platform" label="数据源" min-width="170" />
          <el-table-column label="支持的取证类型" min-width="240" class-name="optional-column" label-class-name="optional-column">
            <template #default="scope">{{ sourceSignalLabel(scope.row.supportedSignals) }}</template>
          </el-table-column>
          <el-table-column label="就绪检查" min-width="260">
            <template #default="scope">
              <div class="source-checks">
                <el-tag :type="sourceCheckTagType(scope.row.endpointStatus)" effect="plain" size="small">
                  {{ sourceCheckLabel(scope.row.endpointStatus, '端点') }}
                </el-tag>
                <el-tag :type="sourceCheckTagType(scope.row.credentialStatus)" effect="plain" size="small">
                  {{ sourceCheckLabel(scope.row.credentialStatus, '凭据') }}
                </el-tag>
                <el-tag :type="scope.row.verified ? 'success' : 'info'" effect="plain" size="small">
                  {{ scope.row.verified ? '已验证' : '未验证' }}
                </el-tag>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="scope">
              <el-tag :type="sourceStateTagType(scope.row)" effect="plain" size="small">
                {{ sourceStateLabel(scope.row) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130" align="right" fixed="right">
            <template #default="scope">
              <el-button
                v-if="!isRecordedReplay(scope.row.platform)"
                type="primary"
                text
                :loading="loading"
                @click="loadCatalog"
              >刷新状态</el-button>
              <span v-else class="muted-action">演示回放</span>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else :description="query ? '没有匹配当前搜索的数据源' : '尚未发现已启用的数据源适配器'" />
      </section>

      <section
        v-else-if="setupSection === 'modules' && filteredSetupModules.length"
        class="list-workspace module-list-workspace"
      >
        <div class="list-heading">
          <div>
            <h2>系统模块列表</h2>
            <p>共 {{ filteredSetupModules.length }} 个模块；系统标识相同的模块归在同一系统下。</p>
          </div>
        </div>
        <el-table :data="filteredSetupModules" class="management-table module-table" stripe>
          <el-table-column label="系统 / 模块" min-width="240">
            <template #default="scope">
              <div class="table-primary-cell">
                <b>{{ scope.row.service }}</b>
                <small>{{ scope.row.system }} · {{ scope.row.displayName }}</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="运行范围" min-width="230" class-name="optional-column" label-class-name="optional-column">
            <template #default="scope">
              <div class="table-primary-cell compact-cell">
                <b>{{ scope.row.asset?.environment || '未登记环境' }}</b>
                <small>{{ moduleResourceScope(scope.row) }}</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="配置来源" width="120">
            <template #default="scope">
              <el-tag :type="moduleOriginTagType(scope.row)" effect="plain" size="small">
                {{ moduleOriginLabel(scope.row) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="取证就绪" width="110">
            <template #default="scope">
              <span class="readiness-count">{{ moduleRailLabel(scope.row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="接入进度" min-width="180">
            <template #default="scope">
              <button
                type="button"
                class="onboarding-progress-trigger"
                @click="openModuleOnboarding(scope.row)"
              >
                <b>{{ moduleOnboarding(scope.row).completedSteps }}/{{ moduleOnboarding(scope.row).totalSteps }}</b>
                <small>
                  {{ moduleOnboarding(scope.row).status === 'READY_FOR_PILOT'
                    ? '已具备试点条件'
                    : `下一步：${moduleOnboarding(scope.row).nextStep?.label || '核对状态'}` }}
                </small>
              </button>
            </template>
          </el-table-column>
          <el-table-column label="操作" min-width="280" align="right" fixed="right">
            <template #default="scope">
              <el-button type="primary" text @click="openModuleWorkspace(scope.row)">打开配置</el-button>
              <el-button text @click="openModuleWorkspace(scope.row)">查看</el-button>
              <el-button text @click="openModuleWorkspace(scope.row, 'tools')">取证方法</el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section v-else-if="setupSection === 'tools'" class="list-workspace tool-list-workspace">
        <div class="list-heading">
          <div>
            <h2>取证方法库</h2>
            <p>
              方法本体都在这里维护和修改（含部署发布的）。新增、修改都在右侧侧栏完成，不弹窗。
              作用域可选「通用」或指定系统/模块；模块接入时再勾选用哪些。
            </p>
          </div>
        </div>
        <el-table
          v-if="filteredEvidenceContracts.length"
          :data="filteredEvidenceContracts"
          class="management-table tool-table"
          stripe
        >
          <el-table-column label="取证方法" min-width="280">
            <template #default="scope">
              <div class="table-primary-cell">
                <b>{{ signalKindLabel(scope.row.signalKind) }} · {{ scope.row.scenario }}</b>
                <small><code>{{ scope.row.contractRef }}</code> · {{ scope.row.question }}</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="作用域" min-width="180">
            <template #default="scope">
              <div class="table-primary-cell compact-cell">
                <b>{{ contractScopeLabel(scope.row) }}</b>
                <small>{{ contractScopeDetail(scope.row) }}</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="来源" width="110">
            <template #default="scope">
              <el-tag effect="plain" size="small">
                {{ scope.row.origin === 'WORKSPACE' ? '页面维护' : '部署发布' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="参数" min-width="160" class-name="optional-column" label-class-name="optional-column">
            <template #default="scope">
              {{ scope.row.requiredAssetParameters.join('、') || '无额外参数' }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120" align="right" fixed="right">
            <template #default="scope">
              <el-button type="primary" text @click="openEvidenceContractEditor(scope.row)">修改</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty
          v-else
          :description="query
            ? '没有匹配当前搜索的取证方法'
            : '还没有取证方法；点右上角新增，或等待平台发布部署方法'"
        />
      </section>
      <!--
        空态分两种，原来只有一句话概括了它们：源没通时先去联调，源通了就登记模块。
        「登记模块」在源没通时仍然可做（离线准备），所以它留着，只是不抢主位。
      -->
      <section v-else-if="!hasModules" class="setup-empty">
        <h2>{{ setupGate === 'NEEDS_SOURCE' ? '先检查数据连接' : '接入第一个系统' }}</h2>
        <p v-if="setupGate === 'NEEDS_SOURCE'">
          还没有任何模块，也还没有真实数据源。两件事都要做，但先做数据源——
          {{ replayOnly
            ? '现在只有受控回放，模块登记完也仍然取不到真实证据。'
            : '没有源的话，模块登记完也仍然取不到证据。' }}
        </p>
        <p v-else>
          数据连接已就绪。新增你要排障的系统和模块，然后选择发生故障时要使用的取证方法。
        </p>
        <div class="setup-empty-actions">
          <el-button v-if="setupGate === 'NEEDS_SOURCE'" type="primary" @click="selectSetupSection('source')">检查数据连接</el-button>
          <el-button
            :type="setupGate === 'NEEDS_SOURCE' ? 'default' : 'primary'"
            @click="openNewSystem"
          >{{ setupGate === 'NEEDS_SOURCE' ? '仍然先新增系统' : '新增第一个系统' }}</el-button>
        </div>
      </section>
      <el-empty v-else description="没有匹配当前搜索的系统模块" />
    </div>

    <el-drawer
      v-model="moduleWorkspaceOpen"
      :size="moduleWorkspacePanel === 'overview' ? 'min(720px, 100%)' : 'min(880px, 100%)'"
      destroy-on-close
      class="module-workspace-drawer"
      :title="moduleWorkspaceDrawerTitle"
      @closed="moduleWorkspacePanel = 'overview'"
    >
      <template v-if="moduleWorkspaceTarget">
        <div v-if="moduleWorkspacePanel !== 'overview'" class="module-workspace-nav">
          <el-button text type="primary" @click="backToModuleOverview">← 回到模块总览</el-button>
        </div>

        <template v-if="moduleWorkspacePanel === 'overview'">
          <header class="module-workspace-head">
            <div>
              <p class="scope-line">{{ moduleWorkspaceTarget.system }}</p>
              <h2>{{ moduleWorkspaceTarget.service }}</h2>
              <p>{{ moduleWorkspaceTarget.displayName }}</p>
            </div>
            <el-tag
              v-if="moduleWorkspaceOnboarding"
              :type="moduleWorkspaceOnboarding.status === 'READY_FOR_PILOT' ? 'success' : 'warning'"
              effect="plain"
            >
              {{ moduleWorkspaceOnboarding.completedSteps }}/{{ moduleWorkspaceOnboarding.totalSteps }} 已完成
            </el-tag>
          </header>

          <section class="module-workspace-guide" data-workspace-focus="scope">
            <b>你只需要做一件事</b>
            <p>
              点下面按钮，填清楚「服务在哪跑」和「出故障要查什么」。
              密钥、查询语句不用你配。后面的操作都在这个侧栏里完成，不会再弹窗。
            </p>
            <el-button type="primary" size="large" @click="openModuleEntryConfig(moduleWorkspaceTarget)">
              {{ moduleWorkspaceTarget.asset?.origin === 'WORKSPACE' ? '去改配置' : '去填配置' }}
            </el-button>
            <small>
              {{ moduleWorkspaceTarget.asset
                ? `当前：${moduleWorkspaceTarget.asset.environment || '环境未填'} · 已选 ${Object.keys(moduleWorkspaceTarget.asset.signalBindings || {}).length} 种方法`
                : '还没登记过，先填一次就能保存' }}
            </small>
          </section>

          <section class="module-workspace-block" data-workspace-focus="tools">
            <div class="module-workspace-block-head">
              <div>
                <h3>出故障时会查这些</h3>
                <p>这里只看状态。要增删方法，还是点上面的「去填 / 去改配置」。</p>
              </div>
            </div>
            <div v-if="moduleWorkspaceTools.length" class="module-tool-list">
              <article
                v-for="tool in moduleWorkspaceTools"
                :key="tool.contractRef"
                class="module-tool-card"
              >
                <div class="module-tool-main">
                  <div>
                    <b>{{ signalKindLabel(tool.signalKind) }}</b>
                    <p>{{ tool.question }}</p>
                  </div>
                  <el-tag :type="toolStateTagType(tool)" effect="plain" size="small">
                    {{ tool.statusLabel }}
                  </el-tag>
                </div>
                <small class="module-tool-next">{{ toolNextStepLabel(tool) }}</small>
                <div class="module-tool-actions">
                  <el-button
                    v-if="toolNeedsModuleConfig(tool)"
                    type="primary"
                    text
                    @click="openModuleEntryConfig(moduleWorkspaceTarget)"
                  >去补齐</el-button>
                  <el-button
                    text
                    @click="openToolDetail({
                      entry: moduleWorkspaceTarget,
                      tool,
                      row: contractRowFor(moduleWorkspaceTarget, tool),
                    })"
                  >看说明</el-button>
                  <el-button
                    type="primary"
                    text
                    :disabled="Boolean(toolListTrialBlocker({
                      entry: moduleWorkspaceTarget,
                      tool,
                      row: contractRowFor(moduleWorkspaceTarget, tool),
                    }))"
                    @click="openTrialForToolListRow({
                      entry: moduleWorkspaceTarget,
                      tool,
                      row: contractRowFor(moduleWorkspaceTarget, tool),
                    })"
                  >试一下能不能查到</el-button>
                </div>
              </article>
            </div>
            <el-empty
              v-else
              :image-size="48"
              description="平台还没发布可用方法；开发这边先把服务位置登记好即可"
            />
          </section>

          <section class="module-workspace-block" data-workspace-focus="source">
            <div class="module-workspace-block-head">
              <div>
                <h3>观测云连得上吗</h3>
                <p>只在这里看结果。不通的话找平台改部署配置，不是在页面里填密钥。</p>
              </div>
              <el-button :loading="loading" @click="loadCatalog">刷新状态</el-button>
            </div>
            <el-alert
              v-if="realSourceReady"
              type="success"
              :closable="false"
              show-icon
              class="module-workspace-alert"
              title="已连上，可以试跑取证方法"
            />
            <el-alert
              v-else-if="replayOnly"
              type="warning"
              :closable="false"
              show-icon
              class="module-workspace-alert"
              title="现在只有演示回放，查不到真实生产数据；模块配置仍可先填"
            />
            <el-alert
              v-else
              type="warning"
              :closable="false"
              show-icon
              class="module-workspace-alert"
              title="还没连上；你可以先填模块配置，但「试一下」会失败"
            />
            <ul class="module-source-list">
              <li v-for="source in moduleWorkspaceSources" :key="source.platform">
                <div class="module-source-main">
                  <b>{{ source.platform }}</b>
                  <el-tag :type="sourceStateTagType(source)" effect="plain" size="small">
                    {{ sourceStateLabel(source) }}
                  </el-tag>
                </div>
                <div class="source-checks">
                  <el-tag :type="sourceCheckTagType(source.endpointStatus)" effect="plain" size="small">
                    {{ sourceCheckLabel(source.endpointStatus, '端点') }}
                  </el-tag>
                  <el-tag :type="sourceCheckTagType(source.credentialStatus)" effect="plain" size="small">
                    {{ sourceCheckLabel(source.credentialStatus, '凭据') }}
                  </el-tag>
                </div>
                <small v-if="source.detail">{{ source.detail }}</small>
              </li>
            </ul>
            <p v-if="!moduleWorkspaceSources.length" class="empty-inline">当前部署还没有观测云数据源信息。</p>
          </section>
        </template>

        <section v-else-if="moduleWorkspacePanel === 'edit'" class="module-workspace-panel">
          <el-alert
            type="info"
            :closable="false"
            class="asset-dialog-alert"
            title="这里只保存系统、模块范围和所选取证方法。API Key、端点主机、原始 DQL 与原始日志不进入配置，由服务端保管。"
          />
          <el-alert
            type="warning"
            :closable="false"
            class="asset-dialog-alert"
            title="修改会保存为新版本，不会覆盖原来的生产审计记录。"
          />
          <el-alert
            :type="assetDraftReadiness.ready ? 'success' : 'info'"
            :closable="false"
            show-icon
            class="asset-dialog-alert"
            :title="assetDraftReadiness.ready ? '可以保存了' : `还缺 ${assetDraftReadiness.missing.length} 项`"
            :description="assetDraftReadiness.ready
              ? '保存后就能回来试跑。'
              : `请补齐：${assetDraftReadiness.missing.join('、')}`"
          />
          <el-form label-position="top" class="asset-form">
            <div class="asset-form-grid">
              <el-form-item label="系统标识">
                <el-input v-model="assetForm.system" :disabled="assetForm.systemLocked" placeholder="例如 CSDP" />
              </el-form-item>
              <el-form-item label="模块 / 服务标识">
                <el-input v-model="assetForm.service" :disabled="assetForm.serviceLocked" placeholder="例如 csdp-session-service" />
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
            <el-form-item label="选择这个模块的取证方法">
              <p class="field-help">发生故障时，系统会按照这里选择的方法查询日志、调用链、拨测或服务状态。</p>
              <div v-if="contractGroups.length" class="contract-binding-grid">
                <div v-for="group in contractGroups" :key="group.signalKind" class="contract-binding-row">
                  <div>
                    <b>{{ signalKindLabel(group.signalKind) }}</b>
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
                      :label="`${option.question}（${option.contractRef}）`"
                      :value="option.contractRef"
                    />
                  </el-select>
                </div>
              </div>
              <el-empty v-else :image-size="56" description="当前还没有可选择的取证方法，请先由平台管理员发布" />
            </el-form-item>
            <section v-if="editableAssetParameters.length" class="asset-parameter-section">
              <div class="asset-parameter-heading">
                <b>取证时要查询哪个资源</b>
                <small>这些值固定当前模块的查询范围，排障时不会临时改到其他系统。</small>
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
          <div class="module-workspace-actions">
            <el-button @click="backToModuleOverview">取消</el-button>
            <el-button
              type="primary"
              :loading="assetSaving"
              :disabled="!assetDraftReadiness.ready"
              @click="saveAsset"
            >保存</el-button>
          </div>
        </section>

        <section v-else-if="moduleWorkspacePanel === 'tool' && toolDetailTarget" class="module-workspace-panel">
          <div class="tool-detail-heading">
            <div>
              <small>{{ toolDetailTarget.entry.system }} / {{ toolDetailTarget.entry.service }}</small>
              <h3>{{ signalKindLabel(toolDetailTarget.tool.signalKind) }} · {{ toolDetailTarget.tool.scenario }}</h3>
              <p>{{ toolDetailTarget.tool.question }}</p>
            </div>
            <el-tag :type="toolStateTagType(toolDetailTarget.tool)" effect="plain">
              {{ toolDetailTarget.tool.statusLabel }}
            </el-tag>
          </div>
          <div class="checklist">
            <div
              v-for="item in toolDetailTarget.tool.checklist"
              :key="item.key"
              class="checklist-item"
              :class="{ done: item.done }"
            >
              <span class="check-mark">{{ item.done ? '✓' : '○' }}</span>
              <div><b>{{ item.label }}</b><small>{{ item.detail }}</small></div>
            </div>
          </div>
          <div v-if="toolDetailTarget.tool.contract?.blockers?.length" class="blocker-box">
            <b>现在卡住的原因</b>
            <ul><li v-for="blocker in toolDetailTarget.tool.contract.blockers" :key="blocker">{{ blocker }}</li></ul>
          </div>
          <details v-if="toolDetailTarget.tool.contract" class="contract-spec">
            <summary>取证方法详情：需要什么 · 返回什么 · 执行限制</summary>
            <div class="detail-grid">
              <section class="detail-card">
                <div class="card-title"><small>INPUT</small><h3>需要传什么</h3></div>
                <el-table :data="toolDetailTarget.tool.contract.parameters" size="small">
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
                  <el-tag v-for="field in toolDetailTarget.tool.contract.canonicalOutputs" :key="field" effect="plain">{{ field }}</el-tag>
                </div>
              </section>
              <section class="detail-card">
                <div class="card-title"><small>FIXED</small><h3>服务端固定条件</h3></div>
                <div class="tag-list">
                  <el-tag
                    v-for="condition in toolDetailTarget.tool.contract.fixedConditions"
                    :key="condition"
                    type="info"
                    effect="plain"
                  >{{ condition }}</el-tag>
                  <span v-if="!toolDetailTarget.tool.contract.fixedConditions.length" class="empty-inline">未记录</span>
                </div>
              </section>
            </div>
          </details>
          <section v-if="toolDetailTarget.tool.contract" class="trial-history">
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
              <el-table-column label="资产版本" width="100"><template #default="scope">v{{ scope.row.assetVersion }}</template></el-table-column>
              <el-table-column label="返回字段" min-width="180"><template #default="scope">{{ scope.row.canonicalFields.join('、') || '无' }}</template></el-table-column>
              <el-table-column prop="durationMs" label="耗时" width="90" />
              <el-table-column prop="actor" label="操作人" width="120" />
              <el-table-column prop="completedAt" label="完成时间" min-width="160" />
            </el-table>
            <el-empty v-else :image-size="40" description="还没有只读试跑记录" />
          </section>
          <div class="module-workspace-actions">
            <el-button
              v-if="toolNeedsModuleConfig(toolDetailTarget.tool)"
              type="primary"
              @click="openModuleConfigFor(toolDetailTarget.entry)"
            >去补齐配置</el-button>
            <el-button
              v-if="toolDetailTarget.row"
              @click="openRouteEditor(toolDetailTarget.row)"
            >改查询顺序</el-button>
            <el-button
              type="primary"
              :disabled="Boolean(toolListTrialBlocker(toolDetailTarget))"
              @click="openTrialForToolListRow(toolDetailTarget)"
            >试一下能不能查到</el-button>
          </div>
        </section>

        <section v-else-if="moduleWorkspacePanel === 'trial' && trialTarget" class="module-workspace-panel">
          <div class="route-scope">
            <span>本次只查询</span>
            <b>{{ trialTarget.system }} / {{ trialTarget.service }} / {{ trialTarget.contract.contractRef }}</b>
            <small>系统资产中的资源范围和服务端查询条件不可在此修改。</small>
          </div>
          <el-alert
            type="warning"
            :closable="false"
            title="只验证这个取证方法能否返回规范证据，不会创建排障单，也不代表真源已验收。"
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
          <div class="module-workspace-actions">
            <el-button @click="backToModuleOverview">回到总览</el-button>
            <el-button type="primary" :loading="trialRunning" @click="runTrial">执行只读查询</el-button>
          </div>
        </section>

        <section v-else-if="moduleWorkspacePanel === 'route' && routeTarget" class="module-workspace-panel">
          <div class="route-scope">
            <span>当前模块</span>
            <b>{{ routeTarget.system }} / {{ routeTarget.service }}</b>
            <small>{{ signalKindLabel(routeTarget.contract.signalKind) }} · {{ routeTarget.contract.contractRef }}</small>
          </div>
          <el-form label-position="top">
            <el-form-item label="按顺序选择适配器">
              <div class="route-platform-editor">
                <div v-for="(platform, index) in routePlatforms" :key="`${platform}-${index}`" class="route-platform-row">
                  <el-select v-model="routePlatforms[index]" filterable allow-create>
                    <el-option
                      v-for="source in catalog?.sources || []"
                      :key="source.platform"
                      :label="source.platform"
                      :value="source.platform"
                    />
                  </el-select>
                  <el-button text :disabled="index === 0" @click="moveRoutePlatform(index, -1)">上移</el-button>
                  <el-button text :disabled="index === routePlatforms.length - 1" @click="moveRoutePlatform(index, 1)">下移</el-button>
                  <el-button text type="danger" @click="routePlatforms.splice(index, 1)">删除</el-button>
                </div>
                <el-button @click="routePlatforms.push('guance')">增加适配器</el-button>
              </div>
            </el-form-item>
            <el-form-item label="变更原因">
              <el-input v-model="routeReason" type="textarea" :rows="3" maxlength="500" show-word-limit />
            </el-form-item>
          </el-form>
          <div class="module-workspace-actions">
            <el-button @click="moduleWorkspacePanel = toolDetailTarget ? 'tool' : 'overview'">取消</el-button>
            <el-button type="primary" :loading="routeSaving" @click="saveRoute">保存路由</el-button>
          </div>
        </section>
      </template>
    </el-drawer>

    <el-dialog v-model="moduleChooserOpen" title="选择模块所属系统" width="520px" destroy-on-close>
      <p class="dialog-help">新模块会复用所选系统标识；如果是全新系统，请使用“新增系统”。</p>
      <div class="system-choice-list">
        <button
          v-for="choice in systemChoices"
          :key="choice.system"
          type="button"
          @click="chooseSystemForNewModule(choice.system)"
        >
          <span><b>{{ choice.system }}</b><small>{{ choice.moduleCount }} 个已登记模块</small></span>
          <span aria-hidden="true">›</span>
        </button>
      </div>
      <el-empty v-if="!systemChoices.length" :image-size="48" description="尚无可选系统，请先新增系统" />
      <template #footer><el-button @click="moduleChooserOpen = false">取消</el-button></template>
    </el-dialog>

    <el-dialog
      v-model="onboardingDialogOpen"
      title="模块接入进度"
      width="680px"
      destroy-on-close
    >
      <template v-if="onboardingTarget && onboardingReadiness">
        <div class="onboarding-dialog-heading">
          <div>
            <small>{{ onboardingTarget.system }} / {{ onboardingTarget.service }}</small>
            <h3>{{ onboardingTarget.displayName }}</h3>
            <p>这五项全部有真实记录后，才能把该模块称为“可试点”。</p>
          </div>
          <el-tag
            :type="onboardingReadiness.status === 'READY_FOR_PILOT' ? 'success' : 'warning'"
            effect="plain"
          >
            {{ onboardingReadiness.completedSteps }}/{{ onboardingReadiness.totalSteps }}
          </el-tag>
        </div>

        <div class="onboarding-step-list">
          <article
            v-for="(step, index) in onboardingReadiness.steps"
            :key="step.code"
            class="onboarding-step"
            :class="step.state.toLowerCase()"
          >
            <span class="onboarding-step-index">{{ step.state === 'DONE' ? '✓' : index + 1 }}</span>
            <div>
              <div class="onboarding-step-title">
                <b>{{ step.label }}</b>
                <em>{{ step.state === 'DONE' ? '已完成' : step.state === 'UNKNOWN' ? '未读取' : '待补齐' }}</em>
              </div>
              <p>{{ step.detail }}</p>
            </div>
          </article>
        </div>

        <el-alert
          v-if="onboardingReadiness.status === 'READY_FOR_PILOT'"
          type="success"
          :closable="false"
          show-icon
          title="该模块已具备生产试点的配置条件"
          description="这只代表配置和责任人验收完整；真实效果仍需在 T8 样本台账中累积。"
        />
        <el-alert
          v-else-if="onboardingReadiness.nextStep"
          type="info"
          :closable="false"
          show-icon
          :title="`当前先做：${onboardingReadiness.nextStep.label}`"
          :description="onboardingReadiness.nextStep.detail"
        />
      </template>
      <template #footer>
        <el-button @click="onboardingDialogOpen = false">关闭</el-button>
        <el-button
          v-if="onboardingReadiness?.nextStep"
          type="primary"
          @click="continueModuleOnboarding"
        >{{ onboardingNextActionLabel }}</el-button>
      </template>
    </el-dialog>

    <el-drawer
      v-model="contractDrawerOpen"
      size="min(880px, 100%)"
      destroy-on-close
      class="contract-library-drawer"
      :title="contractDrawerTitle"
      @closed="resetContractForm"
    >
      <el-alert
        type="info"
        :closable="false"
        class="asset-dialog-alert"
        title="作用域选「通用」后任意模块可勾选；选系统/模块则只有匹配范围能用。部署发布的方法也可在此修改，保存后成为页面版本，并覆盖同名的部署发布方法。"
      />
      <el-form label-position="top" class="asset-form">
        <div class="asset-form-grid">
          <el-form-item label="方法标识">
            <el-input
              v-model="contractForm.contractRef"
              :disabled="contractForm.contractRefLocked"
              placeholder="例如 csdp-message-send-log-search"
            />
            <p class="field-help">给这条取证方法起的唯一 ID，供模块勾选时引用；创建后不可改。</p>
          </el-form-item>
          <el-form-item label="证据维度">
            <el-select v-model="contractForm.signalKind" filterable>
              <el-option label="日志检索" value="log_search" />
              <el-option label="链路日志包" value="log_trace_bundle" />
              <el-option label="对比样本" value="contrast_sample" />
              <el-option label="错误日志巡检" value="error_log_scan" />
              <el-option label="监控告警" value="monitor_event_scan" />
              <el-option label="K8s 工作负载" value="k8s_workload_health" />
              <el-option label="服务 Pod 状态" value="k8s_pod_status" />
              <el-option label="服务 Node 状态" value="k8s_node_status" />
              <el-option label="服务主机状态" value="host_status" />
              <el-option label="拨测" value="synthetic_probe" />
            </el-select>
          </el-form-item>
          <el-form-item label="作用域">
            <el-select v-model="contractForm.scopeType">
              <el-option label="通用（所有模块可用）" value="GENERIC" />
              <el-option label="指定系统" value="SYSTEM" />
              <el-option label="指定系统 + 模块" value="MODULE" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="contractForm.scopeType !== 'GENERIC'" label="系统">
            <el-input v-model="contractForm.system" placeholder="例如 CSDP" />
          </el-form-item>
          <el-form-item v-if="contractForm.scopeType === 'MODULE'" label="模块 / 服务">
            <el-input v-model="contractForm.service" placeholder="例如 csdp-session-service" />
          </el-form-item>
          <el-form-item label="场景名">
            <el-input v-model="contractForm.scenario" placeholder="例如 会话消息发送失败" />
          </el-form-item>
          <el-form-item label="要回答的问题">
            <el-input v-model="contractForm.question" placeholder="发生故障时要查清什么" />
          </el-form-item>
          <el-form-item label="摘要">
            <el-input v-model="contractForm.summary" placeholder="一句话说明" />
          </el-form-item>
          <el-form-item label="命名空间">
            <el-input v-model="contractForm.namespace" placeholder="L / T / M" />
          </el-form-item>
        </div>
        <el-form-item label="模块需填写的参数（逗号分隔）">
          <el-input
            v-model="contractForm.requiredAssetParametersText"
            placeholder="例如 namespace,deployment"
          />
        </el-form-item>
        <el-form-item label="固定条件说明（每行一条，给人看的）">
          <el-input
            v-model="contractForm.fixedConditionsText"
            type="textarea"
            :rows="3"
            placeholder="例如：日志源=csp-rpc-msg"
          />
        </el-form-item>
        <el-form-item label="查询模板（DQL）">
          <el-input
            v-model="contractForm.queryTemplate"
            type="textarea"
            :rows="5"
            placeholder="L::`index`:(...) { ... } [{{window_span}}::{{window_span}}]"
          />
        </el-form-item>
        <div class="asset-enabled-row">
          <div><b>启用该方法</b><small>停用后模块不能再新绑到它。</small></div>
          <el-switch v-model="contractForm.enabled" />
        </div>
        <el-form-item label="变更原因">
          <el-input
            v-model="contractForm.reason"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <div class="module-workspace-actions">
        <el-button @click="contractDrawerOpen = false">取消</el-button>
        <el-button type="primary" :loading="contractSaving" @click="saveEvidenceContract">保存</el-button>
      </div>
    </el-drawer>
  </CapabilityWorkspaceShell>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { vLoading } from 'element-plus/es/components/loading/index'
import {
  troubleshootingApi,
  type EvidenceCatalogSource,
  type EvidenceCatalogModule,
  type EvidenceContractCatalog,
  type EvidenceContractTrial,
  type EvidenceContractView,
  type EvidenceQueryCatalog,
  type EvidenceQueryContract,
  type ObservabilityAsset,
  type ObservabilityAssetCatalog,
  type ObservabilityAssetContractOption,
  type SopSummary,
} from '@/api'
import CapabilityWorkspaceShell from './CapabilityWorkspaceShell.vue'
import {
  assetParameterLabel,
  bindingStatusLabel,
  buildModuleOnboardingReadiness,
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
  type ModuleOnboardingReadiness,
  type SetupModuleEntry,
} from './evidenceCatalog'
import {
  EVIDENCE_SETUP_SECTIONS,
  normalizeEvidenceSetupSection,
  safeTroubleshootingReturnPath,
} from './workbenchCapabilityMenu'

type ContractRow = {
  system: string
  service: string
  module: EvidenceCatalogModule
  contract: EvidenceQueryContract
}

type ToolListRow = {
  entry: SetupModuleEntry
  tool: ModuleToolSetup
  row: ContractRow | null
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
  systemLocked: boolean
  serviceLocked: boolean
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
    systemLocked: false,
    serviceLocked: false,
  }
}

const route = useRoute()
const router = useRouter()
const catalog = ref<EvidenceQueryCatalog | null>(null)
const assetCatalog = ref<ObservabilityAssetCatalog | null>(null)
const evidenceContractCatalog = ref<EvidenceContractCatalog | null>(null)
const query = ref('')
const contractScopeFilter = ref<'ALL' | 'GENERIC' | 'SYSTEM' | 'MODULE'>('ALL')
const toolStatusFilter = ref<'ALL' | ModuleToolSetup['status']>('ALL')
const contractDrawerOpen = ref(false)
const contractSaving = ref(false)
const contractForm = ref(emptyContractForm())

function emptyContractForm() {
  return {
    contractRef: '',
    contractRefLocked: false,
    signalKind: 'log_search',
    scopeType: 'GENERIC' as 'GENERIC' | 'SYSTEM' | 'MODULE',
    system: '',
    service: '',
    scenario: '',
    question: '',
    summary: '',
    namespace: 'L',
    requiredAssetParametersText: '',
    fixedConditionsText: '',
    queryTemplate: '',
    enabled: true,
    expectedVersion: undefined as number | undefined,
    reason: '',
    editingOrigin: '' as '' | 'DEPLOYMENT' | 'WORKSPACE',
  }
}

const contractDrawerTitle = computed(() => {
  if (!contractForm.value.contractRefLocked) return '新增取证方法'
  if (contractForm.value.editingOrigin === 'DEPLOYMENT') {
    return `修改取证方法（将保存为页面版本）· ${contractForm.value.contractRef}`
  }
  return `修改取证方法 · ${contractForm.value.contractRef}`
})

function resetContractForm() {
  contractForm.value = emptyContractForm()
}
const loading = ref(false)
const error = ref('')
const selectedModuleKey = ref('')
const selectedToolRef = ref('')
const selectedKey = ref('')
const routeSaving = ref(false)
const routeTarget = ref<ContractRow | null>(null)
const routePlatforms = ref<string[]>([])
const routeReason = ref('')
const moduleChooserOpen = ref(false)
const assetSaving = ref(false)
const assetForm = ref<AssetForm>(emptyAssetForm())
const playbooks = ref<SopSummary[] | null>(null)
const onboardingDialogOpen = ref(false)
const onboardingTarget = ref<SetupModuleEntry | null>(null)
const moduleWorkspaceOpen = ref(false)
const moduleWorkspaceTarget = ref<SetupModuleEntry | null>(null)
const moduleWorkspaceFocus = ref<'scope' | 'tools' | 'source'>('scope')
const moduleWorkspacePanel = ref<'overview' | 'edit' | 'tool' | 'trial' | 'route'>('overview')
const trialRunning = ref(false)
const trialHistoryLoading = ref(false)
const trialTarget = ref<ContractRow | null>(null)
const trialParameterValues = ref<Record<string, string>>({})
const trialWindow = ref('-15m')
const trialOccurredAt = ref<Date | null>(null)
const trialResult = ref<EvidenceContractTrial | null>(null)
const trialError = ref('')
const trialHistory = ref<EvidenceContractTrial[]>([])
const toolDetailTarget = ref<ToolListRow | null>(null)
const setupSection = computed(() => normalizeEvidenceSetupSection(route.query.section))
const activeSetupSection = computed(() => EVIDENCE_SETUP_SECTIONS.find(
  section => section.key === setupSection.value,
  ) || EVIDENCE_SETUP_SECTIONS[0])

function selectSetupSection(section: 'modules' | 'tools' | 'source') {
  void router.push({
    path: route.path,
    query: { ...route.query, section },
  })
}

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

const blockedSources = computed(() => (catalog.value?.sources || [])
  .filter(source => source.status !== 'READY' && !isRecordedReplay(source.platform))
  .map(source => ({
    platform: source.platform,
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
const systemChoices = computed(() => {
  const counts = new Map<string, number>()
  for (const entry of setupModules.value) {
    counts.set(entry.system, (counts.get(entry.system) || 0) + 1)
  }
  return [...counts.entries()]
    .map(([system, moduleCount]) => ({ system, moduleCount }))
    .sort((left, right) => left.system.localeCompare(right.system))
})
const showListToolbar = computed(() => setupSection.value === 'source'
  ? Boolean(catalog.value?.sources?.length)
  : setupSection.value === 'tools'
    ? true
    : hasModules.value)
const listSearchPlaceholder = computed(() => ({
  modules: '搜索系统或模块',
  tools: '搜索方法标识、场景或问题',
  source: '搜索数据源或支持的取证类型',
}[setupSection.value]))

const filteredEvidenceContracts = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  return (evidenceContractCatalog.value?.contracts || []).filter(contract => {
    if (contractScopeFilter.value !== 'ALL'
      && String(contract.scopeType).toUpperCase() !== contractScopeFilter.value) {
      return false
    }
    if (!keyword) return true
    return [
      contract.contractRef,
      contract.scenario,
      contract.question,
      contract.summary,
      contract.system,
      contract.service,
      signalKindLabel(contract.signalKind),
    ].some(value => String(value || '').toLowerCase().includes(keyword))
  })
})

function contractScopeLabel(contract: EvidenceContractView) {
  const scope = String(contract.scopeType || '').toUpperCase()
  if (scope === 'SYSTEM') return '指定系统'
  if (scope === 'MODULE') return '指定模块'
  return '通用'
}

function contractScopeDetail(contract: EvidenceContractView) {
  const scope = String(contract.scopeType || '').toUpperCase()
  if (scope === 'SYSTEM') return contract.system || '未填系统'
  if (scope === 'MODULE') {
    return [contract.system, contract.service].filter(Boolean).join(' / ') || '未填系统/模块'
  }
  return '任意系统模块都可勾选'
}

const filteredSources = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  const sources = catalog.value?.sources || []
  if (!keyword) return sources
  return sources.filter(source => [
    source.platform,
    source.detail,
    ...source.supportedSignals,
    ...source.supportedSignals.map(signalKindLabel),
  ].some(value => value.toLowerCase().includes(keyword)))
})

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

const allToolRows = computed<ToolListRow[]>(() => setupModules.value.flatMap(entry =>
  buildModuleToolSetups({
    options: assetCatalog.value?.contracts || [],
    module: entry.module,
    asset: entry.asset,
    sourceReady: sourceReady.value,
  }).map(tool => ({ entry, tool, row: contractRowFor(entry, tool) }))))

const filteredToolRows = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  return allToolRows.value.filter(({ entry, tool }) => {
    if (toolStatusFilter.value !== 'ALL' && tool.status !== toolStatusFilter.value) return false
    if (!keyword) return true
    return [
      entry.system,
      entry.service,
      entry.displayName,
      tool.signalKind,
      signalKindLabel(tool.signalKind),
      tool.contractRef,
      tool.scenario,
      tool.question,
      tool.contract?.route.platforms.join(' ') || '',
    ].some(value => value.toLowerCase().includes(keyword))
  })
})

const selectedSetupModule = computed(() => filteredSetupModules.value.find(entry =>
  moduleKey(entry.system, entry.service) === selectedModuleKey.value)
  || filteredSetupModules.value[0]
  || null)

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

function moduleOnboarding(entry: SetupModuleEntry): ModuleOnboardingReadiness {
  return buildModuleOnboardingReadiness({
    entry,
    tools: buildModuleToolSetups({
      options: assetCatalog.value?.contracts || [],
      module: entry.module,
      asset: entry.asset,
      sourceReady: sourceReady.value,
    }),
    sources: catalog.value?.sources || [],
    playbooks: playbooks.value,
  })
}

const onboardingReadiness = computed(() => onboardingTarget.value
  ? moduleOnboarding(onboardingTarget.value)
  : null)

const moduleWorkspaceOnboarding = computed(() => moduleWorkspaceTarget.value
  ? moduleOnboarding(moduleWorkspaceTarget.value)
  : null)

const moduleWorkspaceTools = computed(() => {
  const entry = moduleWorkspaceTarget.value
  if (!entry) return [] as ModuleToolSetup[]
  return buildModuleToolSetups({
    options: assetCatalog.value?.contracts || [],
    module: entry.module,
    asset: entry.asset,
    sourceReady: sourceReady.value,
  })
})

const moduleWorkspaceSources = computed(() => (catalog.value?.sources || [])
  .filter(source => !isRecordedReplay(source.platform) || source.status === 'READY'))

const moduleWorkspaceDrawerTitle = computed(() => {
  const target = moduleWorkspaceTarget.value
  const base = target ? `${target.system} / ${target.service}` : '模块配置'
  const form = assetForm.value
  const editTitle = !form.systemLocked
    ? '新增系统及第一个模块'
    : !form.serviceLocked
      ? `给 ${form.system || '当前系统'} 新增模块`
      : form.expectedVersion
        ? `修改配置 · ${base}`
        : `填写配置 · ${base}`
  return ({
    overview: base,
    edit: editTitle,
    tool: `方法说明 · ${base}`,
    trial: `试一下 · ${base}`,
    route: `查询顺序 · ${base}`,
  })[moduleWorkspacePanel.value]
})

const onboardingNextActionLabel = computed(() => ({
  source: '查看连接状态',
  asset: '去登记模块',
  tools: '去配置取证方法',
  playbook: '去排障规则库',
  acceptance: '去负责人验收',
}[onboardingReadiness.value?.nextStep?.action || 'source']))

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
  const asset = setupModules.value.find(entry =>
    entry.system.trim().toLowerCase() === row.system.trim().toLowerCase()
      && entry.service.trim().toLowerCase() === row.service.trim().toLowerCase())?.asset
  return directTrialBlockReason(row.contract, asset)
}

function fallbackEvidenceModule(service: string): EvidenceCatalogModule {
  return {
    service,
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
  }
}

function contractRowFor(entry: SetupModuleEntry, tool: ModuleToolSetup): ContractRow | null {
  if (!tool.contract) return null
  return {
    system: entry.system,
    service: entry.service,
    module: entry.module || fallbackEvidenceModule(entry.service),
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

function moduleResourceScope(entry: SetupModuleEntry) {
  if (!entry.asset) return '未登记'
  return [entry.asset.region, entry.asset.cluster, entry.asset.namespace]
    .filter(Boolean)
    .join(' / ') || '未记录'
}

function moduleOriginLabel(entry: SetupModuleEntry) {
  if (!entry.asset) return '未登记'
  return entry.asset.origin === 'WORKSPACE' ? 'Workspace' : '部署默认'
}

function moduleOriginTagType(entry: SetupModuleEntry): 'success' | 'info' | 'warning' {
  if (!entry.asset) return 'warning'
  return entry.asset.origin === 'WORKSPACE' ? 'success' : 'info'
}

function openModuleEntryConfig(entry: SetupModuleEntry) {
  selectSetupModule(entry)
  if (entry.asset) prepareAssetEditor(entry.asset)
  else prepareExistingModuleRegistration(entry)
  showAssetEditor()
}

function backToModuleOverview() {
  moduleWorkspacePanel.value = 'overview'
}

function draftModuleEntry(): SetupModuleEntry {
  const form = assetForm.value
  return {
    system: form.system || 'new-system',
    service: form.service || 'new-module',
    displayName: form.displayName || form.service || '新模块',
    asset: null,
    module: null,
  }
}

function showAssetEditor() {
  if (!moduleWorkspaceOpen.value || !moduleWorkspaceTarget.value) {
    moduleWorkspaceTarget.value = draftModuleEntry()
    onboardingTarget.value = moduleWorkspaceTarget.value
    moduleWorkspaceOpen.value = true
  }
  moduleWorkspacePanel.value = 'edit'
}

function openModuleWorkspace(entry: SetupModuleEntry, focus: 'scope' | 'tools' | 'source' = 'scope') {
  selectSetupModule(entry)
  moduleWorkspaceTarget.value = entry
  moduleWorkspaceFocus.value = focus
  moduleWorkspacePanel.value = 'overview'
  moduleWorkspaceOpen.value = true
  onboardingTarget.value = entry
  void nextTick(() => {
    document
      .querySelector(`.module-workspace-drawer [data-workspace-focus="${focus}"]`)
      ?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  })
}

function openModuleOnboarding(entry: SetupModuleEntry) {
  selectSetupModule(entry)
  onboardingTarget.value = entry
  onboardingDialogOpen.value = true
}

function continueModuleOnboarding() {
  const target = onboardingTarget.value || moduleWorkspaceTarget.value
  const next = (onboardingTarget.value
    ? onboardingReadiness.value
    : moduleWorkspaceOnboarding.value)?.nextStep
  if (!target || !next) return
  onboardingDialogOpen.value = false
  if (next.action === 'asset') {
    openModuleWorkspace(target, 'scope')
    openModuleEntryConfig(target)
    return
  }
  if (next.action === 'source') {
    openModuleWorkspace(target, 'source')
    return
  }
  if (next.action === 'tools') {
    openModuleWorkspace(target, 'tools')
    return
  }
  if (next.action === 'playbook') {
    void router.push({
      path: '/troubleshooting/sops',
      query: { returnTo: route.fullPath, system: target.system, service: target.service },
    })
    return
  }
  if (next.action === 'acceptance') {
    void router.push({
      path: '/troubleshooting',
      query: {
        capability: 'guance',
        system: target.system,
        service: target.service,
        returnTo: route.fullPath,
      },
    })
    return
  }
  openModuleWorkspace(target, 'source')
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

function openModuleConfigFor(entry: SetupModuleEntry) {
  selectSetupModule(entry)
  if (entry.asset) prepareAssetEditor(entry.asset)
  else prepareExistingModuleRegistration(entry)
  ensureModuleWorkspace(entry)
  showAssetEditor()
}

function ensureModuleWorkspace(entry: SetupModuleEntry) {
  moduleWorkspaceTarget.value = entry
  onboardingTarget.value = entry
  moduleWorkspaceOpen.value = true
}

function openToolDetail(target: ToolListRow) {
  toolDetailTarget.value = target
  selectSetupModule(target.entry)
  selectTool(target.tool)
  ensureModuleWorkspace(target.entry)
  moduleWorkspacePanel.value = 'tool'
  void loadTrialHistory()
}

function openTrialForToolListRow(target: ToolListRow) {
  selectSetupModule(target.entry)
  selectTool(target.tool)
  ensureModuleWorkspace(target.entry)
  if (target.row) openTrialForRow(target.row)
}

function toolListTrialBlocker(target: ToolListRow) {
  return target.row ? rowTrialBlocker(target.row) : '先启用并绑定这条工具'
}

function toolRouteLabel(tool: ModuleToolSetup) {
  if (!tool.contract) return '未投影'
  return tool.contract.route.platforms.join(' → ') || '未声明'
}

function toolBindingLabel(tool: ModuleToolSetup) {
  if (!tool.enabled) return '未启用'
  return tool.contract ? bindingStatusLabel(tool.contract.binding.status) : '待刷新'
}

function toolStateTagType(tool: ModuleToolSetup): 'success' | 'warning' | 'info' {
  return tool.status === 'READY' ? 'success' : tool.status === 'BLOCKED' ? 'warning' : 'info'
}

function toolNextStepLabel(tool: ModuleToolSetup) {
  if (tool.status === 'READY') return '可直接只读试跑'
  return tool.checklist.find(item => !item.done)?.label || '查看详情确认阻断点'
}

function sourceSignalLabel(signals: string[]) {
  return signals.map(signalKindLabel).join('、') || '未声明'
}

function sourceCheckLabel(status: string, subject: string) {
  if (status === 'MISSING') return `缺${subject}`
  if (status === 'READY' || status === 'CONFIGURED' || status === 'PRESENT') return '已配置'
  if (status === 'NOT_REPORTED' || !status) return '未报告'
  return status
}

function sourceCheckTagType(status: string): 'success' | 'warning' | 'info' {
  if (status === 'MISSING') return 'warning'
  return status === 'READY' || status === 'CONFIGURED' || status === 'PRESENT' ? 'success' : 'info'
}

function sourceStateLabel(source: EvidenceCatalogSource) {
  if (source.status !== 'READY') return '待联调'
  return isRecordedReplay(source.platform) ? '仅回放' : '已就绪'
}

function sourceStateTagType(source: EvidenceCatalogSource): 'success' | 'warning' | 'info' {
  if (source.status !== 'READY') return 'warning'
  return isRecordedReplay(source.platform) ? 'info' : 'success'
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
    probe_name: '拨测任务名',
    monitor_checker: '监控规则标识',
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
  const entry = setupModules.value.find(item =>
    moduleKey(item.system, item.service) === moduleKey(row.system, row.service))
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
  if (entry) ensureModuleWorkspace(entry)
  else ensureModuleWorkspace(draftModuleEntry())
  moduleWorkspacePanel.value = 'trial'
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
        ? '数据源查询失败，请核对运行日志和所选取证方法'
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
    const playbookRequest = troubleshootingApi.listSops?.({
      status: 'approved',
      limit: 500,
    })
    const [catalogResponse, assetResponse, contractResponse, playbookResponse] = await Promise.all([
      troubleshootingApi.evidenceCatalog(),
      troubleshootingApi.observabilityAssets(),
      troubleshootingApi.evidenceContracts().catch(() => ({ data: null })),
      playbookRequest && typeof playbookRequest.then === 'function'
        ? playbookRequest.catch(() => null)
        : Promise.resolve(null),
    ])
    catalog.value = catalogResponse.data
    assetCatalog.value = assetResponse.data
    evidenceContractCatalog.value = contractResponse.data
    playbooks.value = playbookResponse?.data || null
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
    if (moduleWorkspaceOpen.value && moduleWorkspaceTarget.value) {
      const current = moduleWorkspaceTarget.value
      moduleWorkspaceTarget.value = setupModules.value.find(entry =>
        moduleKey(entry.system, entry.service) === moduleKey(current.system, current.service))
        || null
      if (!moduleWorkspaceTarget.value) moduleWorkspaceOpen.value = false
    }
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '系统接入配置加载失败'
  } finally {
    loading.value = false
  }
}

function openNewSystem() {
  assetForm.value = emptyAssetForm()
  moduleWorkspaceTarget.value = draftModuleEntry()
  onboardingTarget.value = moduleWorkspaceTarget.value
  moduleWorkspaceOpen.value = true
  moduleWorkspacePanel.value = 'edit'
}

function openNewEvidenceContract() {
  contractForm.value = emptyContractForm()
  contractDrawerOpen.value = true
}

async function openEvidenceContractEditor(contract: EvidenceContractView) {
  contractForm.value = emptyContractForm()
  contractForm.value.contractRef = contract.contractRef
  contractForm.value.contractRefLocked = true
  contractForm.value.editingOrigin = contract.origin === 'WORKSPACE' ? 'WORKSPACE' : 'DEPLOYMENT'
  contractForm.value.signalKind = contract.signalKind || 'log_search'
  contractForm.value.scopeType = (String(contract.scopeType || 'GENERIC').toUpperCase() as
    'GENERIC' | 'SYSTEM' | 'MODULE')
  contractForm.value.system = contract.system || ''
  contractForm.value.service = contract.service || ''
  contractForm.value.scenario = contract.scenario || ''
  contractForm.value.question = contract.question || ''
  contractForm.value.summary = contract.summary || ''
  contractForm.value.namespace = contract.namespace || 'L'
  contractForm.value.requiredAssetParametersText =
    (contract.requiredAssetParameters || []).join(',')
  contractForm.value.fixedConditionsText = (contract.fixedConditions || []).join('\n')
  contractForm.value.enabled = contract.enabled !== false
  if (contract.origin === 'WORKSPACE' && contract.version > 0) {
    contractForm.value.expectedVersion = contract.version
  } else {
    // 部署发布的方法第一次在页面保存：写入 Workspace 版本并覆盖同名部署方法
    contractForm.value.expectedVersion = undefined
    contractForm.value.reason = `修改部署发布方法 ${contract.contractRef}`
  }
  try {
    const detail = await troubleshootingApi.evidenceContractDetail(contract.contractRef)
    contractForm.value.queryTemplate = detail.data.queryTemplate || ''
    if (detail.data.origin === 'WORKSPACE' && detail.data.version > 0) {
      contractForm.value.expectedVersion = detail.data.version
      contractForm.value.editingOrigin = 'WORKSPACE'
      if (!contractForm.value.reason) contractForm.value.reason = ''
    }
  } catch {
    contractForm.value.queryTemplate = ''
  }
  contractDrawerOpen.value = true
}

async function saveEvidenceContract() {
  const form = contractForm.value
  if (!form.contractRef.trim() || !form.scenario.trim() || !form.question.trim()) {
    ElMessage.warning('请填写方法标识、场景和问题')
    return
  }
  if (!form.queryTemplate.trim()) {
    ElMessage.warning('请填写查询模板')
    return
  }
  if (!form.reason.trim()) {
    ElMessage.warning('请填写变更原因')
    return
  }
  if (form.scopeType !== 'GENERIC' && !form.system.trim()) {
    ElMessage.warning('指定作用域时请填写系统')
    return
  }
  if (form.scopeType === 'MODULE' && !form.service.trim()) {
    ElMessage.warning('指定模块时请填写模块/服务标识')
    return
  }
  contractSaving.value = true
  try {
    await troubleshootingApi.declareEvidenceContract({
      contractRef: form.contractRef.trim(),
      signalKind: form.signalKind,
      scopeType: form.scopeType,
      system: form.scopeType === 'GENERIC' ? undefined : form.system.trim(),
      service: form.scopeType === 'MODULE' ? form.service.trim() : undefined,
      scenario: form.scenario.trim(),
      question: form.question.trim(),
      summary: form.summary.trim() || form.scenario.trim(),
      namespace: form.namespace.trim() || 'L',
      maxRows: 200,
      queryTemplate: form.queryTemplate.trim(),
      fixedConditions: form.fixedConditionsText
        .split('\n')
        .map(line => line.trim())
        .filter(Boolean),
      requiredAssetParameters: form.requiredAssetParametersText
        .split(/[,，]/)
        .map(item => item.trim())
        .filter(Boolean),
      enabled: form.enabled,
      expectedVersion: form.expectedVersion,
      reason: form.reason.trim(),
    })
    contractDrawerOpen.value = false
    ElMessage.success(form.expectedVersion || form.contractRefLocked
      ? '取证方法已更新'
      : '取证方法已保存')
    await loadCatalog()
  } catch (failure) {
    ElMessage.error(failure instanceof Error ? failure.message : '取证方法保存失败')
  } finally {
    contractSaving.value = false
  }
}

function openModuleForSystem(system: string) {
  assetForm.value = emptyAssetForm()
  if (system) {
    assetForm.value.system = system
    assetForm.value.systemLocked = true
  }
  moduleWorkspaceTarget.value = draftModuleEntry()
  onboardingTarget.value = moduleWorkspaceTarget.value
  moduleWorkspaceOpen.value = true
  moduleWorkspacePanel.value = 'edit'
}

function chooseSystemForNewModule(system: string) {
  moduleChooserOpen.value = false
  openModuleForSystem(system)
}

function openExistingModuleRegistration(entry: SetupModuleEntry) {
  prepareExistingModuleRegistration(entry)
  showAssetEditor()
}

function prepareExistingModuleRegistration(entry: SetupModuleEntry) {
  assetForm.value = emptyAssetForm()
  assetForm.value.system = entry.system
  assetForm.value.service = entry.service
  assetForm.value.displayName = entry.displayName || entry.service
  assetForm.value.systemLocked = true
  assetForm.value.serviceLocked = true
  if (entry.asset) {
    assetForm.value.environment = entry.asset.environment || ''
    assetForm.value.region = entry.asset.region || ''
    assetForm.value.cluster = entry.asset.cluster || ''
    assetForm.value.namespace = entry.asset.namespace || ''
    assetForm.value.contractRefs = { ...entry.asset.signalBindings }
    assetForm.value.parameters = { ...entry.asset.parameters }
    assetForm.value.enabled = entry.asset.enabled
    assetForm.value.expectedVersion = entry.asset.origin === 'WORKSPACE'
      ? entry.asset.version
      : undefined
  }
}

function openAssetEditor(asset: ObservabilityAsset) {
  prepareAssetEditor(asset)
  const entry = setupModules.value.find(item =>
    moduleKey(item.system, item.service) === moduleKey(asset.system, asset.service))
  if (entry) ensureModuleWorkspace(entry)
  showAssetEditor()
}

function prepareAssetEditor(asset: ObservabilityAsset) {
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
    systemLocked: true,
    serviceLocked: true,
  }
}

function metadataFieldLabel(parameter: string, label: string) {
  return requiredAssetParameters.value.includes(parameter)
    ? `${label}（所选取证方法必填）`
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
    if (moduleWorkspaceOpen.value) moduleWorkspacePanel.value = 'overview'
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

function openRouteEditor(row: ContractRow) {
  const entry = setupModules.value.find(item =>
    moduleKey(item.system, item.service) === moduleKey(row.system, row.service))
  if (entry) ensureModuleWorkspace(entry)
  else ensureModuleWorkspace(draftModuleEntry())
  routeTarget.value = row
  routePlatforms.value = [...row.contract.route.platforms]
  routeReason.value = ''
  moduleWorkspacePanel.value = 'route'
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
    if (moduleWorkspaceOpen.value) {
      moduleWorkspacePanel.value = toolDetailTarget.value ? 'tool' : 'overview'
    }
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

watch(setupSection, () => {
  query.value = ''
})

onMounted(loadCatalog)
</script>

<style scoped>
.assets-page { display: grid; gap: 14px; }
.page-alert { margin-bottom: 2px; }
.toolbar { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
.search-input { flex: 1; min-width: min(100%, 240px); }
.status-filter { width: 138px; }
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
.source-gate-compact { padding: 14px 16px; }
.source-gate-list-compact { display: flex; flex-wrap: wrap; gap: 6px 18px; margin-top: 10px; }
.list-workspace { min-width: 0; border-top: 1px solid var(--mc-border-light); }
.source-list-workspace { min-height: 420px; }
.list-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; padding: 18px 2px 14px; }
.list-heading h2 { margin: 0; color: var(--mc-text-primary); font-size: 16px; letter-spacing: -.02em; }
.list-heading p { margin: 5px 0 0; color: var(--mc-text-secondary); font-size: 11px; }
.management-table { width: 100%; border-top: 1px solid var(--mc-border-light); }
.management-table :deep(.el-table__header th) { color: var(--mc-text-secondary); background: var(--mc-bg-muted); font-size: 11px; font-weight: 650; }
.management-table :deep(.el-table__row td) { padding-top: 11px; padding-bottom: 11px; }
.management-table :deep(.cell) { line-height: 1.4; }
.management-table :deep(.el-table__row) { transition: background-color .14s ease; }
.management-table :deep(.el-table__fixed-right) { box-shadow: -8px 0 16px rgb(35 45 58 / 4%); }
.management-table code { color: var(--mc-primary); font-size: 11px; overflow-wrap: anywhere; }
.table-primary-cell b, .table-primary-cell small { display: block; }
.table-primary-cell b { color: var(--mc-text-primary); font-size: 13px; }
.table-primary-cell small { margin-top: 4px; color: var(--mc-text-secondary); font-size: 11px; }
.table-detail-trigger { width: 100%; padding: 0; border: 0; color: inherit; background: transparent; text-align: left; cursor: pointer; }
.table-detail-trigger:hover b { color: var(--mc-primary); }
.compact-cell b { font-weight: 600; }
.readiness-count { font: 650 12px var(--mc-font-mono, monospace); }
.onboarding-progress-trigger {
  display: grid;
  gap: 3px;
  width: 100%;
  padding: 0;
  border: 0;
  color: inherit;
  background: transparent;
  text-align: left;
  cursor: pointer;
}
.onboarding-progress-trigger b {
  color: var(--mc-primary);
  font: 700 13px var(--mc-font-mono, monospace);
}
.onboarding-progress-trigger small {
  color: var(--mc-text-secondary);
  font-size: 11px;
  line-height: 1.35;
}
.onboarding-progress-trigger:hover small { color: var(--mc-primary); }
.module-workspace-nav { margin-bottom: 12px; }
.module-workspace-panel { display: grid; gap: 4px; }
.module-workspace-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--mc-border-light);
}
.module-workspace-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}
.module-workspace-head h2 { margin: 4px 0 6px; font-size: 22px; }
.module-workspace-head p { margin: 0; color: var(--mc-text-secondary); }
.module-workspace-guide {
  margin-bottom: 18px;
  padding: 18px 16px;
  border: 1px solid color-mix(in srgb, var(--mc-primary) 28%, var(--mc-border-light));
  border-radius: 12px;
  background: color-mix(in srgb, var(--mc-primary) 6%, var(--mc-bg));
}
.module-workspace-guide b { display: block; margin-bottom: 8px; font-size: 16px; }
.module-workspace-guide p { margin: 0 0 14px; color: var(--mc-text-secondary); line-height: 1.55; }
.module-workspace-guide small { display: block; margin-top: 10px; color: var(--mc-text-secondary); line-height: 1.45; }
.module-workspace-alert { margin-bottom: 14px; }
.module-workspace-block {
  margin-bottom: 18px;
  padding: 16px;
  border: 1px solid var(--mc-border-light);
  border-radius: 12px;
  background: var(--mc-bg-elevated);
}
.module-workspace-block-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.module-workspace-block-head h3 { margin: 0 0 4px; font-size: 15px; }
.module-workspace-block-head p { margin: 0; color: var(--mc-text-secondary); font-size: 12px; line-height: 1.5; }
.module-tool-list { display: grid; gap: 10px; }
.module-tool-card {
  padding: 12px 14px;
  border: 1px solid var(--mc-border-light);
  border-radius: 10px;
  background: var(--mc-bg-muted);
}
.module-tool-main { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.module-tool-main b { display: block; margin-bottom: 4px; }
.module-tool-main p { margin: 0 0 6px; color: var(--mc-text-secondary); font-size: 12px; line-height: 1.45; }
.module-tool-next { display: block; margin: 8px 0; color: var(--mc-text-secondary); font-size: 11px; }
.module-tool-actions { display: flex; flex-wrap: wrap; gap: 4px; }
.module-source-list { margin: 0; padding: 0; list-style: none; display: grid; gap: 10px; }
.module-source-list li {
  display: grid;
  gap: 8px;
  padding: 12px;
  border-radius: 10px;
  background: var(--mc-bg-muted);
}
.module-source-main { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.module-source-list small { color: var(--mc-text-secondary); line-height: 1.45; }
@media (max-width: 960px) {
  .setup-grid.compact { grid-template-columns: 1fr; }
}
.onboarding-dialog-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--mc-border-light);
}
.onboarding-dialog-heading small { color: var(--mc-primary); font-weight: 700; }
.onboarding-dialog-heading h3 { margin: 5px 0; color: var(--mc-text-primary); font-size: 20px; }
.onboarding-dialog-heading p { margin: 0; color: var(--mc-text-secondary); font-size: 12px; }
.onboarding-step-list { display: grid; gap: 0; margin: 18px 0; border-top: 1px solid var(--mc-border-light); }
.onboarding-step {
  display: grid;
  grid-template-columns: 30px minmax(0, 1fr);
  gap: 12px;
  padding: 14px 2px;
  border-bottom: 1px solid var(--mc-border-light);
}
.onboarding-step-index {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border: 1px solid var(--mc-border);
  border-radius: 50%;
  color: var(--mc-text-secondary);
  background: var(--mc-bg-muted);
  font-size: 11px;
  font-weight: 750;
}
.onboarding-step.done .onboarding-step-index {
  border-color: color-mix(in srgb, var(--mc-success, #2f7d67) 35%, transparent);
  color: var(--mc-success, #2f7d67);
  background: color-mix(in srgb, var(--mc-success, #2f7d67) 10%, var(--mc-bg));
}
.onboarding-step.unknown .onboarding-step-index {
  border-style: dashed;
  color: var(--mc-warning, #9b6a28);
}
.onboarding-step-title { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
.onboarding-step-title b { color: var(--mc-text-primary); font-size: 13px; }
.onboarding-step-title em { color: var(--mc-text-tertiary); font-size: 10.5px; font-style: normal; }
.onboarding-step.done .onboarding-step-title em { color: var(--mc-success, #2f7d67); }
.onboarding-step.unknown .onboarding-step-title em { color: var(--mc-warning, #9b6a28); }
.onboarding-step p { margin: 5px 0 0; color: var(--mc-text-secondary); font-size: 11.5px; line-height: 1.55; }
.status-cell { display: grid; justify-items: start; gap: 5px; }
.status-cell small { color: var(--mc-text-secondary); font-size: 10.5px; line-height: 1.35; }
.source-checks { display: flex; flex-wrap: wrap; gap: 6px; }
.muted-action { color: var(--mc-text-tertiary); font-size: 11px; }
.dialog-help { margin: 0 0 14px; color: var(--mc-text-secondary); font-size: 12px; line-height: 1.6; }
.system-choice-list { display: grid; border-top: 1px solid var(--mc-border-light); }
.system-choice-list button { display: flex; align-items: center; justify-content: space-between; gap: 16px; width: 100%; padding: 14px 4px; border: 0; border-bottom: 1px solid var(--mc-border-light); color: inherit; background: transparent; text-align: left; cursor: pointer; }
.system-choice-list button:hover { color: var(--mc-primary); background: var(--mc-bg-muted); }
.system-choice-list b, .system-choice-list small { display: block; }
.system-choice-list small { margin-top: 4px; color: var(--mc-text-secondary); font-size: 11px; }
.tool-detail-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; padding-bottom: 16px; border-bottom: 1px solid var(--mc-border-light); }
.tool-detail-heading small { color: var(--mc-primary); font-weight: 700; }
.tool-detail-heading h3 { margin: 6px 0; color: var(--mc-text-primary); font-size: 20px; }
.tool-detail-heading p { margin: 0 0 7px; color: var(--mc-text-secondary); line-height: 1.55; }
.tool-detail-heading code { color: var(--mc-primary); font-size: 11px; }

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
.field-help { width: 100%; margin: 0 0 10px; color: var(--mc-text-secondary); font-size: 12px; line-height: 1.6; }
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
  .asset-detail-grid, .asset-parameter-list, .asset-form-grid, .contract-binding-row, .trial-time-grid { grid-template-columns: 1fr; }
  .tool-detail-heading { flex-direction: column; }
  .management-table :deep(.optional-column) { display: none; }
}
</style>
