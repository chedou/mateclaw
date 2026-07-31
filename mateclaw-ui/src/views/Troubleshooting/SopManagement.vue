<template>
  <div class="sop-page">
    <SopTopbar
      :active-desk="registry.activeDesk.value"
      :list-loading="registry.listLoading.value"
      :review-loading="review.reviewLoading.value"
      @back="router.push('/troubleshooting')"
      @reload="reload"
      @open-register="registry.openRegister()"
      @open-synthesis="synthesisOpen = true"
    />

    <SopFilterbar
      :active-desk="registry.activeDesk.value"
      :row-count="registry.rows.value.length"
      :knowledge-row-count="review.knowledgeRows.value.length"
      :status-filter="registry.statusFilter.value"
      :system-filter="registry.systemFilter.value"
      :origin-filter="review.originFilter.value"
      :review-query="review.reviewQuery.value"
      @update:active-desk="registry.activeDesk.value = $event"
      @update:status-filter="registry.statusFilter.value = $event"
      @update:system-filter="registry.systemFilter.value = $event"
      @update:origin-filter="review.originFilter.value = $event"
      @update:review-query="review.reviewQuery.value = $event"
      @apply-filters="registry.loadList()"
      @clear-filters="registry.clearFilters()"
    />

    <SopRegistryWorkspace
      v-if="registry.activeDesk.value === 'registry'"
      :rows="registry.rows.value"
      :selected-route-key="registry.selectedRouteKey.value"
      :selected-sop="registry.selectedSop.value"
      :selected-sop-summary="registry.selectedSopSummary.value"
      :list-loading="registry.listLoading.value"
      :detail-loading="registry.detailLoading.value"
      :status-updating="registry.statusUpdating.value"
      :next-status="registry.nextStatus.value"
      :pretty-contract="registry.prettyContract.value"
      :contains-manual-write="registry.containsManualWrite.value"
      :contract-warnings="registry.contractWarnings.value"
      :row-class-name="registry.rowClassName"
      :status-tag-type="registry.statusTagType"
      :status-label="registry.statusLabel"
      :format-time="registry.formatTime"
      @select-sop="registry.selectSop"
      @open-register="registry.openRegister()"
      @advance-status="registry.advanceStatus()"
      @open-review-for-version="openReviewForSelectedVersion"
      @deprecate-legacy="registry.deprecateLegacyVersion(review.askReviewReason)"
      @copy-contract="registry.copyContract()"
    />

    <SopReviewWorkspace
      v-else
      :filtered-rows="review.filteredKnowledgeRows.value"
      :knowledge-row-count="review.knowledgeRows.value.length"
      :selected-review-key="review.selectedReviewKey.value"
      :review-loading="review.reviewLoading.value"
      :manual-detail-loading="review.manualDetailLoading.value"
      :review-unavailable="review.reviewUnavailable.value"
      :review-decision-loading="review.reviewDecisionLoading.value"
      :capability-limits="review.reviewInbox.value.capabilityLimits"
      :selected-review="review.selectedReview.value"
      :selected-evidence-record="review.selectedEvidenceRecord.value"
      :selected-outcome-candidate="review.selectedOutcomeCandidate.value"
      :selected-manual-sop="review.selectedManualSop.value"
      :selected-comparison-issues="review.selectedComparisonIssues.value"
      :selected-review-snapshot-issues="review.selectedReviewSnapshotIssues.value"
      :row-class-name="review.reviewRowClassName"
      :origin-label="review.originLabel"
      :origin-tag-type="review.originTagType"
      :review-status-label="review.reviewStatusLabel"
      :eligibility-label="review.eligibilityLabel"
      :format-time="review.formatTime"
      :percent="review.percent"
      @select-review="review.selectReview"
      @retry-inbox="review.loadReviewInbox()"
      @start-review="review.startReview"
      @reject-review="review.rejectReview"
      @approve-review="handleApproveReview"
      @run-manual-replay="review.runManualReplay"
      @deprecate-review="handleDeprecateReview"
    />

    <SopRegisterDialog
      v-model="registry.registerOpen.value"
      :register-json="registry.registerJson.value"
      :registering="registry.registering.value"
      :import-validation="registry.importValidation.value"
      @update:register-json="registry.registerJson.value = $event"
      @register="registry.registerSop()"
      @load-template="registry.loadDeploymentTopologyTemplate()"
    />

    <SynthesisPreviewDialog v-model="synthesisOpen" />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { SopEntry } from '@/api'
import { useSopRegistry } from './useSopRegistry'
import { useKnowledgeReview } from './useKnowledgeReview'
import type { KnowledgeReviewRow } from './knowledgeReview'
import SynthesisPreviewDialog from './SynthesisPreviewDialog.vue'
import { isEvidenceSynthesisFocus } from './synthesisPreview'
import SopTopbar from './SopTopbar.vue'
import SopFilterbar from './SopFilterbar.vue'
import SopRegistryWorkspace from './SopRegistryWorkspace.vue'
import SopReviewWorkspace from './SopReviewWorkspace.vue'
import SopRegisterDialog from './SopRegisterDialog.vue'

const router = useRouter()
const route = useRoute()
const synthesisOpen = ref(isEvidenceSynthesisFocus(route.query.focus))

const registry = useSopRegistry()
const review = useKnowledgeReview()

// Wire cross-workspace refresh: registry actions (register) can refresh review inbox
registry.setRefreshReviewInbox(review.loadReviewInbox)

// Cross-workspace coordination: approve → refresh registry
function handleApproveReview(row: KnowledgeReviewRow) {
  review.approveReview(row, (selectorKey, playbook, _playbookVersion) => {
    if (registry.selectedRouteKey.value === selectorKey) {
      registry.selectedSop.value = playbook
    }
    registry.loadList()
  })
}

// Cross-workspace coordination: deprecate → refresh registry
function handleDeprecateReview(row: KnowledgeReviewRow) {
  review.deprecateReview(row, (selectorKey, playbook, playbookVersion) => {
    if (registry.selectedRouteKey.value === selectorKey) {
      registry.selectedSop.value = playbook
    }
    ElMessage.success(`Playbook v${playbookVersion} 已退出命中路`)
    registry.loadList()
  })
}

// Cross-workspace coordination: navigate from registry inspector to review workspace
async function openReviewForSelectedVersion() {
  const summary = registry.selectedSopSummary.value
  if (!summary?.reviewId
    || !summary.sourceOrigin
    || summary.sourceOrigin === 'LEGACY'
    || !summary.sourceRecordId) return
  registry.activeDesk.value = 'review'
  review.originFilter.value = ''
  review.reviewQuery.value = ''
  await review.loadReviewInbox()
  const row = review.knowledgeRows.value.find((candidate) =>
    candidate.origin === summary.sourceOrigin
      && candidate.recordId === summary.sourceRecordId)
  if (!row) {
    ElMessage.warning('原审核来源已不在当前候选读取范围，请刷新后核对审计记录')
    return
  }
  await review.selectReview(row)
}

// Unified reload dispatches to the active workspace
async function reload() {
  if (registry.activeDesk.value === 'review') {
    await review.loadReviewInbox()
    return
  }
  await registry.reload()
}
</script>

<style scoped>
.sop-page {
  --el-color-primary: var(--mc-primary);
  --el-color-primary-light-3: color-mix(in srgb, var(--mc-primary) 70%, var(--el-bg-color));
  --el-color-primary-light-5: color-mix(in srgb, var(--mc-primary) 50%, var(--el-bg-color));
  --el-color-primary-light-7: color-mix(in srgb, var(--mc-primary) 30%, var(--el-bg-color));
  --el-color-primary-light-8: color-mix(in srgb, var(--mc-primary) 20%, var(--el-bg-color));
  --el-color-primary-light-9: color-mix(in srgb, var(--mc-primary) 10%, var(--el-bg-color));
  --el-color-primary-dark-2: color-mix(in srgb, var(--mc-primary) 80%, black);
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

:global(.sop-status-confirm) {
  --el-color-primary: var(--mc-primary);
  --el-color-primary-light-3: color-mix(in srgb, var(--mc-primary) 70%, var(--el-bg-color));
  --el-color-primary-dark-2: color-mix(in srgb, var(--mc-primary) 80%, black);
}

@media (max-width: 1280px) {
  .sop-page { height: auto; min-height: 100%; overflow: auto; }
}

@media (max-width: 720px) {
  :deep(.el-dialog) { max-width: calc(100vw - 32px); }
}
</style>
