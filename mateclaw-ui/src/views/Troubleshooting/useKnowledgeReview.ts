import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  troubleshootingApi,
  type KnowledgeOrigin,
  type KnowledgeReviewInbox,
  type KnowledgeReviewState,
  type SopEntry,
} from '@/api'
import {
  buildKnowledgeReviewRows,
  filterKnowledgeReviewRows,
  missingKnowledgeOwnerLabel,
  missingOutcomeProofLabel,
  referenceComparisonIssues,
  reviewReasonLabel,
  type KnowledgeReviewRow,
} from './knowledgeReview'

export function useKnowledgeReview() {
  const reviewInbox = ref<KnowledgeReviewInbox>({
    evidenceDerived: [],
    outcomeBacked: [],
    manual: [],
    sourceStates: [],
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

  let manualDetailRequest = 0

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

  async function loadReviewInbox() {
    reviewLoading.value = true
    try {
      const { data } = await troubleshootingApi.knowledgeReviewInbox({ limit: 200 })
      reviewInbox.value = data ?? {
        evidenceDerived: [], outcomeBacked: [], manual: [], sourceStates: [],
        reviewStates: [], capabilityLimits: [],
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
      const { data } = await troubleshootingApi.getSopById(row.source.summary.sopId)
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
        '请写明本次审阅的事实依据或决策原因。审核人将从当前登录账号记录；请勿粘贴凭据、DQL、原始日志或堆栈。',
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

  async function approveReview(
    row: KnowledgeReviewRow,
    onApproved: (selectorKey: string, playbook: SopEntry, playbookVersion: number) => void,
  ) {
    if (row.reviewStatus !== 'IN_REVIEW'
      || row.reviewVersion < 1
      || row.approvalEligibility !== 'ELIGIBLE_FOR_APPROVAL') return
    const reason = await askReviewReason(
      `批准 ${row.selector} / review v${row.reviewVersion}`,
      '例：服务端资格已通过，确认以当前候选创建新的权威版本',
      '批准并创建新版本',
    )
    if (!reason) return
    reviewDecisionLoading.value = `approve:${row.key}`
    try {
      const { data } = await troubleshootingApi.approveKnowledgeReview(
        row.origin,
        row.recordId,
        { expectedVersion: row.reviewVersion, reason },
      )
      upsertReviewState(data.review)
      reviewUnavailable.value = false
      onApproved(data.approvedVersion.selectorKey, data.approvedVersion.playbook, data.approvedVersion.playbookVersion)
      ElMessage.success(`已创建 Playbook v${data.approvedVersion.playbookVersion}`)
    } finally {
      reviewDecisionLoading.value = null
    }
  }

  async function runManualReplay(row: KnowledgeReviewRow) {
    if (row.origin !== 'MANUAL'
      || (row.reviewStatus !== 'CANDIDATE' && row.reviewStatus !== 'IN_REVIEW')) return
    reviewDecisionLoading.value = `replay:${row.key}`
    try {
      const { data } = await troubleshootingApi.replayManualKnowledgeCandidate(row.recordId)
      await loadReviewInbox()
      if (data.status === 'PASSED') {
        ElMessage.success(
            `固定回放通过：正例 ${data.positivePassed}/${data.positiveTotal}，`
            + `负例/弃权 ${data.negativeOrAbstainPassed}/${data.negativeOrAbstainTotal}`,
        )
      } else {
        ElMessage.warning(`固定回放未通过：${data.failureCodes.join('、')}`)
      }
    } finally {
      reviewDecisionLoading.value = null
    }
  }

  async function deprecateReview(
    row: KnowledgeReviewRow,
    onDeprecated: (selectorKey: string, playbook: SopEntry, playbookVersion: number) => void,
  ) {
    if (row.reviewStatus !== 'APPROVED' || row.reviewVersion < 2) return
    const reason = await askReviewReason(
      `退役 ${row.selector} / review v${row.reviewVersion}`,
      '例：固定反例回放证明该版本会产生错误命中',
      '确认退役当前版本',
    )
    if (!reason) return
    reviewDecisionLoading.value = `deprecate:${row.key}`
    try {
      const { data } = await troubleshootingApi.deprecateKnowledgeReview(
        row.origin,
        row.recordId,
        { expectedVersion: row.reviewVersion, reason },
      )
      upsertReviewState(data.review)
      reviewUnavailable.value = false
      onDeprecated(data.deprecatedVersion.selectorKey, data.deprecatedVersion.playbook, data.deprecatedVersion.playbookVersion)
      ElMessage.success(`Playbook v${data.deprecatedVersion.playbookVersion} 已退出命中路`)
    } finally {
      reviewDecisionLoading.value = null
    }
  }

  function reviewRowClassName({ row }: { row: KnowledgeReviewRow }) {
    return row.key === selectedReviewKey.value ? 'selected-row' : ''
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

  function formatTime(value?: string | null) {
    if (!value) return '—'
    return value.replace('T', ' ').replace(/\.\d+Z?$/, '').slice(0, 19)
  }

  function percent(value: number) {
    return `${Math.round(value * 100)}%`
  }

  onMounted(() => loadReviewInbox())

  return {
    // state
    reviewInbox,
    reviewLoading,
    reviewUnavailable,
    reviewDecisionLoading,
    originFilter,
    reviewQuery,
    selectedReviewKey,
    selectedManualSop,
    manualDetailLoading,
    // computed
    knowledgeRows,
    filteredKnowledgeRows,
    selectedReview,
    selectedEvidenceRecord,
    selectedOutcomeCandidate,
    selectedComparisonIssues,
    selectedReviewSnapshotIssues,
    // methods
    loadReviewInbox,
    selectReview,
    startReview,
    rejectReview,
    approveReview,
    runManualReplay,
    deprecateReview,
    askReviewReason,
    // utils
    reviewRowClassName,
    originLabel,
    originTagType,
    reviewStatusLabel,
    eligibilityLabel,
    reviewReasonLabel,
    missingKnowledgeOwnerLabel,
    missingOutcomeProofLabel,
    formatTime,
    percent,
  }
}
