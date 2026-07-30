<template>
  <div
    class="formal-workbench"
    :class="{
      'traditional-list-mode': viewMode === 'LIST',
      'full-detail-mode': viewMode === 'DETAIL',
    }"
  >
    <DiagnosisListView
      v-if="viewMode === 'LIST'"
      v-model:status-filter="statusFilter"
      :rows="rows"
      :loading="listLoading"
      :can-operate="canOperateTroubleshooting"
      :can-manage="canManageTroubleshooting"
      @refresh="loadList(false)"
      @launch="openTroubleshootingScenario"
      @capability-command="handleCapabilityCommand"
      @open-diagnosis="openDiagnosisFromList"
      @switch-view="switchWorkbenchView('QUEUE')"
      @open-legacy="openLegacy"
    />

    <template v-else>
    <aside v-if="shouldShowQueuePanel(viewMode)" class="queue-panel">
      <header class="queue-head">
        <div><span class="eyebrow">MateClaw</span><h2>排障队列</h2></div>
        <div class="queue-head-actions">
          <el-tag size="small" type="info" round>{{ rows.length }}</el-tag>
          <WorkbenchViewSwitch mode="QUEUE" compact @change="switchWorkbenchView" />
        </div>
      </header>
      <div class="queue-tools">
        <el-select v-model="statusFilter" size="small" clearable placeholder="全部状态" @change="loadList(false)">
          <el-option v-for="status in STATUSES" :key="status" :label="statusLabel(status)" :value="status" />
        </el-select>
        <div class="queue-action-row">
          <el-button
            v-if="canOperateTroubleshooting || canManageTroubleshooting"
            size="small"
            type="primary"
            plain
            :icon="Plus"
            @click="openTroubleshootingScenario"
          >{{ TROUBLESHOOTING_UI_LABELS.launch }}</el-button>
          <WorkbenchCapabilityMenu
            v-if="canManageTroubleshooting"
            size="small"
            @command="handleCapabilityCommand"
          />
        </div>
      </div>
      <div v-loading="listLoading" class="queue-list">
        <button
          v-for="row in rows"
          :key="row.diagnosisId"
          type="button"
          class="queue-item"
          :class="{ active: row.diagnosisId === selectedId }"
          @click="selectDiagnosis(row.diagnosisId)"
        >
          <div class="queue-item-top">
            <code>{{ row.system }}:{{ row.errorCode || 'NO-CODE' }}</code>
            <span v-if="row.rehearsal" class="rehearsal">演练</span>
          </div>
          <strong>{{ row.service }}</strong>
          <div class="queue-item-bottom">
            <span :class="statusTone(row.status)">{{ statusLabel(row.status) }}</span>
            <time>{{ shortTime(row.updateTime) }}</time>
          </div>
        </button>
        <div v-if="!listLoading && !rows.length" class="queue-empty">
          <b>还没有诊断记录</b>
          <p>从正式入口选择排障场景；通用事件会进入 Diagnosis 主链，专项场景遵守各自能力边界。</p>
          <el-button
            v-if="canOperateTroubleshooting || canManageTroubleshooting"
            size="small"
            type="primary"
            plain
            @click="openTroubleshootingScenario"
          >{{ TROUBLESHOOTING_UI_LABELS.launch }}</el-button>
          <code v-else>需要 operate:troubleshooting 权限</code>
        </div>
      </div>
      <footer class="queue-foot">
        <span>正式入口 · 真实 API</span>
        <button type="button" @click="openLegacy">旧版处置台</button>
      </footer>
    </aside>

    <main v-loading="detailLoading" class="work-area">
      <div v-if="!business || !developer || !current" class="detail-empty">
        <div class="empty-mark">MC</div>
        <h1>选择一条诊断开始排障</h1>
        <p>服务经理先看业务摘要；开发证据在同一页面按需展开。</p>
        <el-button
          v-if="canOperateTroubleshooting || canManageTroubleshooting"
          type="primary"
          plain
          :icon="Plus"
          @click="openTroubleshootingScenario"
        >{{ TROUBLESHOOTING_UI_LABELS.launch }}</el-button>
      </div>

      <template v-else>
        <header class="work-head">
          <div>
            <span class="eyebrow">正式排障工作台 · Diagnosis {{ business.diagnosisId }}</span>
            <h1>{{ business.problem }}</h1>
          </div>
          <div class="work-head-actions">
            <el-button
              v-if="viewMode === 'DETAIL'"
              size="small"
              plain
              @click="switchWorkbenchView('LIST')"
            >返回排障列表</el-button>
            <el-button size="small" :icon="Refresh" text @click="reload">刷新</el-button>
            <el-button v-if="canManageTroubleshooting" size="small" plain @click="openEvaluationLedger">{{ TROUBLESHOOTING_UI_LABELS.evaluation }}</el-button>
            <el-button size="small" plain @click="openLegacy">打开旧版处置台</el-button>
          </div>
        </header>

        <div v-if="business.fixtureMode" class="fixture-banner">
          <span class="fixture-dot" />
          <b>Recorded Replay · 非真实观测云</b>
          <span>当前数据来自受控回放；页面不会把演练证据描述成真实生产观测。</span>
        </div>

        <section class="business-card">
          <div class="verdict-head">
            <div class="verdict-copy">
              <div class="badge-row">
                <span class="conclusion-badge" :class="business.conclusionType.toLowerCase()">
                  {{ conclusionLabel(business.conclusionType) }}
                </span>
                <span class="status-badge" :class="statusTone(business.status)">{{ statusLabel(business.status) }}</span>
                <span class="confidence-badge" :class="business.confidence.toLowerCase()">
                  可信等级 {{ business.confidence }}
                </span>
              </div>
              <h2>{{ business.headline }}</h2>
              <p>{{ business.narrative }}</p>
            </div>
          </div>

          <div class="summary-grid">
            <article>
              <span class="section-label">问题</span>
              <strong>{{ business.problem }}</strong>
            </article>
            <article>
              <span class="section-label">影响</span>
              <strong>{{ business.impact.functionScope }}</strong>
              <div v-if="impactMetricList.length" class="impact-metrics">
                <span v-for="metric in impactMetricList" :key="metric">{{ metric }}</span>
              </div>
              <small>{{ blastRadiusLabel(business.impact.blastRadius) }} · {{ business.impact.note }}</small>
            </article>
            <article>
              <span class="section-label">{{ business.nextStep.label }}</span>
              <strong>{{ business.nextStep.text }}</strong>
              <small class="capability-boundary">{{ business.nextStep.capabilityBoundary }}</small>
            </article>
          </div>

          <section v-if="closure" class="closure-result">
            <div>
              <span class="section-label">最终处置结果</span>
              <b>{{ closureOutcomeLabel(closure.outcome) }}</b>
            </div>
            <strong>{{ closure.summary }}</strong>
            <small>
              {{ closure.recoveryVerified ? '恢复已经人工验证' : '未声明恢复已经验证' }}
              · {{ shortTime(closure.closedAt) }}
            </small>
          </section>

          <div class="timing-strip">
            <article>
              <span>补问 / Intake</span>
              <b>{{ timingState(business.timings.readyAt, business.timings.intakeCost, 'recorded') }}</b>
              <small>{{ timeRange(business.timings.reportedAt, business.timings.readyAt) }}</small>
            </article>
            <i />
            <article>
              <span>调查 / Investigate</span>
              <b>{{ timingState(business.timings.conclusionAt, business.timings.investigateCost, 'recorded') }}</b>
              <small>{{ timeRange(business.timings.readyAt, business.timings.conclusionAt) }}</small>
            </article>
            <i />
            <article>
              <span>采纳 / Handoff</span>
              <b>{{ timingState(business.timings.handoffAt, business.timings.adoptCost, 'pending') }}</b>
              <small>{{ timeRange(business.timings.conclusionAt, business.timings.handoffAt, true) }}</small>
            </article>
          </div>

          <div class="lifecycle-bar">
            <el-button
              v-if="canOperateTroubleshooting && current.diagnosis.status === 'READY_FOR_HUMAN'"
              type="primary"
              :loading="actionLoading"
              @click="confirm"
            >确认结论</el-button>
            <el-button v-if="canTransfer" :disabled="actionLoading" @click="transferOpen = true">结构化转派</el-button>
            <el-button v-if="canClose" :disabled="actionLoading" @click="closeOpen = true">关闭并沉淀知识</el-button>
            <span v-if="current.diagnosis.status === 'NEEDS_INVESTIGATION'">当前已弃权：补齐证据后才能重新形成结论。</span>
            <span v-else>按钮只推进领域状态，MateClaw 不执行任何生产变更。</span>
          </div>
        </section>

        <section class="topology-evidence-card">
          <div class="topology-evidence-head">
            <div>
              <span class="section-label">场景证据 · deployment_topology_probe</span>
              <h3>部署拓扑拨测</h3>
              <p>选择 Workspace 已导入拓扑，经 <code>topology_synthetic_probe</code> 只读取证，结果归属当前 Diagnosis。</p>
            </div>
            <el-button
              v-if="canManageTroubleshooting"
              type="primary"
              plain
              :disabled="current.diagnosis.status === 'CLOSED'"
              @click="deploymentTopologyOpen = true"
            >选择拓扑并运行</el-button>
          </div>
          <div v-if="latestTopologyProbeRun" class="topology-evidence-result">
            <div>
              <span>最新运行</span>
              <b>{{ deploymentAnalysisLabel(latestTopologyProbeRun.result.status) }}</b>
              <small>{{ shortTime(latestTopologyProbeRun.completedAt) }} · {{ latestTopologyProbeRun.actorRef }}</small>
            </div>
            <dl>
              <div><dt>已配置</dt><dd>{{ latestTopologyProbeRun.result.summary.configuredProbeNodes }}</dd></div>
              <div><dt>已观测</dt><dd>{{ latestTopologyProbeRun.result.summary.observedProbeNodes }}</dd></div>
              <div class="failed"><dt>失败</dt><dd>{{ latestTopologyProbeRun.result.summary.failingProbeNodes }}</dd></div>
              <div><dt>不可用</dt><dd>{{ latestTopologyProbeRun.result.summary.unavailableProbeNodes }}</dd></div>
            </dl>
            <div v-if="latestTopologyProbeRun.result.suspectLinks.length" class="topology-link-hints">
              <span>需核查相邻链路</span>
              <code v-for="link in latestTopologyProbeRun.result.suspectLinks" :key="`${link.source}-${link.target}`">
                {{ link.source }} → {{ link.target }}
              </code>
            </div>
            <div v-if="latestTopologyProbeRun.result.observations.length" class="topology-observations">
              <span>节点观测</span>
              <div v-for="observation in latestTopologyProbeRun.result.observations" :key="observation.nodeKey">
                <b>{{ observation.label }}</b>
                <code>{{ observation.nodeKey }}</code>
                <em>{{ observationStatusLabel(observation.status) }}</em>
                <small v-if="observation.statusCode">HTTP {{ observation.statusCode }}</small>
              </div>
            </div>
            <small class="topology-history-count">已保留 {{ topologyProbeRuns.length }} 次安全证据运行；异常节点和相邻链路是核查提示，不等于根因。</small>
            <details class="topology-run-history">
              <summary>查看运行历史（{{ topologyProbeRuns.length }}）</summary>
              <ol>
                <li v-for="run in topologyProbeRuns" :key="run.runId">
                  <header>
                    <div>
                      <b>{{ deploymentAnalysisLabel(run.result.status) }}</b>
                      <small>{{ shortTime(run.completedAt) }} · {{ run.actorRef }}</small>
                    </div>
                    <code>{{ run.topologyId }}</code>
                  </header>
                  <p>
                    已观测 {{ run.result.summary.observedProbeNodes }} / 已配置 {{ run.result.summary.configuredProbeNodes }}；
                    失败 {{ run.result.summary.failingProbeNodes }}；不可用 {{ run.result.summary.unavailableProbeNodes }}
                  </p>
                  <p v-if="run.result.suspectLinks.length" class="history-links">
                    核查链路：{{ run.result.suspectLinks.map(link => `${link.source} → ${link.target}`).join('、') }}
                  </p>
                  <p v-if="run.result.warnings.length" class="history-warning">
                    {{ run.result.warnings.join('；') }}
                  </p>
                  <ul v-if="run.result.observations.length" class="history-observations">
                    <li v-for="observation in run.result.observations" :key="observation.nodeKey">
                      <b>{{ observation.label }}</b>
                      <code>{{ observation.nodeKey }}</code>
                      <span>{{ observationStatusLabel(observation.status) }}</span>
                      <small v-if="observation.statusCode">HTTP {{ observation.statusCode }}</small>
                      <small>{{ observation.evidenceRef }}</small>
                    </li>
                  </ul>
                </li>
              </ol>
            </details>
          </div>
          <div v-else class="empty-evidence">当前 Diagnosis 还没有部署拓扑拨测证据。</div>
        </section>

        <details class="developer-fold">
          <summary>
            <span class="fold-caret" />
            <div><b>展开开发证据台</b><small>证据、判据与能力边界，可复核但不展示模型私有思维链</small></div>
            <span>{{ developer.steps.length }} 个证据 / 判据步骤</span>
          </summary>
          <div class="developer-body">
            <div class="route-card">
              <span>调查路径</span>
              <b>{{ investigationLabel(developer.investigationMode, developer.routeAuthority) }}</b>
              <code>{{ developer.playbookRef || '未命中已审核 Playbook' }}</code>
            </div>

            <div class="convergence-grid">
              <section class="trace-summary">
                <div class="section-head">
                  <div><span class="section-label">证据收敛</span><h3>PS / Trace 全链路</h3></div>
                  <code>{{ developer.callChain.psId || '未贯通' }}</code>
                </div>
                <div v-if="developer.callChain.hops.length" class="hop-line">
                  <div
                    v-for="(hop, index) in developer.callChain.hops"
                    :key="hop.hopId"
                    class="hop"
                    :class="{ anomalous: hop.anomalous }"
                  >
                    <span>{{ index + 1 }}</span><b>{{ hop.service }}</b><small>{{ hop.duration }}</small>
                  </div>
                </div>
                <p v-else class="empty-evidence">{{ developer.callChain.emptyReason }}</p>
                <div class="contrast-row" :class="{ unavailable: !developer.contrast.available }">
                  <span>成功样本对照</span>
                  <template v-if="developer.contrast.available">
                    <b>{{ developer.contrast.failedSample }}</b><em>vs</em>
                    <b class="baseline">{{ developer.contrast.baselineSample }}</b>
                  </template>
                  <b v-else>未取得</b>
                  <small>{{ developer.contrast.note }}</small>
                </div>
              </section>

              <aside class="draft-summary">
                <div class="section-head">
                  <div><span class="section-label">知识草稿</span><h3>{{ developer.draft.title }}</h3></div>
                  <span class="draft-state">{{ developer.draft.reviewStatus }}</span>
                </div>
                <ol v-if="developer.draft.steps.length">
                  <li v-for="step in developer.draft.steps" :key="step">{{ step }}</li>
                </ol>
                <p v-else class="empty-evidence">{{ developer.draft.emptyReason }}</p>
                <small>{{ developer.draft.stateNote }}</small>
              </aside>
            </div>

            <section class="evidence-timeline">
              <div class="developer-section-head">
                <div><span class="section-label">证据时间线</span><h3>事实与判据逐行复核</h3></div>
                <span>{{ conclusionLabel(business.conclusionType) }} · {{ business.confidence }}</span>
              </div>
              <article
                v-for="step in developer.steps"
                :key="`${step.kind}-${step.at || ''}-${step.ref}`"
                class="evidence-step"
                :class="step.tone.toLowerCase()"
              >
                <time>{{ evidenceTime(step.kind, step.at) }}</time>
                <span class="step-line"><i /></span>
                <div><b>{{ step.title }}</b><p>{{ step.detail }}</p><code>{{ step.ref }}</code></div>
                <span class="tone-label">{{ stepToneLabel(step.tone) }}</span>
              </article>
              <div v-if="!developer.steps.length" class="empty-evidence">当前 Diagnosis 没有可展示的证据或判据步骤。</div>
            </section>

            <aside class="developer-side">
              <section v-loading="readinessLoading" class="source-gate-card">
                <div class="source-gate-head">
                  <div><span class="section-label">{{ TROUBLESHOOTING_UI_LABELS.guanceOnboarding }}</span><h3>Guance 只读证据适配器</h3></div>
                  <span v-if="guanceReadiness" class="source-gate-state" :class="readinessTone(guanceReadiness.status)">
                    {{ guanceReadinessLabel(guanceReadiness.status) }}
                  </span>
                </div>
                <template v-if="guanceReadiness">
                  <p class="source-scope"><code>{{ guanceReadiness.system }}</code><span>/</span><code>{{ guanceReadiness.service }}</code></p>
                  <div class="source-meta">
                    <span :class="guanceReadiness.endpointConfigured ? 'success' : 'warning'">端点 {{ guanceReadiness.endpointConfigured ? '已配置' : '未就绪' }}</span>
                    <span :class="guanceReadiness.uniqueAssetAuthorized ? 'success' : 'warning'">Workspace 资产 {{ guanceReadiness.uniqueAssetAuthorized ? '唯一授权' : '未唯一授权' }}</span>
                  </div>
                  <ol v-if="guanceAcceptance" class="acceptance-ladder">
                    <li v-for="stage in guanceAcceptance.stages" :key="stage.code">
                      <span>{{ stage.code }}</span>
                      <div><b>{{ stage.title }}</b><small>{{ stage.detail }}</small></div>
                      <strong :class="acceptanceTone(stage.state)">{{ guanceAcceptanceStateLabel(stage.state) }}</strong>
                    </li>
                  </ol>
                  <div
                    v-if="guanceOwnerAcceptance"
                    class="validation-result owner-acceptance-result"
                    :class="ownerAcceptanceTone(guanceOwnerAcceptance.status)"
                  >
                    <b>{{ ownerAcceptanceStateLabel(guanceOwnerAcceptance.status) }}</b>
                    <span v-if="guanceOwnerAcceptance.acceptance">
                      {{ guanceOwnerAcceptance.acceptance.acceptedBy }} ·
                      {{ shortTime(guanceOwnerAcceptance.acceptance.acceptedAt) }}
                    </span>
                    <small>
                      当前配置指纹
                      <code>{{ shortFingerprint(guanceOwnerAcceptance.currentBindingFingerprint) }}</code>
                      <template v-if="guanceOwnerAcceptance.acceptance">
                        · 验收指纹
                        <code>{{ shortFingerprint(guanceOwnerAcceptance.acceptance.bindingFingerprint) }}</code>
                      </template>
                    </small>
                    <small v-for="blocker in guanceOwnerAcceptance.blockers" :key="blocker">{{ blocker }}</small>
                  </div>
                  <p v-if="guanceAcceptance" class="next-source-action"><b>下一步</b>{{ guanceAcceptance.nextAction }}</p>
                  <ul class="signal-readiness-list">
                    <li v-for="signal in guanceReadiness.signals" :key="signal.signalKind">
                      <code>{{ signal.signalKind }}</code>
                      <span :class="signalTone(signal.status)">{{ guanceSignalLabel(signal.status) }}</span>
                      <small>{{ signal.bindingRef || '无 binding' }}</small>
                    </li>
                  </ul>
                  <p v-for="blocker in guanceReadiness.blockers" :key="blocker" class="source-blocker">{{ blocker }}</p>
                  <div v-if="guanceValidation" class="validation-result" :class="guanceValidation.stage === 'CANONICAL_CHAIN_OBSERVED' ? 'passed' : 'blocked'">
                    <b>{{ guanceValidationLabel(guanceValidation.stage) }}</b>
                    <span v-if="guanceValidation.stage === 'CANONICAL_CHAIN_OBSERVED'">
                      {{ guanceValidation.matchCount }} 条命中 · PS {{ guanceValidation.psId }} · {{ guanceValidation.traceEntries }} 个链路节点 · 总耗时 {{ guanceValidation.totalDurationMs }} ms
                    </span>
                    <small>{{ guanceValidation.warnings[0] }}</small>
                  </div>
                  <div v-if="guanceSpinePreview" class="validation-result spine-preview-result" :class="guanceSpinePreview.stage === 'FULL_SPINE_OBSERVED' ? 'passed' : 'blocked'">
                    <b>{{ guanceSpinePreviewLabel(guanceSpinePreview.stage) }}</b>
                    <span v-if="guanceSpinePreview.stage !== 'BLOCKED'">
                      {{ guanceSpinePreview.serviceSequence.join(' → ') }} · {{ guanceSpinePreview.anomalyCount }} 个异常点 · {{ guanceSpinePreview.totalDurationMs }} ms
                    </span>
                    <span v-if="guanceSpinePreview.contrast.available">
                      失败 {{ percent(guanceSpinePreview.contrast.failureRate) }} ↔ 成功 {{ percent(guanceSpinePreview.contrast.successRate) }}
                    </span>
                    <small>{{ guanceSpinePreview.warnings[0] }}</small>
                  </div>
                  <el-button
                    v-if="canManageTroubleshooting"
                    size="small"
                    plain
                    :disabled="!canValidateGuance"
                    @click="openGuanceValidation"
                  >打开真源验收</el-button>
                  <small class="gate-note">单次读链不会自动通过 T7。owner 验收会绑定当前配置指纹；配置变化后自动过期。T8 仍需 20–30 条真实样本，fixtureMode 不会自动关闭。</small>
                </template>
                <p v-else class="empty-evidence">{{ readinessError || '正在检查当前 Workspace 的真源绑定…' }}</p>
              </section>
              <section>
                <span class="section-label">明确做不到</span><h3>能力边界</h3>
                <ul class="capability-list"><li v-for="item in developer.capabilityLimits" :key="item">{{ item }}</li></ul>
              </section>
              <section v-if="current.diagnosis.recommendedActions.length">
                <span class="section-label">人工处置动作</span><h3>平台不执行</h3>
                <article
                  v-for="action in current.diagnosis.recommendedActions"
                  :key="action.actionId"
                  class="action-card"
                  :class="{ write: action.actionType === 'MANUAL_WRITE' }"
                >
                  <div><code>{{ action.actionType }}</code><span>{{ action.approvalStatus }}</span></div>
                  <b>{{ action.title }}</b><p>{{ action.description }}</p>
                  <el-button v-if="canApprove(action)" size="small" type="warning" plain @click="openApprove(action)">批准（不执行）</el-button>
                  <el-button v-if="canRecordOutcome(action)" size="small" plain @click="openOutcome(action)">登记外部结果</el-button>
                </article>
              </section>
            </aside>
          </div>
        </details>
      </template>
    </main>
    </template>

    <TroubleshootingScenarioDialog
      v-model="scenarioLauncherOpen"
      :can-operate="canOperateTroubleshooting"
      :can-manage="canManageTroubleshooting"
      @select="startTroubleshootingScenario"
    />

    <el-dialog
      v-model="incidentReportOpen"
      :title="TROUBLESHOOTING_UI_LABELS.incident"
      width="min(620px, calc(100vw - 32px))"
    >
      <el-alert type="info" :closable="false" class="dialog-alert">
        该入口调用正式 Incident API 并真实创建 Diagnosis 记录；只提交现象和标识符，不接收原始日志、DQL、凭据、影响人数或调用方证据，也不会执行生产变更。
      </el-alert>
      <el-form label-position="top" @submit.prevent="reportIncident">
        <div class="incident-form-grid">
          <el-form-item label="故障系统" required>
            <el-input v-model="incidentReportForm.system" maxlength="128" placeholder="例如 CSDP" />
          </el-form-item>
          <el-form-item label="故障服务" required>
            <el-input v-model="incidentReportForm.service" maxlength="128" placeholder="例如 csdp-session-service" />
          </el-form-item>
          <el-form-item label="严重级别" required>
            <el-select v-model="incidentReportForm.severity" style="width: 100%">
              <el-option label="P0 · 全局阻断" value="P0" />
              <el-option label="P1 · 核心故障" value="P1" />
              <el-option label="P2 · 一般故障" value="P2" />
              <el-option label="P3 · 低优先级" value="P3" />
            </el-select>
          </el-form-item>
          <el-form-item label="错误码（可选）">
            <el-input v-model="incidentReportForm.errorCode" maxlength="128" placeholder="例如 903001" />
          </el-form-item>
        </div>
        <el-form-item label="故障现象" required>
          <el-input
            v-model="incidentReportForm.title"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="描述用户可见现象；不要粘贴原始日志或密钥"
          />
        </el-form-item>
        <el-form-item label="Trace / PS 线索（可选）">
          <el-input v-model="incidentReportForm.traceId" maxlength="128" placeholder="只填写安全标识符，不粘贴链路正文" />
        </el-form-item>
        <div class="incident-route-preview" :class="incidentRoutePreview.tone.toLowerCase()">
          <span>预期调查路径</span>
          <b>{{ incidentRoutePreview.title }}</b>
          <p>{{ incidentRoutePreview.detail }}</p>
        </div>
        <el-checkbox v-model="incidentReportForm.rehearsal" class="incident-rehearsal">
          演练记录（推荐；不参与五分钟生产事件去重）
        </el-checkbox>
        <p class="form-hint">演练记录也会进入队列并明确标记；关闭演练标记后按正式事件启用五分钟幂等。两种模式都只读取证，生产处置仍由人工完成。</p>
      </el-form>
      <template #footer>
        <el-button @click="incidentReportOpen = false">取消</el-button>
        <el-button
          type="primary"
          :loading="incidentReportLoading"
          :disabled="!canSubmitIncidentReport"
          @click="reportIncident"
        >创建 Diagnosis</el-button>
      </template>
    </el-dialog>

    <GuanceOnboardingDialog
      v-model="guanceOnboardingOpen"
      :initial-request="guanceOnboardingInitialRequest"
      @start-validation="startGuanceValidationFromOnboarding"
    />

    <DeploymentTopologySopDialog
      v-model="deploymentTopologyOpen"
      :diagnosis-id="business?.diagnosisId"
      @completed="handleTopologyProbeCompleted"
    />

    <el-dialog v-model="guanceValidationOpen" :title="TROUBLESHOOTING_UI_LABELS.guanceValidation" width="min(620px, calc(100vw - 32px))">
      <el-alert type="warning" :closable="false" class="dialog-alert">两种验证都只读真实观测数据，不持久化原始日志，不回退 Recorded Replay。先用两步读链核对 T7，再用完整 Evidence Spine 检查成功样本对照与确定性压缩；两者都不会自动通过 T7/T8。</el-alert>
      <el-form label-position="top">
        <div class="validation-scope">
          <span>Workspace 资产</span>
          <code>{{ guanceValidationForm.system }} / {{ guanceValidationForm.service }}</code>
        </div>
        <el-form-item label="可安全插入 DQL 模板的搜索键">
          <el-input v-model="guanceValidationForm.searchTerm" placeholder="例如 message_send_failed" />
          <p class="form-hint">仅允许资源标识符字符；未提供 errorCode 时不会用自由文本故障描述自动填充。</p>
        </el-form-item>
        <el-form-item label="时间窗口">
          <el-select v-model="guanceValidationForm.window" style="width: 100%">
            <el-option
              v-for="option in EVIDENCE_WINDOW_OPTIONS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <div v-if="validationDialogReport" class="dialog-validation-result">
        <b>{{ guanceValidationLabel(validationDialogReport.stage) }}</b>
        <ul>
          <li v-for="step in validationDialogReport.steps" :key="step.signalKind"><code>{{ step.signalKind }}</code><span>{{ step.detail }}</span><time>{{ step.durationMs == null ? '未执行' : `${step.durationMs} ms` }}</time></li>
        </ul>
        <p>端到端 {{ validationDialogReport.totalDurationMs }} ms</p>
        <small v-for="warning in validationDialogReport.warnings" :key="warning">{{ warning }}</small>
      </div>
      <div
        v-if="validationDialogOwnerAcceptance"
        class="dialog-validation-result owner-acceptance-result"
        :class="ownerAcceptanceTone(validationDialogOwnerAcceptance.status)"
      >
        <b>{{ ownerAcceptanceStateLabel(validationDialogOwnerAcceptance.status) }}</b>
        <p v-if="validationDialogOwnerAcceptance.acceptance">
          {{ validationDialogOwnerAcceptance.acceptance.acceptedBy }} ·
          {{ shortTime(validationDialogOwnerAcceptance.acceptance.acceptedAt) }}
        </p>
        <small>
          当前配置指纹
          <code>{{ shortFingerprint(validationDialogOwnerAcceptance.currentBindingFingerprint) }}</code>
        </small>
        <small v-for="blocker in validationDialogOwnerAcceptance.blockers" :key="blocker">{{ blocker }}</small>
      </div>
      <div
        v-if="validationDialogReport?.stage === 'CANONICAL_CHAIN_OBSERVED' && validationDialogOwnerAcceptance?.status !== 'ACCEPTED' && canAcceptGuanceOwner"
        class="t7-owner-checklist"
      >
        <b>T7 owner 字段核实清单</b>
        <el-checkbox v-model="guanceAcceptanceChecklist.measurementAndFieldsVerified">
          已核实真实 measurement 与 canonical 字段映射
        </el-checkbox>
        <el-checkbox v-model="guanceAcceptanceChecklist.indexVerified">
          已核实索引、数据范围与查询资产
        </el-checkbox>
        <el-checkbox v-model="guanceAcceptanceChecklist.psIdJoinVerified">
          已确认 log_search 与 trace 使用同一 PS ID
        </el-checkbox>
        <el-checkbox v-model="guanceAcceptanceChecklist.timestampUnitVerified">
          已核实时间戳单位
        </el-checkbox>
        <el-checkbox v-model="guanceAcceptanceChecklist.timeWindowVerified">
          已核实时间窗口语义
        </el-checkbox>
        <el-checkbox v-model="guanceAcceptanceChecklist.dqlLatencyReviewed">
          已在 Guance 侧核对 DQL 延迟
        </el-checkbox>
        <el-checkbox v-model="guanceAcceptanceChecklist.legacyRouteConflictReviewed">
          已复核 903001 与历史 route key 冲突
        </el-checkbox>
        <p class="form-hint">提交时服务端会再次运行 Guance-only 两步读链，并将验收绑定到当前查询模板、字段映射、端点和路由的 SHA-256 指纹；不保存搜索键、PS ID 原文、DQL、凭据或日志。</p>
      </div>
      <p
        v-else-if="validationDialogReport?.stage === 'CANONICAL_CHAIN_OBSERVED' && validationDialogOwnerAcceptance?.status !== 'ACCEPTED'"
        class="source-blocker"
      >只有当前 Workspace owner 可以提交 T7 验收；admin 可以执行只读验证，但不能替 owner 解锁真实 T8。</p>
      <div v-if="validationDialogSpinePreview" class="dialog-validation-result spine-dialog-result">
        <b>{{ guanceSpinePreviewLabel(validationDialogSpinePreview.stage) }}</b>
        <ul>
          <li v-for="step in validationDialogSpinePreview.steps" :key="step.signalKind">
            <code>{{ step.signalKind }}</code>
            <span>{{ spineStepStatusLabel(step.status) }}</span>
            <time>{{ step.collectedAt ? shortTime(step.collectedAt).slice(11) : '未执行' }}</time>
          </li>
        </ul>
        <div v-if="validationDialogSpinePreview.stage !== 'BLOCKED'" class="spine-facts">
          <p><span>调用链骨架</span><b>{{ validationDialogSpinePreview.serviceSequence.join(' → ') }}</b></p>
          <p><span>核心样本</span><b>{{ validationDialogSpinePreview.matchCount }} 条命中 · {{ validationDialogSpinePreview.traceEntries }} 个节点 · {{ validationDialogSpinePreview.anomalyCount }} 个异常点</b></p>
          <p v-if="validationDialogSpinePreview.contrast.available"><span>失败 ↔ 成功对照</span><b>{{ validationDialogSpinePreview.contrast.failureMatchCount }}/{{ validationDialogSpinePreview.contrast.failureSampleCount }}（{{ percent(validationDialogSpinePreview.contrast.failureRate) }}） ↔ {{ validationDialogSpinePreview.contrast.successMatchCount }}/{{ validationDialogSpinePreview.contrast.successSampleCount }}（{{ percent(validationDialogSpinePreview.contrast.successRate) }}）</b></p>
          <p v-else><span>失败 ↔ 成功对照</span><b>未取得，继续校准期</b></p>
          <p><span>应用侧总耗时</span><b>{{ validationDialogSpinePreview.totalDurationMs }} ms</b></p>
        </div>
        <small v-for="warning in validationDialogSpinePreview.warnings" :key="warning">{{ warning }}</small>
      </div>
      <template #footer>
        <el-button @click="guanceValidationOpen = false">关闭</el-button>
        <el-button
          plain
          :loading="validationLoading"
          :disabled="!guanceValidationForm.searchTerm"
          @click="validateGuance"
        >T7 两步读链</el-button>
        <el-button
          type="primary"
          :loading="spinePreviewLoading"
          :disabled="!guanceValidationForm.searchTerm"
          @click="previewGuanceSpine"
        >完整 Evidence Spine</el-button>
        <el-button
          v-if="validationDialogReport?.stage === 'CANONICAL_CHAIN_OBSERVED' && validationDialogOwnerAcceptance?.status !== 'ACCEPTED' && canAcceptGuanceOwner"
          type="success"
          :loading="acceptanceLoading"
          :disabled="!canAcceptGuance"
          @click="acceptGuance"
        >确认当前绑定 T7 验收</el-button>
        <el-button
          v-if="validationDialogSpinePreview && validationDialogSpinePreview.stage !== 'BLOCKED' && validationCanOpenCurrentEvaluationLedger"
          type="success"
          plain
          @click="openEvaluationLedger"
        >进入{{ TROUBLESHOOTING_UI_LABELS.evaluation }}</el-button>
      </template>
    </el-dialog>

    <EvaluationSampleLedgerDialog
      v-model="evaluationLedgerOpen"
      :current-diagnosis-id="current?.diagnosis.diagnosisId || null"
      :current-diagnosis-status="current?.diagnosis.status || null"
      :capture-context="evaluationCaptureContext"
      :replay-capture-context="replayEvaluationCaptureContextValue"
      :capture-enabled="canCaptureEvaluationSample"
      :capture-disabled-reason="evaluationCaptureDisabledReason"
      :replay-capture-enabled="canCaptureReplayEvaluationSample"
      :replay-capture-disabled-reason="replayCaptureDisabledReason"
      @open-diagnosis="openDiagnosisFromLedger"
    />

    <el-dialog v-model="transferOpen" title="结构化转派" width="460px">
      <el-form label-position="top">
        <el-form-item label="目标团队"><el-input v-model="transferForm.targetTeam" placeholder="如 DBA 组" /></el-form-item>
        <el-form-item label="转派说明"><el-input v-model="transferForm.note" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="transferOpen = false">取消</el-button>
        <el-button type="primary" :loading="actionLoading" :disabled="!transferForm.targetTeam || !transferForm.note" @click="transfer">转派</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="approveOpen" title="批准生产写操作" width="480px">
      <el-alert type="warning" :closable="false" class="dialog-alert">批准只推进状态机，MateClaw 不执行任何操作。变更须由授权人员在平台外完成。</el-alert>
      <el-form label-position="top"><el-form-item label="批准理由（审计依据）"><el-input v-model="approveForm.reason" type="textarea" :rows="3" /></el-form-item></el-form>
      <template #footer>
        <el-button @click="approveOpen = false">取消</el-button>
        <el-button type="warning" :loading="actionLoading" :disabled="!approveForm.reason" @click="approve">批准（不执行）</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="outcomeOpen" title="登记外部处置结果" width="480px">
      <el-form label-position="top">
        <el-form-item label="处置结果"><el-select v-model="outcomeForm.outcome" style="width: 100%"><el-option label="SUCCEEDED · 成功" value="SUCCEEDED" /><el-option label="FAILED · 失败" value="FAILED" /><el-option label="SKIPPED · 未执行" value="SKIPPED" /></el-select></el-form-item>
        <el-form-item label="结果说明"><el-input v-model="outcomeForm.notes" type="textarea" :rows="3" /></el-form-item>
        <el-form-item><el-checkbox v-model="outcomeForm.recoveryVerified" :disabled="outcomeForm.outcome !== 'SUCCEEDED'">已验证故障恢复</el-checkbox></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="outcomeOpen = false">取消</el-button>
        <el-button type="primary" :loading="actionLoading" :disabled="!outcomeForm.notes" @click="recordOutcome">登记</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="closeOpen" title="关闭归档" width="480px">
      <el-form label-position="top">
        <el-form-item label="关闭结论"><el-select v-model="closeForm.outcome" style="width: 100%"><el-option label="RECOVERED · 已恢复" value="RECOVERED" /><el-option label="FALSE_POSITIVE · 误报" value="FALSE_POSITIVE" /><el-option label="TRANSFERRED_OUT · 转出处置" value="TRANSFERRED_OUT" /><el-option label="UNRESOLVED · 未解决" value="UNRESOLVED" /></el-select></el-form-item>
        <el-form-item label="关闭摘要"><el-input v-model="closeForm.summary" type="textarea" :rows="3" /></el-form-item>
        <el-form-item v-if="closeForm.outcome === 'RECOVERED'"><el-checkbox v-model="closeForm.recoveryVerified">已验证恢复</el-checkbox></el-form-item>
        <el-form-item label="对 Playbook 的反馈（可选）"><el-input v-model="closeForm.sopFeedback" type="textarea" :rows="2" /></el-form-item>
        <el-form-item><el-checkbox v-model="closeForm.createKnowledgeCandidate">生成知识候选</el-checkbox><p class="form-hint">候选只会被记录并进入发布链路；当前没有独立审核状态，更不会覆盖已批准 Playbook。</p></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeOpen = false">取消</el-button>
        <el-button type="primary" :loading="actionLoading" :disabled="!closeForm.summary" @click="close">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus/es/components/message/index'
import { vLoading } from 'element-plus/es/components/loading/index'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import {
  troubleshootingApi,
  type ActionOutcomeStatus,
  type BlastRadius,
  type ClosureOutcome,
  type DiagnosisExperienceProjection,
  type DiagnosisStatus,
  type DiagnosisSummary,
  type EvidenceChainPreviewRequest,
  type EvidenceStepTone,
  type EvidenceStepKind,
  type GuanceEvidenceAcceptanceChecklist,
  type GuanceEvidenceAcceptanceView,
  type GuanceEvidenceReadiness,
  type GuanceEvidenceSpinePreview,
  type GuanceEvidenceValidationReport,
  type GuanceReadinessStatus,
  type GuanceSignalStatus,
  type GuanceSpinePreviewStepStatus,
  type RecommendedAction,
  type RecordedReplayEvaluationCapability,
  type StoredDiagnosis,
  type TopologyProbeEvidenceRun,
} from '@/api'
import {
  canStartGuanceValidation,
  closureOutcomeLabel,
  conclusionLabel,
  guanceAcceptanceProgress,
  guanceAcceptanceStateLabel,
  guanceReadinessLabel,
  guanceSignalLabel,
  guanceSpinePreviewLabel,
  guanceValidationLabel,
  impactMetrics,
  investigationLabel,
  timingState,
} from './formalProjection'
import {
  buildFormalIncidentReport,
  EMPTY_FORMAL_INCIDENT,
  formalIncidentFormErrors,
  formalIncidentRoutePreview,
  type FormalIncidentForm,
} from './incidentReport'
import { EVIDENCE_SYNTHESIS_FOCUS, EVIDENCE_WINDOW_OPTIONS } from './synthesisPreview'
import EvaluationSampleLedgerDialog from './EvaluationSampleLedgerDialog.vue'
import GuanceOnboardingDialog from './GuanceOnboardingDialog.vue'
import DeploymentTopologySopDialog from './DeploymentTopologySopDialog.vue'
import { deploymentAnalysisLabel, observationStatusLabel } from './deploymentTopologySop'
import DiagnosisListView from './DiagnosisListView.vue'
import TroubleshootingScenarioDialog from './TroubleshootingScenarioDialog.vue'
import WorkbenchCapabilityMenu from './WorkbenchCapabilityMenu.vue'
import WorkbenchViewSwitch from './WorkbenchViewSwitch.vue'
import {
  canAttachGuanceResultToDiagnosis,
  isActiveGuanceValidationSession,
  sameEvidenceChainLookup,
  type GuanceOnboardingValidationPayload,
  type GuanceValidationOrigin,
  type GuanceValidationSessionSnapshot,
} from './guanceOnboarding'
import {
  type EvaluationSampleCaptureContext,
  replayEvaluationCaptureContext,
  suggestedEvaluationScenarioKey,
} from './evaluationSamples'
import {
  TROUBLESHOOTING_UI_LABELS,
  WORKBENCH_DIAGNOSIS_STATUSES as STATUSES,
  diagnosisSelectionMode,
  diagnosisStatusLabel as statusLabel,
  diagnosisStatusTone as statusTone,
  formatWorkbenchTime as shortTime,
  isDiagnosisViewMode,
  resolveWorkbenchView,
  shouldShowQueuePanel,
  workbenchViewQuery,
  type TroubleshootingScenarioCommand,
  type WorkbenchCapabilityCommand,
  type WorkbenchDiagnosisViewMode,
  type WorkbenchViewMode,
  type WorkbenchViewSwitchMode,
} from './workbenchView'

const BLAST_RADIUS_LABEL: Record<BlastRadius, string> = {
  SINGLE_CUSTOMER: '单客户影响', MULTI_CUSTOMER: '多客户影响', SYSTEM_WIDE: '系统级影响', UNKNOWN: '影响范围未知',
}
const STEP_TONE_LABEL: Record<EvidenceStepTone, string> = {
  NORMAL: '正常', ANOMALY: '异常 / 命中', EXCLUDED: '已排除', UNEVALUATED: '未求值',
}

const router = useRouter()
const route = useRoute()
const workspaceStore = useWorkspaceStore()
const canOperateTroubleshooting = computed(() => workspaceStore.can('operate:troubleshooting'))
const canManageTroubleshooting = computed(() => workspaceStore.can('manage:troubleshooting'))
const canAcceptGuanceOwner = computed(() => workspaceStore.isAtLeast('owner'))
const rows = ref<DiagnosisSummary[]>([])
const selectedId = ref<string | null>(null)
const current = ref<StoredDiagnosis | null>(null)
const projection = ref<DiagnosisExperienceProjection | null>(null)
const topologyProbeRuns = ref<TopologyProbeEvidenceRun[]>([])
const statusFilter = ref<DiagnosisStatus | ''>('')
const viewMode = ref<WorkbenchViewMode>(resolveWorkbenchView(route.query.view, route.query.diagnosisId))
const listLoading = ref(false)
const detailLoading = ref(false)
const actionLoading = ref(false)
const incidentReportLoading = ref(false)
const readinessLoading = ref(false)
const validationLoading = ref(false)
const spinePreviewLoading = ref(false)
const acceptanceLoading = ref(false)
const replayCapabilityLoading = ref(false)
const guanceReadiness = ref<GuanceEvidenceReadiness | null>(null)
const guanceValidation = ref<GuanceEvidenceValidationReport | null>(null)
const guanceSpinePreview = ref<GuanceEvidenceSpinePreview | null>(null)
const guanceOwnerAcceptance = ref<GuanceEvidenceAcceptanceView | null>(null)
const guanceDiagnosisLookup = ref<EvidenceChainPreviewRequest | null>(null)
const validationDialogReport = ref<GuanceEvidenceValidationReport | null>(null)
const validationDialogSpinePreview = ref<GuanceEvidenceSpinePreview | null>(null)
const validationDialogOwnerAcceptance = ref<GuanceEvidenceAcceptanceView | null>(null)
const guanceValidationOrigin = ref<GuanceValidationOrigin | null>(null)
const replayCapability = ref<RecordedReplayEvaluationCapability | null>(null)
const readinessError = ref('')
let selectionVersion = 0
let guanceValidationSessionVersion = 0

const business = computed(() => projection.value?.businessSummary ?? null)
const developer = computed(() => projection.value?.developerEvidence ?? null)
const closure = computed(() => current.value?.diagnosis.closure ?? null)
const latestTopologyProbeRun = computed(() => topologyProbeRuns.value[0] ?? null)
const impactMetricList = computed(() => {
  const impact = business.value?.impact
  return impact ? impactMetrics(impact.affectedCustomers, impact.affectedUsers) : []
})
const canTransfer = computed(() => canOperateTroubleshooting.value
  && ['CONFIRMED', 'TRANSFERRED'].includes(current.value?.diagnosis.status || ''))
const canClose = computed(() => canTransfer.value)
const canValidateGuance = computed(() => {
  const status = guanceReadiness.value?.status
  return status ? canStartGuanceValidation(status) : false
})
const guanceAcceptance = computed(() => guanceReadiness.value
  ? guanceAcceptanceProgress(guanceReadiness.value, guanceOwnerAcceptance.value)
  : null)
const currentDiagnosisEvidenceLookup = computed<EvidenceChainPreviewRequest | null>(() => {
  const incident = current.value?.diagnosis.incident
  if (!incident) return null
  return {
    system: incident.system,
    service: incident.service,
    searchTerm: incident.errorCode || '',
    window: '-15m',
    occurredAt: incident.occurredAt,
  }
})
const evaluationCaptureContext = computed<EvaluationSampleCaptureContext | null>(() => {
  const diagnosis = current.value?.diagnosis
  const incident = diagnosis?.incident
  if (!diagnosis || !incident) return null
  const currentLookup = currentDiagnosisEvidenceLookup.value
  const frozenLookup = guanceDiagnosisLookup.value
  const lookup = currentLookup && frozenLookup && sameEvidenceChainLookup(currentLookup, frozenLookup)
    ? frozenLookup
    : currentLookup
  const searchTerm = lookup?.searchTerm.trim() || ''
  if (!searchTerm) return null
  return {
    diagnosisId: diagnosis.diagnosisId,
    system: incident.system,
    service: incident.service,
    scenarioKey: suggestedEvaluationScenarioKey(incident.errorCode),
    searchTerm,
    window: lookup?.window || '-15m',
  }
})
const replayEvaluationCaptureContextValue = computed(() => {
  const diagnosis = current.value?.diagnosis
  const incident = diagnosis?.incident
  return replayEvaluationCaptureContext(diagnosis && incident ? {
    diagnosisId: diagnosis.diagnosisId,
    system: incident.system,
    service: incident.service,
  } : null, replayCapability.value)
})
const canCaptureEvaluationSample = computed(() => canManageTroubleshooting.value
  && guanceOwnerAcceptance.value?.status === 'ACCEPTED'
  && Boolean(guanceDiagnosisLookup.value)
  && Boolean(evaluationCaptureContext.value)
  && Boolean(guanceSpinePreview.value)
  && guanceSpinePreview.value?.stage !== 'BLOCKED')
const evaluationCaptureDisabledReason = computed(() => {
  if (!canManageTroubleshooting.value) return '当前 Workspace 缺少 manage:troubleshooting 权限。'
  if (!evaluationCaptureContext.value) return '当前 Diagnosis 没有可安全映射的搜索键。'
  if (guanceOwnerAcceptance.value?.status === 'STALE') return 'Guance 绑定配置已变化，必须重新完成 T7 owner 验收。'
  if (guanceOwnerAcceptance.value?.status !== 'ACCEPTED') return '当前 Guance 绑定尚未完成持久化 T7 owner 验收。'
  return '先在真源验收中取得一条非 BLOCKED Evidence Spine，再采集历史样本。'
})
const canCaptureReplayEvaluationSample = computed(() => canManageTroubleshooting.value
  && Boolean(replayEvaluationCaptureContextValue.value))
const replayCaptureDisabledReason = computed(() => {
  if (!canManageTroubleshooting.value) return '当前 Workspace 缺少 manage:troubleshooting 权限。'
  if (replayCapabilityLoading.value) return '正在核对服务端 Replay 能力与 fixture 登记范围。'
  return replayCapability.value?.reason || '服务端尚未确认 Replay 能力与 fixture 登记范围。'
})

const scenarioLauncherOpen = ref(false)
const incidentReportOpen = ref(false)
const guanceOnboardingOpen = ref(false)
const deploymentTopologyOpen = ref(false)
const guanceValidationOpen = ref(false)
const evaluationLedgerOpen = ref(false)
const transferOpen = ref(false)
const approveOpen = ref(false)
const outcomeOpen = ref(false)
const closeOpen = ref(false)
const targetAction = ref<RecommendedAction | null>(null)
const incidentReportForm = reactive<FormalIncidentForm>({ ...EMPTY_FORMAL_INCIDENT })
const transferForm = reactive({ targetTeam: '', note: '' })
const approveForm = reactive({ reason: '' })
const outcomeForm = reactive({ outcome: 'SUCCEEDED' as ActionOutcomeStatus, notes: '', recoveryVerified: false })
const closeForm = reactive({ outcome: 'RECOVERED' as ClosureOutcome, summary: '', recoveryVerified: false, sopFeedback: '', createKnowledgeCandidate: true })
const guanceValidationForm = reactive({ system: '', service: '', searchTerm: '', window: '-15m', occurredAt: null as string | null })
const guanceOnboardingInitialRequest = computed<EvidenceChainPreviewRequest>(() => {
  return currentDiagnosisEvidenceLookup.value || {
    system: 'CSDP',
    service: 'csdp-session-service',
    searchTerm: 'message_send_failed',
    window: '-15m',
    occurredAt: null,
  }
})
const validationCanOpenCurrentEvaluationLedger = computed(() =>
  isCurrentDiagnosisValidationRequest(guanceValidationForm))
const EMPTY_T7_CHECKLIST: GuanceEvidenceAcceptanceChecklist = {
  measurementAndFieldsVerified: false,
  indexVerified: false,
  psIdJoinVerified: false,
  timestampUnitVerified: false,
  timeWindowVerified: false,
  dqlLatencyReviewed: false,
  legacyRouteConflictReviewed: false,
}
const guanceAcceptanceChecklist = reactive<GuanceEvidenceAcceptanceChecklist>({
  ...EMPTY_T7_CHECKLIST,
})
const incidentReportErrors = computed(() => formalIncidentFormErrors(incidentReportForm))
const incidentRoutePreview = computed(() => formalIncidentRoutePreview(incidentReportForm))
const canSubmitIncidentReport = computed(() => canOperateTroubleshooting.value
  && incidentReportErrors.value.length === 0)
const canAcceptGuance = computed(() => canManageTroubleshooting.value
  && canAcceptGuanceOwner.value
  && validationDialogReport.value?.stage === 'CANONICAL_CHAIN_OBSERVED'
  && Object.values(guanceAcceptanceChecklist).every(Boolean))

async function replaceWorkbenchRoute(mode: WorkbenchViewMode, diagnosisId?: string | null) {
  const query = { ...route.query }
  delete query.view
  delete query.diagnosisId
  Object.assign(query, workbenchViewQuery(mode, diagnosisId))
  await router.replace({ query })
}

async function switchWorkbenchView(mode: WorkbenchViewSwitchMode) {
  viewMode.value = mode
  if (mode === 'LIST') {
    await replaceWorkbenchRoute('LIST')
    return
  }

  const queryDiagnosisId = typeof route.query.diagnosisId === 'string'
    ? route.query.diagnosisId
    : null
  const target = queryDiagnosisId || selectedId.value || rows.value[0]?.diagnosisId
  if (target) {
    await selectDiagnosis(target, true, 'QUEUE')
  } else {
    await replaceWorkbenchRoute('QUEUE')
  }
}

async function openDiagnosisFromList(row: DiagnosisSummary) {
  await selectDiagnosis(row.diagnosisId, true, 'DETAIL')
}

function handleCapabilityCommand(command: WorkbenchCapabilityCommand) {
  if (command === 'playbooks') {
    void router.push('/troubleshooting/sops')
  } else if (command === 'synthesis') {
    openSynthesisPreview()
  } else if (command === 'guance') {
    guanceOnboardingOpen.value = true
  } else if (command === 'ledger') {
    openEvaluationLedger()
  }
}

function blastRadiusLabel(value: BlastRadius) { return BLAST_RADIUS_LABEL[value] }
function stepToneLabel(value: EvidenceStepTone) { return STEP_TONE_LABEL[value] }
function readinessTone(value: GuanceReadinessStatus) {
  if (canStartGuanceValidation(value)) return 'active'
  return 'warning'
}
function signalTone(value: GuanceSignalStatus) {
  if (value === 'CANONICAL_RESULT_OBSERVED') return 'success'
  if (value === 'READY_FOR_VALIDATION') return 'active'
  return 'warning'
}
function acceptanceTone(value: 'BLOCKED' | 'READY' | 'OWNER_EVIDENCE_REQUIRED') {
  if (value === 'READY') return 'success'
  if (value === 'OWNER_EVIDENCE_REQUIRED') return 'active'
  return 'warning'
}
function ownerAcceptanceStateLabel(value: GuanceEvidenceAcceptanceView['status']) {
  if (value === 'ACCEPTED') return '当前绑定已验收'
  if (value === 'STALE') return '配置变化，验收已过期'
  if (value === 'NOT_ACCEPTED') return '尚未完成 owner 验收'
  return '当前绑定不可验收'
}
function ownerAcceptanceTone(value: GuanceEvidenceAcceptanceView['status']) {
  if (value === 'ACCEPTED') return 'passed'
  return value === 'STALE' ? 'blocked' : 'pending'
}
function shortFingerprint(value?: string | null) {
  return value ? `${value.slice(0, 12)}…` : '不可用'
}
function spineStepStatusLabel(value: GuanceSpinePreviewStepStatus) {
  if (value === 'CANONICAL_RESULT_OBSERVED') return '规范化证据已观测'
  if (value === 'MISSING') return '证据缺失 / 来源不可用'
  return '未执行'
}
function percent(value: number) { return `${Math.round(Number(value) * 100)}%` }
function evidenceTime(kind: EvidenceStepKind, value: string | null) {
  return kind === 'CRITERION' ? '判据' : shortTime(value).slice(11)
}
function timeRange(from: string | null, to: string | null, pending = false) {
  if (!from && !to) return pending ? '尚未发生交接' : '阶段时间戳尚未纳入 Diagnosis'
  return `${shortTime(from)} → ${shortTime(to)}`
}
function errorText(error: unknown) { return error instanceof Error ? error.message : String(error) }

function resetIncidentReportForm() {
  Object.assign(incidentReportForm, EMPTY_FORMAL_INCIDENT)
}

function openTroubleshootingScenario() {
  if (!canOperateTroubleshooting.value && !canManageTroubleshooting.value) return
  scenarioLauncherOpen.value = true
}

function startTroubleshootingScenario(command: TroubleshootingScenarioCommand) {
  if (command === 'incident' && canOperateTroubleshooting.value) {
    incidentReportOpen.value = true
  } else if (command === 'deployment' && canManageTroubleshooting.value) {
    if (!current.value?.diagnosis.diagnosisId) {
      ElMessage.info('部署拓扑拨测是 Diagnosis 内的场景证据；请先创建或打开一条排障记录。')
      return
    }
    if (current.value.diagnosis.status === 'CLOSED') {
      ElMessage.warning('已关闭 Diagnosis 不再接收新的拓扑拨测证据。')
      return
    }
    deploymentTopologyOpen.value = true
  }
}

function handleTopologyProbeCompleted(run: TopologyProbeEvidenceRun) {
  topologyProbeRuns.value = [run, ...topologyProbeRuns.value
    .filter(item => item.runId !== run.runId)]
}

async function reportIncident() {
  if (!canSubmitIncidentReport.value) {
    if (incidentReportErrors.value[0]) ElMessage.warning(incidentReportErrors.value[0])
    return
  }
  incidentReportLoading.value = true
  try {
    const request = buildFormalIncidentReport(incidentReportForm)
    const { data } = await troubleshootingApi.report(request)
    incidentReportOpen.value = false
    resetIncidentReportForm()
    statusFilter.value = ''
    await loadList(false)
    await selectDiagnosis(data.diagnosis.diagnosisId)
    if (data.created) {
      ElMessage.success('排障事件已进入正式 Diagnosis 主链')
    } else {
      ElMessage.info('命中五分钟幂等窗口，已打开既有 Diagnosis')
    }
  } catch (error) {
    const routeBoundary = incidentRoutePreview.value.tone === 'DETERMINISTIC'
      ? '错误码未命中已审核 Playbook 时，受限未命中路径会按设计 fail-closed。'
      : '受限只读调查未启用或未通过配置校验时会按设计 fail-closed。'
    ElMessage.error(`上报未创建：${errorText(error)} ${routeBoundary}`)
  } finally {
    incidentReportLoading.value = false
  }
}

async function loadList(autoSelect = true) {
  listLoading.value = true
  try {
    const { data } = await troubleshootingApi.list({ status: statusFilter.value || undefined, limit: 100 })
    rows.value = data ?? []
    if (autoSelect && !selectedId.value) {
      const queryId = typeof route.query.diagnosisId === 'string' ? route.query.diagnosisId : null
      const target = queryId || rows.value[0]?.diagnosisId
      if (target) {
        const targetMode = diagnosisSelectionMode(viewMode.value)
        await selectDiagnosis(target, !queryId, targetMode)
      }
    }
  } catch (error) {
    ElMessage.error(`加载排障队列失败：${errorText(error)}`)
  } finally { listLoading.value = false }
}

async function selectDiagnosis(
  diagnosisId: string,
  updateQuery = true,
  targetMode: WorkbenchDiagnosisViewMode = diagnosisSelectionMode(viewMode.value),
) {
  const version = ++selectionVersion
  viewMode.value = targetMode
  selectedId.value = diagnosisId
  detailLoading.value = true
  guanceOnboardingOpen.value = false
  guanceValidationOpen.value = false
  validationLoading.value = false
  spinePreviewLoading.value = false
  acceptanceLoading.value = false
  validationDialogReport.value = null
  validationDialogSpinePreview.value = null
  validationDialogOwnerAcceptance.value = null
  guanceValidationOrigin.value = null
  guanceDiagnosisLookup.value = null
  try {
    const [projectionResponse, diagnosisResponse, topologyRunsResponse] = await Promise.all([
      troubleshootingApi.projection(diagnosisId),
      troubleshootingApi.get(diagnosisId),
      troubleshootingApi.diagnosisTopologyProbeRuns(diagnosisId),
    ])
    if (version !== selectionVersion) return
    projection.value = projectionResponse.data
    current.value = diagnosisResponse.data
    topologyProbeRuns.value = topologyRunsResponse.data
    guanceValidation.value = null
    guanceSpinePreview.value = null
    guanceReadiness.value = null
    guanceOwnerAcceptance.value = null
    replayCapability.value = null
    readinessError.value = ''
    const targetQuery = workbenchViewQuery(targetMode, diagnosisId)
    if (updateQuery && (route.query.diagnosisId !== diagnosisId || route.query.view !== targetQuery.view)) {
      await replaceWorkbenchRoute(targetMode, diagnosisId)
    }
    void loadGuanceReadiness(
      diagnosisResponse.data.diagnosis.incident.system,
      diagnosisResponse.data.diagnosis.incident.service,
      version,
    )
  } catch (error) {
    if (version !== selectionVersion) return
    projection.value = null
    current.value = null
    topologyProbeRuns.value = []
    ElMessage.error(`加载诊断投影失败：${errorText(error)}`)
  } finally { if (version === selectionVersion) detailLoading.value = false }
}

async function loadGuanceReadiness(system: string, service: string, version = selectionVersion) {
  readinessLoading.value = true
  try {
    const [readinessResponse, acceptanceResponse] = await Promise.all([
      troubleshootingApi.evidenceReadiness({ system, service }),
      troubleshootingApi.guanceEvidenceAcceptance({ system, service }),
    ])
    if (version !== selectionVersion) return
    guanceReadiness.value = readinessResponse.data
    guanceOwnerAcceptance.value = acceptanceResponse.data
    readinessError.value = ''
  } catch {
    if (version !== selectionVersion) return
    guanceReadiness.value = null
    guanceOwnerAcceptance.value = null
    readinessError.value = '真源就绪检查暂不可用；不影响当前 Diagnosis 的阅读和处置。'
  } finally {
    if (version === selectionVersion) readinessLoading.value = false
  }
}

function openGuanceValidation() {
  const request = currentDiagnosisEvidenceLookup.value
  if (!request || !canValidateGuance.value) return
  guanceValidation.value = null
  guanceSpinePreview.value = null
  guanceDiagnosisLookup.value = null
  openGuanceValidationDialog(request, guanceOwnerAcceptance.value, 'DIAGNOSIS')
}

function openGuanceValidationDialog(
  request: EvidenceChainPreviewRequest,
  ownerAcceptance: GuanceEvidenceAcceptanceView | null,
  origin: GuanceValidationOrigin,
) {
  guanceValidationSessionVersion += 1
  Object.assign(guanceValidationForm, request)
  validationDialogReport.value = null
  validationDialogSpinePreview.value = null
  validationDialogOwnerAcceptance.value = ownerAcceptance
  guanceValidationOrigin.value = origin
  validationLoading.value = false
  spinePreviewLoading.value = false
  acceptanceLoading.value = false
  Object.assign(guanceAcceptanceChecklist, EMPTY_T7_CHECKLIST)
  guanceValidationOpen.value = true
}

function startGuanceValidationFromOnboarding(payload: GuanceOnboardingValidationPayload) {
  guanceOnboardingOpen.value = false
  openGuanceValidationDialog(payload.request, payload.ownerAcceptance, 'ONBOARDING')
}

function isCurrentDiagnosisValidationRequest(request: EvidenceChainPreviewRequest) {
  return canAttachGuanceResultToDiagnosis(
    guanceValidationOrigin.value,
    currentDiagnosisEvidenceLookup.value,
    request,
  )
}

function captureGuanceValidationSession(): GuanceValidationSessionSnapshot {
  return {
    sessionVersion: guanceValidationSessionVersion,
    origin: guanceValidationOrigin.value,
    request: { ...guanceValidationForm },
  }
}

function isActiveGuanceValidationRequest(
  version: number,
  requested: GuanceValidationSessionSnapshot,
) {
  return version === selectionVersion && isActiveGuanceValidationSession(
    requested,
    captureGuanceValidationSession(),
    guanceValidationOpen.value,
  )
}

function isCurrentGuanceValidationGeneration(
  version: number,
  requested: GuanceValidationSessionSnapshot,
) {
  return version === selectionVersion
    && requested.sessionVersion === guanceValidationSessionVersion
    && requested.origin === guanceValidationOrigin.value
}

async function openEvaluationLedger() {
  if (!canManageTroubleshooting.value) return
  const diagnosisId = current.value?.diagnosis.diagnosisId
  if (diagnosisId) await loadReplayCapability(diagnosisId, selectionVersion)
  evaluationLedgerOpen.value = true
}

async function loadReplayCapability(
  diagnosisId: string,
  version = selectionVersion,
) {
  replayCapabilityLoading.value = true
  try {
    const response = await troubleshootingApi.recordedReplayEvaluationCapability({
      diagnosisId,
    })
    if (version !== selectionVersion) return
    replayCapability.value = response.data
  } catch {
    if (version !== selectionVersion) return
    replayCapability.value = {
      available: false,
      reasonCode: 'CAPABILITY_UNAVAILABLE',
      reason: '服务端 Replay 能力检查暂不可用。',
      scenarioKey: null,
      searchTerm: null,
      window: null,
    }
  } finally {
    if (version === selectionVersion) replayCapabilityLoading.value = false
  }
}

async function openDiagnosisFromLedger(diagnosisId: string) {
  evaluationLedgerOpen.value = false
  await selectDiagnosis(diagnosisId)
}

async function validateGuance() {
  const version = selectionVersion
  const session = captureGuanceValidationSession()
  const request = session.request
  validationLoading.value = true
  try {
    const response = await troubleshootingApi.validateGuanceEvidence(request)
    if (!isActiveGuanceValidationRequest(version, session)) return
    validationDialogReport.value = response.data
    if (canAttachGuanceResultToDiagnosis(
      session.origin,
      currentDiagnosisEvidenceLookup.value,
      request,
    )) {
      guanceValidation.value = response.data
      guanceReadiness.value = response.data.readiness
    }
    if (response.data.stage === 'CANONICAL_CHAIN_OBSERVED') {
      ElMessage.success('单次规范化读链已观测；待 T7 owner 字段验收，fixtureMode 保持开启')
    } else {
      ElMessage.warning('真源验证被就绪门或规范化合同阻断')
    }
  } catch (error) {
    if (!isActiveGuanceValidationRequest(version, session)) return
    ElMessage.error(`Guance 只读验证失败：${errorText(error)}`)
  } finally {
    if (isCurrentGuanceValidationGeneration(version, session)) validationLoading.value = false
  }
}

async function acceptGuance() {
  if (!canAcceptGuance.value) return
  const version = selectionVersion
  const session = captureGuanceValidationSession()
  const request = {
    ...session.request,
    checklist: { ...guanceAcceptanceChecklist },
  }
  acceptanceLoading.value = true
  try {
    const response = await troubleshootingApi.acceptGuanceEvidence(request)
    if (!isActiveGuanceValidationRequest(version, session)) return
    validationDialogOwnerAcceptance.value = response.data
    if (canAttachGuanceResultToDiagnosis(
      session.origin,
      currentDiagnosisEvidenceLookup.value,
      request,
    )) guanceOwnerAcceptance.value = response.data
    ElMessage.success('当前 Guance 绑定已完成 T7 owner 验收；配置变化会自动使该记录过期')
  } catch (error) {
    if (!isActiveGuanceValidationRequest(version, session)) return
    ElMessage.error(`T7 owner 验收未记录：${errorText(error)}`)
  } finally {
    if (isCurrentGuanceValidationGeneration(version, session)) acceptanceLoading.value = false
  }
}

async function previewGuanceSpine() {
  const version = selectionVersion
  const session = captureGuanceValidationSession()
  const request = session.request
  spinePreviewLoading.value = true
  try {
    const response = await troubleshootingApi.previewGuanceEvidenceSpine(request)
    if (!isActiveGuanceValidationRequest(version, session)) return
    validationDialogSpinePreview.value = response.data
    if (canAttachGuanceResultToDiagnosis(
      session.origin,
      currentDiagnosisEvidenceLookup.value,
      request,
    )) {
      guanceSpinePreview.value = response.data
      guanceReadiness.value = response.data.readiness
      guanceDiagnosisLookup.value = { ...request }
    }
    if (response.data.stage === 'FULL_SPINE_OBSERVED') {
      ElMessage.success('真实三段 Evidence Spine 已观测；待 owner 完成 T7/T8 验收')
    } else if (response.data.stage === 'CORE_CHAIN_OBSERVED') {
      ElMessage.warning('核心链路可压缩，但成功样本对照缺失，继续校准期')
    } else {
      ElMessage.warning('真实 Evidence Spine 被就绪门或规范化合同阻断')
    }
  } catch (error) {
    if (!isActiveGuanceValidationRequest(version, session)) return
    ElMessage.error(`Guance Evidence Spine 验证失败：${errorText(error)}`)
  } finally {
    if (isCurrentGuanceValidationGeneration(version, session)) spinePreviewLoading.value = false
  }
}

async function reload() {
  await Promise.all([loadList(false), selectedId.value ? selectDiagnosis(selectedId.value, false) : Promise.resolve()])
}
function openLegacy() { router.push({ path: '/troubleshooting/legacy', query: selectedId.value ? { diagnosisId: selectedId.value } : {} }) }
function openSynthesisPreview() {
  router.push({
    path: '/troubleshooting/sops',
    query: { focus: EVIDENCE_SYNTHESIS_FOCUS },
  })
}
async function applyLifecycle(operation: () => Promise<unknown>, message: string) {
  if (!canOperateTroubleshooting.value) {
    ElMessage.error('当前 Workspace 缺少 operate:troubleshooting 权限')
    return false
  }
  actionLoading.value = true
  try {
    await operation()
    await reload()
    ElMessage.success(message)
    return true
  } catch (error) {
    ElMessage.error(errorText(error))
    return false
  }
  finally { actionLoading.value = false }
}
async function confirm() { await applyLifecycle(() => troubleshootingApi.confirm(selectedId.value!), '已确认诊断结论') }
async function transfer() {
  const applied = await applyLifecycle(
    () => troubleshootingApi.transfer(selectedId.value!, { ...transferForm }),
    '已完成结构化转派',
  )
  if (!applied) return
  transferOpen.value = false; transferForm.targetTeam = ''; transferForm.note = ''
}
function canApprove(action: RecommendedAction) { return action.actionType === 'MANUAL_WRITE' && action.approvalStatus === 'PENDING' && canTransfer.value }
function canRecordOutcome(action: RecommendedAction) { return action.actionType === 'MANUAL_WRITE' && action.approvalStatus === 'APPROVED_NOT_EXECUTED' && canTransfer.value }
function openApprove(action: RecommendedAction) { targetAction.value = action; approveForm.reason = ''; approveOpen.value = true }
async function approve() {
  const applied = await applyLifecycle(
    () => troubleshootingApi.approveAction(
      selectedId.value!, targetAction.value!.actionId, { reason: approveForm.reason },
    ),
    '已批准；系统未执行生产变更',
  )
  if (!applied) return
  approveOpen.value = false
}
function openOutcome(action: RecommendedAction) { targetAction.value = action; outcomeForm.outcome = 'SUCCEEDED'; outcomeForm.notes = ''; outcomeForm.recoveryVerified = false; outcomeOpen.value = true }
async function recordOutcome() {
  const applied = await applyLifecycle(
    () => troubleshootingApi.recordOutcome(selectedId.value!, targetAction.value!.actionId, {
      outcome: outcomeForm.outcome, notes: outcomeForm.notes,
      recoveryVerified: outcomeForm.outcome === 'SUCCEEDED' && outcomeForm.recoveryVerified,
    }),
    '已登记平台外处置结果',
  )
  if (!applied) return
  outcomeOpen.value = false
}
async function close() {
  const applied = await applyLifecycle(
    () => troubleshootingApi.close(selectedId.value!, {
      outcome: closeForm.outcome, summary: closeForm.summary,
      recoveryVerified: closeForm.outcome === 'RECOVERED' && closeForm.recoveryVerified,
      sopFeedback: closeForm.sopFeedback || null,
      createKnowledgeCandidate: closeForm.createKnowledgeCandidate,
    }),
    '诊断已关闭归档',
  )
  if (!applied) return
  closeOpen.value = false
}

watch(
  [() => route.query.view, () => route.query.diagnosisId],
  ([queryView, diagnosisId]) => {
    const nextMode = resolveWorkbenchView(queryView, diagnosisId)
    viewMode.value = nextMode
    if (isDiagnosisViewMode(nextMode)
      && typeof diagnosisId === 'string'
      && diagnosisId
      && diagnosisId !== selectedId.value) {
      void selectDiagnosis(diagnosisId, false, nextMode)
    }
  },
)
onMounted(() => loadList(isDiagnosisViewMode(viewMode.value)))
</script>

<style scoped>
.formal-workbench { --ink:#172033; --muted:#667085; --line:#e1e6ef; --soft:#f5f7fb; --blue:#2f5cf5; --green:#138a58; --amber:#b54708; --red:#d92d20; display:grid; grid-template-columns:264px minmax(0,1fr); height:100%; overflow:hidden; color:var(--ink); background:#f4f6fa; }
.formal-workbench.traditional-list-mode { display:block; overflow-y:auto; }
.formal-workbench.full-detail-mode { grid-template-columns:minmax(0,1fr); }
.queue-panel { display:flex; flex-direction:column; min-width:0; overflow:hidden; background:#fff; border-right:1px solid var(--line); }
.queue-head { display:flex; align-items:center; justify-content:space-between; padding:18px 16px 14px; border-bottom:1px solid var(--line); }
.queue-head-actions { display:flex; align-items:flex-end; flex-direction:column; }
.eyebrow { display:block; color:var(--blue); font-size:10px; font-weight:750; letter-spacing:.12em; text-transform:uppercase; }
.queue-head h2 { margin:4px 0 0; font-size:17px; letter-spacing:-.02em; }
.queue-tools { display:flex; flex-direction:column; gap:8px; padding:10px 12px; border-bottom:1px solid var(--line); }
.queue-tools .el-select { flex:1; min-width:0; }
.queue-action-row { display:flex; flex-wrap:wrap; align-items:center; gap:5px; }
.queue-action-row>.el-button,.queue-action-row>.el-dropdown { flex:1 1 92px; margin-left:0; }
.queue-action-row>.el-dropdown .el-button { width:100%; margin-left:0; }
.queue-list { flex:1; min-height:0; overflow-y:auto; }
.queue-item { width:100%; padding:13px 14px 12px; border:0; border-bottom:1px solid #edf0f5; border-left:3px solid transparent; background:#fff; color:inherit; font:inherit; text-align:left; cursor:pointer; }
.queue-item:hover { background:#f8f9fc; } .queue-item.active { border-left-color:var(--blue); background:#f1f4ff; }
.queue-item-top,.queue-item-bottom { display:flex; align-items:center; gap:8px; } .queue-item-top code { color:#344054; font-size:11px; font-weight:700; }
.queue-item strong { display:block; margin-top:5px; font-size:13px; } .queue-item-bottom { margin-top:7px; color:var(--muted); font-size:10.5px; }
.queue-item-bottom time { margin-left:auto; font-family:var(--mc-mono,monospace); } .rehearsal { padding:1px 6px; border-radius:10px; color:#6941c6; background:#f4f0ff; font-size:9px; }
.active { color:var(--blue)!important; } .success { color:var(--green)!important; } .warning { color:var(--amber)!important; } .muted { color:#98a2b3!important; }
.queue-empty { padding:26px 17px; color:var(--muted); font-size:12px; line-height:1.6; } .queue-empty b { color:var(--ink); } .queue-empty p { margin:5px 0 10px; } .queue-empty code { color:var(--blue); font-size:10.5px; } .queue-empty .el-button { width:100%; }
.queue-foot { display:flex; align-items:center; justify-content:space-between; padding:10px 13px; border-top:1px solid var(--line); color:#98a2b3; font-size:10px; }
.queue-foot button { border:0; background:none; color:var(--blue); font:inherit; cursor:pointer; }
.work-area { min-width:0; overflow-y:auto; padding:24px clamp(20px,3vw,46px) 48px; }
.detail-empty { display:grid; place-items:center; align-content:center; min-height:70vh; color:var(--muted); text-align:center; }
.empty-mark { display:grid; place-items:center; width:52px; height:52px; border:1px solid #cdd6f8; border-radius:15px; color:var(--blue); background:#fff; font-weight:800; box-shadow:0 10px 30px rgba(47,92,245,.08); }
.detail-empty h1 { margin:16px 0 4px; color:var(--ink); font-size:20px; } .detail-empty p { margin:0; font-size:13px; } .detail-empty .el-button { margin-top:16px; }
.work-head { display:flex; align-items:flex-end; justify-content:space-between; gap:20px; max-width:1320px; margin:0 auto 14px; }
.work-head h1 { margin:5px 0 0; font-size:23px; letter-spacing:-.025em; } .work-head-actions { display:flex; gap:8px; }
.fixture-banner { display:flex; align-items:center; gap:8px; max-width:1320px; margin:0 auto 12px; padding:9px 13px; border:1px solid #f0d69a; border-radius:9px; color:#7a4e00; background:#fff9e8; font-size:11.5px; }
.fixture-banner span:last-child { color:#986a13; } .fixture-dot { width:7px; height:7px; border-radius:50%; background:#f79009; box-shadow:0 0 0 4px rgba(247,144,9,.13); }
.business-card,.developer-fold { max-width:1320px; margin:0 auto; border:1px solid var(--line); border-radius:14px; background:#fff; box-shadow:0 8px 28px rgba(21,37,68,.035); }
.business-card { padding:clamp(18px,2.5vw,30px); } .verdict-head { padding-bottom:22px; }
.badge-row { display:flex; align-items:center; gap:8px; flex-wrap:wrap; } .conclusion-badge,.status-badge,.confidence-badge { padding:4px 9px; border:1px solid var(--line); border-radius:20px; font-size:10.5px; font-weight:700; }
.conclusion-badge.located { color:#175cd3; border-color:#b2ccff; background:#eff4ff; } .conclusion-badge.excluded { color:#475467; background:#f2f4f7; }
.conclusion-badge.hypothesis { color:#6941c6; border-color:#d9d6fe; background:#f4f3ff; } .conclusion-badge.insufficient_evidence { color:#b54708; border-color:#fedf89; background:#fffaeb; }
.confidence-badge.high { color:var(--green); background:#ecfdf3; } .confidence-badge.medium { color:var(--amber); background:#fffaeb; } .confidence-badge.low { color:var(--red); background:#fff2f0; }
.verdict-copy h2 { margin:14px 0 7px; font-size:clamp(21px,2vw,29px); line-height:1.25; letter-spacing:-.035em; } .verdict-copy>p { max-width:820px; margin:0; color:var(--muted); font-size:13px; line-height:1.75; }
.route-card { align-self:start; padding:15px; border:1px solid var(--line); border-radius:10px; background:var(--soft); }
.route-card span,.section-label { display:block; color:#98a2b3; font-size:9.5px; font-weight:750; letter-spacing:.1em; text-transform:uppercase; }
.route-card b { display:block; margin:7px 0; font-size:12.5px; } .route-card code { color:var(--blue); font-size:10px; word-break:break-all; }
.summary-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); overflow:hidden; border:1px solid var(--line); border-radius:11px; }
.summary-grid article { min-height:130px; padding:17px 18px; } .summary-grid article+article { border-left:1px solid var(--line); }
.summary-grid strong { display:block; margin:10px 0 8px; font-size:13.5px; line-height:1.55; } .summary-grid small { display:block; color:var(--muted); font-size:10.5px; line-height:1.55; }
.impact-metrics { display:flex; gap:7px; margin:8px 0; } .impact-metrics span { padding:2px 7px; border-radius:5px; color:#175cd3; background:#eff4ff; font-size:10px; } .capability-boundary { color:var(--amber)!important; }
.closure-result { display:grid; grid-template-columns:180px minmax(0,1fr) auto; align-items:center; gap:18px; margin-top:14px; padding:14px 16px; border:1px solid #a9e7c8; border-radius:10px; background:#f2fcf7; }
.closure-result div b { display:block; margin-top:5px; color:var(--green); font-size:13px; } .closure-result>strong { font-size:12.5px; line-height:1.55; } .closure-result>small { color:var(--muted); font-size:10px; text-align:right; }
.timing-strip { display:grid; grid-template-columns:1fr 16px 1fr 16px 1fr; align-items:center; margin-top:14px; padding:13px 16px; border:1px solid var(--line); border-radius:10px; background:#fbfcfe; }
.timing-strip article { display:grid; grid-template-columns:1fr auto; gap:3px 12px; } .timing-strip span { color:var(--muted); font-size:10.5px; } .timing-strip b { color:#344054; font-size:13px; }
.timing-strip small { grid-column:1/-1; color:#98a2b3; font-size:9.5px; } .timing-strip i { width:5px; height:5px; justify-self:center; border-radius:50%; background:#c7cfdb; }
.convergence-grid { display:grid; grid-template-columns:minmax(0,1.6fr) minmax(270px,.8fr); gap:14px; margin-top:14px; } .trace-summary,.draft-summary { padding:18px; border:1px solid var(--line); border-radius:10px; }
.section-head,.developer-section-head { display:flex; align-items:flex-start; justify-content:space-between; gap:16px; } .section-head h3,.developer-section-head h3,.developer-side h3 { margin:5px 0 0; font-size:14px; } .section-head>code { color:var(--blue); font-size:10.5px; }
.hop-line { display:flex; align-items:stretch; gap:8px; margin-top:17px; } .hop { flex:1; padding:10px; border:1px solid var(--line); border-radius:8px; background:#fbfcfe; }
.hop>span { display:inline-grid; place-items:center; width:18px; height:18px; border-radius:50%; color:#fff; background:var(--blue); font-size:9px; } .hop b,.hop small { display:block; margin-top:5px; font-size:11px; } .hop small { color:var(--muted); }
.hop.anomalous { border-color:#f5b7b1; background:#fff5f4; } .hop.anomalous>span { background:var(--red); }
.empty-evidence { margin:14px 0 0; padding:11px 12px; border:1px dashed #cfd6e2; border-radius:7px; color:var(--muted); background:#f8f9fb; font-size:11px; line-height:1.65; }
.contrast-row { display:flex; align-items:center; gap:9px; flex-wrap:wrap; margin-top:14px; padding:10px 12px; border-radius:7px; background:#ecfdf3; font-size:10.5px; }
.contrast-row>span { color:var(--muted); } .contrast-row em { color:#98a2b3; font-style:normal; } .contrast-row .baseline { color:var(--green); } .contrast-row small { flex-basis:100%; color:var(--muted); } .contrast-row.unavailable { color:var(--amber); background:#fffaeb; }
.draft-state { padding:2px 7px; border-radius:5px; color:#6941c6; background:#f4f3ff; font-size:9px; font-weight:750; } .draft-summary ol { margin:14px 0 9px; padding-left:20px; color:#344054; font-size:11.5px; line-height:1.6; } .draft-summary>small { color:var(--muted); font-size:10px; }
.lifecycle-bar { display:flex; align-items:center; gap:9px; margin-top:19px; padding-top:17px; border-top:1px solid var(--line); } .lifecycle-bar>span { margin-left:5px; color:var(--muted); font-size:10.5px; }
.topology-evidence-card { max-width:1320px; margin:14px auto 0; padding:18px 20px; border:1px solid #b7dfcd; border-radius:14px; background:#f7fcf9; box-shadow:0 8px 28px rgba(21,37,68,.025); }
.topology-evidence-head { display:flex; align-items:flex-start; justify-content:space-between; gap:18px; }
.topology-evidence-head h3 { margin:5px 0; font-size:16px; }
.topology-evidence-head p { margin:0; color:var(--muted); font-size:11px; line-height:1.6; }
.topology-evidence-head code { color:#067647; }
.topology-evidence-result { display:grid; grid-template-columns:minmax(190px,.8fr) minmax(340px,1.4fr); gap:14px 22px; align-items:center; margin-top:14px; padding-top:14px; border-top:1px solid #d4ebe0; }
.topology-evidence-result>div:first-child span,.topology-evidence-result>div:first-child b,.topology-evidence-result>div:first-child small { display:block; }
.topology-evidence-result>div:first-child span { color:var(--muted); font-size:9.5px; }
.topology-evidence-result>div:first-child b { margin-top:4px; font-size:13px; }
.topology-evidence-result>div:first-child small { margin-top:4px; color:var(--muted); font-size:9px; }
.topology-evidence-result dl { display:grid; grid-template-columns:repeat(4,1fr); gap:8px; margin:0; }
.topology-evidence-result dl>div { padding:9px; border:1px solid #d4ebe0; border-radius:8px; background:#fff; }
.topology-evidence-result dt { color:var(--muted); font-size:9px; }
.topology-evidence-result dd { margin:3px 0 0; color:#067647; font-size:17px; font-weight:800; }
.topology-evidence-result .failed dd { color:var(--red); }
.topology-link-hints { grid-column:1/-1; display:flex; flex-wrap:wrap; align-items:center; gap:7px; color:#912018; font-size:9.5px; }
.topology-link-hints code { padding:3px 6px; border-radius:5px; background:#fff1f0; }
.topology-observations { grid-column:1/-1; display:flex; flex-wrap:wrap; gap:7px; align-items:center; color:var(--muted); font-size:9.5px; }
.topology-observations>div { display:flex; align-items:center; gap:6px; padding:6px 8px; border:1px solid #d4ebe0; border-radius:7px; background:#fff; }
.topology-observations b { color:var(--ink); }.topology-observations code { color:#475467; }.topology-observations em { color:#067647; font-style:normal; font-weight:700; }.topology-observations small { color:var(--muted); }
.topology-history-count { grid-column:1/-1; color:var(--muted); font-size:9.5px; }
.topology-run-history { grid-column:1/-1; overflow:hidden; border:1px solid #d4ebe0; border-radius:9px; background:#fff; }
.topology-run-history summary { padding:9px 11px; color:#067647; cursor:pointer; font-size:10px; font-weight:750; }
.topology-run-history ol { display:grid; gap:8px; margin:0; padding:0 10px 10px; list-style:none; }
.topology-run-history li { padding:10px; border:1px solid #e4efe9; border-radius:8px; background:#fbfefc; }
.topology-run-history header { display:flex; justify-content:space-between; gap:12px; align-items:flex-start; }
.topology-run-history header b,.topology-run-history header small { display:block; }.topology-run-history header small { margin-top:3px; color:var(--muted); font-size:8.5px; }.topology-run-history header code { color:#475467; font-size:8.5px; }
.topology-run-history p { margin:7px 0 0; color:#475467; font-size:9px; line-height:1.55; }.topology-run-history .history-links { color:#912018; }.topology-run-history .history-warning { color:#b54708; }
.topology-run-history .history-observations { display:grid; gap:5px; margin:8px 0 0; padding:0; list-style:none; }
.topology-run-history .history-observations li { display:flex; flex-wrap:wrap; align-items:center; gap:6px; padding:6px 7px; border:0; border-radius:6px; background:#f2f8f5; color:#475467; font-size:8.5px; }
.topology-run-history .history-observations b { color:var(--ink); }.topology-run-history .history-observations code { color:#475467; }.topology-run-history .history-observations span { color:#067647; font-weight:700; }.topology-run-history .history-observations small { color:var(--muted); }
.developer-fold { margin-top:14px; overflow:hidden; } .developer-fold>summary { display:flex; align-items:center; gap:12px; padding:16px 20px; list-style:none; cursor:pointer; user-select:none; }
.developer-fold>summary::-webkit-details-marker { display:none; } .developer-fold>summary>div b,.developer-fold>summary>div small { display:block; } .developer-fold>summary>div b { font-size:13.5px; } .developer-fold>summary>div small { margin-top:3px; color:var(--muted); font-size:10.5px; }
.developer-fold>summary>span:last-child { margin-left:auto; color:var(--muted); font-size:10.5px; } .fold-caret { width:0; height:0; border-top:5px solid transparent; border-bottom:5px solid transparent; border-left:6px solid #98a2b3; transition:transform .18s; } .developer-fold[open] .fold-caret { transform:rotate(90deg); }
.developer-body { display:grid; grid-template-columns:minmax(0,1.65fr) minmax(300px,.75fr); gap:20px; padding:22px; border-top:1px solid var(--line); background:#fbfcfe; } .developer-body>.route-card,.developer-body>.convergence-grid { grid-column:1/-1; margin-top:0; } .developer-section-head>span { padding:3px 8px; border-radius:5px; color:#175cd3; background:#eff4ff; font-size:10px; }
.evidence-timeline { min-width:0; } .evidence-step { display:grid; grid-template-columns:74px 20px minmax(0,1fr) auto; gap:8px; padding-top:17px; } .evidence-step time { padding-top:2px; color:#98a2b3; font-family:var(--mc-mono,monospace); font-size:9.5px; }
.step-line { position:relative; display:flex; justify-content:center; } .step-line::after { content:''; position:absolute; top:10px; bottom:-18px; width:1px; background:#d7dce5; } .evidence-step:last-child .step-line::after { display:none; }
.step-line i { position:relative; z-index:1; width:9px; height:9px; margin-top:3px; border-radius:50%; background:var(--blue); box-shadow:0 0 0 4px #eef2ff; }
.evidence-step.anomaly .step-line i { background:var(--red); box-shadow:0 0 0 4px #fff0ee; } .evidence-step.excluded .step-line i { background:var(--green); box-shadow:0 0 0 4px #eafbf1; } .evidence-step.unevaluated .step-line i { border:1.5px dashed #667085; background:#fff; box-shadow:none; }
.evidence-step b { font-size:12.5px; } .evidence-step p { margin:4px 0 6px; color:var(--muted); font-size:11px; line-height:1.55; } .evidence-step code { color:var(--blue); font-size:9.5px; }
.tone-label { align-self:start; padding:2px 6px; border-radius:5px; color:var(--muted); background:#f2f4f7; font-size:9px; } .evidence-step.anomaly .tone-label { color:var(--red); background:#fff2f0; } .evidence-step.excluded .tone-label { color:var(--green); background:#ecfdf3; } .evidence-step.unevaluated .tone-label { color:var(--amber); background:#fffaeb; }
.developer-side { display:flex; flex-direction:column; gap:14px; } .developer-side>section { padding:16px; border:1px solid var(--line); border-radius:9px; background:#fff; } .capability-list { margin:13px 0 0; padding-left:17px; color:#7a271a; font-size:11px; line-height:1.6; } .capability-list li+li { margin-top:7px; }
.source-gate-card { min-height:120px; } .source-gate-head { display:flex; align-items:flex-start; justify-content:space-between; gap:10px; } .source-gate-state { flex:none; padding:3px 7px; border-radius:5px; background:#f2f4f7; font-size:9px; font-weight:700; }
.source-scope { display:flex; align-items:center; gap:5px; margin:12px 0 8px; color:#98a2b3; font-size:9.5px; } .source-scope code { color:#344054; word-break:break-all; } .source-meta { display:flex; flex-wrap:wrap; gap:7px; font-size:9px; }
.acceptance-ladder { margin:12px 0 0; padding:0; list-style:none; border:1px solid var(--line); border-radius:7px; overflow:hidden; }
.acceptance-ladder li { display:grid; grid-template-columns:30px minmax(0,1fr) auto; align-items:start; gap:8px; padding:8px; background:#fbfcfe; }
.acceptance-ladder li+li { border-top:1px solid var(--line); }
.acceptance-ladder li>span { display:grid; place-items:center; width:25px; height:20px; border-radius:4px; color:#344054; background:#eef1f6; font:700 8.5px var(--mc-mono,monospace); }
.acceptance-ladder b,.acceptance-ladder small { display:block; } .acceptance-ladder b { font-size:9.5px; } .acceptance-ladder small { margin-top:3px; color:var(--muted); font-size:8.5px; line-height:1.45; }
.acceptance-ladder strong { font-size:8px; white-space:nowrap; } .next-source-action { margin:8px 0 0; padding:8px; border-radius:6px; color:#344054; background:#eff4ff; font-size:9px; line-height:1.5; } .next-source-action b { display:block; margin-bottom:2px; color:#175cd3; }
.signal-readiness-list { margin:12px 0; padding:0; list-style:none; } .signal-readiness-list li { display:grid; grid-template-columns:minmax(0,1fr) auto; gap:3px 8px; padding:7px 0; border-top:1px solid #edf0f5; } .signal-readiness-list code { font-size:9.5px; } .signal-readiness-list span { font-size:9px; } .signal-readiness-list small { grid-column:1/-1; color:#98a2b3; font-size:8.5px; word-break:break-all; }
.source-blocker { margin:7px 0; padding:7px 8px; border-radius:5px; color:#7a271a; background:#fff2f0; font-size:9.5px; line-height:1.5; } .gate-note { display:block; margin-top:9px; color:var(--muted); font-size:9px; line-height:1.55; }
.validation-result { margin:9px 0; padding:9px; border-radius:7px; font-size:9.5px; } .validation-result.passed { color:#067647; background:#ecfdf3; } .validation-result.blocked { color:#b54708; background:#fffaeb; } .validation-result.pending { color:#175cd3; background:#eff4ff; } .validation-result b,.validation-result span,.validation-result small { display:block; } .validation-result span { margin-top:4px; } .validation-result small { margin-top:5px; color:var(--muted); line-height:1.45; }
.owner-acceptance-result code { font-size:9px; overflow-wrap:anywhere; } .owner-acceptance-result.passed { border-color:#a9e7c8; background:#ecfdf3; } .owner-acceptance-result.blocked { border-color:#fedf89; background:#fffaeb; } .owner-acceptance-result.pending { border-color:#b2ccff; background:#eff4ff; }
.spine-preview-result span { overflow-wrap:anywhere; line-height:1.45; }
.validation-scope { margin-bottom:14px; padding:10px 12px; border:1px solid var(--line); border-radius:7px; background:#f8f9fc; } .validation-scope span,.validation-scope code { display:block; } .validation-scope span { color:var(--muted); font-size:10px; } .validation-scope code { margin-top:5px; color:var(--blue); font-size:11px; }
.incident-form-grid { display:grid; grid-template-columns:minmax(0,1fr) minmax(0,1fr); gap:0 14px; }
.incident-route-preview { margin:4px 0 12px; padding:12px 13px; border:1px solid #b2ccff; border-radius:8px; background:#f5f8ff; }
.incident-route-preview>span { display:block; color:#667085; font-size:9.5px; font-weight:750; letter-spacing:.08em; text-transform:uppercase; }
.incident-route-preview>b { display:block; margin-top:5px; color:#175cd3; font-size:12px; }
.incident-route-preview>p { margin:5px 0 0; color:#475467; font-size:10.5px; line-height:1.6; }
.incident-route-preview.bounded_discovery { border-color:#fedf89; background:#fffaeb; }
.incident-route-preview.bounded_discovery>b { color:var(--amber); }
.incident-rehearsal { margin-top:2px; }
.dialog-validation-result { margin-top:12px; padding:12px; border:1px solid var(--line); border-radius:8px; background:#fbfcfe; } .dialog-validation-result>b { font-size:12px; } .dialog-validation-result ul { margin:10px 0; padding:0; list-style:none; } .dialog-validation-result li { display:grid; grid-template-columns:auto minmax(0,1fr) auto; gap:10px; padding:5px 0; color:var(--muted); font-size:10px; } .dialog-validation-result li code { color:var(--blue); } .dialog-validation-result li time { color:#344054; font-family:var(--mc-mono,monospace); font-size:9px; white-space:nowrap; } .dialog-validation-result>p { margin:8px 0; color:#344054; font-size:10px; font-weight:700; } .dialog-validation-result>small { display:block; color:var(--amber); font-size:9.5px; line-height:1.5; }
.t7-owner-checklist { display:grid; gap:7px; margin-top:12px; padding:12px; border:1px solid #b2ccff; border-radius:8px; background:#f5f8ff; } .t7-owner-checklist>b { margin-bottom:2px; color:#175cd3; font-size:12px; } .t7-owner-checklist .el-checkbox { height:auto; margin-right:0; white-space:normal; } .t7-owner-checklist .form-hint { margin-top:5px; line-height:1.55; }
.spine-facts { display:grid; gap:7px; margin:10px 0; padding:10px; border-radius:7px; background:#f5f8ff; }
.spine-facts p { display:grid; grid-template-columns:110px minmax(0,1fr); gap:10px; margin:0; font-size:10px; line-height:1.5; }
.spine-facts span { color:var(--muted); }
.spine-facts b { color:#344054; overflow-wrap:anywhere; }
.action-card { margin-top:12px; padding:12px; border:1px solid var(--line); border-radius:8px; } .action-card.write { border-color:#f2c4bf; } .action-card>div { display:flex; justify-content:space-between; gap:8px; } .action-card code,.action-card>div span { color:var(--muted); font-size:8.5px; }
.action-card>b { display:block; margin-top:7px; font-size:11.5px; } .action-card>p { margin:4px 0 9px; color:var(--muted); font-size:10px; line-height:1.5; } .dialog-alert { margin-bottom:14px; } .form-hint { margin:4px 0 0; color:var(--muted); font-size:10.5px; }
@media(max-width:1100px){.formal-workbench{grid-template-columns:220px minmax(0,1fr)}.verdict-head,.developer-body{grid-template-columns:1fr}.summary-grid{grid-template-columns:1fr}.summary-grid article+article{border-top:1px solid var(--line);border-left:0}.convergence-grid{grid-template-columns:1fr}}
@media(max-width:760px){.formal-workbench{display:block;height:auto;min-height:100%;overflow:visible}.queue-panel{max-height:320px;border-right:0;border-bottom:1px solid var(--line)}.work-area{overflow:visible;padding:18px 12px 36px}.work-head,.topology-evidence-head{align-items:flex-start;flex-direction:column}.topology-evidence-result{grid-template-columns:1fr}.topology-evidence-result dl{grid-template-columns:repeat(2,1fr)}.timing-strip{grid-template-columns:1fr;gap:12px}.timing-strip i{display:none}.evidence-step{grid-template-columns:52px 16px minmax(0,1fr)}.tone-label{grid-column:3;justify-self:start}.incident-form-grid{grid-template-columns:1fr}.spine-facts p{grid-template-columns:1fr;gap:2px}}
</style>
