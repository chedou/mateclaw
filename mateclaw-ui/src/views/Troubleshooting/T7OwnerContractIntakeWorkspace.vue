<template>
  <CapabilityWorkspaceShell
    eyebrow="智能排障"
    title="标准查登记"
    description="把权威首批 20 条重点故障登记成可交接的查询清单。登记齐只表示材料已准备，仍需开发冻结运行目录和 Owner 正式验收。"
    @back="returnToWorkbench"
    @refresh="reloadFromTemplate"
  >
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      class="page-alert"
      title="这是登记表，不是验收按钮"
      description="每条都要用自己的真实告警核对服务、检索键、绑定和历史时间。不要复用 message-send、CTI 或 ITGW 的查询名，也不要填 API Key、DQL 全文或原始日志。"
    />

    <div class="status-bar">
      <div class="status-metrics">
        <span>首批 {{ selectedCount }} / {{ minSelected }}</span>
        <span>整条完成 {{ completeCount }} / {{ selectedCount }}</span>
        <span :class="validationTone">{{ validationLabel }}</span>
      </div>
      <div class="status-actions">
        <el-button type="success" plain @click="fillFirstBatchDrafts">生成开发侧引用草稿</el-button>
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
      title="已加载权威首批 20 条候选，Owner 事实尚未填写"
      description="批次固定为 15 条有日志特征提示、2 条只有业务上下文、3 条仍有来源缺口。提示只帮助定位材料，不能替代 Owner 对真实服务、查询绑定和历史故障时间的确认。"
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
              {{ rowProgress(row).complete ? '已就绪' : `已核对 ${rowProgress(row).filled}/${T7_OWNER_FACT_COUNT}` }}
            </span>
            <small>{{ row.sourceHints.scenarios[0] || row.sourceHints.modules[0] || '无场景提示' }}</small>
          </div>
        </div>
      </aside>

      <section v-if="activeRow" class="editor-pane">
        <div class="active-row-head">
          <div>
            <span>当前正在登记</span>
            <h2>{{ activeRow.selectorKey }}</h2>
            <p>先确认真实故障，再确认查法和判断方法；三步都完成才算这一条材料齐全。</p>
          </div>
          <el-button
            plain
            :disabled="!canOpenNextIncomplete"
            @click="openNextIncomplete"
          >{{ nextIncompleteLabel }}</el-button>
        </div>

        <ol class="section-progress" aria-label="本条标准查登记进度">
          <li
            v-for="(section, index) in activeSections"
            :key="section.key"
            :class="{ complete: section.complete }"
          >
            <i>{{ section.complete ? '✓' : index + 1 }}</i>
            <span>
              <b>{{ section.label }}</b>
              <small>
                {{ section.complete
                  ? '已核对'
                  : `已核对 ${section.filled} / ${section.total}${section.issue ? ` · ${section.issue}` : ''}` }}
              </small>
            </span>
          </li>
        </ol>

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
          <section class="contract-step">
            <header>
              <i>1</i>
              <div><b>确认这是什么故障</b><span>用一条真实告警确认责任团队、运行服务、故障时间和来源。</span></div>
              <em>{{ sectionProgress('INCIDENT').filled }} / {{ sectionProgress('INCIDENT').total }}</em>
            </header>
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
              <el-form-item label="真实运行服务 verifiedRuntimeService（如 csdp-wechat）">
                <el-input v-model="activeContract.verifiedRuntimeService" maxlength="128" />
              </el-form-item>
              <el-form-item label="故障发生时间 historicalOccurredAt（UTC 整秒）">
                <el-input
                  v-model="activeContract.historicalOccurredAt"
                  placeholder="2026-08-07T09:12:00Z"
                  maxlength="20"
                />
              </el-form-item>
              <el-form-item label="对应的告警 / 工单号 historicalSourceReference" class="span-2">
                <el-input v-model="activeContract.historicalSourceReference" maxlength="256" />
              </el-form-item>
            </div>
          </section>

          <section class="contract-step">
            <header>
              <i>2</i>
              <div><b>确认在观测云怎么查</b><span>只登记安全检索键、时间窗和服务端查法编号，不填 DQL 和原始日志。</span></div>
              <em>{{ sectionProgress('QUERY').filled }} / {{ sectionProgress('QUERY').total }}</em>
            </header>
            <div class="form-grid">
              <el-form-item label="搜什么 safeSearchTerm（错误码或稳定关键词）">
                <el-input v-model="activeContract.safeSearchTerm" maxlength="128" />
              </el-form-item>
              <el-form-item label="查多长时间 window（如 -6h / -15m）">
                <el-input v-model="activeContract.window" maxlength="16" />
              </el-form-item>
              <el-form-item label="服务端查法编号 serverQueryContractReference" class="span-2">
                <el-input v-model="activeContract.serverQueryContractReference" maxlength="256" />
              </el-form-item>
              <el-form-item label="失败日志查询 bindingRefs.log_search">
                <el-input v-model="activeContract.bindingRefs.log_search" maxlength="128" />
              </el-form-item>
              <el-form-item label="关联调用还原 bindingRefs.log_trace_bundle">
                <el-input v-model="activeContract.bindingRefs.log_trace_bundle" maxlength="128" />
              </el-form-item>
              <el-form-item label="成功 / 失败对照 bindingRefs.contrast_sample" class="span-2">
                <el-input v-model="activeContract.bindingRefs.contrast_sample" maxlength="128" />
              </el-form-item>
            </div>
          </section>

          <section class="contract-step">
            <header>
              <i>3</i>
              <div><b>确认平台怎么判断</b><span>把材料、异常判据和结论规则绑定到唯一编号，便于审核和回放。</span></div>
              <em>{{ sectionProgress('DECISION').filled }} / {{ sectionProgress('DECISION').total }}</em>
            </header>
            <div class="form-grid">
              <el-form-item label="候选材料编号 candidateReference" class="span-2">
                <el-input v-model="activeContract.candidateReference" maxlength="256" />
              </el-form-item>
              <el-form-item label="异常判据编号 anomalyCriterionReference">
                <el-input v-model="activeContract.anomalyCriterionReference" maxlength="256" />
              </el-form-item>
              <el-form-item label="诊断规则编号 diagnosisRuleReference">
                <el-input v-model="activeContract.diagnosisRuleReference" maxlength="256" />
              </el-form-item>
            </div>
          </section>
        </el-form>

        <p v-if="activeProgress.issues.length" class="remaining">
          本条还需修正：{{ activeProgress.issues.join('；') }}
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
  T7_OWNER_FACT_COUNT,
  applyFirstBatchDeveloperDrafts,
  applySourceHintsDraft,
  cloneRecommendedWorksheet,
  draftStorageKey,
  downloadOwnerDocument,
  nextIncompleteOwnerSelector,
  ownerContractBatchProgress,
  validateOwnerInput,
  type OwnerContract,
  type OwnerContractDocument,
  type OwnerContractProgress,
  type OwnerContractRow,
  type OwnerContractSectionKey,
  type OwnerContractSectionProgress,
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
const batchProgress = computed(() => ownerContractBatchProgress(selectedRows.value))
const completeCount = computed(() => selectedRows.value.filter(
  row => batchProgress.value.get(row.selectorKey)?.complete,
).length)
const activeRow = computed(() => selectedRows.value.find(row => row.selectorKey === activeSelector.value)
  || selectedRows.value[0]
  || null)
const activeContract = computed<OwnerContract | null>(() => activeRow.value?.ownerContract ?? null)
const activeProgress = computed(() => activeRow.value
  ? rowProgress(activeRow.value)
  : emptyProgress())
const activeSections = computed(() => activeProgress.value.sections)
const nextIncompleteSelector = computed(() => nextIncompleteOwnerSelector(
  selectedRows.value,
  activeSelector.value,
))
const canOpenNextIncomplete = computed(() => Boolean(
  nextIncompleteSelector.value && nextIncompleteSelector.value !== activeSelector.value,
))
const nextIncompleteLabel = computed(() => {
  if (!nextIncompleteSelector.value) return '首批 20 条已齐全'
  if (nextIncompleteSelector.value === activeSelector.value) return '先完成本条'
  return '下一条未完成 →'
})
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
  const { complete, filled } = rowProgress(row)
  if (complete) return 'complete'
  if (filled > 0) return 'partial'
  return 'empty'
}

function emptyProgress(): OwnerContractProgress {
  return {
    filled: 0,
    total: T7_OWNER_FACT_COUNT,
    complete: false,
    sections: [],
    issues: [],
  }
}

function rowProgress(row: OwnerContractRow): OwnerContractProgress {
  return batchProgress.value.get(row.selectorKey) || emptyProgress()
}

function sectionProgress(key: OwnerContractSectionKey): OwnerContractSectionProgress {
  return activeSections.value.find(section => section.key === key)!
}

function openNextIncomplete() {
  if (!canOpenNextIncomplete.value || !nextIncompleteSelector.value) return
  activeSelector.value = nextIncompleteSelector.value
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
    if (!matchesAuthoritativeWorksheet(parsed)) {
      ElMessage.warning('本地草稿与当前权威首批 20 条模板不一致，已忽略草稿')
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
  ElMessage.success(`已为 ${count} 条生成不可执行的开发侧引用草稿；请逐条补齐 Owner 事实`)
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
    if (!matchesAuthoritativeWorksheet(parsed)) {
      ElMessage.error('导入文件与当前权威首批 20 条模板不一致')
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

function matchesAuthoritativeWorksheet(document: OwnerContractDocument | null | undefined): boolean {
  if (
    document?.preparationFingerprint !== template.preparationFingerprint
    || document?.contractVersion !== template.contractVersion
    || !Array.isArray(document.contracts)
    || document.contracts.length !== template.contracts.length
  ) return false
  return document.contracts.every((row, index) => {
    const expected = template.contracts[index]
    return row.selectorKey === expected.selectorKey
      && row.preparationTier === expected.preparationTier
      && row.selectedForWindow === expected.selectedForWindow
      && JSON.stringify(row.sourceHints) === JSON.stringify(expected.sourceHints)
  })
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
.active-row-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 2px 2px 0;
}
.active-row-head > div { min-width: 0; }
.active-row-head span {
  color: var(--mc-primary);
  font-size: 10px;
  font-weight: 750;
  letter-spacing: .08em;
}
.active-row-head h2 {
  margin: 3px 0 2px;
  color: var(--mc-text-primary);
  font-size: 18px;
  word-break: break-word;
}
.active-row-head p {
  margin: 0;
  color: var(--mc-text-secondary);
  font-size: 11px;
  line-height: 1.55;
}
.active-row-head .el-button { flex: none; }
.section-progress {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.section-progress li {
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  padding: 9px 10px;
  border: 1px solid var(--mc-border-light);
  border-radius: 10px;
  background: var(--mc-bg-elevated);
}
.section-progress i {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--mc-bg-muted);
  color: var(--mc-text-secondary);
  font-size: 11px;
  font-style: normal;
  font-weight: 750;
}
.section-progress span,
.section-progress b,
.section-progress small { display: block; min-width: 0; }
.section-progress b { color: var(--mc-text-primary); font-size: 11px; }
.section-progress small { margin-top: 2px; color: var(--mc-text-tertiary); font-size: 9px; }
.section-progress li.complete {
  border-color: rgba(47, 125, 74, .28);
  background: var(--mc-status-success-bg);
}
.section-progress li.complete i {
  background: var(--mc-success);
  color: var(--mc-text-inverse);
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
  display: grid;
  gap: 14px;
  padding: 0;
  border: 0;
  background: transparent;
}
.contract-step {
  padding: 14px 16px 4px;
  border: 1px solid var(--mc-border-light);
  border-radius: 12px;
  background: var(--mc-bg-elevated);
}
.contract-step > header {
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr) auto;
  align-items: start;
  gap: 10px;
  margin-bottom: 12px;
  padding-bottom: 11px;
  border-bottom: 1px solid var(--mc-border-light);
}
.contract-step > header > i {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--mc-primary-bg);
  color: var(--mc-primary);
  font-size: 12px;
  font-style: normal;
  font-weight: 750;
}
.contract-step > header b,
.contract-step > header span { display: block; }
.contract-step > header b { color: var(--mc-text-primary); font-size: 13px; }
.contract-step > header span {
  margin-top: 3px;
  color: var(--mc-text-secondary);
  font-size: 10px;
  line-height: 1.5;
}
.contract-step > header em {
  color: var(--mc-text-tertiary);
  font-size: 10px;
  font-style: normal;
  font-weight: 700;
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
  .active-row-head { align-items: stretch; flex-direction: column; }
  .active-row-head .el-button { width: 100%; }
  .section-progress { grid-template-columns: 1fr; }
  .hints-card dl, .form-grid { grid-template-columns: 1fr; }
}
</style>
