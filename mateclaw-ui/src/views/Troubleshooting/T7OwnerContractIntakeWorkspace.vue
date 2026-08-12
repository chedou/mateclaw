<template>
  <CapabilityWorkspaceShell
    eyebrow="智能排障"
    title="标准查登记"
    description="把重点故障登记成可查清单（先做 10 条）。登记齐了只表示草稿可用，还不能点正式验收；仓库完整验收仍要 20 条。"
    @back="returnToWorkbench"
    @refresh="reloadFromTemplate"
  >
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      class="page-alert"
      title="这是登记表，不是验收按钮"
      description="不要把已有的 message-send / CTI创建会话 / ITGW 那三套查询名填进别的故障。历史时间按此前演示日锚定草稿，正式上线前请用各故障自己的告警再核对。"
    />

    <div class="status-bar">
      <div class="status-metrics">
        <span>首批 {{ selectedCount }} / {{ minSelected }}</span>
        <span>字段完整 {{ completeCount }} / {{ selectedCount }}</span>
        <span :class="validationTone">{{ validationLabel }}</span>
      </div>
      <div class="status-actions">
        <el-button type="success" plain @click="fillFirstBatchDrafts">填充首批开发草稿</el-button>
        <el-button @click="confirmReload">重置模板</el-button>
        <el-button @click="triggerImport">导入 JSON</el-button>
        <el-button @click="exportDocument">导出 JSON</el-button>
        <el-button type="primary" @click="runValidation">运行校验</el-button>
        <input
          ref="fileInput"
          class="hidden-file"
          type="file"
          accept="application/json,.json"
          @change="onImportFile"
        >
      </div>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="page-alert"
      title="已按演示信息预填首批 10 条"
      description="消息域锚定 2026-07-31 SendMsg 真源日；CTI 域锚定 2026-08-07 17:24+08 演示日；微信工单锚定 2026-08-07 17:12+08 ITGW 同日窗口。来源编号带 draft-anchor: 前缀，便于你之后换成真实告警号。"
    />

    <el-alert
      v-if="validationResult"
      class="page-alert"
      :type="validationResult.ok ? 'success' : 'error'"
      :closable="false"
      show-icon
      :title="validationResult.ok ? 'PREPARED_NOT_EXECUTABLE' : '校验未通过'"
    >
      <template v-if="validationResult.ok">
        <p>
          selectedCount={{ validationResult.selectedCount }}；
          canAcceptT7={{ validationResult.canAcceptT7 }}；
          canWriteRuntimeCatalog={{ validationResult.canWriteRuntimeCatalog }}。
          请把导出的 JSON 交给开发写 catalog，再用 Python 工具做最终核验。
        </p>
      </template>
      <ul v-else class="issue-list">
        <li v-for="issue in validationResult.issues.slice(0, 40)" :key="issue">{{ issue }}</li>
        <li v-if="validationResult.issues.length > 40">
          …另有 {{ validationResult.issues.length - 40 }} 条
        </li>
      </ul>
    </el-alert>

    <div class="workspace-grid">
      <aside class="selector-list">
        <div
          v-for="row in selectedRows"
          :key="row.selectorKey"
          class="selector-item"
          :class="{ active: row.selectorKey === activeSelector }"
          @click="activeSelector = row.selectorKey"
        >
          <div class="selector-top">
            <strong>{{ row.selectorKey }}</strong>
            <span class="tier">{{ row.preparationTier }}</span>
          </div>
          <div class="selector-meta">
            <span :class="completenessClass(row)">
              {{ ownerContractCompleteness(row.ownerContract).filled }}/15
            </span>
            <small>{{ row.sourceHints.scenarios[0] || row.sourceHints.modules[0] || '无场景提示' }}</small>
          </div>
        </div>
      </aside>

      <section v-if="activeRow" class="editor-pane">
        <div class="hints-card">
          <div class="hints-head">
            <h2>来源提示（只读）</h2>
            <el-button size="small" @click="applyHints">用提示填入草稿</el-button>
          </div>
          <dl>
            <div><dt>档位</dt><dd>{{ activeRow.preparationTier }}</dd></div>
            <div><dt>等级提示</dt><dd>{{ joinHint(activeRow.sourceHints.levels) }}</dd></div>
            <div><dt>服务提示</dt><dd>{{ joinHint(activeRow.sourceHints.sourceServices) }}</dd></div>
            <div><dt>模块</dt><dd>{{ joinHint(activeRow.sourceHints.modules) }}</dd></div>
            <div><dt>场景</dt><dd>{{ joinHint(activeRow.sourceHints.scenarios) }}</dd></div>
            <div><dt>错误码</dt><dd>{{ joinHint(activeRow.sourceHints.signatureErrorCodes) }}</dd></div>
          </dl>
        </div>

        <el-form v-if="activeContract" label-position="top" class="contract-form">
          <div class="form-grid">
            <el-form-item label="责任团队 ownerTeam（可中文）">
              <el-input v-model="activeContract.ownerTeam" maxlength="128" show-word-limit />
            </el-form-item>
            <el-form-item label="故障等级 ownerLevel（P0/P1/P2）">
              <el-select v-model="activeContract.ownerLevel" style="width: 100%">
                <el-option label="P0" value="P0" />
                <el-option label="P1" value="P1" />
                <el-option label="P2" value="P2" />
                <el-option
                  v-if="isPlaceholderLevel(activeContract.ownerLevel)"
                  :label="activeContract.ownerLevel"
                  :value="activeContract.ownerLevel"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="故障场景 ownerScenario（可中文）" class="span-2">
              <el-input v-model="activeContract.ownerScenario" maxlength="160" show-word-limit />
            </el-form-item>
            <el-form-item label="运行服务名 verifiedRuntimeService（如 csdp-wechat）">
              <el-input v-model="activeContract.verifiedRuntimeService" maxlength="128" />
            </el-form-item>
            <el-form-item label="安全检索键 safeSearchTerm（错误码或关键词）">
              <el-input v-model="activeContract.safeSearchTerm" maxlength="128" />
            </el-form-item>
            <el-form-item label="查询时间窗 window（如 -6h / -15m）">
              <el-input v-model="activeContract.window" maxlength="16" />
            </el-form-item>
            <el-form-item label="历史故障时间 historicalOccurredAt（UTC 整秒，必填）">
              <el-input
                v-model="activeContract.historicalOccurredAt"
                placeholder="2026-08-07T09:12:00Z"
                maxlength="20"
              />
            </el-form-item>
            <el-form-item label="候选材料引用 candidateReference（给这条故障的材料编号）">
              <el-input v-model="activeContract.candidateReference" maxlength="256" />
            </el-form-item>
            <el-form-item label="查询合同引用 serverQueryContractReference（服务端查法编号）">
              <el-input v-model="activeContract.serverQueryContractReference" maxlength="256" />
            </el-form-item>
            <el-form-item label="异常判据引用 anomalyCriterionReference（怎么判定异常）">
              <el-input v-model="activeContract.anomalyCriterionReference" maxlength="256" />
            </el-form-item>
            <el-form-item label="诊断规则引用 diagnosisRuleReference（怎么下结论）">
              <el-input v-model="activeContract.diagnosisRuleReference" maxlength="256" />
            </el-form-item>
            <el-form-item label="历史来源引用 historicalSourceReference（告警/工单号，必填）">
              <el-input v-model="activeContract.historicalSourceReference" maxlength="256" />
            </el-form-item>
            <el-form-item label="日志检索绑定 bindingRefs.log_search（查失败日志用哪条）">
              <el-input v-model="activeContract.bindingRefs.log_search" maxlength="128" />
            </el-form-item>
            <el-form-item label="链路还原绑定 bindingRefs.log_trace_bundle（按 PS ID 追链路）">
              <el-input v-model="activeContract.bindingRefs.log_trace_bundle" maxlength="128" />
            </el-form-item>
            <el-form-item label="成败对照绑定 bindingRefs.contrast_sample（失败 vs 成功样本）">
              <el-input v-model="activeContract.bindingRefs.contrast_sample" maxlength="128" />
            </el-form-item>
          </div>
        </el-form>

        <p v-if="activeRemaining.length" class="remaining">
          本条 Owner 待补：{{ activeRemaining.join('、') }}
        </p>
        <p class="fingerprint">
          preparationFingerprint={{ worksheet.preparationFingerprint }}
          · 与 docs 推荐模板对齐；改指纹会被校验拒绝。
        </p>
      </section>
    </div>
  </CapabilityWorkspaceShell>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import recommendedTemplate from '@/assets/troubleshooting/t7-owner-contract-intake.recommended.template.json'
import CapabilityWorkspaceShell from './CapabilityWorkspaceShell.vue'
import { safeTroubleshootingReturnPath } from './workbenchCapabilityMenu'
import {
  T7_MIN_SELECTED,
  applyFirstBatchDeveloperDrafts,
  applySourceHintsDraft,
  cloneRecommendedWorksheet,
  draftStorageKey,
  downloadOwnerDocument,
  ownerContractCompleteness,
  ownerRemainingFields,
  validateOwnerInput,
  type OwnerContract,
  type OwnerContractDocument,
  type OwnerContractRow,
  type OwnerValidationResult,
} from './t7OwnerContractIntake'

const route = useRoute()
const router = useRouter()
const workspaceStore = useWorkspaceStore()

const template = recommendedTemplate as OwnerContractDocument
const worksheet = ref<OwnerContractDocument>(cloneRecommendedWorksheet(template))
const activeSelector = ref('')
const validationResult = ref<OwnerValidationResult | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const minSelected = T7_MIN_SELECTED

const selectedRows = computed(() => worksheet.value.contracts.filter(row => row.selectedForWindow))
const selectedCount = computed(() => selectedRows.value.length)
const completeCount = computed(() => selectedRows.value.filter(
  row => ownerContractCompleteness(row.ownerContract).complete,
).length)
const activeRow = computed(() => selectedRows.value.find(row => row.selectorKey === activeSelector.value)
  || selectedRows.value[0]
  || null)
const activeContract = computed<OwnerContract | null>(() => activeRow.value?.ownerContract ?? null)
const FIELD_LABELS_ZH: Record<string, string> = {
  ownerTeam: '责任团队',
  historicalOccurredAt: '历史故障时间',
  historicalSourceReference: '历史来源引用',
}

const activeRemaining = computed(() =>
  ownerRemainingFields(activeContract.value).map(field => FIELD_LABELS_ZH[field] || field),
)

const validationLabel = computed(() => {
  if (!validationResult.value) return '尚未校验'
  return validationResult.value.ok ? 'PREPARED_NOT_EXECUTABLE' : `失败 ${validationResult.value.issues.length} 项`
})
const validationTone = computed(() => {
  if (!validationResult.value) return ''
  return validationResult.value.ok ? 'ok' : 'bad'
})

watch(selectedRows, (rows) => {
  if (!rows.some(row => row.selectorKey === activeSelector.value)) {
    activeSelector.value = rows[0]?.selectorKey || ''
  }
}, { immediate: true })

watch(worksheet, () => {
  validationResult.value = null
  persistDraft()
}, { deep: true })

onMounted(() => {
  restoreDraft()
})

function joinHint(values: string[]) {
  return values.length ? values.join(' · ') : '—'
}

function isPlaceholderLevel(value: string) {
  return value !== 'P0' && value !== 'P1' && value !== 'P2'
}

function completenessClass(row: OwnerContractRow) {
  const { complete, filled } = ownerContractCompleteness(row.ownerContract)
  if (complete) return 'complete'
  if (filled > 0) return 'partial'
  return 'empty'
}

function returnToWorkbench() {
  const returnTo = safeTroubleshootingReturnPath(route.query.returnTo) || '/troubleshooting?view=list'
  void router.push(returnTo)
}

function persistDraft() {
  try {
    localStorage.setItem(
      draftStorageKey(workspaceStore.currentWorkspaceId),
      JSON.stringify(worksheet.value),
    )
  } catch {
    // Ignore quota / private mode failures; export still works.
  }
}

function restoreDraft() {
  try {
    const raw = localStorage.getItem(draftStorageKey(workspaceStore.currentWorkspaceId))
    if (!raw) return
    const parsed = JSON.parse(raw) as OwnerContractDocument
    if (
      parsed?.preparationFingerprint !== template.preparationFingerprint
      || parsed?.windowTargetRange?.minimum !== template.windowTargetRange.minimum
    ) {
      ElMessage.warning('本地草稿与当前首批 10 条模板不一致，已忽略草稿')
      return
    }
    worksheet.value = parsed
  } catch {
    ElMessage.warning('本地草稿损坏，已忽略')
  }
}

function reloadFromTemplate() {
  worksheet.value = cloneRecommendedWorksheet(template)
  validationResult.value = null
  localStorage.removeItem(draftStorageKey(workspaceStore.currentWorkspaceId))
  ElMessage.success('已恢复推荐模板')
}

async function confirmReload() {
  try {
    await ElMessageBox.confirm('将丢弃当前表单与本地草稿，恢复推荐模板。继续？', '重置模板', {
      type: 'warning',
      confirmButtonText: '重置',
      cancelButtonText: '取消',
    })
    reloadFromTemplate()
  } catch {
    // cancelled
  }
}

function applyHints() {
  if (!activeRow.value) return
  activeRow.value.ownerContract = applySourceHintsDraft(activeRow.value)
  ElMessage.success('已用提示填入可安全草稿字段')
}

function fillFirstBatchDrafts() {
  const count = applyFirstBatchDeveloperDrafts(worksheet.value, {
    ownerTeam: 'CSDP',
    window: '-6h',
  })
  ElMessage.success(`已为 ${count} 条生成开发侧引用/binding 草稿；请补每条历史时间与来源编号`)
}

function runValidation() {
  validationResult.value = validateOwnerInput(worksheet.value, template)
  if (validationResult.value.ok) {
    ElMessage.success('校验通过：PREPARED_NOT_EXECUTABLE（仍不能点验收）')
  } else {
    ElMessage.error(`校验失败：${validationResult.value.issues.length} 项`)
  }
}

function exportDocument() {
  downloadOwnerDocument(worksheet.value)
  ElMessage.success('已导出 standard-query-intake.local.json')
}

function triggerImport() {
  fileInput.value?.click()
}

async function onImportFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  try {
    await ElMessageBox.confirm('导入将覆盖当前表单。继续？', '导入 JSON', {
      type: 'warning',
      confirmButtonText: '导入',
      cancelButtonText: '取消',
    })
    const text = await file.text()
    const parsed = JSON.parse(text) as OwnerContractDocument
    if (parsed?.preparationFingerprint !== template.preparationFingerprint) {
      ElMessage.error('导入文件指纹与当前推荐模板不一致')
      return
    }
    worksheet.value = parsed
    validationResult.value = null
    ElMessage.success('已导入 JSON')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error('导入失败：JSON 无效或已取消')
  }
}
</script>

<style scoped>
.page-alert { margin-bottom: 14px; }
.status-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
  padding: 12px 14px;
  border: 1px solid var(--mc-border-light);
  border-radius: 12px;
  background: var(--mc-bg-elevated);
}
.status-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  color: var(--mc-text-secondary);
  font-size: 13px;
}
.status-metrics .ok { color: var(--mc-success, #2f7d4a); font-weight: 650; }
.status-metrics .bad { color: var(--mc-danger, #b42318); font-weight: 650; }
.status-actions { display: flex; flex-wrap: wrap; gap: 8px; justify-content: flex-end; }
.hidden-file { display: none; }
.issue-list {
  margin: 8px 0 0;
  padding-left: 18px;
  max-height: 180px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.5;
}
.workspace-grid {
  display: grid;
  grid-template-columns: var(--mc-ts-side-rail-width) minmax(0, 1fr);
  gap: 14px;
  min-height: 520px;
}
.selector-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: calc(100vh - 280px);
  overflow: auto;
  padding-right: 4px;
}
.selector-item {
  padding: 10px 12px;
  border: 1px solid var(--mc-border-light);
  border-radius: 10px;
  background: var(--mc-bg-elevated);
  cursor: pointer;
}
.selector-item:hover { border-color: rgba(217, 109, 70, .35); }
.selector-item.active {
  border-color: var(--mc-primary);
  background: var(--mc-primary-bg);
  box-shadow: inset 0 0 0 1px rgba(217, 109, 70, .08);
}
.selector-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.selector-top strong {
  min-width: 0;
  overflow: hidden;
  color: var(--mc-text-primary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tier {
  flex: 0 0 auto;
  color: var(--mc-text-secondary);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: .04em;
}
.selector-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
}
.selector-meta span {
  flex: 0 0 auto;
  font-size: 11px;
  font-weight: 700;
}
.selector-meta .complete { color: var(--mc-success, #2f7d4a); }
.selector-meta .partial { color: var(--mc-warning, #b54708); }
.selector-meta .empty { color: var(--mc-text-tertiary, #98a2b3); }
.selector-meta small {
  min-width: 0;
  overflow: hidden;
  color: var(--mc-text-secondary);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.editor-pane {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}
.hints-card {
  padding: 14px 16px;
  border: 1px solid var(--mc-border-light);
  border-radius: 12px;
  background: var(--mc-bg-muted);
}
.hints-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}
.hints-head h2 {
  margin: 0;
  color: var(--mc-text-primary);
  font-size: 14px;
}
.hints-card dl {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 16px;
  margin: 0;
}
.hints-card dl > div { min-width: 0; }
.hints-card dt {
  color: var(--mc-text-tertiary, #98a2b3);
  font-size: 11px;
}
.hints-card dd {
  margin: 2px 0 0;
  color: var(--mc-text-primary);
  font-size: 12px;
  word-break: break-word;
}
.contract-form {
  padding: 14px 16px 4px;
  border: 1px solid var(--mc-border-light);
  border-radius: 12px;
  background: var(--mc-bg-elevated);
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 4px 16px;
}
.form-grid .span-2 { grid-column: 1 / -1; }
.fingerprint {
  margin: 0;
  color: var(--mc-text-tertiary, #98a2b3);
  font-size: 11px;
  line-height: 1.5;
  word-break: break-all;
}
.remaining {
  margin: 0;
  color: var(--mc-warning, #b54708);
  font-size: 12px;
  font-weight: 650;
}
@media (max-width: 960px) {
  .workspace-grid { grid-template-columns: 1fr; }
  .selector-list { max-height: 240px; }
  .status-bar { align-items: flex-start; flex-direction: column; }
  .hints-card dl, .form-grid { grid-template-columns: 1fr; }
}
</style>
