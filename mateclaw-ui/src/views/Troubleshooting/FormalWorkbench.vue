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
      @refresh="store.loadList(false)"
      @launch="openTroubleshootingScenario"
      @capability-command="handleCapabilityCommand"
      @open-diagnosis="openDiagnosisFromList"
      @switch-view="switchWorkbenchView('QUEUE')"
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
        <el-select v-model="statusFilter" size="small" clearable placeholder="全部状态" @change="store.loadList(false)">
          <el-option v-for="status in STATUSES" :key="status" :label="statusLabel(status)" :value="status" />
        </el-select>
        <el-select
          v-model="investigationModeFilter"
          size="small"
          clearable
          placeholder="全部调查模式"
          @change="store.loadList(false)"
        >
          <el-option
            v-for="mode in WORKBENCH_INVESTIGATION_MODES"
            :key="mode"
            :label="investigationModeLabel(mode)"
            :value="mode"
          />
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
          @click="store.selectDiagnosis(row.diagnosisId)"
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
            <el-button size="small" :icon="Refresh" text @click="store.reload">刷新</el-button>
            <el-button v-if="canManageTroubleshooting" size="small" plain @click="openEvaluationLedger">{{ TROUBLESHOOTING_UI_LABELS.evaluation }}</el-button>

          </div>
        </header>

        <div
          v-if="evidenceSourcePresentation.showBanner"
          class="fixture-banner"
          :class="`source-${evidenceSourcePresentation.kind.toLowerCase()}`"
        >
          <span class="fixture-dot" />
          <b>{{ evidenceSourcePresentation.title }}</b>
          <span>{{ evidenceSourcePresentation.detail }}</span>
        </div>

        <BusinessSummaryCard
          :business="business"
          :closure="closure"
          :can-operate="canOperateTroubleshooting"
          :can-transfer="canTransfer"
          :can-close="canClose"
          :action-loading="actionLoading"
          :status="current.diagnosis.status"
          @confirm="store.confirmDiagnosis"
          @transfer="transferOpen = true"
          @close="closeOpen = true"
        />

        <MessageSendEvidenceRunCard
          v-if="messageSendScenarioActive"
          :diagnosis="current.diagnosis"
          :can-operate="canOperateTroubleshooting"
          :loading="messageSendEvidenceLoading"
          @run="runMessageSendEvidence"
        />

        <TopologyEvidenceCard
          v-if="deploymentTopologyRequired"
          :runs="topologyProbeRuns"
          :diagnosis-id="business?.diagnosisId"
          :can-manage="canManageTroubleshooting"
          :disabled="current.diagnosis.status === 'CLOSED'"
          @run-probe="deploymentTopologyOpen = true"
        />

        <DeveloperEvidencePanel
          :developer="developer"
          :business="business"
          :current="current"
          :guance-readiness="guanceReadiness"
          :guance-acceptance="guanceAcceptance"
          :guance-owner-acceptance="guanceOwnerAcceptance"
          :readiness-loading="readinessLoading"
          :readiness-error="readinessError"
          :can-manage="canManageTroubleshooting"
          :can-approve-action="canApprove"
          :can-record-outcome-action="canRecordOutcome"
          @open-guance-onboarding="openGuanceOnboarding"
          @open-evaluation="openEvaluationLedger"
          @approve="openApprove"
          @record-outcome="openOutcome"
        />
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
      v-model="caseKnowledgeImportOpen"
      :title="TROUBLESHOOTING_UI_LABELS.caseKnowledge"
      width="min(680px, calc(100vw - 32px))"
    >
      <el-alert type="info" :closable="false" class="dialog-alert">
        把已有排障单确定性转换为脱敏案例快照，写入现有 Wiki 知识库。未闭环案例只标记为“调查记录”，不作为根因依据。
      </el-alert>
      <el-form label-position="top" @submit.prevent="importHistoricalCases">
        <el-form-item label="目标知识库" required>
          <el-select
            v-model="caseKnowledgeImportForm.knowledgeBaseId"
            :loading="caseKnowledgeBasesLoading"
            filterable
            placeholder="选择当前工作区的知识库"
            style="width:100%"
          >
            <el-option
              v-for="kb in caseKnowledgeBases"
              :key="String(kb.id)"
              :label="`${kb.name} · ${kb.pageCount ?? 0} 页 / ${kb.rawCount ?? 0} 份素材`"
              :value="String(kb.id)"
              :disabled="kb.status !== 'active'"
            />
          </el-select>
          <p v-if="!caseKnowledgeBasesLoading && !caseKnowledgeBases.length" class="form-hint">
            当前工作区还没有知识库，请先到 Wiki 新建一个。
          </p>
        </el-form-item>
        <el-form-item label="最多导入条数">
          <el-input-number
            v-model="caseKnowledgeImportForm.limit"
            :min="1"
            :max="MAX_CASE_KNOWLEDGE_IMPORT_LIMIT"
            :step="10"
          />
          <p class="form-hint">从最新排障单开始；重复执行会复用同一版本的案例页面和原始素材。</p>
        </el-form-item>
      </el-form>

      <div v-if="caseKnowledgeImportResult" class="case-knowledge-result">
        <b>{{ caseKnowledgeImportSummary(caseKnowledgeImportResult) }}</b>
        <p :class="caseKnowledgeVectorStatus.tone">
          {{ caseKnowledgeVectorStatus.text }}
        </p>
        <small>
          入库内容不包含原始日志、DQL、观测载荷或凭据；语义检索只对“向量已就绪”的案例生效。
        </small>
      </div>

      <template #footer>
        <el-button text @click="router.push('/wiki')">管理 Wiki 知识库</el-button>
        <el-button @click="caseKnowledgeImportOpen = false">关闭</el-button>
        <el-button
          type="primary"
          :loading="caseKnowledgeImportLoading"
          :disabled="!canSubmitCaseKnowledgeImport"
          @click="importHistoricalCases"
        >导入历史案例</el-button>
      </template>
    </el-dialog>

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

    <el-dialog
      v-model="messageSendScenarioOpen"
      title="创建“会话消息发送失败”排障单"
      width="min(640px, calc(100vw - 32px))"
    >
      <el-alert type="info" :closable="false" class="dialog-alert">
        这是当前优先打通的单场景竖线。创建时只锁定排查指南，不会直接给出根因；进入详情后由你显式开始三次只读取证。
      </el-alert>
      <el-form label-position="top" @submit.prevent="createMessageSendScenario">
        <div class="incident-form-grid">
          <el-form-item label="故障系统">
            <el-input v-model="messageSendScenarioForm.system" disabled />
          </el-form-item>
          <el-form-item label="故障服务">
            <el-input v-model="messageSendScenarioForm.service" disabled />
          </el-form-item>
          <el-form-item label="严重级别" required>
            <el-select v-model="messageSendScenarioForm.severity" style="width:100%">
              <el-option label="P0 · 全局阻断" value="P0" />
              <el-option label="P1 · 核心故障" value="P1" />
              <el-option label="P2 · 一般故障" value="P2" />
              <el-option label="P3 · 低优先级" value="P3" />
            </el-select>
          </el-form-item>
          <el-form-item label="Trace / PS 线索（可选）">
            <el-input
              v-model="messageSendScenarioForm.traceId"
              maxlength="128"
              placeholder="仅填写安全标识符"
            />
          </el-form-item>
          <el-form-item label="故障发生时间（可选）">
            <el-date-picker
              v-model="messageSendScenarioForm.occurredAt"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ssZ"
              placeholder="不选则取当前时间"
              clearable
              style="width:100%"
            />
            <p class="form-hint">真实 Guance 查询会围绕这个时间读取 Playbook 规定的窗口；不选择时由服务端取当前时间。</p>
          </el-form-item>
        </div>
        <el-form-item label="故障现象" required>
          <el-input
            v-model="messageSendScenarioForm.title"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="只描述用户可见现象，不粘贴日志、DQL 或凭据"
          />
        </el-form-item>
        <el-form-item label="影响对象（可选）">
          <el-input
            v-model="messageSendScenarioForm.customerRef"
            maxlength="500"
            placeholder="例如：马来区域客户；不填人数或原始名单"
          />
        </el-form-item>
        <div class="incident-route-preview scenario">
          <span>已锁定排查指南</span>
          <b>{{ MESSAGE_SEND_SCENARIO_SELECTOR }}</b>
          <p>三个步骤固定为：失败请求 → PS ID 调用链 → 成功/失败样本对比。浏览器不能指定查询或判据。</p>
        </div>
        <el-checkbox v-model="messageSendScenarioForm.rehearsal" class="incident-rehearsal">
          演练记录（仅影响事件去重，不决定证据来源）
        </el-checkbox>
        <p class="form-hint">证据来源由工作区的服务端绑定决定，页面不能强制选择 Guance 或回放；执行后以详情中每条证据记录的实际来源为准。</p>
      </el-form>
      <template #footer>
        <el-button text @click="handleCapabilityCommand('playbooks')">查看排查指南</el-button>
        <el-button @click="messageSendScenarioOpen = false">取消</el-button>
        <el-button
          type="primary"
          :loading="messageSendScenarioLoading"
          :disabled="!canSubmitMessageSendScenario"
          @click="createMessageSendScenario"
        >创建排障单</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="deploymentTopologyScenarioOpen"
      title="创建部署拓扑拨测 Diagnosis"
      width="min(620px, calc(100vw - 32px))"
    >
      <el-alert type="info" :closable="false" class="dialog-alert">
        先由服务端锁定已审核启用的部署拓扑 Scenario Playbook 并创建 Diagnosis；此时不调用模型、不执行拨测，也不提前判断网络根因。
      </el-alert>
      <el-form label-position="top" @submit.prevent="createDeploymentTopologyScenario">
        <div class="incident-form-grid">
          <el-form-item label="故障系统" required>
            <el-input
              v-model="deploymentTopologyScenarioForm.system"
              maxlength="128"
              placeholder="必须与已审核 Scenario Playbook 的系统一致"
            />
          </el-form-item>
          <el-form-item label="故障服务" required>
            <el-input
              v-model="deploymentTopologyScenarioForm.service"
              maxlength="128"
              placeholder="例如 csp-prm-miniapp"
            />
          </el-form-item>
          <el-form-item label="严重级别" required>
            <el-select v-model="deploymentTopologyScenarioForm.severity" style="width: 100%">
              <el-option label="P0 · 全局阻断" value="P0" />
              <el-option label="P1 · 核心故障" value="P1" />
              <el-option label="P2 · 一般故障" value="P2" />
              <el-option label="P3 · 低优先级" value="P3" />
            </el-select>
          </el-form-item>
          <el-form-item label="Trace / PS 线索（可选）">
            <el-input
              v-model="deploymentTopologyScenarioForm.traceId"
              maxlength="128"
              placeholder="只填写安全标识符"
            />
          </el-form-item>
        </div>
        <el-form-item label="故障现象" required>
          <el-input
            v-model="deploymentTopologyScenarioForm.title"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="描述需要通过部署拓扑拨测核查的用户可见现象"
          />
        </el-form-item>
        <div class="incident-route-preview scenario">
          <span>服务端权威选择器</span>
          <b>{{ deploymentTopologySelector }}</b>
          <p>浏览器不能指定 Playbook 版本、Tool Key 或查询参数；服务端找不到精确权威版本时会 fail-closed。</p>
        </div>
        <el-checkbox
          v-model="deploymentTopologyScenarioForm.rehearsal"
          class="incident-rehearsal"
        >
          演练记录（推荐；每次生成独立 Diagnosis）
        </el-checkbox>
        <p class="form-hint">关闭演练标记后，相同系统、服务与现象在五分钟窗口内会复用既有 Diagnosis。创建成功后再选择 Workspace 拓扑并执行只读拨测。</p>
      </el-form>
      <template #footer>
        <el-button text @click="handleCapabilityCommand('playbooks')">查看排障规则库</el-button>
        <el-button @click="deploymentTopologyScenarioOpen = false">取消</el-button>
        <el-button
          type="primary"
          :loading="deploymentTopologyScenarioLoading"
          :disabled="!canSubmitDeploymentTopologyScenario"
          @click="createDeploymentTopologyScenario"
        >创建并选择拓扑</el-button>
      </template>
    </el-dialog>

    <GuanceOnboardingDialog
      v-model="guanceOnboardingOpen"
      :initial-request="guanceOnboardingInitialRequest"
      @start-validation="startGuanceValidationFromOnboarding"
    />

    <DeploymentTopologySopDialog
      v-if="deploymentTopologyRequired"
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
        <el-form-item label="故障时间（可选）">
          <el-date-picker
            v-model="guanceValidationForm.occurredAt"
            type="datetime"
            format="YYYY-MM-DD HH:mm:ss"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
            placeholder="不选择则使用当前时间"
            clearable
            style="width: 100%"
          />
          <p class="form-hint">默认带入当前 Diagnosis 的故障时间；清空后，服务端会以点击验证时的当前时间作为查询结束点，不会改写 Diagnosis。</p>
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
        <small v-for="blocker in validationDialogOwnerAcceptance.blockers" :key="blocker">
          {{ guanceOwnerBlockerLabel(blocker) }}
        </small>
      </div>
      <div v-if="guanceRecordingTargets" class="dialog-validation-result" :class="recordingBatchReady ? 'passed' : 'blocked'">
        <b>T7 窗口批次目标 · {{ guanceRecordingTargets.executableTargetCount }} / 20</b>
        <p>服务端冻结 {{ guanceRecordingTargets.frozenTargetCount }} 个未录制 D1 目标；只有与当前三份 binding 精确匹配的目标才计入。</p>
        <small v-for="blocker in guanceRecordingTargets.blockers" :key="blocker">{{ blocker }}</small>
      </div>
      <div
        v-if="validationDialogReport?.stage === 'CANONICAL_CHAIN_OBSERVED' && validationDialogOwnerAcceptance?.status !== 'ACCEPTED' && canAcceptGuanceOwner && recordingBatchReady"
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
        v-else-if="validationDialogReport?.stage === 'CANONICAL_CHAIN_OBSERVED' && validationDialogOwnerAcceptance?.status !== 'ACCEPTED' && !recordingBatchReady"
        class="source-blocker"
      >当前录制批次目标未达 20 个，只可在窗口外继续验证单条查询合同；服务端不会记录 owner ACCEPTED。</p>
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

    <TransferDialog
      v-model="transferOpen"
      :diagnosis-id="selectedId"
      @submitted="store.reload"
    />

    <ApproveActionDialog
      v-model="approveOpen"
      :diagnosis-id="selectedId"
      :target-action="targetAction"
      @submitted="store.reload"
    />

    <RecordOutcomeDialog
      v-model="outcomeOpen"
      :diagnosis-id="selectedId"
      :target-action="targetAction"
      @submitted="store.reload"
    />

    <CloseDiagnosisDialog
      v-model="closeOpen"
      :diagnosis-id="selectedId"
      @submitted="store.reload"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useRoute, useRouter } from 'vue-router'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus/es/components/message/index'
import { vLoading } from 'element-plus/es/components/loading/index'
import {
  troubleshootingApi,
  wikiApi,
  type DiagnosisSummary,
  type EvidenceChainPreviewRequest,
  type GuanceEvidenceAcceptanceChecklist,
  type GuanceEvidenceAcceptanceView,
  type GuanceEvidenceSpinePreview,
  type GuanceEvidenceValidationReport,
  type GuanceSpinePreviewStepStatus,
  type HistoricalCaseKnowledgeImportResult,
  type RecommendedAction,
  type StoredDiagnosis,
  type TopologyProbeEvidenceRun,
} from '@/api'
import { useTroubleshootingStore } from '@/stores/useTroubleshootingStore'
import {
  diagnosisEvidenceSourcePresentation,
  guanceOwnerBlockerLabel,
  guanceSpinePreviewLabel,
  guanceValidationLabel,
  investigationModeLabel,
} from './formalProjection'
import {
  buildFormalIncidentReport,
  EMPTY_FORMAL_INCIDENT,
  formalIncidentFormErrors,
  formalIncidentRoutePreview,
  type FormalIncidentForm,
} from './incidentReport'
import {
  buildDeploymentTopologyScenarioRequest,
  deploymentTopologyScenarioLoadFailureMessage,
  deploymentTopologyScenarioFormErrors,
  deploymentTopologyScenarioProjectionLoaded,
  deploymentTopologyScenarioSelector,
  EMPTY_DEPLOYMENT_TOPOLOGY_SCENARIO,
  type DeploymentTopologyScenarioForm,
} from './deploymentTopologyScenario'
import { EVIDENCE_SYNTHESIS_FOCUS, EVIDENCE_WINDOW_OPTIONS } from './synthesisPreview'
import EvaluationSampleLedgerDialog from './EvaluationSampleLedgerDialog.vue'
import GuanceOnboardingDialog from './GuanceOnboardingDialog.vue'
import DeploymentTopologySopDialog from './DeploymentTopologySopDialog.vue'
import DiagnosisListView from './DiagnosisListView.vue'
import TroubleshootingScenarioDialog from './TroubleshootingScenarioDialog.vue'
import WorkbenchCapabilityMenu from './WorkbenchCapabilityMenu.vue'
import WorkbenchViewSwitch from './WorkbenchViewSwitch.vue'
import TransferDialog from './TransferDialog.vue'
import ApproveActionDialog from './ApproveActionDialog.vue'
import RecordOutcomeDialog from './RecordOutcomeDialog.vue'
import CloseDiagnosisDialog from './CloseDiagnosisDialog.vue'
import BusinessSummaryCard from './BusinessSummaryCard.vue'
import MessageSendEvidenceRunCard from './MessageSendEvidenceRunCard.vue'
import TopologyEvidenceCard from './TopologyEvidenceCard.vue'
import DeveloperEvidencePanel from './DeveloperEvidencePanel.vue'
import {
  canAttachGuanceResultToDiagnosis,
  normalizeEvidenceChainPreviewRequest,
  type GuanceOnboardingValidationPayload,
  type GuanceValidationOrigin,
} from './guanceOnboarding'
import {
  EMPTY_MESSAGE_SEND_SCENARIO,
  MESSAGE_SEND_SCENARIO_KEY,
  MESSAGE_SEND_SCENARIO_SELECTOR,
  buildMessageSendScenarioRequest,
  canRunMessageSendEvidence as canRunMessageSendEvidenceForDiagnosis,
  isMessageSendScenarioDiagnosis,
  messageSendScenarioFormErrors,
  type MessageSendScenarioForm,
} from './messageSendScenario'
import {
  DEFAULT_CASE_KNOWLEDGE_IMPORT_LIMIT,
  MAX_CASE_KNOWLEDGE_IMPORT_LIMIT,
  caseKnowledgeImportCanSubmit,
  caseKnowledgeImportSummary,
  caseKnowledgeVectorMessage,
} from './caseKnowledgeImport'
import {
  TROUBLESHOOTING_UI_LABELS,
  WORKBENCH_DIAGNOSIS_STATUSES as STATUSES,
  WORKBENCH_INVESTIGATION_MODES,
  diagnosisStatusLabel as statusLabel,
  diagnosisStatusTone as statusTone,
  formatWorkbenchTime as shortTime,
  isDiagnosisViewMode,
  resolveWorkbenchView,
  shouldShowQueuePanel,
  type TroubleshootingScenarioCommand,
  type WorkbenchCapabilityCommand,
  type WorkbenchViewSwitchMode,
} from './workbenchView'

const router = useRouter()
const route = useRoute()
const store = useTroubleshootingStore()
const {
  canOperateTroubleshooting, canManageTroubleshooting, canAcceptGuanceOwner,
  rows, selectedId, current, projection,
  topologyProbeRuns, statusFilter, investigationModeFilter, viewMode,
  listLoading, detailLoading, actionLoading, readinessLoading,
  guanceReadiness,
  guanceOwnerAcceptance, readinessError,
  guanceRecordingTargets,
  business, developer, closure,
  deploymentTopologyRequired,
  canTransfer, canClose, guanceAcceptance,
  currentDiagnosisEvidenceLookup, evaluationCaptureContext,
  replayEvaluationCaptureContextValue,
  canCaptureEvaluationSample, evaluationCaptureDisabledReason,
  canCaptureReplayEvaluationSample, replayCaptureDisabledReason,
} = storeToRefs(store)

const incidentReportLoading = ref(false)
const messageSendScenarioLoading = ref(false)
const messageSendEvidenceLoading = ref(false)
const caseKnowledgeImportLoading = ref(false)
const caseKnowledgeBasesLoading = ref(false)
const deploymentTopologyScenarioLoading = ref(false)
const validationLoading = ref(false)
const spinePreviewLoading = ref(false)
const acceptanceLoading = ref(false)
const validationDialogReport = ref<GuanceEvidenceValidationReport | null>(null)
const validationDialogSpinePreview = ref<GuanceEvidenceSpinePreview | null>(null)
const validationDialogOwnerAcceptance = ref<GuanceEvidenceAcceptanceView | null>(null)
const guanceValidationOrigin = ref<GuanceValidationOrigin | null>(null)

const scenarioLauncherOpen = ref(false)
const incidentReportOpen = ref(false)
const messageSendScenarioOpen = ref(false)
const caseKnowledgeImportOpen = ref(false)
const deploymentTopologyScenarioOpen = ref(false)
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
const messageSendScenarioForm = reactive<MessageSendScenarioForm>({
  ...EMPTY_MESSAGE_SEND_SCENARIO,
})
type CaseKnowledgeBaseOption = {
  id: string | number
  name: string
  status: string
  pageCount?: number
  rawCount?: number
}
const caseKnowledgeBases = ref<CaseKnowledgeBaseOption[]>([])
const caseKnowledgeImportForm = reactive({
  knowledgeBaseId: '',
  limit: DEFAULT_CASE_KNOWLEDGE_IMPORT_LIMIT,
})
const caseKnowledgeImportResult = ref<HistoricalCaseKnowledgeImportResult | null>(null)
const deploymentTopologyScenarioForm = reactive<DeploymentTopologyScenarioForm>({
  ...EMPTY_DEPLOYMENT_TOPOLOGY_SCENARIO,
})

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
const evidenceSourcePresentation = computed(() => diagnosisEvidenceSourcePresentation(
  current.value?.diagnosis.evidence ?? [],
))
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
const messageSendScenarioErrors = computed(() =>
  messageSendScenarioFormErrors(messageSendScenarioForm))
const canSubmitMessageSendScenario = computed(() => canOperateTroubleshooting.value
  && messageSendScenarioErrors.value.length === 0)
const messageSendScenarioActive = computed(() =>
  isMessageSendScenarioDiagnosis(current.value?.diagnosis))
const canSubmitCaseKnowledgeImport = computed(() => caseKnowledgeImportCanSubmit(
  caseKnowledgeImportForm.knowledgeBaseId,
  caseKnowledgeImportForm.limit,
  canManageTroubleshooting.value,
))
const caseKnowledgeVectorStatus = computed(() => caseKnowledgeImportResult.value
  ? caseKnowledgeVectorMessage(caseKnowledgeImportResult.value)
  : { tone: 'warning' as const, text: '' })
const deploymentTopologyScenarioErrors = computed(() =>
  deploymentTopologyScenarioFormErrors(deploymentTopologyScenarioForm))
const deploymentTopologySelector = computed(() =>
  deploymentTopologyScenarioSelector(deploymentTopologyScenarioForm.system))
const canSubmitDeploymentTopologyScenario = computed(() =>
  canManageTroubleshooting.value && deploymentTopologyScenarioErrors.value.length === 0)
const recordingBatchReady = computed(() =>
  (guanceRecordingTargets.value?.executableTargetCount ?? 0) >= 20)
const canAcceptGuance = computed(() => canManageTroubleshooting.value
  && canAcceptGuanceOwner.value
  && recordingBatchReady.value
  && validationDialogReport.value?.stage === 'CANONICAL_CHAIN_OBSERVED'
  && Object.values(guanceAcceptanceChecklist).every(Boolean))

async function switchWorkbenchView(mode: WorkbenchViewSwitchMode) {
  viewMode.value = mode
  if (mode === 'LIST') {
    await store.replaceWorkbenchRoute('LIST')
    return
  }

  const queryDiagnosisId = typeof route.query.diagnosisId === 'string'
    ? route.query.diagnosisId
    : null
  const target = queryDiagnosisId || selectedId.value || rows.value[0]?.diagnosisId
  if (target) {
    await store.selectDiagnosis(target, true, 'QUEUE')
  } else {
    await store.replaceWorkbenchRoute('QUEUE')
  }
}

async function openDiagnosisFromList(row: DiagnosisSummary) {
  await store.selectDiagnosis(row.diagnosisId, true, 'DETAIL')
}

function handleCapabilityCommand(command: WorkbenchCapabilityCommand) {
  if (command === 'playbooks') {
    void router.push('/troubleshooting/sops')
  } else if (command === 'evidence-catalog') {
    void router.push('/troubleshooting/evidence-catalog')
  } else if (command === 'synthesis') {
    openSynthesisPreview()
  } else if (command === 'guance') {
    openGuanceOnboarding()
  } else if (command === 'ledger') {
    openEvaluationLedger()
  } else if (command === 'case-knowledge') {
    void openCaseKnowledgeImport()
  }
}

async function openCaseKnowledgeImport() {
  if (!canManageTroubleshooting.value) return
  caseKnowledgeImportOpen.value = true
  caseKnowledgeImportResult.value = null
  caseKnowledgeBasesLoading.value = true
  try {
    const response = await wikiApi.listKBs()
    caseKnowledgeBases.value = (response.data || []).map((kb: CaseKnowledgeBaseOption) => ({
      id: kb.id,
      name: kb.name,
      status: kb.status,
      pageCount: kb.pageCount,
      rawCount: kb.rawCount,
    }))
    const selectedStillExists = caseKnowledgeBases.value.some(
      kb => String(kb.id) === caseKnowledgeImportForm.knowledgeBaseId && kb.status === 'active',
    )
    if (!selectedStillExists) {
      const recommended = caseKnowledgeBases.value.find(
        kb => kb.status === 'active' && kb.name.includes('排障'),
      ) ?? caseKnowledgeBases.value.find(kb => kb.status === 'active')
      caseKnowledgeImportForm.knowledgeBaseId = recommended ? String(recommended.id) : ''
    }
  } catch (error) {
    caseKnowledgeBases.value = []
    ElMessage.error(`知识库列表加载失败：${errorText(error)}`)
  } finally {
    caseKnowledgeBasesLoading.value = false
  }
}

async function importHistoricalCases() {
  if (!canSubmitCaseKnowledgeImport.value) return
  caseKnowledgeImportLoading.value = true
  caseKnowledgeImportResult.value = null
  try {
    const response = await troubleshootingApi.importHistoricalCases({
      knowledgeBaseId: caseKnowledgeImportForm.knowledgeBaseId,
      limit: caseKnowledgeImportForm.limit,
    })
    caseKnowledgeImportResult.value = response.data
    const vectorStatus = caseKnowledgeVectorMessage(response.data)
    if (vectorStatus.tone === 'success') {
      ElMessage.success('历史案例已入库并完成向量化')
    } else {
      ElMessage.warning('历史案例素材已入库，部分向量待生成')
    }
  } catch (error) {
    ElMessage.error(`历史案例导入失败：${errorText(error)}`)
  } finally {
    caseKnowledgeImportLoading.value = false
  }
}

function openGuanceOnboarding() {
  guanceOnboardingOpen.value = true
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
function errorText(error: unknown) { return error instanceof Error ? error.message : String(error) }

function resetIncidentReportForm() {
  Object.assign(incidentReportForm, EMPTY_FORMAL_INCIDENT)
}

function resetMessageSendScenarioForm() {
  Object.assign(messageSendScenarioForm, EMPTY_MESSAGE_SEND_SCENARIO)
}

function resetDeploymentTopologyScenarioForm() {
  Object.assign(deploymentTopologyScenarioForm, EMPTY_DEPLOYMENT_TOPOLOGY_SCENARIO)
}

function openDeploymentTopologyScenarioIntake() {
  resetDeploymentTopologyScenarioForm()
  const incident = current.value?.diagnosis.incident
  if (incident) {
    Object.assign(deploymentTopologyScenarioForm, {
      system: incident.system,
      service: incident.service,
      title: incident.title || EMPTY_DEPLOYMENT_TOPOLOGY_SCENARIO.title,
      traceId: incident.traceId || '',
    })
  }
  deploymentTopologyScenarioOpen.value = true
}

function openTroubleshootingScenario() {
  if (!canOperateTroubleshooting.value && !canManageTroubleshooting.value) return
  scenarioLauncherOpen.value = true
}

function startTroubleshootingScenario(command: TroubleshootingScenarioCommand) {
  if (command === 'message-send-failed' && canOperateTroubleshooting.value) {
    resetMessageSendScenarioForm()
    messageSendScenarioOpen.value = true
  } else if (command === 'incident' && canOperateTroubleshooting.value) {
    incidentReportOpen.value = true
  } else if (command === 'deployment' && canManageTroubleshooting.value) {
    openDeploymentTopologyScenarioIntake()
  }
}

async function createMessageSendScenario() {
  if (!canSubmitMessageSendScenario.value) {
    if (messageSendScenarioErrors.value[0]) {
      ElMessage.warning(messageSendScenarioErrors.value[0])
    }
    return
  }
  messageSendScenarioLoading.value = true
  try {
    const request = buildMessageSendScenarioRequest(messageSendScenarioForm)
    const { data } = await troubleshootingApi.createScenarioDiagnosis(
      MESSAGE_SEND_SCENARIO_KEY,
      request,
    )
    messageSendScenarioOpen.value = false
    resetMessageSendScenarioForm()
    statusFilter.value = ''
    investigationModeFilter.value = ''
    await store.loadList(false)
    await store.selectDiagnosis(data.diagnosis.diagnosisId)
    ElMessage.success(data.created
      ? '排障单已创建，请在详情中开始三次只读取证'
      : '命中五分钟幂等窗口，已打开原排障单')
  } catch (error) {
    ElMessage.error(
      `场景排障单未创建：${errorText(error)} 请确认排查指南 ${MESSAGE_SEND_SCENARIO_SELECTOR} 已审核启用。`,
    )
  } finally {
    messageSendScenarioLoading.value = false
  }
}

async function runMessageSendEvidence() {
  const diagnosis = current.value?.diagnosis
  if (!diagnosis || !canRunMessageSendEvidenceForDiagnosis(diagnosis)) return
  messageSendEvidenceLoading.value = true
  try {
    await troubleshootingApi.runScenarioEvidence(diagnosis.diagnosisId)
    await store.reload()
    ElMessage.success('三次只读取证已完成，结论与证据链已写入排障详情')
  } catch (error) {
    ElMessage.error(`只读取证未完成：${errorText(error)} 系统未伪造结论，排障单仍保持等待状态。`)
  } finally {
    messageSendEvidenceLoading.value = false
  }
}

function handleTopologyProbeCompleted(run: TopologyProbeEvidenceRun) {
  store.handleTopologyProbeCompleted(run)
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
    investigationModeFilter.value = ''
    await store.loadList(false)
    await store.selectDiagnosis(data.diagnosis.diagnosisId)
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

async function createDeploymentTopologyScenario() {
  if (!canSubmitDeploymentTopologyScenario.value) {
    if (deploymentTopologyScenarioErrors.value[0]) {
      ElMessage.warning(deploymentTopologyScenarioErrors.value[0])
    }
    return
  }
  deploymentTopologyScenarioLoading.value = true
  let stored: StoredDiagnosis
  try {
    const request = buildDeploymentTopologyScenarioRequest(
      deploymentTopologyScenarioForm,
    )
    const response = await troubleshootingApi.createDeploymentTopologyScenario(request)
    stored = response.data
  } catch (error) {
    ElMessage.error(
      `场景未创建：${errorText(error)} 请先确认“排障规则库”中已审核启用 ${deploymentTopologySelector.value}。`,
    )
    return
  } finally {
    deploymentTopologyScenarioLoading.value = false
  }

  deploymentTopologyScenarioOpen.value = false
  resetDeploymentTopologyScenarioForm()
  statusFilter.value = ''
  investigationModeFilter.value = ''
  await store.loadList(false)
  await store.selectDiagnosis(stored.diagnosis.diagnosisId)
  const loadedDiagnosis = current.value
  if (!loadedDiagnosis || !deploymentTopologyScenarioProjectionLoaded(
    stored.diagnosis.diagnosisId,
    loadedDiagnosis.diagnosis.diagnosisId,
    Boolean(projection.value),
  )) {
    ElMessage.error(deploymentTopologyScenarioLoadFailureMessage(
      stored.diagnosis.diagnosisId,
    ))
    return
  }
  if (!deploymentTopologyRequired.value) {
    ElMessage.error('场景 Diagnosis 已创建，但服务端未确认部署拓扑拨测能力；已停止打开工具。')
    return
  }
  if (loadedDiagnosis.diagnosis.status === 'CLOSED') {
    ElMessage.warning('命中的既有 Diagnosis 已关闭，不能追加新的拓扑拨测证据。')
    return
  }
  deploymentTopologyOpen.value = true
  if (stored.created) {
    ElMessage.success('部署拓扑场景已进入 Diagnosis 主链，请选择拓扑资产')
  } else {
    ElMessage.info('命中五分钟幂等窗口，已打开既有场景 Diagnosis')
  }
}

function openGuanceValidationDialog(
  request: EvidenceChainPreviewRequest,
  ownerAcceptance: GuanceEvidenceAcceptanceView | null,
  origin: GuanceValidationOrigin,
) {
  store.nextGuanceValidationSessionVersion()
  Object.assign(guanceValidationForm, normalizeEvidenceChainPreviewRequest(request))
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

function captureGuanceValidationSession() {
  return {
    sessionVersion: store.getSelectionVersion(),
    origin: guanceValidationOrigin.value,
    request: normalizeEvidenceChainPreviewRequest(guanceValidationForm),
  }
}

async function openEvaluationLedger() {
  if (!canManageTroubleshooting.value) return
  const diagnosisId = current.value?.diagnosis.diagnosisId
  if (diagnosisId) await store.loadReplayCapability(diagnosisId, store.getSelectionVersion())
  evaluationLedgerOpen.value = true
}

async function openDiagnosisFromLedger(diagnosisId: string) {
  evaluationLedgerOpen.value = false
  await store.selectDiagnosis(diagnosisId)
}

async function validateGuance() {
  const version = store.getSelectionVersion()
  const session = captureGuanceValidationSession()
  const request = session.request
  validationLoading.value = true
  try {
    const response = await store.validateGuance(request, session, version)
    if (response) {
      validationDialogReport.value = response.data
      if (response.data.stage === 'CANONICAL_CHAIN_OBSERVED') {
        ElMessage.success('单次规范化读链已观测；待 T7 owner 字段验收，fixtureMode 保持开启')
      } else {
        ElMessage.warning('真源验证被就绪门或规范化合同阻断')
      }
    }
  } finally {
    if (store.isCurrentGuanceValidationGeneration(version)) validationLoading.value = false
  }
}

async function acceptGuance() {
  if (!canAcceptGuance.value) return
  const version = store.getSelectionVersion()
  const session = captureGuanceValidationSession()
  const request = {
    ...session.request,
    checklist: { ...guanceAcceptanceChecklist },
  }
  acceptanceLoading.value = true
  try {
    const response = await store.acceptGuanceEvidence(request, session, version)
    if (response) {
      validationDialogOwnerAcceptance.value = response.data
      ElMessage.success('当前 Guance 绑定已完成 T7 owner 验收；配置变化会自动使该记录过期')
    }
  } finally {
    if (store.isCurrentGuanceValidationGeneration(version)) acceptanceLoading.value = false
  }
}

async function previewGuanceSpine() {
  const version = store.getSelectionVersion()
  const session = captureGuanceValidationSession()
  const request = session.request
  spinePreviewLoading.value = true
  try {
    const response = await store.previewGuanceSpine(request, session, version)
    if (response) {
      validationDialogSpinePreview.value = response.data
      if (response.data.stage === 'FULL_SPINE_OBSERVED') {
        ElMessage.success('真实三段 Evidence Spine 已观测；待 owner 完成 T7/T8 验收')
      } else if (response.data.stage === 'CORE_CHAIN_OBSERVED') {
        ElMessage.warning('核心链路可压缩，但成功样本对照缺失，继续校准期')
      } else {
        ElMessage.warning('真实 Evidence Spine 被就绪门或规范化合同阻断')
      }
    }
  } finally {
    if (store.isCurrentGuanceValidationGeneration(version)) spinePreviewLoading.value = false
  }
}


function openSynthesisPreview() {
  router.push({
    path: '/troubleshooting/sops',
    query: { focus: EVIDENCE_SYNTHESIS_FOCUS },
  })
}
function canApprove(action: RecommendedAction) { return action.actionType === 'MANUAL_WRITE' && action.approvalStatus === 'PENDING' && canTransfer.value }
function canRecordOutcome(action: RecommendedAction) { return action.actionType === 'MANUAL_WRITE' && action.approvalStatus === 'APPROVED_NOT_EXECUTED' && canTransfer.value }
function openApprove(action: RecommendedAction) { targetAction.value = action; approveOpen.value = true }
function openOutcome(action: RecommendedAction) { targetAction.value = action; outcomeOpen.value = true }

watch(
  [() => route.query.view, () => route.query.diagnosisId],
  ([queryView, diagnosisId]) => {
    const nextMode = resolveWorkbenchView(queryView, diagnosisId)
    viewMode.value = nextMode
    if (isDiagnosisViewMode(nextMode)
      && typeof diagnosisId === 'string'
      && diagnosisId
      && diagnosisId !== selectedId.value) {
      void store.selectDiagnosis(diagnosisId, false, nextMode)
    }
  },
  { immediate: true },
)
onMounted(() => store.loadList(isDiagnosisViewMode(viewMode.value)))
</script>

<style scoped>
.formal-workbench { --ink:var(--mc-text-primary); --muted:var(--mc-text-secondary); --line:var(--mc-border); --soft:var(--mc-bg-muted); --blue:var(--mc-primary); --green:var(--mc-success); --amber:var(--mc-warning); --red:var(--mc-danger); display:grid; grid-template-columns:264px minmax(0,1fr); height:100%; overflow:hidden; color:var(--ink); background:var(--mc-bg); }
.formal-workbench.traditional-list-mode { display:block; overflow-y:auto; }
.formal-workbench.full-detail-mode { grid-template-columns:minmax(0,1fr); }
.queue-panel { display:flex; flex-direction:column; min-width:0; overflow:hidden; background:var(--mc-bg-elevated); border-right:1px solid var(--line); }
.queue-head { display:flex; align-items:center; justify-content:space-between; padding:18px 16px 14px; border-bottom:1px solid var(--line); }
.queue-head-actions { display:flex; align-items:flex-end; flex-direction:column; }
.eyebrow { display:block; color:var(--blue); font-size:var(--mc-text-xs); font-weight:750; letter-spacing:.12em; text-transform:uppercase; }
.queue-head h2 { margin:4px 0 0; font-size:var(--mc-text-base); letter-spacing:-.02em; }
.queue-tools { display:flex; flex-direction:column; gap:8px; padding:10px 12px; border-bottom:1px solid var(--line); }
.queue-tools .el-select { flex:1; min-width:0; }
.queue-action-row { display:flex; flex-wrap:wrap; align-items:center; gap:5px; }
.queue-action-row>.el-button,.queue-action-row>.el-dropdown { flex:1 1 92px; margin-left:0; }
.queue-action-row>.el-dropdown .el-button { width:100%; margin-left:0; }
.queue-list { flex:1; min-height:0; overflow-y:auto; }
.queue-item { width:100%; padding:13px 14px 12px; border:0; border-bottom:1px solid var(--mc-border-light); border-left:3px solid transparent; background:var(--mc-bg-elevated); color:inherit; font:inherit; text-align:left; cursor:pointer; }
.queue-item:hover { background:var(--mc-bg-elevated); } .queue-item.active { border-left-color:var(--blue); background:var(--mc-sidebar-active); }
.queue-item-top,.queue-item-bottom { display:flex; align-items:center; gap:8px; } .queue-item-top code { color:var(--mc-text-secondary); font-size:var(--mc-text-xs); font-weight:700; }
.queue-item strong { display:block; margin-top:5px; font-size:var(--mc-text-sm); } .queue-item-bottom { margin-top:7px; color:var(--muted); font-size:var(--mc-text-xs); }
.queue-item-bottom time { margin-left:auto; font-family:var(--mc-mono,monospace); } .rehearsal { padding:1px 6px; border-radius:var(--mc-radius-sm); color:var(--mc-status-purple-text); background:var(--mc-status-info-bg); font-size:var(--mc-text-xs); }
.active { color:var(--blue)!important; } .success { color:var(--green)!important; } .warning { color:var(--amber)!important; } .muted { color:var(--mc-text-tertiary)!important; }
.queue-empty { padding:26px 17px; color:var(--muted); font-size:var(--mc-text-sm); line-height:1.65; } .queue-empty b { color:var(--ink); } .queue-empty p { margin:5px 0 10px; } .queue-empty code { color:var(--blue); font-size:var(--mc-text-xs); } .queue-empty .el-button { width:100%; }
.queue-foot { display:flex; align-items:center; justify-content:space-between; padding:10px 13px; border-top:1px solid var(--line); color:var(--mc-text-tertiary); font-size:var(--mc-text-xs); }
.queue-foot button { border:0; background:none; color:var(--blue); font:inherit; cursor:pointer; }
.work-area { min-width:0; overflow-y:auto; padding:20px clamp(20px,3vw,40px) 40px; }
.detail-empty { display:grid; place-items:center; align-content:center; min-height:70vh; color:var(--muted); text-align:center; }
.empty-mark { display:grid; place-items:center; width:52px; height:52px; border:1px solid var(--mc-border); border-radius:var(--mc-radius-md); color:var(--blue); background:var(--mc-bg-elevated); font-weight:800; box-shadow:0 10px 30px var(--mc-shadow-soft); }
.detail-empty h1 { margin:16px 0 4px; color:var(--ink); font-size:var(--mc-text-lg); } .detail-empty p { margin:0; font-size:var(--mc-text-sm); } .detail-empty .el-button { margin-top:16px; }
.work-head { display:flex; align-items:flex-end; justify-content:space-between; gap:14px; max-width:1320px; margin:0 auto 20px; }
.work-head h1 { margin:5px 0 0; font-size:var(--mc-text-xl); letter-spacing:-.025em; } .work-head-actions { display:flex; gap:8px; }
.fixture-banner { display:flex; align-items:center; gap:8px; max-width:1320px; margin:0 auto 16px; padding:9px 13px; border:1px solid var(--mc-warning); border-radius:var(--mc-radius-sm); color:var(--mc-status-warning-text); background:var(--mc-status-warning-bg); font-size:var(--mc-text-xs); }
.fixture-banner span:last-child { color:var(--mc-status-warning-text); } .fixture-dot { width:7px; height:7px; border-radius:50%; background:var(--mc-warning); box-shadow:0 0 0 4px rgba(245,158,11,0.13); }
.business-card,.developer-fold { max-width:1320px; margin:0 auto; border:1px solid var(--line); border-radius:var(--mc-radius-md); background:var(--mc-bg-elevated); box-shadow:0 8px 28px var(--mc-shadow-soft); }
.business-card { padding:clamp(20px,3vw,36px); } .verdict-head { padding-bottom:16px; }
.badge-row { display:flex; align-items:center; gap:8px; flex-wrap:wrap; } .conclusion-badge,.status-badge,.confidence-badge { padding:4px 9px; border:1px solid var(--line); border-radius:var(--mc-radius-lg); font-size:var(--mc-text-xs); font-weight:700; }
.conclusion-badge.located { color:var(--mc-status-info-text); border-color:var(--mc-border); background:var(--mc-status-info-bg); } .conclusion-badge.excluded { color:var(--mc-text-secondary); background:var(--mc-bg-muted); }
.conclusion-badge.hypothesis { color:var(--mc-status-purple-text); border-color:var(--mc-border); background:var(--mc-status-purple-bg); } .conclusion-badge.insufficient_evidence { color:var(--mc-warning); border-color:var(--mc-warning); background:var(--mc-status-warning-bg); }
.confidence-badge.high { color:var(--green); background:var(--mc-status-success-bg); } .confidence-badge.medium { color:var(--amber); background:var(--mc-status-warning-bg); } .confidence-badge.low { color:var(--red); background:var(--mc-status-error-bg); }
.verdict-copy h2 { margin:14px 0 7px; font-size:clamp(24px,2.5vw,32px); line-height:1.25; letter-spacing:-.035em; } .verdict-copy>p { max-width:820px; margin:0; color:var(--muted); font-size:var(--mc-text-sm); line-height:1.7; }
.route-card { align-self:start; padding:18px; border:1px solid var(--line); border-radius:var(--mc-radius-sm); background:var(--soft); }
.route-card span,.section-label { display:block; color:var(--mc-text-tertiary); font-size:var(--mc-text-xs); font-weight:750; letter-spacing:.1em; text-transform:uppercase; }
.route-card b { display:block; margin:7px 0; font-size:var(--mc-text-xs); } .route-card code { color:var(--blue); font-size:var(--mc-text-xs); word-break:break-all; }
.summary-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); overflow:hidden; border:1px solid var(--line); border-radius:var(--mc-radius-sm); }
.summary-grid article { min-height:140px; padding:20px 22px; } .summary-grid article+article { border-left:1px solid var(--line); }
.summary-grid strong { display:block; margin:12px 0 10px; font-size:var(--mc-text-sm); line-height:1.6; } .summary-grid small { display:block; color:var(--muted); font-size:var(--mc-text-xs); line-height:1.6; }
.impact-metrics { display:flex; gap:7px; margin:8px 0; } .impact-metrics span { padding:2px 7px; border-radius:var(--mc-radius-xs); color:var(--mc-status-info-text); background:var(--mc-status-info-bg); font-size:var(--mc-text-xs); } .capability-boundary { color:var(--amber)!important; }
.closure-result { display:grid; grid-template-columns:180px minmax(0,1fr) auto; align-items:center; gap:12px; margin-top:12px; padding:14px 16px; border:1px solid var(--mc-success); border-radius:var(--mc-radius-sm); background:var(--mc-status-success-bg); }
.closure-result div b { display:block; margin-top:5px; color:var(--green); font-size:var(--mc-text-sm); } .closure-result>strong { font-size:var(--mc-text-xs); line-height:1.6; } .closure-result>small { color:var(--muted); font-size:var(--mc-text-xs); text-align:right; }
.timing-strip { display:grid; grid-template-columns:1fr 16px 1fr 16px 1fr; align-items:center; margin-top:12px; padding:16px 20px; border:1px solid var(--line); border-radius:var(--mc-radius-sm); background:var(--mc-bg-elevated); }
.timing-strip article { display:grid; grid-template-columns:1fr auto; gap:3px 12px; } .timing-strip span { color:var(--muted); font-size:var(--mc-text-xs); } .timing-strip b { color:var(--mc-text-secondary); font-size:var(--mc-text-sm); }
.timing-strip small { grid-column:1/-1; color:var(--mc-text-tertiary); font-size:var(--mc-text-xs); } .timing-strip i { width:5px; height:5px; justify-self:center; border-radius:50%; background:var(--mc-text-tertiary); }
.convergence-grid { display:grid; grid-template-columns:minmax(0,1.6fr) minmax(270px,.8fr); gap:14px; margin-top:12px; } .trace-summary,.draft-summary { padding:18px; border:1px solid var(--line); border-radius:var(--mc-radius-sm); }
.section-head,.developer-section-head { display:flex; align-items:flex-start; justify-content:space-between; gap:16px; } .section-head h3,.developer-section-head h3,.developer-side h3 { margin:5px 0 0; font-size:var(--mc-text-sm); } .section-head>code { color:var(--blue); font-size:var(--mc-text-xs); }
.hop-line { display:flex; align-items:stretch; gap:8px; margin-top:12px; } .hop { flex:1; padding:10px; border:1px solid var(--line); border-radius:var(--mc-radius-xs); background:var(--mc-bg-elevated); }
.hop>span { display:inline-grid; place-items:center; width:18px; height:18px; border-radius:50%; color:var(--mc-text-inverse); background:var(--blue); font-size:var(--mc-text-xs); } .hop b,.hop small { display:block; margin-top:5px; font-size:var(--mc-text-xs); } .hop small { color:var(--muted); }
.hop.anomalous { border-color:var(--mc-danger-border); background:var(--mc-status-error-bg); } .hop.anomalous>span { background:var(--red); }
.empty-evidence { margin:14px 0 0; padding:11px 12px; border:1px dashed var(--mc-border); border-radius:var(--mc-radius-xs); color:var(--muted); background:var(--mc-bg-elevated); font-size:var(--mc-text-xs); line-height:1.65; }
.contrast-row { display:flex; align-items:center; gap:9px; flex-wrap:wrap; margin-top:12px; padding:10px 12px; border-radius:var(--mc-radius-xs); background:var(--mc-status-success-bg); font-size:var(--mc-text-xs); }
.contrast-row>span { color:var(--muted); } .contrast-row em { color:var(--mc-text-tertiary); font-style:normal; } .contrast-row .baseline { color:var(--green); } .contrast-row small { flex-basis:100%; color:var(--muted); } .contrast-row.unavailable { color:var(--amber); background:var(--mc-status-warning-bg); }
.draft-state { padding:2px 7px; border-radius:var(--mc-radius-xs); color:var(--mc-status-purple-text); background:var(--mc-status-purple-bg); font-size:var(--mc-text-xs); font-weight:750; } .draft-summary ol { margin:14px 0 9px; padding-left:20px; color:var(--mc-text-secondary); font-size:var(--mc-text-xs); line-height:1.65; } .draft-summary>small { color:var(--muted); font-size:var(--mc-text-xs); }
.lifecycle-bar { display:flex; align-items:center; gap:9px; margin-top:12px; padding-top:12px; border-top:1px solid var(--line); } .lifecycle-bar>span { margin-left:5px; color:var(--muted); font-size:var(--mc-text-xs); }
.topology-evidence-card { max-width:1320px; margin:20px auto 0; padding:22px 24px; border:1px solid var(--mc-border); border-radius:var(--mc-radius-md); background:var(--mc-status-success-bg); box-shadow:0 8px 28px var(--mc-shadow-soft); }
.topology-evidence-head { display:flex; align-items:flex-start; justify-content:space-between; gap:12px; }
.topology-evidence-head h3 { margin:5px 0; font-size:var(--mc-text-lg); }
.topology-evidence-head p { margin:0; color:var(--muted); font-size:var(--mc-text-xs); line-height:1.65; }
.topology-evidence-head code { color:var(--mc-status-success-text); }
.topology-evidence-result { display:grid; grid-template-columns:minmax(190px,.8fr) minmax(340px,1.4fr); gap:14px 22px; align-items:center; margin-top:12px; padding-top:14px; border-top:1px solid var(--mc-border-light); }
.topology-evidence-result>div:first-child span,.topology-evidence-result>div:first-child b,.topology-evidence-result>div:first-child small { display:block; }
.topology-evidence-result>div:first-child span { color:var(--muted); font-size:var(--mc-text-xs); }
.topology-evidence-result>div:first-child b { margin-top:4px; font-size:var(--mc-text-sm); }
.topology-evidence-result>div:first-child small { margin-top:4px; color:var(--muted); font-size:var(--mc-text-xs); }
.topology-evidence-result dl { display:grid; grid-template-columns:repeat(4,1fr); gap:8px; margin:0; }
.topology-evidence-result dl>div { padding:9px; border:1px solid var(--mc-border-light); border-radius:var(--mc-radius-xs); background:var(--mc-bg-elevated); }
.topology-evidence-result dt { color:var(--muted); font-size:var(--mc-text-xs); }
.topology-evidence-result dd { margin:3px 0 0; color:var(--mc-status-success-text); font-size:var(--mc-text-base); font-weight:800; }
.topology-evidence-result .failed dd { color:var(--red); }
.topology-link-hints { grid-column:1/-1; display:flex; flex-wrap:wrap; align-items:center; gap:7px; color:var(--mc-status-error-text); font-size:var(--mc-text-xs); }
.topology-link-hints code { padding:3px 6px; border-radius:var(--mc-radius-xs); background:var(--mc-status-error-bg); }
.topology-observations { grid-column:1/-1; display:flex; flex-wrap:wrap; gap:7px; align-items:center; color:var(--muted); font-size:var(--mc-text-xs); }
.topology-observations>div { display:flex; align-items:center; gap:6px; padding:6px 8px; border:1px solid var(--mc-border-light); border-radius:var(--mc-radius-xs); background:var(--mc-bg-elevated); }
.topology-observations b { color:var(--ink); }.topology-observations code { color:var(--mc-text-secondary); }.topology-observations em { color:var(--mc-status-success-text); font-style:normal; font-weight:700; }.topology-observations small { color:var(--muted); }
.topology-history-count { grid-column:1/-1; color:var(--muted); font-size:var(--mc-text-xs); }
.topology-run-history { grid-column:1/-1; overflow:hidden; border:1px solid var(--mc-border-light); border-radius:var(--mc-radius-sm); background:var(--mc-bg-elevated); }
.topology-run-history summary { padding:9px 11px; color:var(--mc-status-success-text); cursor:pointer; font-size:var(--mc-text-xs); font-weight:750; }
.topology-run-history ol { display:grid; gap:8px; margin:0; padding:0 10px 10px; list-style:none; }
.topology-run-history li { padding:10px; border:1px solid var(--mc-border-light); border-radius:var(--mc-radius-xs); background:var(--mc-bg-elevated); }
.topology-run-history header { display:flex; justify-content:space-between; gap:12px; align-items:flex-start; }
.topology-run-history header b,.topology-run-history header small { display:block; }.topology-run-history header small { margin-top:3px; color:var(--muted); font-size:var(--mc-text-xs); }.topology-run-history header code { color:var(--mc-text-secondary); font-size:var(--mc-text-xs); }
.topology-run-history p { margin:7px 0 0; color:var(--mc-text-secondary); font-size:var(--mc-text-xs); line-height:1.6; }.topology-run-history .history-links { color:var(--mc-status-error-text); }.topology-run-history .history-warning { color:var(--mc-warning); }
.topology-run-history .history-observations { display:grid; gap:5px; margin:8px 0 0; padding:0; list-style:none; }
.topology-run-history .history-observations li { display:flex; flex-wrap:wrap; align-items:center; gap:6px; padding:6px 7px; border:0; border-radius:6px; background:var(--mc-status-success-bg); color:var(--mc-text-secondary); font-size:var(--mc-text-xs); }
.topology-run-history .history-observations b { color:var(--ink); }.topology-run-history .history-observations code { color:var(--mc-text-secondary); }.topology-run-history .history-observations span { color:var(--mc-status-success-text); font-weight:700; }.topology-run-history .history-observations small { color:var(--muted); }
.developer-fold { margin-top:12px; overflow:hidden; } .developer-fold>summary { display:flex; align-items:center; gap:12px; padding:18px 24px; list-style:none; cursor:pointer; user-select:none; }
.developer-fold>summary::-webkit-details-marker { display:none; } .developer-fold>summary>div b,.developer-fold>summary>div small { display:block; } .developer-fold>summary>div b { font-size:var(--mc-text-sm); } .developer-fold>summary>div small { margin-top:3px; color:var(--muted); font-size:var(--mc-text-xs); }
.developer-fold>summary>span:last-child { margin-left:auto; color:var(--muted); font-size:var(--mc-text-xs); } .fold-caret { width:0; height:0; border-top:5px solid transparent; border-bottom:5px solid transparent; border-left:6px solid var(--mc-text-tertiary); transition:transform .18s; } .developer-fold[open] .fold-caret { transform:rotate(90deg); }
.developer-body { display:grid; grid-template-columns:minmax(0,1.65fr) minmax(300px,.75fr); gap:24px; padding:24px; border-top:1px solid var(--line); background:var(--mc-bg-elevated); } .developer-body>.route-card,.developer-body>.convergence-grid { grid-column:1/-1; margin-top:0; } .developer-section-head>span { padding:3px 8px; border-radius:var(--mc-radius-xs); color:var(--mc-status-info-text); background:var(--mc-status-info-bg); font-size:var(--mc-text-xs); }
.evidence-timeline { min-width:0; } .evidence-step { display:grid; grid-template-columns:74px 20px minmax(0,1fr) auto; gap:10px; padding-top:20px; } .evidence-step time { padding-top:2px; color:var(--mc-text-tertiary); font-family:var(--mc-mono,monospace); font-size:var(--mc-text-xs); }
.step-line { position:relative; display:flex; justify-content:center; } .step-line::after { content:''; position:absolute; top:10px; bottom:-18px; width:1px; background:var(--mc-border); } .evidence-step:last-child .step-line::after { display:none; }
.step-line i { position:relative; z-index:1; width:9px; height:9px; margin-top:3px; border-radius:50%; background:var(--blue); box-shadow:0 0 0 4px var(--mc-border-light); }
.evidence-step.anomaly .step-line i { background:var(--red); box-shadow:0 0 0 4px var(--mc-danger-light); } .evidence-step.excluded .step-line i { background:var(--green); box-shadow:0 0 0 4px var(--mc-success-light); } .evidence-step.unevaluated .step-line i { border:1.5px dashed var(--mc-text-tertiary); background:var(--mc-bg-elevated); box-shadow:none; }
.evidence-step b { font-size:var(--mc-text-xs); } .evidence-step p { margin:4px 0 6px; color:var(--muted); font-size:var(--mc-text-xs); line-height:1.6; } .evidence-step code { color:var(--blue); font-size:var(--mc-text-xs); }
.tone-label { align-self:start; padding:2px 6px; border-radius:var(--mc-radius-xs); color:var(--muted); background:var(--mc-bg-muted); font-size:var(--mc-text-xs); } .evidence-step.anomaly .tone-label { color:var(--red); background:var(--mc-status-error-bg); } .evidence-step.excluded .tone-label { color:var(--green); background:var(--mc-status-success-bg); } .evidence-step.unevaluated .tone-label { color:var(--amber); background:var(--mc-status-warning-bg); }
.developer-side { display:flex; flex-direction:column; gap:14px; } .developer-side>section { padding:16px; border:1px solid var(--line); border-radius:var(--mc-radius-sm); background:var(--mc-bg-elevated); } .capability-list { margin:13px 0 0; padding-left:17px; color:var(--mc-status-error-text); font-size:var(--mc-text-xs); line-height:1.65; } .capability-list li+li { margin-top:7px; }
.source-gate-card { min-height:120px; } .source-gate-head { display:flex; align-items:flex-start; justify-content:space-between; gap:10px; } .source-gate-state { flex:none; padding:3px 7px; border-radius:var(--mc-radius-xs); background:var(--mc-bg-muted); font-size:var(--mc-text-xs); font-weight:700; }
.source-scope { display:flex; align-items:center; gap:5px; margin:12px 0 8px; color:var(--mc-text-tertiary); font-size:var(--mc-text-xs); } .source-scope code { color:var(--mc-text-secondary); word-break:break-all; } .source-meta { display:flex; flex-wrap:wrap; gap:7px; font-size:var(--mc-text-xs); }
.acceptance-ladder { margin:12px 0 0; padding:0; list-style:none; border:1px solid var(--line); border-radius:var(--mc-radius-xs); overflow:hidden; }
.acceptance-ladder li { display:grid; grid-template-columns:30px minmax(0,1fr) auto; align-items:start; gap:8px; padding:8px; background:var(--mc-bg-elevated); }
.acceptance-ladder li+li { border-top:1px solid var(--line); }
.acceptance-ladder li>span { display:grid; place-items:center; width:25px; height:20px; border-radius:4px; color:var(--mc-text-secondary); background:var(--mc-border-light); font:700 10px var(--mc-mono,monospace); }
.acceptance-ladder b,.acceptance-ladder small { display:block; } .acceptance-ladder b { font-size:var(--mc-text-xs); } .acceptance-ladder small { margin-top:3px; color:var(--muted); font-size:var(--mc-text-xs); line-height:1.45; }
.acceptance-ladder strong { font-size:var(--mc-text-xs); white-space:nowrap; } .next-source-action { margin:8px 0 0; padding:8px; border-radius:6px; color:var(--mc-text-secondary); background:var(--mc-status-info-bg); font-size:var(--mc-text-xs); line-height:1.5; } .next-source-action b { display:block; margin-bottom:2px; color:var(--mc-status-info-text); }
.signal-readiness-list { margin:12px 0; padding:0; list-style:none; } .signal-readiness-list li { display:grid; grid-template-columns:minmax(0,1fr) auto; gap:3px 8px; padding:7px 0; border-top:1px solid var(--mc-border-light); } .signal-readiness-list code { font-size:var(--mc-text-xs); } .signal-readiness-list span { font-size:var(--mc-text-xs); } .signal-readiness-list small { grid-column:1/-1; color:var(--mc-text-tertiary); font-size:var(--mc-text-xs); word-break:break-all; }
.source-blocker { margin:7px 0; padding:7px 8px; border-radius:var(--mc-radius-xs); color:var(--mc-status-error-text); background:var(--mc-status-error-bg); font-size:var(--mc-text-xs); line-height:1.5; } .gate-note { display:block; margin-top:9px; color:var(--muted); font-size:var(--mc-text-xs); line-height:1.6; }
.validation-result { margin:9px 0; padding:9px; border-radius:var(--mc-radius-xs); font-size:var(--mc-text-xs); } .validation-result.passed { color:var(--mc-status-success-text); background:var(--mc-status-success-bg); } .validation-result.blocked { color:var(--mc-warning); background:var(--mc-status-warning-bg); } .validation-result.pending { color:var(--mc-status-info-text); background:var(--mc-status-info-bg); } .validation-result b,.validation-result span,.validation-result small { display:block; } .validation-result span { margin-top:4px; } .validation-result small { margin-top:5px; color:var(--muted); line-height:1.45; }
.owner-acceptance-result code { font-size:var(--mc-text-xs); overflow-wrap:anywhere; } .owner-acceptance-result.passed { border-color:var(--mc-success); background:var(--mc-status-success-bg); } .owner-acceptance-result.blocked { border-color:var(--mc-warning); background:var(--mc-status-warning-bg); } .owner-acceptance-result.pending { border-color:var(--mc-border); background:var(--mc-status-info-bg); }
.spine-preview-result span { overflow-wrap:anywhere; line-height:1.45; }
.validation-scope { margin-bottom:14px; padding:10px 12px; border:1px solid var(--line); border-radius:var(--mc-radius-xs); background:var(--mc-bg-elevated); } .validation-scope span,.validation-scope code { display:block; } .validation-scope span { color:var(--muted); font-size:var(--mc-text-xs); } .validation-scope code { margin-top:5px; color:var(--blue); font-size:var(--mc-text-xs); }
.incident-form-grid { display:grid; grid-template-columns:minmax(0,1fr) minmax(0,1fr); gap:0 14px; }
.incident-route-preview { margin:4px 0 12px; padding:12px 13px; border:1px solid var(--mc-border); border-radius:var(--mc-radius-xs); background:var(--mc-status-info-bg); }
.incident-route-preview>span { display:block; color:var(--mc-text-tertiary); font-size:var(--mc-text-xs); font-weight:750; letter-spacing:.08em; text-transform:uppercase; }
.incident-route-preview>b { display:block; margin-top:5px; color:var(--mc-status-info-text); font-size:var(--mc-text-sm); }
.incident-route-preview>p { margin:5px 0 0; color:var(--mc-text-secondary); font-size:var(--mc-text-xs); line-height:1.65; }
.incident-route-preview.bounded_discovery { border-color:var(--mc-warning); background:var(--mc-status-warning-bg); }
.incident-route-preview.bounded_discovery>b { color:var(--amber); }
.incident-rehearsal { margin-top:2px; }
.dialog-validation-result { margin-top:12px; padding:12px; border:1px solid var(--line); border-radius:var(--mc-radius-xs); background:var(--mc-bg-elevated); } .dialog-validation-result>b { font-size:var(--mc-text-sm); } .dialog-validation-result ul { margin:10px 0; padding:0; list-style:none; } .dialog-validation-result li { display:grid; grid-template-columns:auto minmax(0,1fr) auto; gap:10px; padding:5px 0; color:var(--muted); font-size:var(--mc-text-xs); } .dialog-validation-result li code { color:var(--blue); } .dialog-validation-result li time { color:var(--mc-text-secondary); font-family:var(--mc-mono,monospace); font-size:var(--mc-text-xs); white-space:nowrap; } .dialog-validation-result>p { margin:8px 0; color:var(--mc-text-secondary); font-size:var(--mc-text-xs); font-weight:700; } .dialog-validation-result>small { display:block; color:var(--amber); font-size:var(--mc-text-xs); line-height:1.5; }
.case-knowledge-result { margin-top:14px; padding:13px; border:1px solid var(--line); border-radius:var(--mc-radius-xs); background:var(--mc-bg-elevated); } .case-knowledge-result>b { font-size:var(--mc-text-sm); } .case-knowledge-result>p { margin:8px 0; font-size:var(--mc-text-xs); line-height:1.6; } .case-knowledge-result>p.success { color:var(--green); } .case-knowledge-result>p.warning { color:var(--amber); } .case-knowledge-result>small { display:block; color:var(--muted); font-size:var(--mc-text-xs); line-height:1.6; }
.t7-owner-checklist { display:grid; gap:7px; margin-top:12px; padding:12px; border:1px solid var(--mc-border); border-radius:var(--mc-radius-xs); background:var(--mc-status-info-bg); } .t7-owner-checklist>b { margin-bottom:2px; color:var(--mc-status-info-text); font-size:var(--mc-text-sm); } .t7-owner-checklist .el-checkbox { height:auto; margin-right:0; white-space:normal; } .t7-owner-checklist .form-hint { margin-top:5px; line-height:1.6; }
.spine-facts { display:grid; gap:7px; margin:10px 0; padding:10px; border-radius:var(--mc-radius-xs); background:var(--mc-status-info-bg); }
.spine-facts p { display:grid; grid-template-columns:110px minmax(0,1fr); gap:10px; margin:0; font-size:var(--mc-text-xs); line-height:1.5; }
.spine-facts span { color:var(--muted); }
.spine-facts b { color:var(--mc-text-secondary); overflow-wrap:anywhere; }
.action-card { margin-top:12px; padding:12px; border:1px solid var(--line); border-radius:var(--mc-radius-xs); } .action-card.write { border-color:var(--mc-border); } .action-card>div { display:flex; justify-content:space-between; gap:8px; } .action-card code,.action-card>div span { color:var(--muted); font-size:var(--mc-text-xs); }
.action-card>b { display:block; margin-top:7px; font-size:var(--mc-text-xs); } .action-card>p { margin:4px 0 9px; color:var(--muted); font-size:var(--mc-text-xs); line-height:1.5; } .dialog-alert { margin-bottom:14px; } .form-hint { margin:4px 0 0; color:var(--muted); font-size:var(--mc-text-xs); }
@media(max-width:1100px){.formal-workbench{grid-template-columns:220px minmax(0,1fr)}.verdict-head,.developer-body{grid-template-columns:1fr}.summary-grid{grid-template-columns:1fr}.summary-grid article+article{border-top:1px solid var(--line);border-left:0}.convergence-grid{grid-template-columns:1fr}}
@media(max-width:760px){.formal-workbench{display:block;height:auto;min-height:100%;overflow:visible}.queue-panel{max-height:320px;border-right:0;border-bottom:1px solid var(--line)}.work-area{overflow:visible;padding:20px 14px 40px}.work-head,.topology-evidence-head{align-items:flex-start;flex-direction:column}.topology-evidence-result{grid-template-columns:1fr}.topology-evidence-result dl{grid-template-columns:repeat(2,1fr)}.timing-strip{grid-template-columns:1fr;gap:12px}.timing-strip i{display:none}.evidence-step{grid-template-columns:52px 16px minmax(0,1fr)}.tone-label{grid-column:3;justify-self:start}.incident-form-grid{grid-template-columns:1fr}.spine-facts p{grid-template-columns:1fr;gap:2px}}
</style>
