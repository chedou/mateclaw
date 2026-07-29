<template>
  <div class="sop-page">
    <header class="topbar">
      <div class="heading">
        <button class="back-link" type="button" @click="router.push('/troubleshooting')">
          <el-icon><ArrowLeft /></el-icon>
          诊断工作台
        </button>
        <span class="divider">/</span>
        <div>
          <h1>Playbook 与知识治理</h1>
          <p>已批准 Playbook 驱动确定性命中；三类候选共用独立、可审计的审核流程。</p>
        </div>
      </div>
      <div class="top-actions">
        <el-button
          :icon="Refresh"
          :loading="activeDesk === 'registry' ? listLoading : reviewLoading"
          @click="reload"
        >刷新</el-button>
        <template v-if="activeDesk === 'registry'">
          <el-button plain @click="synthesisOpen = true">无错误码证据预览</el-button>
          <el-button type="primary" :icon="Plus" @click="openRegister">注册候选</el-button>
        </template>
      </div>
    </header>

    <section class="filterbar" aria-label="Playbook 工作区筛选">
      <div class="desk-switch" role="tablist" aria-label="知识治理工作区">
        <button
          type="button"
          role="tab"
          :aria-selected="activeDesk === 'registry'"
          :class="{ active: activeDesk === 'registry' }"
          @click="activeDesk = 'registry'"
        >生效路由 <b>{{ rows.length }}</b></button>
        <button
          type="button"
          role="tab"
          :aria-selected="activeDesk === 'review'"
          :class="{ active: activeDesk === 'review' }"
          @click="activeDesk = 'review'"
        >知识候选 <b>{{ knowledgeRows.length }}</b></button>
      </div>

      <template v-if="activeDesk === 'registry'">
        <span class="filter-separator" />
        <el-select
          v-model="statusFilter"
          clearable
          placeholder="全部状态"
          style="width: 152px"
          @change="loadList()"
        >
          <el-option
            v-for="status in SOP_STATUSES"
            :key="status"
            :label="STATUS_LABEL[status]"
            :value="status"
          />
        </el-select>
        <el-input
          v-model="systemFilter"
          clearable
          placeholder="按 system 精确筛选"
          style="width: 220px"
          @clear="loadList()"
          @keyup.enter="loadList()"
        />
        <el-button @click="loadList()">查询</el-button>
        <button
          v-if="statusFilter || systemFilter"
          class="clear-filter"
          type="button"
          @click="clearFilters"
        >清除筛选</button>
        <span class="registry-count">{{ rows.length }} 条路由</span>
        <div class="lifecycle" aria-label="SOP 生命周期">
          <span>candidate</span><i>→</i><span>qualification gate</span><i>→</i><span>approved version</span><i>→</i><span>deprecated</span>
        </div>
      </template>

      <template v-else>
        <span class="filter-separator" />
        <el-select v-model="originFilter" clearable placeholder="全部来源" style="width: 180px">
          <el-option label="证据生成" value="EVIDENCE_DERIVED" />
          <el-option label="关闭结果沉淀" value="OUTCOME_BACKED" />
          <el-option label="人工注册" value="MANUAL" />
        </el-select>
        <el-input
          v-model="reviewQuery"
          clearable
          placeholder="搜索 selector、标题或来源"
          style="width: 260px"
        />
        <span class="registry-count">{{ filteredKnowledgeRows.length }} 条候选</span>
        <div class="lifecycle review-lifecycle" aria-label="知识审核生命周期">
          <span>candidate</span><i>→</i><span>in review</span><i>→</i><span>approved / rejected</span><i>→</i><span>deprecated</span>
        </div>
      </template>
    </section>

    <div v-if="activeDesk === 'registry'" class="workspace">
      <section class="registry" aria-label="SOP 注册表">
        <el-table
          :data="rows"
          :aria-busy="listLoading"
          row-key="routeKey"
          height="100%"
          :row-class-name="rowClassName"
          @row-click="selectSop"
        >
          <el-table-column label="路由键" min-width="170">
            <template #default="{ row }">
              <div class="route-cell">
                <strong>{{ row.system }}:{{ row.errorCode }}</strong>
                <span>{{ row.sopId }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="service" label="服务" min-width="130" />
          <el-table-column label="状态" width="126">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" size="small" effect="plain">
                {{ statusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="命中路" width="104">
            <template #default="{ row }">
              <span class="operational" :class="{ live: row.operational }">
                <i />{{ row.operational ? '已生效' : '未生效' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="166">
            <template #default="{ row }">
              <span class="mono muted">{{ formatTime(row.updateTime) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="!listLoading && !rows.length" class="empty-state">
          <strong>没有匹配的 SOP</strong>
          <p>新知识只能以 candidate 注册，再由管理员显式审核。</p>
          <el-button type="primary" plain @click="openRegister">注册第一条 SOP</el-button>
        </div>
      </section>

      <aside
        class="inspector"
        :class="{ 'is-loading': detailLoading }"
        :aria-busy="detailLoading"
        aria-label="SOP 详情检查器"
      >
        <div v-if="!selectedSop" class="inspector-empty">
          <span class="empty-mark">{ }</span>
          <strong>选择一条路由查看完整契约</strong>
          <p>列表只读索引列；判据、规则和建议动作按需加载。</p>
        </div>

        <Transition v-else name="inspector" mode="out-in">
          <div :key="selectedSop.system + ':' + selectedSop.errorCode" class="inspector-body">
            <div class="inspector-head">
              <div>
                <span class="eyebrow">{{ selectedSop.contractVersion }}</span>
                <h2>{{ selectedSop.title }}</h2>
                <div class="route-line">{{ selectedSop.system }}:{{ selectedSop.errorCode }}</div>
              </div>
              <el-tag :type="statusTagType(selectedSop.status)" effect="plain">
                {{ STATUS_LABEL[selectedSop.status] }}
              </el-tag>
            </div>

            <el-alert
              v-if="selectedSop.status === 'candidate'"
              type="warning"
              :closable="false"
              title="候选 SOP 不参与确定性诊断；审核通过后才进入命中路。"
            />
            <el-alert
              v-else-if="selectedSop.status === 'deprecated'"
              type="info"
              :closable="false"
              title="该版本已退出命中路；当前注册表按 routeKey 唯一，替代版本需等待版本化模型落地。"
            />

            <dl class="metadata">
              <div><dt>service</dt><dd>{{ selectedSop.service }}</dd></div>
              <div><dt>owner</dt><dd>{{ selectedSop.ownerTeam || '未指定' }}</dd></div>
              <div><dt>category</dt><dd>{{ selectedSop.category || '未分类' }}</dd></div>
              <div><dt>verified</dt><dd class="mono">{{ selectedSop.verified }}</dd></div>
            </dl>

            <section class="contract-health">
              <div class="section-title">
                <span>契约组成</span>
                <span v-if="contractWarnings.length" class="warning-count">
                  {{ contractWarnings.length }} 个审核提示
                </span>
              </div>
              <div class="counts">
                <div><b>{{ selectedSop.evidenceRequests.length }}</b><span>取证请求</span></div>
                <div><b>{{ selectedSop.anomalyCriteria.length }}</b><span>异常判据</span></div>
                <div><b>{{ selectedSop.diagnosisRules.length }}</b><span>诊断规则</span></div>
                <div><b>{{ selectedSop.actions.length }}</b><span>建议动作</span></div>
              </div>
              <ul v-if="contractWarnings.length" class="review-warnings">
                <li v-for="warning in contractWarnings" :key="warning">{{ warning }}</li>
              </ul>
            </section>

            <section v-if="containsManualWrite" class="redline-note">
              <strong>生产写红线</strong>
              <p>此 SOP 含 MANUAL_WRITE 建议，但平台只允许转派、批准状态推进和外部结果登记，绝不执行写操作。</p>
            </section>

            <section class="json-section">
              <div class="section-title">
                <span>完整 SOP JSON</span>
                <el-button size="small" text @click="copyContract">复制</el-button>
              </div>
              <pre>{{ prettyContract }}</pre>
            </section>

            <div class="review-action">
              <template v-if="selectedSop.status === 'candidate'">
                <div>
                  <strong>晋升门禁尚未开放</strong>
                  <p>先在“知识候选”核对来源、回放、owner 与版本替代条件；旧式 candidate → approved 按钮已停止暴露。</p>
                </div>
                <el-button disabled>等待资格门禁</el-button>
              </template>
              <template v-else-if="nextStatus === 'deprecated'">
                <div>
                  <strong>将当前版本退出命中路</strong>
                  <p>标记后该路由将退出命中路；替代版本必须通过新的版本化晋升合同。</p>
                </div>
                <el-button
                  type="danger"
                  plain
                  :loading="statusUpdating"
                  @click="advanceStatus"
                >标记过期</el-button>
              </template>
              <span v-else>生命周期已结束；该版本只保留审计记录。</span>
            </div>
          </div>
        </Transition>
      </aside>
    </div>

    <div v-else class="workspace review-workspace">
      <section class="registry" aria-label="知识候选审阅队列">
        <el-table
          :data="filteredKnowledgeRows"
          :aria-busy="reviewLoading"
          row-key="key"
          height="100%"
          :row-class-name="reviewRowClassName"
          @row-click="selectReview"
        >
          <el-table-column label="候选知识" min-width="230">
            <template #default="{ row }">
              <div class="review-title-cell">
                <strong>{{ row.title }}</strong>
                <span class="mono">{{ row.selector }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="来源" width="132">
            <template #default="{ row }">
              <el-tag :type="originTagType(row.origin)" size="small" effect="plain">
                {{ originLabel(row.origin) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="审核状态" width="118">
            <template #default="{ row }">
              <span class="mono state-text">{{ reviewStatusLabel(row.reviewStatus) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="晋升资格" width="122">
            <template #default="{ row }">
              <span
                class="eligibility"
                :class="{ eligible: row.approvalEligibility === 'ELIGIBLE_FOR_APPROVAL' }"
              ><i />{{ eligibilityLabel(row.approvalEligibility) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="来源记录" min-width="150">
            <template #default="{ row }">
              <span class="mono muted source-ref">{{ row.sourceRef }}</span>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="166">
            <template #default="{ row }">
              <span class="mono muted">{{ formatTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="!reviewLoading && reviewUnavailable" class="review-unavailable" role="alert">
          <div>
            <strong>Review Inbox · UNAVAILABLE</strong>
            <p>
              {{ knowledgeRows.length
                ? '无法刷新，当前继续展示上一次成功快照；请勿据此判断候选已清空。'
                : '无法读取持久化候选；当前状态不是“零候选”。' }}
            </p>
          </div>
          <el-button size="small" type="danger" plain @click="loadReviewInbox">重试</el-button>
        </div>
        <div v-else-if="!reviewLoading && !filteredKnowledgeRows.length" class="empty-state">
          <strong>没有匹配的知识候选</strong>
          <p>候选来自证据生成、关闭结果沉淀或人工注册；此页面不生成模拟记录。</p>
        </div>
      </section>

      <aside
        class="inspector review-inspector"
        :class="{ 'is-loading': reviewLoading || manualDetailLoading }"
        :aria-busy="reviewLoading || manualDetailLoading"
        aria-label="知识候选详情检查器"
      >
        <div v-if="!selectedReview" class="inspector-empty">
          <span class="empty-mark">K</span>
          <strong>选择一条候选查看资格证据</strong>
          <p>这里展示服务端真实持久化记录，不会把发布成功、模型输出或一次关闭误写成审批通过。</p>
        </div>

        <Transition v-else name="inspector" mode="out-in">
          <div :key="selectedReview.key" class="inspector-body">
            <div class="inspector-head">
              <div>
                <span class="eyebrow">{{ selectedReview.recordId }}</span>
                <h2>{{ selectedReview.title }}</h2>
                <div class="route-line">{{ selectedReview.selector }}</div>
              </div>
              <el-tag :type="originTagType(selectedReview.origin)" effect="plain">
                {{ originLabel(selectedReview.origin) }}
              </el-tag>
            </div>

            <el-alert
              v-if="!selectedReview.reviewStatePersisted"
              type="info"
              :closable="false"
              title="独立审核尚未开始；当前为 CANDIDATE / v0。"
            />
            <el-alert
              v-if="selectedReview.fixtureMode"
              type="info"
              :closable="false"
              title="该证据草稿仍带 fixture 标记，只能用于校准与审阅，不能晋升为生产 Playbook。"
            />

            <dl class="metadata review-metadata">
              <div><dt>origin</dt><dd class="mono">{{ selectedReview.origin }}</dd></div>
              <div><dt>review</dt><dd class="mono">{{ selectedReview.reviewStatus }} / v{{ selectedReview.reviewVersion }}</dd></div>
              <div><dt>validation</dt><dd class="mono">{{ selectedReview.validationStatus }}</dd></div>
              <div><dt>eligibility</dt><dd class="mono">{{ selectedReview.approvalEligibility }}</dd></div>
              <div><dt>service</dt><dd>{{ selectedReview.service || '合同未提供' }}</dd></div>
              <div><dt>source</dt><dd class="mono">{{ selectedReview.sourceRef }}</dd></div>
            </dl>

            <section v-if="selectedReview.reviewState" class="candidate-detail-card review-audit-card">
              <div class="section-title">
                <span>审核台账</span>
                <el-tag
                  :type="selectedReview.reviewStatus === 'REJECTED' ? 'danger' : 'primary'"
                  size="small"
                  effect="plain"
                >{{ reviewStatusLabel(selectedReview.reviewStatus) }} · v{{ selectedReview.reviewVersion }}</el-tag>
              </div>
              <dl class="compact-facts">
                <div><dt>reviewer</dt><dd>{{ selectedReview.reviewer }}</dd></div>
                <div><dt>updated</dt><dd class="mono">{{ formatTime(selectedReview.reviewState.updatedAt) }}</dd></div>
                <div><dt>snapshot validation</dt><dd class="mono">{{ selectedReview.reviewState.snapshot.validationStatus }}</dd></div>
                <div><dt>model config</dt><dd class="mono">{{ selectedReview.reviewState.snapshot.modelConfigVersion || 'NOT_APPLICABLE' }}</dd></div>
                <div><dt>reference</dt><dd class="mono">{{ selectedReview.reviewState.snapshot.referenceComparison?.referenceId || 'NOT_APPLICABLE' }}</dd></div>
                <div><dt>fixture</dt><dd class="mono">{{ selectedReview.reviewState.snapshot.fixtureMode ?? 'UNKNOWN' }}</dd></div>
              </dl>
              <div class="audit-reason">
                <span>审核理由</span>
                <p>{{ selectedReview.reviewReason }}</p>
              </div>
              <ul
                v-if="selectedReview.reviewState.snapshot.validationErrors.length"
                class="reference-issues"
              >
                <li
                  v-for="issue in selectedReview.reviewState.snapshot.validationErrors"
                  :key="issue.code + ':' + issue.fieldPath"
                  class="danger"
                >
                  <strong>{{ issue.code }} · {{ issue.fieldPath }}</strong>
                  <code>{{ issue.message }}</code>
                </li>
              </ul>
              <ul v-if="selectedReviewSnapshotIssues.length" class="reference-issues">
                <li
                  v-for="issue in selectedReviewSnapshotIssues"
                  :key="issue.code"
                  :class="{ danger: issue.danger }"
                >
                  <strong>{{ issue.label }}</strong>
                  <code>{{ issue.items.join(' · ') }}</code>
                </li>
              </ul>
            </section>

            <section class="qualification-card">
              <div class="section-title">
                <span>资格门禁</span>
                <el-tag type="danger" size="small" effect="plain">
                  {{ eligibilityLabel(selectedReview.approvalEligibility) }}
                </el-tag>
              </div>
              <ul class="gate-reasons">
                <li v-for="reason in selectedReview.eligibilityReasons" :key="reason">
                  <code>{{ reason }}</code>
                  <span>{{ reviewReasonLabel(reason) }}</span>
                </li>
              </ul>
            </section>

            <section class="evidence-reference-card">
              <div class="section-title">
                <span>证据引用</span>
                <span class="muted">{{ selectedReview.evidenceRefs.length }} 条</span>
              </div>
              <div v-if="selectedReview.evidenceRefs.length" class="reference-list">
                <code v-for="reference in selectedReview.evidenceRefs" :key="reference">
                  {{ reference }}
                </code>
              </div>
              <p v-else class="empty-copy">当前读取合同没有可审计引用。</p>
            </section>

            <template v-if="selectedEvidenceRecord">
              <section class="candidate-detail-card">
                <div class="section-title"><span>证据生成草稿</span></div>
                <div class="counts">
                  <div><b>{{ selectedEvidenceRecord.draft.evidencePlan.length }}</b><span>取证步骤</span></div>
                  <div><b>{{ selectedEvidenceRecord.draft.criteria.length }}</b><span>判据</span></div>
                  <div><b>{{ selectedEvidenceRecord.draft.diagnosisHypotheses.length }}</b><span>根因假设</span></div>
                  <div><b>{{ selectedEvidenceRecord.draft.humanActions.length }}</b><span>人工动作</span></div>
                </div>
                <ol v-if="selectedEvidenceRecord.draft.evidencePlan.length" class="draft-steps">
                  <li v-for="step in selectedEvidenceRecord.draft.evidencePlan" :key="step.intentKey">
                    <code>{{ step.signalKind }}</code>
                    <span>{{ step.purpose }}</span>
                    <small>{{ step.required ? 'required' : 'optional' }}</small>
                  </li>
                </ol>
              </section>

              <section class="candidate-detail-card">
                <div class="section-title"><span>模型与参考解法</span></div>
                <dl class="compact-facts">
                  <div><dt>provider / model</dt><dd>{{ selectedEvidenceRecord.draft.modelProvenance.provider }} / {{ selectedEvidenceRecord.draft.modelProvenance.modelName }}</dd></div>
                  <div><dt>config</dt><dd class="mono">{{ selectedEvidenceRecord.draft.modelProvenance.modelConfigVersion }}</dd></div>
                  <div><dt>generated at</dt><dd class="mono">{{ formatTime(selectedEvidenceRecord.draft.modelProvenance.generatedAt) }}</dd></div>
                  <div><dt>invocations</dt><dd>{{ selectedEvidenceRecord.draft.modelProvenance.invocationCount }}</dd></div>
                  <div><dt>reference</dt><dd class="mono">{{ selectedEvidenceRecord.referenceComparison.referenceId }}</dd></div>
                  <div><dt>intent coverage</dt><dd>{{ percent(selectedEvidenceRecord.referenceComparison.requiredIntentCoverage) }}</dd></div>
                  <div><dt>reference verdict</dt><dd>{{ selectedEvidenceRecord.referenceComparison.passed ? 'PASS' : 'DELTA' }}</dd></div>
                  <div><dt>contrast</dt><dd>{{ selectedEvidenceRecord.draft.contrastAvailable ? 'AVAILABLE' : 'UNAVAILABLE' }}</dd></div>
                </dl>
                <ul v-if="selectedComparisonIssues.length" class="reference-issues">
                  <li
                    v-for="issue in selectedComparisonIssues"
                    :key="issue.code"
                    :class="{ danger: issue.danger }"
                  >
                    <strong>{{ issue.label }}</strong>
                    <code>{{ issue.items.join(' · ') }}</code>
                  </li>
                </ul>
                <p v-else class="comparison-pass">结构化参考比较没有记录差异项。</p>
              </section>
            </template>

            <template v-else-if="selectedOutcomeCandidate">
              <section class="candidate-detail-card">
                <div class="section-title"><span>关闭结果沉淀</span></div>
                <p class="candidate-summary">{{ selectedOutcomeCandidate.resolutionSummary }}</p>
                <p v-if="selectedOutcomeCandidate.feedback" class="candidate-feedback">
                  SOP 反馈：{{ selectedOutcomeCandidate.feedback }}
                </p>
                <dl class="compact-facts">
                  <div><dt>case / run</dt><dd class="mono">{{ selectedOutcomeCandidate.sourceCaseId }} / {{ selectedOutcomeCandidate.sourceRunId }}</dd></div>
                  <div><dt>created by</dt><dd>{{ selectedOutcomeCandidate.createdBy }}</dd></div>
                  <div><dt>recommended actions</dt><dd>{{ selectedOutcomeCandidate.recommendedActions.length }}</dd></div>
                  <div><dt>recorded outcomes</dt><dd>{{ selectedOutcomeCandidate.actionOutcomes.length }}</dd></div>
                </dl>
              </section>
            </template>

            <template v-else-if="selectedManualSop">
              <section class="candidate-detail-card">
                <div class="section-title"><span>人工注册合同</span></div>
                <dl class="compact-facts">
                  <div><dt>title</dt><dd>{{ selectedManualSop.title }}</dd></div>
                  <div><dt>owner</dt><dd>{{ selectedManualSop.ownerTeam || '未指定' }}</dd></div>
                  <div><dt>contract</dt><dd class="mono">{{ selectedManualSop.contractVersion }}</dd></div>
                  <div><dt>verified</dt><dd class="mono">{{ selectedManualSop.verified }}</dd></div>
                </dl>
                <div class="counts manual-counts">
                  <div><b>{{ selectedManualSop.evidenceRequests.length }}</b><span>取证请求</span></div>
                  <div><b>{{ selectedManualSop.anomalyCriteria.length }}</b><span>异常判据</span></div>
                  <div><b>{{ selectedManualSop.diagnosisRules.length }}</b><span>诊断规则</span></div>
                  <div><b>{{ selectedManualSop.actions.length }}</b><span>建议动作</span></div>
                </div>
              </section>
            </template>

            <section class="capability-boundary">
              <strong>当前能力边界</strong>
              <ul>
                <li v-for="limit in reviewInbox.capabilityLimits" :key="limit">
                  {{ reviewReasonLabel(limit) }}
                </li>
              </ul>
            </section>

            <div class="review-action locked-review-action">
              <div>
                <strong v-if="selectedReview.reviewStatus === 'CANDIDATE'">开始独立审阅</strong>
                <strong v-else-if="selectedReview.reviewStatus === 'IN_REVIEW'">记录审阅决策</strong>
                <strong v-else>审阅决策已固化</strong>
                <p v-if="selectedReview.reviewStatus === 'CANDIDATE'">将当前校验、参考解法与模型版本冻结进审核台账；不会晋升知识。</p>
                <p v-else-if="selectedReview.reviewStatus === 'IN_REVIEW'">拒绝会对精确版本做乐观锁校验；并发变更后必须重新加载。</p>
                <p v-else>已拒绝候选不能原地重开；修正后应产生新的 source record。</p>
              </div>
              <div class="review-action-buttons">
                <el-button
                  v-if="selectedReview.reviewStatus === 'CANDIDATE'"
                  type="primary"
                  :loading="reviewDecisionLoading === `start:${selectedReview.key}`"
                  @click="startReview(selectedReview)"
                >开始审阅</el-button>
                <el-button
                  v-else-if="selectedReview.reviewStatus === 'IN_REVIEW'"
                  type="danger"
                  plain
                  :loading="reviewDecisionLoading === `reject:${selectedReview.key}`"
                  @click="rejectReview(selectedReview)"
                >拒绝候选</el-button>
                <el-button disabled>批准不可用</el-button>
              </div>
            </div>
          </div>
        </Transition>
      </aside>
    </div>

    <el-dialog
      v-model="registerOpen"
      title="注册候选 SOP"
      width="720px"
      destroy-on-close
      :teleported="false"
    >
      <el-alert type="info" :closable="false" class="register-note">
        <template #title>
          只接受单个 JSON 对象，并强制以 <code>candidate + verified=false</code> 注册。路由键冲突会拒绝覆盖。
        </template>
      </el-alert>
      <el-input
        v-model="registerJson"
        type="textarea"
        :rows="20"
        resize="vertical"
        spellcheck="false"
        class="json-input"
      />
      <div class="validation" :class="{ valid: importValidation.sop }">
        <template v-if="importValidation.sop">
          <span class="validation-dot" />
          合同可提交：<code>{{ importValidation.sop.system }}:{{ importValidation.sop.errorCode }}</code>
          · {{ importValidation.sop.evidenceRequests.length }} 取证
          · {{ importValidation.sop.diagnosisRules.length }} 规则
        </template>
        <template v-else>{{ importValidation.error }}</template>
      </div>
      <template #footer>
        <el-button @click="registerOpen = false">取消</el-button>
        <el-button
          type="primary"
          :disabled="!importValidation.sop"
          :loading="registering"
          @click="registerSop"
        >注册为 candidate</el-button>
      </template>
    </el-dialog>

    <SynthesisPreviewDialog v-model="synthesisOpen" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { copyToClipboard } from '@/utils/clipboard'
import {
  troubleshootingApi,
  type KnowledgeOrigin,
  type KnowledgeReviewInbox,
  type KnowledgeReviewState,
  type SopEntry,
  type SopStatus,
  type SopSummary,
} from '@/api'
import { nextSopStatus, parseCandidateSopJson } from './sopRegistry'
import {
  buildKnowledgeReviewRows,
  filterKnowledgeReviewRows,
  referenceComparisonIssues,
  reviewReasonLabel,
  type KnowledgeReviewRow,
} from './knowledgeReview'
import SynthesisPreviewDialog from './SynthesisPreviewDialog.vue'

const SOP_STATUSES: SopStatus[] = ['candidate', 'approved', 'deprecated']
const STATUS_LABEL: Record<SopStatus, string> = {
  candidate: '待审核',
  approved: '已生效',
  deprecated: '已过期',
}
const EMPTY_TEMPLATE = JSON.stringify({
  sopId: '',
  contractVersion: 'sop.v1',
  system: '',
  errorCode: '',
  service: '',
  title: '',
  cause: '',
  category: '',
  ownerTeam: '',
  status: 'candidate',
  verified: false,
  evidenceRequests: [],
  anomalyCriteria: [],
  diagnosisRules: [],
  actions: [],
}, null, 2)

const router = useRouter()
const activeDesk = ref<'registry' | 'review'>('registry')
const rows = ref<SopSummary[]>([])
const selectedSop = ref<SopEntry | null>(null)
const selectedRouteKey = ref<string | null>(null)
const statusFilter = ref<SopStatus | ''>('')
const systemFilter = ref('')
const listLoading = ref(false)
const detailLoading = ref(false)
const statusUpdating = ref(false)
const registerOpen = ref(false)
const synthesisOpen = ref(false)
const registering = ref(false)
const registerJson = ref(EMPTY_TEMPLATE)
const reviewInbox = ref<KnowledgeReviewInbox>({
  evidenceDerived: [],
  outcomeBacked: [],
  manual: [],
  reviewStates: [],
  capabilityLimits: [],
})
const reviewLoading = ref(false)
const reviewUnavailable = ref(false)
const reviewDecisionLoading = ref<string | null>(null)
const originFilter = ref<'' | KnowledgeOrigin>('')
const reviewQuery = ref('')
const selectedReviewKey = ref<string | null>(null)
const selectedManualSop = ref<SopEntry | null>(null)
const manualDetailLoading = ref(false)
let detailRequest = 0
let manualDetailRequest = 0

const nextStatus = computed(() => selectedSop.value
  ? nextSopStatus(selectedSop.value.status)
  : null)

const knowledgeRows = computed(() => buildKnowledgeReviewRows(reviewInbox.value))
const filteredKnowledgeRows = computed(() => filterKnowledgeReviewRows(
  knowledgeRows.value,
  originFilter.value,
  reviewQuery.value,
))
const selectedReview = computed(() => filteredKnowledgeRows.value.find(
  (row) => row.key === selectedReviewKey.value,
) ?? null)
const selectedEvidenceRecord = computed(() => selectedReview.value?.source.kind === 'EVIDENCE_DERIVED'
  ? selectedReview.value.source.record
  : null)
const selectedOutcomeCandidate = computed(() => selectedReview.value?.source.kind === 'OUTCOME_BACKED'
  ? selectedReview.value.source.candidate
  : null)
const selectedComparisonIssues = computed(() => selectedEvidenceRecord.value
  ? referenceComparisonIssues(selectedEvidenceRecord.value.referenceComparison)
  : [])
const selectedReviewSnapshotIssues = computed(() => {
  const comparison = selectedReview.value?.reviewState?.snapshot.referenceComparison
  return comparison ? referenceComparisonIssues(comparison) : []
})

const prettyContract = computed(() => selectedSop.value
  ? JSON.stringify(selectedSop.value, null, 2)
  : '')

const containsManualWrite = computed(() => selectedSop.value?.actions.some((action) =>
  action.actionType === 'MANUAL_WRITE') ?? false)

const contractWarnings = computed(() => {
  const sop = selectedSop.value
  if (!sop) return []
  const warnings: string[] = []
  if (!sop.evidenceRequests.length) warnings.push('没有取证请求；审核前确认该路由是否真的无需证据。')
  if (!sop.anomalyCriteria.length) warnings.push('没有异常判据；当前合同无法形成可解释的信号。')
  if (!sop.diagnosisRules.length) warnings.push('没有诊断规则；当前合同不能产出确定性根因。')
  if (sop.status === 'approved' && !sop.verified) warnings.push('状态与 verified 不一致，应停止使用并检查数据。')
  return warnings
})

const importValidation = computed<{ sop: SopEntry | null; error: string | null }>(() => {
  try {
    return { sop: parseCandidateSopJson(registerJson.value), error: null }
  } catch (error) {
    return { sop: null, error: error instanceof Error ? error.message : 'SOP JSON 无效' }
  }
})

async function loadList() {
  listLoading.value = true
  try {
    const { data } = await troubleshootingApi.listSops({
      status: statusFilter.value || undefined,
      system: systemFilter.value.trim() || undefined,
      limit: 500,
    })
    rows.value = data ?? []
    const selected = rows.value.find((row) => row.routeKey === selectedRouteKey.value)
    if (selected) return
    if (rows.value.length) {
      await selectSop(rows.value[0])
    } else {
      selectedRouteKey.value = null
      selectedSop.value = null
    }
  } finally {
    listLoading.value = false
  }
}

async function loadReviewInbox() {
  reviewLoading.value = true
  try {
    const { data } = await troubleshootingApi.knowledgeReviewInbox({ limit: 200 })
    reviewInbox.value = data ?? {
      evidenceDerived: [], outcomeBacked: [], manual: [], reviewStates: [], capabilityLimits: [],
    }
    reviewUnavailable.value = false
    const retained = knowledgeRows.value.find((row) => row.key === selectedReviewKey.value)
    if (retained) return
    const first = knowledgeRows.value[0]
    if (first) {
      await selectReview(first)
    } else {
      selectedReviewKey.value = null
      selectedManualSop.value = null
    }
  } catch {
    reviewUnavailable.value = true
  } finally {
    reviewLoading.value = false
  }
}

async function selectSop(row: SopSummary) {
  selectedRouteKey.value = row.routeKey
  const request = ++detailRequest
  detailLoading.value = true
  try {
    const { data } = await troubleshootingApi.getSop(row.system, row.errorCode)
    if (request === detailRequest) selectedSop.value = data
  } finally {
    if (request === detailRequest) detailLoading.value = false
  }
}

async function selectReview(row: KnowledgeReviewRow) {
  selectedReviewKey.value = row.key
  selectedManualSop.value = null
  const request = ++manualDetailRequest
  if (row.source.kind !== 'MANUAL') {
    manualDetailLoading.value = false
    return
  }
  manualDetailLoading.value = true
  try {
    const { data } = await troubleshootingApi.getSop(
      row.source.summary.system,
      row.source.summary.errorCode,
    )
    if (request === manualDetailRequest) selectedManualSop.value = data
  } finally {
    if (request === manualDetailRequest) manualDetailLoading.value = false
  }
}

async function askReviewReason(
  title: string,
  placeholder: string,
  confirmButtonText: string,
) {
  try {
    const result = await ElMessageBox.prompt(
      '请写明本次审阅的事实依据或决策原因。审核人将从当前登录账号记录，不能在这里代填。',
      title,
      {
        confirmButtonText,
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputPlaceholder: placeholder,
        inputValidator: (value) => {
          const length = value.trim().length
          if (!length) return '请填写审阅理由'
          if (length > 1000) return '审阅理由不能超过 1000 字'
          return true
        },
      },
    )
    return result.value.trim()
  } catch {
    return null
  }
}

function upsertReviewState(state: KnowledgeReviewState) {
  const index = reviewInbox.value.reviewStates.findIndex((item) =>
    item.origin === state.origin && item.sourceRecordId === state.sourceRecordId)
  if (index < 0) {
    reviewInbox.value.reviewStates = [state, ...reviewInbox.value.reviewStates]
    return
  }
  reviewInbox.value.reviewStates.splice(index, 1, state)
}

async function startReview(row: KnowledgeReviewRow) {
  if (row.reviewStatus !== 'CANDIDATE' || row.reviewVersion !== 0) return
  const reason = await askReviewReason(
    `开始审阅 ${row.selector}`,
    '例：核对固定回放、证据引用和参考解法',
    '开始审阅',
  )
  if (!reason) return
  reviewDecisionLoading.value = `start:${row.key}`
  try {
    const { data } = await troubleshootingApi.startKnowledgeReview(
      row.origin,
      row.recordId,
      { expectedVersion: 0, reason },
    )
    upsertReviewState(data)
    reviewUnavailable.value = false
    ElMessage.success('已进入审阅中，校验与模型事实已冻结')
  } finally {
    reviewDecisionLoading.value = null
  }
}

async function rejectReview(row: KnowledgeReviewRow) {
  if (row.reviewStatus !== 'IN_REVIEW' || row.reviewVersion < 1) return
  const reason = await askReviewReason(
    `拒绝 ${row.selector} / v${row.reviewVersion}`,
    '例：缺少负例回放，引用无法支持结论',
    '记录拒绝',
  )
  if (!reason) return
  reviewDecisionLoading.value = `reject:${row.key}`
  try {
    const { data } = await troubleshootingApi.rejectKnowledgeReview(
      row.origin,
      row.recordId,
      { expectedVersion: row.reviewVersion, reason },
    )
    upsertReviewState(data)
    reviewUnavailable.value = false
    ElMessage.success('已记录拒绝决策')
  } finally {
    reviewDecisionLoading.value = null
  }
}

async function reload() {
  if (activeDesk.value === 'review') {
    await loadReviewInbox()
    return
  }
  await loadList()
  const row = rows.value.find((item) => item.routeKey === selectedRouteKey.value)
  if (row) await selectSop(row)
}

function clearFilters() {
  statusFilter.value = ''
  systemFilter.value = ''
  loadList()
}

function openRegister() {
  registerJson.value = EMPTY_TEMPLATE
  registerOpen.value = true
}

async function registerSop() {
  const sop = importValidation.value.sop
  if (!sop) return
  registering.value = true
  try {
    const { data } = await troubleshootingApi.registerSop(sop)
    registerOpen.value = false
    statusFilter.value = ''
    systemFilter.value = ''
    selectedRouteKey.value = `${data.system.toLowerCase()}:${data.errorCode}`
    selectedSop.value = data
    await loadList()
    await loadReviewInbox()
    ElMessage.success(`已注册候选 SOP ${data.system}:${data.errorCode}`)
  } finally {
    registering.value = false
  }
}

async function advanceStatus() {
  const sop = selectedSop.value
  const target = nextStatus.value
  if (!sop || target !== 'deprecated') return
  const title = `标记 ${sop.system}:${sop.errorCode} 为过期？`
  const message = '过期后，该版本立即退出命中路且不能恢复；替代版本必须通过新的版本化晋升合同。'
  try {
    await ElMessageBox.confirm(message, title, {
      confirmButtonText: '标记过期',
      cancelButtonText: '取消',
      type: 'error',
      customClass: 'sop-status-confirm',
    })
  } catch {
    return
  }

  statusUpdating.value = true
  try {
    const { data } = await troubleshootingApi.updateSopStatus(
      sop.system, sop.errorCode, target)
    selectedSop.value = data
    statusFilter.value = ''
    await loadList()
    ElMessage.success('SOP 已标记过期')
  } finally {
    statusUpdating.value = false
  }
}

async function copyContract() {
  await copyToClipboard(prettyContract.value)
  ElMessage.success('SOP JSON 已复制')
}

function rowClassName({ row }: { row: SopSummary }) {
  return row.routeKey === selectedRouteKey.value ? 'selected-row' : ''
}

function reviewRowClassName({ row }: { row: KnowledgeReviewRow }) {
  return row.key === selectedReviewKey.value ? 'selected-row' : ''
}

function statusTagType(status: SopStatus): 'warning' | 'success' | 'info' {
  if (status === 'candidate') return 'warning'
  if (status === 'approved') return 'success'
  return 'info'
}

function statusLabel(status: SopStatus) {
  return STATUS_LABEL[status]
}

function originLabel(origin: KnowledgeOrigin) {
  if (origin === 'EVIDENCE_DERIVED') return '证据生成'
  if (origin === 'OUTCOME_BACKED') return '关闭沉淀'
  return '人工注册'
}

function originTagType(origin: KnowledgeOrigin): 'primary' | 'success' | 'warning' {
  if (origin === 'EVIDENCE_DERIVED') return 'primary'
  if (origin === 'OUTCOME_BACKED') return 'success'
  return 'warning'
}

function reviewStatusLabel(status: string) {
  const labels: Record<string, string> = {
    DRAFT: '草稿', CANDIDATE: '候选', IN_REVIEW: '审阅中',
    APPROVED: '已批准', REJECTED: '已拒绝', DEPRECATED: '已过期',
  }
  return labels[status] ?? status
}

function eligibilityLabel(eligibility: string) {
  return eligibility === 'ELIGIBLE_FOR_APPROVAL' ? '可申请批准' : '不可晋升'
}

function percent(value: number) {
  return `${Math.round(value * 100)}%`
}

function formatTime(value?: string | null) {
  if (!value) return '—'
  return value.replace('T', ' ').replace(/\.\d+Z?$/, '').slice(0, 19)
}

onMounted(() => Promise.all([loadList(), loadReviewInbox()]))
</script>

<style scoped>
.sop-page {
  --ts-signal: #2f5cf5;
  --el-color-primary: var(--ts-signal);
  --el-color-primary-light-3: color-mix(in srgb, var(--ts-signal) 70%, var(--el-bg-color));
  --el-color-primary-light-5: color-mix(in srgb, var(--ts-signal) 50%, var(--el-bg-color));
  --el-color-primary-light-7: color-mix(in srgb, var(--ts-signal) 30%, var(--el-bg-color));
  --el-color-primary-light-8: color-mix(in srgb, var(--ts-signal) 20%, var(--el-bg-color));
  --el-color-primary-light-9: color-mix(in srgb, var(--ts-signal) 10%, var(--el-bg-color));
  --el-color-primary-dark-2: color-mix(in srgb, var(--ts-signal) 80%, black);
  --el-color-warning-light-8: color-mix(in srgb, var(--el-color-warning) 20%, var(--el-bg-color));
  --el-color-warning-light-9: color-mix(in srgb, var(--el-color-warning) 10%, var(--el-bg-color));
  --el-color-danger-light-8: color-mix(in srgb, var(--el-color-danger) 20%, var(--el-bg-color));
  --el-color-danger-light-9: color-mix(in srgb, var(--el-color-danger) 10%, var(--el-bg-color));
  --el-color-success-light-8: color-mix(in srgb, var(--el-color-success) 20%, var(--el-bg-color));
  --el-color-success-light-9: color-mix(in srgb, var(--el-color-success) 10%, var(--el-bg-color));
  --el-color-info-light-8: color-mix(in srgb, var(--el-color-info) 20%, var(--el-bg-color));
  --el-color-info-light-9: color-mix(in srgb, var(--el-color-info) 10%, var(--el-bg-color));
  display: flex;
  flex-direction: column;
  height: 100%;
  min-width: 0;
  overflow: hidden;
  background: var(--el-bg-color);
  color: var(--el-text-color-primary);
}

.topbar {
  min-height: 62px;
  padding: 11px 18px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.heading { display: flex; align-items: center; min-width: 0; gap: 10px; }
.heading h1 { margin: 0; font-size: 17px; line-height: 1.3; font-weight: 680; }
.heading p { margin: 2px 0 0; color: var(--el-text-color-secondary); font-size: 11.5px; }
.back-link {
  display: inline-flex; align-items: center; gap: 5px; padding: 6px 0; flex-shrink: 0;
  border: 0; background: transparent; color: var(--el-text-color-secondary); cursor: pointer;
  font: inherit; font-size: 12px;
}
.back-link:hover { color: var(--el-color-primary); }
.divider { color: var(--el-border-color); }
.top-actions { display: flex; gap: 8px; flex-shrink: 0; }

.filterbar {
  min-height: 48px;
  padding: 8px 14px;
  display: flex;
  align-items: center;
  gap: 8px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  background: var(--el-fill-color-blank);
}
.desk-switch {
  display: inline-flex; flex-shrink: 0; padding: 3px; gap: 2px;
  border: 1px solid var(--el-border-color-lighter); border-radius: 7px;
  background: var(--el-fill-color-light);
}
.desk-switch button {
  min-height: 28px; padding: 4px 10px; border: 0; border-radius: 5px;
  background: transparent; color: var(--el-text-color-secondary); cursor: pointer;
  font: 600 11px/1.2 inherit;
}
.desk-switch button:hover { color: var(--el-text-color-primary); }
.desk-switch button.active {
  background: var(--el-bg-color); color: var(--el-color-primary);
  box-shadow: 0 1px 3px color-mix(in srgb, var(--el-text-color-primary) 10%, transparent);
}
.desk-switch b {
  display: inline-block; min-width: 17px; margin-left: 4px; padding: 1px 4px;
  border-radius: 8px; background: var(--el-fill-color-dark); color: inherit;
  font: 600 9.5px var(--mc-mono, monospace);
}
.filter-separator { width: 1px; height: 24px; margin: 0 3px; background: var(--el-border-color-lighter); }
.clear-filter {
  border: 0; background: transparent; color: var(--el-text-color-secondary); cursor: pointer;
  font: inherit; font-size: 12px;
}
.clear-filter:hover { color: var(--el-color-primary); }
.registry-count { margin-left: 4px; font-size: 11.5px; color: var(--el-text-color-secondary); }
.lifecycle {
  margin-left: auto; display: flex; align-items: center; gap: 7px;
  color: var(--el-text-color-secondary); font: 10.5px var(--mc-mono, monospace);
}
.lifecycle span { padding: 3px 7px; border: 1px solid var(--el-border-color-lighter); border-radius: 4px; }
.lifecycle i { color: var(--el-text-color-placeholder); font-style: normal; }

.workspace { flex: 1; min-height: 0; display: grid; grid-template-columns: minmax(560px, 1fr) 430px; }
.review-workspace { grid-template-columns: minmax(680px, 1fr) 480px; }
.registry { min-width: 0; min-height: 0; position: relative; border-right: 1px solid var(--el-border-color-lighter); }
.route-cell { display: flex; flex-direction: column; gap: 2px; }
.route-cell strong { font: 600 12px var(--mc-mono, monospace); color: var(--el-text-color-primary); }
.route-cell span { font: 10px var(--mc-mono, monospace); color: var(--el-text-color-placeholder); }
.review-title-cell { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.review-title-cell strong {
  overflow: hidden; color: var(--el-text-color-primary); font-size: 11.5px;
  font-weight: 600; text-overflow: ellipsis; white-space: nowrap;
}
.review-title-cell span { color: var(--ts-signal); font-size: 10px; }
.mono { font-family: var(--mc-mono, monospace); }
.muted { color: var(--el-text-color-secondary); font-size: 10.5px; }
.state-text { color: var(--el-text-color-regular); font-size: 10.5px; }
.source-ref { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.eligibility {
  display: inline-flex; align-items: center; gap: 6px;
  color: var(--el-color-danger); font-size: 10.5px;
}
.eligibility i { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.eligibility.eligible { color: var(--el-color-success); }
.operational { display: inline-flex; align-items: center; gap: 6px; color: var(--el-text-color-secondary); font-size: 11px; }
.operational i { width: 6px; height: 6px; border-radius: 50%; background: var(--el-text-color-placeholder); }
.operational.live { color: var(--el-color-success); }
.operational.live i {
  background: var(--el-color-success);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--el-color-success) 16%, transparent);
}
.empty-state {
  position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center;
  justify-content: center; text-align: center; pointer-events: none;
}
.empty-state p { margin: 6px 0 14px; font-size: 12px; color: var(--el-text-color-secondary); }
.empty-state .el-button { pointer-events: auto; }
.review-unavailable {
  position: absolute; z-index: 4; top: 10px; right: 12px; left: 12px;
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  padding: 10px 12px; border: 1px solid color-mix(in srgb, var(--el-color-danger) 40%, var(--el-border-color-lighter));
  border-radius: 7px; background: color-mix(in srgb, var(--el-color-danger) 7%, var(--el-bg-color));
  box-shadow: 0 4px 12px color-mix(in srgb, var(--el-text-color-primary) 8%, transparent);
}
.review-unavailable strong { color: var(--el-color-danger); font: 600 10.5px var(--mc-mono, monospace); }
.review-unavailable p { margin: 3px 0 0; color: var(--el-text-color-regular); font-size: 10.5px; }

.inspector {
  min-width: 0; overflow-y: auto;
  background: color-mix(in srgb, var(--el-bg-color) 96%, var(--el-text-color-primary) 4%);
  transition: opacity 120ms ease;
}
.inspector.is-loading { opacity: .62; pointer-events: none; }
.inspector-empty {
  min-height: 65%; display: flex; flex-direction: column; align-items: center; justify-content: center;
  padding: 28px; text-align: center; color: var(--el-text-color-secondary);
}
.inspector-empty strong { color: var(--el-text-color-primary); font-size: 13px; }
.inspector-empty p { max-width: 280px; margin: 7px 0 0; font-size: 11.5px; line-height: 1.6; }
.empty-mark { margin-bottom: 14px; font: 24px var(--mc-mono, monospace); color: var(--el-text-color-placeholder); }
.inspector-body { padding: 18px 18px 28px; }
.inspector-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin-bottom: 14px; }
.eyebrow { color: var(--el-text-color-secondary); font: 10px var(--mc-mono, monospace); }
.inspector-head h2 { margin: 5px 0 4px; font-size: 17px; line-height: 1.45; }
.route-line { color: var(--ts-signal); font: 600 11.5px var(--mc-mono, monospace); }
.metadata { margin: 16px 0 0; display: grid; grid-template-columns: 1fr 1fr; border-top: 1px solid var(--el-border-color-lighter); }
.metadata div { padding: 9px 0; border-bottom: 1px solid var(--el-border-color-lighter); }
.metadata div:nth-child(odd) { padding-right: 12px; }
.metadata dt { color: var(--el-text-color-secondary); font: 10px var(--mc-mono, monospace); }
.metadata dd { margin: 4px 0 0; font-size: 11.5px; color: var(--el-text-color-primary); }
.review-metadata dd { overflow-wrap: anywhere; }
.contract-health, .json-section { margin-top: 18px; }
.section-title { display: flex; align-items: center; justify-content: space-between; margin-bottom: 9px; font-size: 12px; font-weight: 650; }
.warning-count { color: var(--el-color-warning); font-size: 10.5px; font-weight: 500; }
.counts { display: grid; grid-template-columns: repeat(4, 1fr); border: 1px solid var(--el-border-color-lighter); border-radius: 6px; overflow: hidden; }
.counts div { padding: 9px 6px; text-align: center; border-right: 1px solid var(--el-border-color-lighter); }
.counts div:last-child { border-right: 0; }
.counts b { display: block; font: 650 15px var(--mc-mono, monospace); }
.counts span { display: block; margin-top: 2px; color: var(--el-text-color-secondary); font-size: 9.5px; }
.review-warnings {
  margin: 8px 0 0; padding: 9px 11px 9px 27px; border-radius: 5px;
  background: color-mix(in srgb, var(--el-color-warning) 10%, var(--el-bg-color));
  color: var(--el-text-color-regular); font-size: 10.5px; line-height: 1.65;
}
.redline-note {
  margin-top: 14px; padding: 10px 11px; border-left: 2px solid var(--el-color-danger);
  background: color-mix(in srgb, var(--el-color-danger) 10%, var(--el-bg-color));
}
.redline-note strong { font-size: 11.5px; color: var(--el-color-danger); }
.redline-note p { margin: 4px 0 0; font-size: 10.5px; line-height: 1.6; color: var(--el-text-color-regular); }
.json-section pre {
  max-height: 330px; margin: 0; padding: 11px; overflow: auto; border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px; background: var(--el-bg-color); color: var(--el-text-color-regular);
  font: 10px/1.6 var(--mc-mono, monospace); white-space: pre-wrap; word-break: break-word;
}
.review-action {
  margin-top: 18px; padding-top: 14px; border-top: 1px solid var(--el-border-color-lighter);
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
}
.review-action strong { font-size: 12px; }
.review-action p { margin: 3px 0 0; color: var(--el-text-color-secondary); font-size: 10.5px; line-height: 1.5; }
.review-action > span { color: var(--el-text-color-secondary); font-size: 11px; }
.qualification-card,
.evidence-reference-card,
.candidate-detail-card,
.capability-boundary { margin-top: 18px; }
.qualification-card {
  padding: 12px; border: 1px solid color-mix(in srgb, var(--el-color-danger) 35%, var(--el-border-color-lighter));
  border-radius: 7px; background: color-mix(in srgb, var(--el-color-danger) 5%, var(--el-bg-color));
}
.gate-reasons { display: grid; gap: 8px; margin: 0; padding: 0; list-style: none; }
.gate-reasons li { display: grid; gap: 3px; }
.gate-reasons code { color: var(--el-color-danger); font: 9.5px var(--mc-mono, monospace); }
.gate-reasons span { color: var(--el-text-color-regular); font-size: 10.5px; line-height: 1.55; }
.reference-list { display: flex; flex-wrap: wrap; gap: 6px; }
.reference-list code {
  padding: 4px 6px; border: 1px solid var(--el-border-color-lighter); border-radius: 4px;
  background: var(--el-bg-color); color: var(--el-text-color-regular);
  font: 9.5px var(--mc-mono, monospace);
}
.empty-copy { margin: 0; color: var(--el-text-color-secondary); font-size: 10.5px; }
.draft-steps { display: grid; gap: 7px; margin: 10px 0 0; padding: 0; list-style: none; }
.draft-steps li {
  display: grid; grid-template-columns: 92px 1fr auto; align-items: center; gap: 8px;
  padding: 7px 8px; border-bottom: 1px solid var(--el-border-color-lighter);
}
.draft-steps code { color: var(--ts-signal); font: 9.5px var(--mc-mono, monospace); }
.draft-steps span { color: var(--el-text-color-regular); font-size: 10.5px; }
.draft-steps small { color: var(--el-text-color-placeholder); font: 9px var(--mc-mono, monospace); }
.compact-facts { display: grid; grid-template-columns: 1fr 1fr; margin: 0; gap: 0 14px; }
.compact-facts div { padding: 7px 0; border-bottom: 1px solid var(--el-border-color-lighter); }
.compact-facts dt { color: var(--el-text-color-secondary); font: 9.5px var(--mc-mono, monospace); }
.compact-facts dd { margin: 3px 0 0; overflow-wrap: anywhere; color: var(--el-text-color-primary); font-size: 10.5px; }
.reference-issues { display: grid; gap: 7px; margin: 10px 0 0; padding: 0; list-style: none; }
.reference-issues li {
  display: grid; gap: 3px; padding: 8px 9px; border-left: 2px solid var(--el-color-warning);
  background: color-mix(in srgb, var(--el-color-warning) 7%, var(--el-bg-color));
}
.reference-issues li.danger {
  border-left-color: var(--el-color-danger);
  background: color-mix(in srgb, var(--el-color-danger) 7%, var(--el-bg-color));
}
.reference-issues strong { color: var(--el-text-color-regular); font-size: 10.5px; }
.reference-issues code { overflow-wrap: anywhere; color: var(--el-text-color-secondary); font: 9.5px/1.5 var(--mc-mono, monospace); }
.comparison-pass { margin: 9px 0 0; color: var(--el-color-success); font-size: 10.5px; }
.candidate-summary { margin: 0; color: var(--el-text-color-primary); font-size: 11.5px; line-height: 1.65; }
.candidate-feedback {
  margin: 8px 0 0; padding: 8px 9px; border-left: 2px solid var(--ts-signal);
  background: color-mix(in srgb, var(--ts-signal) 7%, var(--el-bg-color));
  color: var(--el-text-color-regular); font-size: 10.5px; line-height: 1.55;
}
.manual-counts { margin-top: 10px; }
.capability-boundary {
  padding: 10px 11px; border-left: 2px solid var(--el-color-warning);
  background: color-mix(in srgb, var(--el-color-warning) 8%, var(--el-bg-color));
}
.capability-boundary strong { color: var(--el-color-warning); font-size: 11px; }
.capability-boundary ul { margin: 6px 0 0; padding-left: 17px; }
.capability-boundary li { margin: 3px 0; color: var(--el-text-color-regular); font-size: 10.5px; line-height: 1.55; }
.locked-review-action { align-items: flex-end; }
.review-action-buttons { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.review-audit-card {
  padding: 11px 12px; border: 1px solid color-mix(in srgb, var(--ts-signal) 30%, var(--el-border-color-lighter));
  border-radius: 7px; background: color-mix(in srgb, var(--ts-signal) 4%, var(--el-bg-color));
}
.audit-reason { margin-top: 10px; padding: 9px 10px; border-left: 2px solid var(--ts-signal); background: var(--el-bg-color); }
.audit-reason span { color: var(--el-text-color-secondary); font: 9.5px var(--mc-mono, monospace); }
.audit-reason p { margin: 4px 0 0; color: var(--el-text-color-primary); font-size: 10.5px; line-height: 1.6; white-space: pre-wrap; }
.register-note { margin-bottom: 12px; }
.register-note code, .validation code { font-family: var(--mc-mono, monospace); }
.json-input :deep(textarea) { font-family: var(--mc-mono, monospace); font-size: 11px; line-height: 1.55; }
.validation { min-height: 20px; margin-top: 9px; color: var(--el-color-danger); font-size: 11px; line-height: 1.5; }
.validation.valid { color: var(--el-color-success); }
.validation-dot { display: inline-block; width: 6px; height: 6px; margin-right: 5px; border-radius: 50%; background: currentColor; }

:deep(.el-table) {
  --el-table-row-hover-bg-color: color-mix(in srgb, var(--ts-signal) 7%, var(--el-bg-color));
}
:deep(.el-table__row) { cursor: pointer; transition: background-color 140ms ease; }
:deep(.selected-row > td.el-table__cell) {
  background: color-mix(in srgb, var(--ts-signal) 12%, var(--el-bg-color)) !important;
}
:deep(.selected-row > td:first-child) { box-shadow: inset 3px 0 0 var(--ts-signal); }
.inspector-enter-active, .inspector-leave-active { transition: opacity 150ms ease, transform 150ms ease; }
.inspector-enter-from { opacity: 0; transform: translateX(6px); }
.inspector-leave-to { opacity: 0; transform: translateX(-4px); }

:global(.sop-status-confirm) {
  --el-color-primary: #2f5cf5;
  --el-color-primary-light-3: color-mix(in srgb, #2f5cf5 70%, var(--el-bg-color));
  --el-color-primary-dark-2: color-mix(in srgb, #2f5cf5 80%, black);
}

@media (max-width: 1280px) {
  .sop-page { height: auto; min-height: 100%; overflow: auto; }
  .workspace {
    grid-template-columns: 1fr;
    grid-template-rows: 520px auto;
    align-content: start;
    overflow: visible;
  }
  .registry { height: 520px; border-right: 0; border-bottom: 1px solid var(--el-border-color-lighter); }
  .inspector { overflow: visible; }
  .lifecycle { display: none; }
}

@media (max-width: 720px) {
  .topbar { align-items: flex-start; flex-direction: column; }
  .top-actions { width: 100%; }
  .top-actions .el-button { flex: 1; }
  .filterbar { align-items: stretch; flex-wrap: wrap; }
  .filterbar .el-select, .filterbar .el-input { width: calc(50% - 4px) !important; }
  .registry-count { margin-left: auto; align-self: center; }
  :deep(.el-dialog) { max-width: calc(100vw - 32px); }
}

@media (prefers-reduced-motion: reduce) {
  :deep(.el-table__row), .inspector, .inspector-enter-active, .inspector-leave-active { transition: none; }
}
</style>
