import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { copyToClipboard } from '@/utils/clipboard'
import {
  troubleshootingApi,
  type SopEntry,
  type KnowledgeEvidenceCoverage,
  type SopStatus,
  type SopSummary,
} from '@/api'
import { findScopedSopSummary, nextSopStatus, parseCandidateSopJson } from './sopRegistry'

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

export function useSopRegistry(initialScope: {
  initialSystem?: string
  initialService?: string
} = {}) {
  const activeDesk = ref<'registry' | 'review'>('registry')
  const rows = ref<SopSummary[]>([])
  const evidenceCoverage = ref<KnowledgeEvidenceCoverage | null>(null)
  const selectedSop = ref<SopEntry | null>(null)
  const selectedRouteKey = ref<string | null>(null)
  const statusFilter = ref<SopStatus | ''>('')
  const initialSystem = initialScope.initialSystem?.trim() || ''
  const initialService = initialScope.initialService?.trim() || ''
  const systemFilter = ref(initialSystem)
  const listLoading = ref(false)
  const detailLoading = ref(false)
  const statusUpdating = ref(false)
  const registerOpen = ref(false)
  const registering = ref(false)
  const registerJson = ref(EMPTY_TEMPLATE)

  /**
   * Placeholder for cross-workspace refresh.
   * The parent assembly layer overrides this with `review.loadReviewInbox`
   * so that registry actions (register, approve) can refresh the review inbox.
   */
  let refreshReviewInbox: () => Promise<unknown> = async () => {}

  const nextStatus = computed(() => selectedSop.value
    ? nextSopStatus(selectedSop.value.status)
    : null)
  const selectedSopSummary = computed(() => rows.value.find(
    (row) => row.routeKey === selectedRouteKey.value,
  ) ?? null)

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
    if (!sop.anomalyCriteria.length) warnings.push('没有异常判据；当前排障规则无法形成可解释的信号。')
    if (!sop.diagnosisRules.length) warnings.push('没有诊断规则；当前排障规则不能产出确定性根因。')
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

  let detailRequest = 0
  let initialScopePending = Boolean(initialSystem && initialService)

  function clearSelection() {
    detailRequest += 1
    selectedRouteKey.value = null
    selectedSop.value = null
    detailLoading.value = false
  }

  async function loadList() {
    listLoading.value = true
    try {
      const [listResult, coverageResult] = await Promise.allSettled([
        troubleshootingApi.listSops({
          status: statusFilter.value || undefined,
          system: systemFilter.value.trim() || undefined,
          limit: 500,
        }),
        troubleshootingApi.knowledgeEvidenceCoverage(),
      ])
      if (listResult.status === 'rejected') throw listResult.reason
      rows.value = listResult.value.data ?? []
      evidenceCoverage.value = coverageResult.status === 'fulfilled'
        ? coverageResult.value.data
        : null
      if (initialScopePending) {
        initialScopePending = false
        const scoped = findScopedSopSummary(rows.value, initialSystem, initialService)
        if (scoped) await selectSop(scoped)
        else clearSelection()
        return
      }
      const selected = rows.value.find((row) => row.routeKey === selectedRouteKey.value)
      if (selected) return
      if (rows.value.length) {
        await selectSop(rows.value[0])
      } else {
        clearSelection()
      }
    } finally {
      listLoading.value = false
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

  async function reload() {
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
      await refreshReviewInbox()
      ElMessage.success(`已注册候选 SOP ${data.system}:${data.errorCode}`)
    } finally {
      registering.value = false
    }
  }

  async function advanceStatus() {
    const sop = selectedSop.value
    const target = nextStatus.value
    if (!sop || target !== 'deprecated') return
    if (selectedSopSummary.value?.playbookVersion != null) {
      ElMessage.warning('版本化 Playbook 必须从原审核记录退役')
      return
    }
    const title = `标记 ${sop.system}:${sop.errorCode} 为过期？`
    const message = '过期后，该版本立即退出命中路且不能恢复；替代版本必须重新通过版本审核流程。'
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

  async function deprecateLegacyVersion(askReason: (
    title: string, placeholder: string, confirmText: string,
  ) => Promise<string | null>) {
    const summary = selectedSopSummary.value
    if (summary?.sourceOrigin !== 'LEGACY'
      || summary.playbookVersion == null
      || !summary.sopId) return
    const reason = await askReason(
      `退役迁移 Playbook v${summary.playbookVersion}`,
      '例：迁移规则已被固定反例证明不再安全',
      '确认退役迁移版本',
    )
    if (!reason) return
    statusUpdating.value = true
    try {
      const { data } = await troubleshootingApi.deprecateLegacyPlaybook(
        summary.sopId,
        { expectedPlaybookVersion: summary.playbookVersion, reason },
      )
      selectedSop.value = data.playbook
      await loadList()
      ElMessage.success(`迁移 Playbook v${data.playbookVersion} 已退出命中路`)
    } finally {
      statusUpdating.value = false
    }
  }

  function rowClassName({ row }: { row: SopSummary }) {
    return row.routeKey === selectedRouteKey.value ? 'selected-row' : ''
  }

  function statusTagType(status: SopStatus): 'warning' | 'success' | 'info' {
    if (status === 'candidate') return 'warning'
    if (status === 'approved') return 'success'
    return 'info'
  }

  function statusLabel(status: SopStatus) {
    return STATUS_LABEL[status]
  }

  function formatTime(value?: string | null) {
    if (!value) return '—'
    return value.replace('T', ' ').replace(/\.\d+Z?$/, '').slice(0, 19)
  }

  function loadDeploymentTopologyTemplate() {
    return troubleshootingApi.manualKnowledgeCandidateExample(
      'csdp:scenario:deployment_topology_probe',
    ).then(({ data }) => {
      registerJson.value = JSON.stringify(data, null, 2)
    })
  }

  onMounted(() => loadList())

  return {
    // state
    activeDesk,
    rows,
    evidenceCoverage,
    selectedSop,
    selectedRouteKey,
    statusFilter,
    systemFilter,
    listLoading,
    detailLoading,
    statusUpdating,
    registerOpen,
    registering,
    registerJson,
    // computed
    nextStatus,
    selectedSopSummary,
    prettyContract,
    containsManualWrite,
    contractWarnings,
    importValidation,
    // methods
    loadList,
    selectSop,
    reload,
    clearFilters,
    clearSelection,
    openRegister,
    registerSop,
    advanceStatus,
    copyContract,
    deprecateLegacyVersion,
    loadDeploymentTopologyTemplate,
    // utils
    rowClassName,
    statusTagType,
    statusLabel,
    formatTime,
    // cross-workspace hook
    setRefreshReviewInbox(fn: () => Promise<unknown>) {
      refreshReviewInbox = fn
    },
    // constants
    SOP_STATUSES,
    STATUS_LABEL,
  }
}
