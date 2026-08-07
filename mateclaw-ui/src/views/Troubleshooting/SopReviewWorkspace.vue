<template>
  <div class="workspace review-workspace">
    <section class="registry" aria-label="知识候选审阅队列">
      <el-table
        :data="filteredRows"
        :aria-busy="reviewLoading"
        row-key="key"
        height="100%"
        :row-class-name="rowClassName"
        @row-click="(row: KnowledgeReviewRow) => $emit('select-review', row)"
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
            {{ knowledgeRowCount
              ? '无法刷新，当前继续展示上一次成功快照；请勿据此判断候选已清空。'
              : '无法读取持久化候选；当前状态不是"零候选"。' }}
          </p>
        </div>
        <el-button size="small" type="danger" plain @click="$emit('retryInbox')">重试</el-button>
      </div>
      <div v-else-if="!reviewLoading && !filteredRows.length" class="empty-state">
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
            <div><dt>phase</dt><dd class="mono">{{ selectedReview.qualificationSnapshot.qualificationPhase }}</dd></div>
            <div><dt>eligibility</dt><dd class="mono">{{ selectedReview.approvalEligibility }}</dd></div>
            <div><dt>service</dt><dd>{{ selectedReview.service || '未填写' }}</dd></div>
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
              <div><dt>snapshot phase</dt><dd class="mono">{{ selectedReview.reviewState.snapshot.qualificationPhase }}</dd></div>
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
              <el-tag
                :type="selectedReview.approvalEligibility === 'ELIGIBLE_FOR_APPROVAL' ? 'success' : 'danger'"
                size="small"
                effect="plain"
              >
                {{ eligibilityLabel(selectedReview.approvalEligibility) }}
              </el-tag>
            </div>
            <ul class="gate-reasons">
              <li v-for="reason in selectedReview.eligibilityReasons" :key="reason">
                <code>{{ reason }}</code>
                <span>{{ reviewReasonLabel(reason) }}</span>
              </li>
            </ul>
            <ul
              v-if="selectedReview.qualificationSnapshot.validationErrors.length"
              class="reference-issues qualification-errors"
            >
              <li
                v-for="issue in selectedReview.qualificationSnapshot.validationErrors"
                :key="issue.code + ':' + issue.fieldPath"
                class="danger"
              >
                <strong>{{ issue.code }} · {{ issue.fieldPath }}</strong>
                <code>{{ issue.message }}</code>
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
            <p v-else class="empty-copy">当前排障规则没有可审计引用。</p>
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
                <div><dt>knowledge owner</dt><dd>{{ selectedOutcomeCandidate.ownerTeam || missingKnowledgeOwnerLabel(selectedOutcomeCandidate) }}</dd></div>
                <div><dt>outcome proof</dt><dd class="mono">{{ selectedOutcomeCandidate.outcomeProof?.outcome || missingOutcomeProofLabel(selectedOutcomeCandidate) }}</dd></div>
                <div><dt>recovery verified</dt><dd class="mono">{{ selectedOutcomeCandidate.outcomeProof?.recoveryVerified ?? missingOutcomeProofLabel(selectedOutcomeCandidate) }}</dd></div>
                <div><dt>outcome registered</dt><dd class="mono">{{ selectedOutcomeCandidate.outcomeProof ? formatTime(selectedOutcomeCandidate.outcomeProof.registeredAt) : missingOutcomeProofLabel(selectedOutcomeCandidate) }}</dd></div>
                <div><dt>created by</dt><dd>{{ selectedOutcomeCandidate.createdBy }}</dd></div>
                <div><dt>recommended actions</dt><dd>{{ selectedOutcomeCandidate.recommendedActions.length }}</dd></div>
                <div><dt>recorded outcomes</dt><dd>{{ selectedOutcomeCandidate.actionOutcomes.length }}</dd></div>
              </dl>
            </section>
          </template>

          <template v-else-if="selectedManualSop">
            <section class="candidate-detail-card">
              <div class="section-title"><span>人工登记的排障规则</span></div>
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
            <section
              v-if="selectedReview.qualificationSnapshot.manualReplay"
              class="candidate-detail-card"
            >
              <div class="section-title">
                <span>固定回放证明</span>
                <el-tag
                  :type="selectedReview.qualificationSnapshot.manualReplay.status === 'PASSED' ? 'success' : 'danger'"
                  size="small"
                  effect="plain"
                >{{ selectedReview.qualificationSnapshot.manualReplay.status }}</el-tag>
              </div>
              <p class="candidate-summary">
                只证明这一份候选通过当前服务端固定套件；不等于 Guance T7/T8 真源验收，也不会自动批准或进入命中路。
              </p>
              <dl class="compact-facts replay-facts">
                <div><dt>suite</dt><dd class="mono">{{ selectedReview.qualificationSnapshot.manualReplay.suiteId }} / v{{ selectedReview.qualificationSnapshot.manualReplay.suiteVersion }}</dd></div>
                <div><dt>正例</dt><dd>{{ selectedReview.qualificationSnapshot.manualReplay.positivePassed }} / {{ selectedReview.qualificationSnapshot.manualReplay.positiveTotal }}</dd></div>
                <div><dt>负例 / 弃权</dt><dd>{{ selectedReview.qualificationSnapshot.manualReplay.negativeOrAbstainPassed }} / {{ selectedReview.qualificationSnapshot.manualReplay.negativeOrAbstainTotal }}</dd></div>
                <div><dt>执行人</dt><dd>{{ selectedReview.qualificationSnapshot.manualReplay.executedBy }}</dd></div>
                <div><dt>执行时间</dt><dd class="mono">{{ formatTime(selectedReview.qualificationSnapshot.manualReplay.executedAt) }}</dd></div>
                <div><dt>fixture</dt><dd class="mono">{{ selectedReview.qualificationSnapshot.manualReplay.fixtureMode }}</dd></div>
              </dl>
              <div class="replay-fingerprint">
                <span>candidate SHA-256</span>
                <code>{{ selectedReview.qualificationSnapshot.manualReplay.candidateFingerprint }}</code>
                <span>suite SHA-256</span>
                <code>{{ selectedReview.qualificationSnapshot.manualReplay.suiteFingerprint }}</code>
              </div>
              <ul
                v-if="selectedReview.qualificationSnapshot.manualReplay.failureCodes.length"
                class="reference-issues qualification-errors"
              >
                <li
                  v-for="failure in selectedReview.qualificationSnapshot.manualReplay.failureCodes"
                  :key="failure"
                  class="danger"
                ><strong>{{ failure }}</strong></li>
              </ul>
            </section>
          </template>

          <section class="capability-boundary">
            <strong>当前能力边界</strong>
            <ul>
              <li v-for="limit in capabilityLimits" :key="limit">
                {{ reviewReasonLabel(limit) }}
              </li>
            </ul>
          </section>

          <div class="review-action locked-review-action">
            <div>
              <strong v-if="selectedReview.reviewStatus === 'CANDIDATE'">开始独立审阅</strong>
              <strong v-else-if="selectedReview.reviewStatus === 'IN_REVIEW'">记录审阅决策</strong>
              <strong v-else-if="selectedReview.reviewStatus === 'APPROVED'">退役当前权威版本</strong>
              <strong v-else>审阅决策已固化</strong>
              <p v-if="selectedReview.reviewStatus === 'CANDIDATE'">将当前校验、参考解法与模型版本冻结进审核台账；不会晋升知识。</p>
              <p v-else-if="selectedReview.reviewStatus === 'IN_REVIEW' && selectedReview.approvalEligibility === 'ELIGIBLE_FOR_APPROVAL'">批准时服务端会重读资格，并以开始审阅时冻结的旧权威版本做并发校验；成功后创建新版本。</p>
              <p v-else-if="selectedReview.reviewStatus === 'IN_REVIEW'">当前仍有资格缺口；可以拒绝，但不能由人工按钮证明缺失的 owner、回放或 outcome。</p>
              <p v-else-if="selectedReview.reviewStatus === 'APPROVED'">只有该审核创建的版本仍占有 selector 时才能退役；服务端会校验精确 review 版本并保留审计原因。</p>
              <p v-else>决策不能原地重开；修正后应产生新的 source record。</p>
            </div>
            <div class="review-action-buttons">
              <el-button
                v-if="selectedReview.origin === 'MANUAL'
                  && (selectedReview.reviewStatus === 'CANDIDATE'
                    || selectedReview.reviewStatus === 'IN_REVIEW')"
                plain
                :loading="reviewDecisionLoading === `replay:${selectedReview.key}`"
                @click="$emit('runManualReplay', selectedReview)"
              >运行固定回放</el-button>
              <el-button
                v-if="selectedReview.reviewStatus === 'CANDIDATE'"
                type="primary"
                :loading="reviewDecisionLoading === `start:${selectedReview.key}`"
                @click="$emit('startReview', selectedReview)"
              >开始审阅</el-button>
              <el-button
                v-else-if="selectedReview.reviewStatus === 'IN_REVIEW'"
                type="danger"
                plain
                :loading="reviewDecisionLoading === `reject:${selectedReview.key}`"
                @click="$emit('rejectReview', selectedReview)"
              >拒绝候选</el-button>
              <el-button
                v-else-if="selectedReview.reviewStatus === 'APPROVED'"
                type="danger"
                plain
                :loading="reviewDecisionLoading === `deprecate:${selectedReview.key}`"
                @click="$emit('deprecateReview', selectedReview)"
              >退役当前版本</el-button>
              <el-button
                v-if="selectedReview.reviewStatus === 'IN_REVIEW'"
                type="success"
                :disabled="selectedReview.approvalEligibility !== 'ELIGIBLE_FOR_APPROVAL'"
                :loading="reviewDecisionLoading === `approve:${selectedReview.key}`"
                @click="$emit('approveReview', selectedReview)"
              >批准并创建新版本</el-button>
            </div>
          </div>
        </div>
      </Transition>
    </aside>
  </div>
</template>

<script setup lang="ts">
import type { KnowledgeCandidate, KnowledgeOrigin, PlaybookKnowledgeRecord, SopEntry } from '@/api'
import {
  missingKnowledgeOwnerLabel,
  missingOutcomeProofLabel,
  reviewReasonLabel,
  type KnowledgeReviewRow,
  type ReferenceComparisonIssue,
} from './knowledgeReview'

defineProps<{
  filteredRows: KnowledgeReviewRow[]
  knowledgeRowCount: number
  selectedReviewKey: string | null
  reviewLoading: boolean
  manualDetailLoading: boolean
  reviewUnavailable: boolean
  reviewDecisionLoading: string | null
  capabilityLimits: string[]
  selectedReview: KnowledgeReviewRow | null
  selectedEvidenceRecord: PlaybookKnowledgeRecord | null
  selectedOutcomeCandidate: KnowledgeCandidate | null
  selectedManualSop: SopEntry | null
  selectedComparisonIssues: ReferenceComparisonIssue[]
  selectedReviewSnapshotIssues: ReferenceComparisonIssue[]
  rowClassName: (args: { row: KnowledgeReviewRow }) => string
  originLabel: (origin: KnowledgeOrigin) => string
  originTagType: (origin: KnowledgeOrigin) => 'primary' | 'success' | 'warning'
  reviewStatusLabel: (status: string) => string
  eligibilityLabel: (eligibility: string) => string
  formatTime: (value?: string | null) => string
  percent: (value: number) => string
}>()

defineEmits<{
  'select-review': [row: KnowledgeReviewRow]
  'retryInbox': []
  'startReview': [row: KnowledgeReviewRow]
  'rejectReview': [row: KnowledgeReviewRow]
  'approveReview': [row: KnowledgeReviewRow]
  'runManualReplay': [row: KnowledgeReviewRow]
  'deprecateReview': [row: KnowledgeReviewRow]
}>()
</script>

<style scoped>
.workspace { flex: 1; min-height: 0; display: grid; grid-template-columns: minmax(680px, 1fr) 480px; }
.review-workspace { grid-template-columns: minmax(680px, 1fr) 480px; }
.registry { min-width: 0; min-height: 0; position: relative; border-right: 1px solid var(--el-border-color-lighter); }
.review-title-cell { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.review-title-cell strong {
  overflow: hidden; color: var(--el-text-color-primary); font-size: 11.5px;
  font-weight: 600; text-overflow: ellipsis; white-space: nowrap;
}
.review-title-cell span { color: var(--mc-primary); font-size: 10px; }
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
.route-line { color: var(--mc-primary); font: 600 11.5px var(--mc-mono, monospace); }
.metadata { margin: 16px 0 0; display: grid; grid-template-columns: 1fr 1fr; border-top: 1px solid var(--el-border-color-lighter); }
.metadata div { padding: 9px 0; border-bottom: 1px solid var(--el-border-color-lighter); }
.metadata div:nth-child(odd) { padding-right: 12px; }
.metadata dt { color: var(--el-text-color-secondary); font: 10px var(--mc-mono, monospace); }
.metadata dd { margin: 4px 0 0; font-size: 11.5px; color: var(--el-text-color-primary); }
.review-metadata dd { overflow-wrap: anywhere; }
.section-title { display: flex; align-items: center; justify-content: space-between; margin-bottom: 9px; font-size: 12px; font-weight: 650; }
.counts { display: grid; grid-template-columns: repeat(4, 1fr); border: 1px solid var(--el-border-color-lighter); border-radius: 6px; overflow: hidden; }
.counts div { padding: 9px 6px; text-align: center; border-right: 1px solid var(--el-border-color-lighter); }
.counts div:last-child { border-right: 0; }
.counts b { display: block; font: 650 15px var(--mc-mono, monospace); }
.counts span { display: block; margin-top: 2px; color: var(--el-text-color-secondary); font-size: 9.5px; }
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
.draft-steps code { color: var(--mc-primary); font: 9.5px var(--mc-mono, monospace); }
.draft-steps span { color: var(--el-text-color-regular); font-size: 10.5px; }
.draft-steps small { color: var(--el-text-color-placeholder); font: 9px var(--mc-mono, monospace); }
.comparison-pass { margin: 9px 0 0; color: var(--el-color-success); font-size: 10.5px; }
.candidate-summary { margin: 0; color: var(--el-text-color-primary); font-size: 11.5px; line-height: 1.65; }
.candidate-feedback {
  margin: 8px 0 0; padding: 8px 9px; border-left: 2px solid var(--mc-primary);
  background: color-mix(in srgb, var(--mc-primary) 7%, var(--el-bg-color));
  color: var(--el-text-color-regular); font-size: 10.5px; line-height: 1.55;
}
.manual-counts { margin-top: 10px; }
.replay-facts { margin-bottom: 10px; }
.replay-fingerprint { display: grid; gap: 4px; }
.replay-fingerprint span { color: var(--el-text-color-secondary); font: 9px var(--mc-mono, monospace); }
.replay-fingerprint code {
  overflow-wrap: anywhere; color: var(--el-text-color-regular);
  font: 9px/1.5 var(--mc-mono, monospace);
}
.capability-boundary {
  padding: 10px 11px; border-left: 2px solid var(--el-color-warning);
  background: color-mix(in srgb, var(--el-color-warning) 8%, var(--el-bg-color));
}
.capability-boundary strong { color: var(--el-color-warning); font-size: 11px; }
.capability-boundary ul { margin: 6px 0 0; padding-left: 17px; }
.capability-boundary li { margin: 3px 0; color: var(--el-text-color-regular); font-size: 10.5px; line-height: 1.55; }
.locked-review-action { align-items: flex-end; }
.review-action {
  margin-top: 18px; padding-top: 14px; border-top: 1px solid var(--el-border-color-lighter);
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
}
.review-action strong { font-size: 12px; }
.review-action p { margin: 3px 0 0; color: var(--el-text-color-secondary); font-size: 10.5px; line-height: 1.5; }
.review-action-buttons { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.review-audit-card {
  padding: 11px 12px; border: 1px solid color-mix(in srgb, var(--mc-primary) 30%, var(--el-border-color-lighter));
  border-radius: 7px; background: color-mix(in srgb, var(--mc-primary) 4%, var(--el-bg-color));
}
.audit-reason { margin-top: 10px; padding: 9px 10px; border-left: 2px solid var(--mc-primary); background: var(--el-bg-color); }
.audit-reason span { color: var(--el-text-color-secondary); font: 9.5px var(--mc-mono, monospace); }
.audit-reason p { margin: 4px 0 0; color: var(--el-text-color-primary); font-size: 10.5px; line-height: 1.6; white-space: pre-wrap; }

:deep(.el-table) {
  --el-table-row-hover-bg-color: color-mix(in srgb, var(--mc-primary) 7%, var(--el-bg-color));
}
:deep(.el-table__row) { cursor: pointer; transition: background-color 140ms ease; }
:deep(.selected-row > td.el-table__cell) {
  background: color-mix(in srgb, var(--mc-primary) 12%, var(--el-bg-color)) !important;
}
:deep(.selected-row > td:first-child) { box-shadow: inset 3px 0 0 var(--mc-primary); }
.inspector-enter-active, .inspector-leave-active { transition: opacity 150ms ease, transform 150ms ease; }
.inspector-enter-from { opacity: 0; transform: translateX(6px); }
.inspector-leave-to { opacity: 0; transform: translateX(-4px); }

@media (max-width: 1280px) {
  .workspace {
    grid-template-columns: 1fr;
    grid-template-rows: 520px auto;
    align-content: start;
    overflow: visible;
  }
  .registry { height: 520px; border-right: 0; border-bottom: 1px solid var(--el-border-color-lighter); }
  .inspector { overflow: visible; }
}

@media (prefers-reduced-motion: reduce) {
  :deep(.el-table__row), .inspector, .inspector-enter-active, .inspector-leave-active { transition: none; }
}
</style>
