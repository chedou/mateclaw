<template>
  <div class="mc-page-shell loop-shell">
    <div class="mc-page-frame loop-frame">
      <div class="mc-page-inner loop-page">
        <div class="mc-page-header compact">
          <div>
            <div class="mc-page-kicker">Loop Engineering</div>
            <h1 class="mc-page-title">工程闭环工作台</h1>
            <p class="mc-page-desc">把可重复工程动作沉淀成 Superpower：复现失败、受控修复、重新验证、人工确认。</p>
          </div>
          <div class="header-actions">
            <button class="btn-secondary icon-btn" :disabled="loading" @click="loadSuperpowers">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M21 12a9 9 0 0 1-15.5 6.3L3 16" />
                <path d="M3 21v-5h5" />
                <path d="M3 12A9 9 0 0 1 18.5 5.7L21 8" />
                <path d="M21 3v5h-5" />
              </svg>
              <span>刷新</span>
            </button>
          </div>
        </div>

        <div class="loop-metrics" aria-label="Loop Engineering metrics">
          <div>
            <span class="metric-value">{{ superpowers.length }}</span>
            <span class="metric-label">Superpower</span>
          </div>
          <div>
            <span class="metric-value">{{ domainCount }}</span>
            <span class="metric-label">工程域</span>
          </div>
          <div>
            <span class="metric-value">{{ humanGateCount }}</span>
            <span class="metric-label">人工闸门</span>
          </div>
        </div>

        <div class="loop-grid">
          <section class="loop-section superpower-section">
            <div class="section-head">
              <div>
                <h2>Superpower 列表</h2>
                <p>当前只接入工程闭环能力包，不混入排障 SOP。</p>
              </div>
              <input v-model.trim="keyword" class="search-input" placeholder="搜索 domain / scenario / owner" />
            </div>

            <div class="superpower-list">
              <article
                v-for="item in filteredSuperpowers"
                :key="String(item.skillId)"
                class="superpower-row"
                :class="{ selected: sameId(item.skillId, selectedSuperpower?.skillId) }"
                @click="selectSuperpower(item)"
              >
                <div class="row-main">
                  <strong>{{ item.domain || '-' }}/{{ item.scenario || '-' }}</strong>
                  <span>{{ item.description || item.name }}</span>
                </div>
                <div class="row-meta">
                  <span class="status-pill">v{{ item.version || '-' }}</span>
                  <span>{{ item.owner || '-' }}</span>
                </div>
                <div class="chips">
                  <span class="chip required">{{ item.triggerType || 'manual' }}</span>
                  <span class="chip">{{ item.workspaceIsolation || 'none' }}</span>
                  <span v-for="check in item.requiredChecks.slice(0, 3)" :key="check" class="chip">{{ check }}</span>
                </div>
              </article>
              <div v-if="!filteredSuperpowers.length && !loading" class="empty-cell">暂无匹配的 Superpower。</div>
              <div v-if="loading" class="empty-cell">加载中...</div>
            </div>
          </section>

          <section class="loop-section run-section">
            <div class="section-head">
              <div>
                <h2>预览与创建 run</h2>
                <p>失败复现后可执行白名单修复命令；验证通过后仍停在人审闸门。</p>
              </div>
            </div>

            <div class="run-form">
              <label>
                Repo Path
                <textarea v-model.trim="form.repoPath" class="code-field" rows="2" placeholder="/Users/.../repo"></textarea>
              </label>
              <label>
                Command
                <textarea v-model.trim="form.command" class="code-field command-field" rows="3" placeholder="mvn -pl mateclaw-server test"></textarea>
              </label>
              <label>
                Agent Repair Command
                <textarea v-model.trim="form.repairCommand" class="code-field command-field" rows="3" placeholder="npm run loop:repair"></textarea>
              </label>
              <label>
                Goal
                <textarea v-model.trim="form.goal" rows="3" placeholder="修复一个可复现失败测试，保持最小 diff"></textarea>
              </label>
              <label>
                Branch
                <input v-model.trim="form.branch" placeholder="可选" />
              </label>
            </div>

            <div class="action-row">
              <button class="btn-secondary icon-btn" :disabled="previewing" @click="previewSuperpower">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <circle cx="11" cy="11" r="8" />
                  <path d="m21 21-4.3-4.3" />
                </svg>
                <span>{{ previewing ? '预览中' : '预览匹配' }}</span>
              </button>
              <button class="btn-primary icon-btn" :disabled="creating || !selectedSuperpower" @click="createRun">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M12 5v14" />
                  <path d="M5 12h14" />
                </svg>
                <span>{{ creating ? '创建中' : '创建 run' }}</span>
              </button>
            </div>

            <div v-if="previewResult" class="preview-panel">
              <div class="preview-title">
                <strong>{{ previewResult.selected?.domain || '-' }}/{{ previewResult.selected?.scenario || '-' }}</strong>
                <span class="status-pill route-confidence">{{ percent(previewResult.confidence) }}</span>
              </div>
              <div class="chips">
                <span v-for="reason in previewResult.reasons" :key="reason" class="chip required">{{ reason }}</span>
                <span v-for="signal in previewResult.missingSignals" :key="signal" class="chip warn">缺 {{ signal }}</span>
              </div>
            </div>
          </section>
        </div>

        <section class="loop-section detail-section">
          <div class="section-head">
            <div>
              <h2>Run 详情</h2>
              <p>展示本次工程闭环的输入、步骤、日志 artifact 和最终报告。</p>
            </div>
            <div class="run-lookup">
              <input v-model.trim="lookupRunId" placeholder="runId" />
              <button class="btn-secondary" :disabled="loadingRun || !lookupRunId" @click="loadRun">查询</button>
            </div>
          </div>

          <div v-if="currentRun" class="run-detail">
            <div class="run-summary">
              <div>
                <strong>{{ currentRun.domain || '-' }}/{{ currentRun.scenario || '-' }}</strong>
                <span>{{ currentRun.superpowerName || '-' }} v{{ currentRun.superpowerVersion || '-' }}</span>
              </div>
              <div class="run-meta">
                <span class="status-pill" :class="statusClass(currentRun.status)">{{ currentRun.status }}</span>
                <span class="run-id">runId：{{ currentRun.id }}</span>
              </div>
            </div>

            <div class="detail-grid">
              <div>
                <h3>Input</h3>
                <pre>{{ formatJson(currentRun.inputJson) }}</pre>
              </div>
              <div>
                <h3>Final Report</h3>
                <pre>{{ formatJson(currentRun.finalReportJson) }}</pre>
              </div>
            </div>

            <div v-if="stepResults.length" class="trace-block">
              <h3>Step Results</h3>
              <div class="step-list">
                <article v-for="step in stepResults" :key="step.stepId || JSON.stringify(step)" class="step-card">
                  <div class="step-head">
                    <strong>{{ step.stepId || '-' }}</strong>
                    <span class="status-pill" :class="statusClass(step.status)">{{ step.status || '-' }}</span>
                  </div>
                  <p>{{ step.interpretation || '-' }}</p>
                  <div v-if="Array.isArray(step.evidenceIds) && step.evidenceIds.length" class="chips">
                    <span v-for="evidenceId in step.evidenceIds" :key="evidenceId" class="chip">{{ evidenceId }}</span>
                  </div>
                  <pre v-if="step.observation">{{ formatObject(step.observation) }}</pre>
                </article>
              </div>
            </div>

            <div v-if="artifacts.length" class="trace-block">
              <h3>Artifacts</h3>
              <div class="artifact-list">
                <article v-for="artifact in artifacts" :key="artifact.id || artifact.path || artifact.name" class="artifact-card">
                  <div>
                    <strong>{{ artifact.name || artifact.id || '-' }}</strong>
                    <span>{{ artifact.type || '-' }}<template v-if="artifact.sizeBytes != null"> · {{ artifact.sizeBytes }} bytes</template></span>
                  </div>
                  <code class="artifact-path">{{ artifact.path || '-' }}</code>
                </article>
              </div>
            </div>

            <div class="action-row">
              <button class="btn-secondary icon-btn" :disabled="executing" @click="executeRun">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M5 3l14 9-14 9V3z" />
                </svg>
                <span>{{ executing ? '执行中' : '执行验证' }}</span>
              </button>
              <span v-if="executeMessage" class="muted">{{ executeMessage }}</span>
            </div>
          </div>

          <div v-else class="empty-cell">创建或查询 run 后显示详情。</div>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { loopEngineeringApi, type LoopRunResponse, type LoopSuperpowerPreviewResponse, type LoopSuperpowerSummary } from '@/api'
import { mcToast } from '@/composables/useMcToast'

const loading = ref(false)
const previewing = ref(false)
const creating = ref(false)
const loadingRun = ref(false)
const executing = ref(false)
const keyword = ref('')
const lookupRunId = ref('')
const executeMessage = ref('')

const superpowers = ref<LoopSuperpowerSummary[]>([])
const selectedSuperpower = ref<LoopSuperpowerSummary | null>(null)
const previewResult = ref<LoopSuperpowerPreviewResponse | null>(null)
const currentRun = ref<LoopRunResponse | null>(null)

const form = reactive({
  repoPath: '',
  command: 'mvn -pl mateclaw-server -Dtest=vip.mate.skill.manifest.SkillManifestParserTest test',
  repairCommand: '',
  goal: '修复一个可复现失败测试，保持最小 diff',
  branch: '',
})

const filteredSuperpowers = computed(() => {
  const kw = keyword.value.toLowerCase()
  if (!kw) return superpowers.value
  return superpowers.value.filter((item) => [
    item.name,
    item.description,
    item.domain,
    item.scenario,
    item.owner,
    item.triggerType,
    item.workspaceIsolation,
    ...item.requiredChecks,
    ...item.outputs,
  ].filter(Boolean).join(' ').toLowerCase().includes(kw))
})

const domainCount = computed(() => new Set(superpowers.value.map((item) => item.domain).filter(Boolean)).size)
const humanGateCount = computed(() => superpowers.value.filter((item) => item.requireHumanBeforePush).length)
const stepResults = computed<any[]>(() => parseJsonArray(currentRun.value?.stepResultsJson))
const artifacts = computed<any[]>(() => parseJsonArray(currentRun.value?.artifactsJson))

onMounted(loadSuperpowers)

async function loadSuperpowers() {
  loading.value = true
  try {
    const res: any = await loopEngineeringApi.listSuperpowers()
    superpowers.value = res.data || []
    if (!selectedSuperpower.value && superpowers.value.length) {
      selectSuperpower(superpowers.value[0])
    }
  } catch (e: any) {
    mcToast.error(e?.message || '加载工程闭环 Superpower 失败')
  } finally {
    loading.value = false
  }
}

function selectSuperpower(item: LoopSuperpowerSummary) {
  selectedSuperpower.value = item
  executeMessage.value = ''
}

async function previewSuperpower() {
  previewing.value = true
  try {
    const res: any = await loopEngineeringApi.previewSuperpower(buildPreviewPayload())
    previewResult.value = res.data || null
    if (previewResult.value?.selected) {
      selectedSuperpower.value = previewResult.value.selected
    }
  } catch (e: any) {
    mcToast.error(e?.message || '预览 Superpower 失败')
  } finally {
    previewing.value = false
  }
}

async function createRun() {
  if (!selectedSuperpower.value) {
    mcToast.warning('请先选择 Superpower')
    return
  }
  creating.value = true
  executeMessage.value = ''
  try {
    const res: any = await loopEngineeringApi.createRun({
      superpowerSkillId: selectedSuperpower.value.skillId,
      repoPath: form.repoPath,
      command: form.command,
      repairCommand: form.repairCommand,
      goal: form.goal,
      branch: form.branch,
    })
    currentRun.value = res.data || null
    lookupRunId.value = currentRun.value?.id == null ? '' : String(currentRun.value.id)
    mcToast.success('工程闭环 run 已创建')
  } catch (e: any) {
    mcToast.error(e?.message || '创建工程闭环 run 失败')
  } finally {
    creating.value = false
  }
}

async function loadRun() {
  if (!lookupRunId.value) return
  loadingRun.value = true
  executeMessage.value = ''
  try {
    const res: any = await loopEngineeringApi.getRun(lookupRunId.value)
    currentRun.value = res.data || null
  } catch (e: any) {
    mcToast.error(e?.message || '查询工程闭环 run 失败')
  } finally {
    loadingRun.value = false
  }
}

async function executeRun() {
  if (!currentRun.value?.id) return
  executing.value = true
  executeMessage.value = ''
  try {
    const res: any = await loopEngineeringApi.executeRun(currentRun.value.id)
    currentRun.value = res.data?.run || currentRun.value
    executeMessage.value = res.data?.message || ''
    mcToast.success('工程闭环验证已完成')
  } catch (e: any) {
    mcToast.error(e?.message || '执行工程闭环 run 失败')
  } finally {
    executing.value = false
  }
}

function buildPreviewPayload() {
  return {
    repoPath: form.repoPath,
    command: form.command,
    goal: form.goal,
  }
}

function sameId(a: unknown, b: unknown) {
  return a != null && b != null && String(a) === String(b)
}

function percent(value?: number) {
  if (value == null) return '-'
  return `${Math.round(value * 100)}%`
}

function formatJson(value?: string) {
  if (!value) return '-'
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return value
  }
}

function parseJsonArray(value?: string): any[] {
  if (!value) return []
  try {
    const parsed = JSON.parse(value)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function formatObject(value: unknown) {
  if (value == null) return '-'
  if (typeof value === 'string') return value
  return JSON.stringify(value, null, 2)
}

function statusClass(status?: string) {
  const normalized = String(status || '').toLowerCase()
  return {
    pass: ['passed', 'succeeded'].includes(normalized),
    danger: ['failed', 'error'].includes(normalized),
    warn: ['running', 'inconclusive', 'needs_human', 'skipped'].includes(normalized),
  }
}
</script>

<style scoped>
.loop-shell {
  height: 100%;
  min-height: 0;
  overflow: auto;
  background: var(--mc-bg);
}

.loop-frame {
  min-height: 100%;
}

.loop-page {
  display: block;
  color: var(--mc-text-primary);
  --loop-font-title: 31px;
  --loop-font-section: 18px;
  --loop-font-body: 14px;
  --loop-font-meta: 13px;
  --loop-font-code: 13px;
  --loop-line-body: 1.58;
  --loop-line-code: 1.66;
}

.loop-page,
.loop-page * {
  box-sizing: border-box;
}

.loop-page .mc-page-title {
  max-width: 680px;
  font-size: var(--loop-font-title);
  line-height: 1.16;
  letter-spacing: 0;
}

.loop-page .mc-page-kicker {
  color: var(--mc-accent);
  font-size: 13px;
  letter-spacing: 0.04em;
}

.loop-page .mc-page-desc {
  max-width: 680px;
  color: var(--mc-text-secondary);
  font-size: 15px;
  line-height: 1.65;
}

.header-actions,
.action-row,
.run-lookup,
.run-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.icon-btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.loop-page .btn-secondary,
.loop-page .btn-primary {
  min-height: 38px;
  padding: 0 14px;
  border-radius: 8px;
  font-size: var(--loop-font-body);
  line-height: 1;
  font-weight: 720;
  white-space: nowrap;
}

.icon-btn svg,
.btn-secondary svg,
.btn-primary svg {
  width: 16px;
  height: 16px;
}

.loop-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin: 18px 0;
}

.loop-metrics > div,
.loop-section {
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  background: linear-gradient(180deg, var(--mc-panel-top), var(--mc-panel-bottom));
  box-shadow: var(--mc-shadow-soft);
}

.loop-metrics > div {
  min-height: 76px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.metric-value {
  font-size: 28px;
  line-height: 1.12;
  font-weight: 760;
  color: var(--mc-text-primary);
}

.metric-label,
.muted {
  color: var(--mc-text-secondary);
  font-size: var(--loop-font-body);
  line-height: var(--loop-line-body);
}

.loop-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(360px, 0.85fr);
  gap: 18px;
  align-items: start;
}

.loop-section {
  min-width: 0;
  padding: 18px;
}

.detail-section {
  margin-top: 18px;
}

.section-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 16px;
}

.section-head h2 {
  margin: 0;
  font-size: var(--loop-font-section);
  line-height: 1.25;
  font-weight: 760;
  color: var(--mc-text-primary);
}

.section-head p {
  margin: 5px 0 0;
  color: var(--mc-text-secondary);
  font-size: var(--loop-font-body);
  line-height: 1.55;
}

.search-input,
.run-form input,
.run-form textarea,
.run-lookup input {
  width: 100%;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  font-size: var(--loop-font-body);
  font-weight: 620;
  line-height: 1.35;
  padding: 10px 12px;
  outline: none;
  transition: border-color 160ms ease, box-shadow 160ms ease;
}

.run-form .code-field {
  min-height: 58px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace;
  font-size: var(--loop-font-code);
  font-weight: 650;
  line-height: var(--loop-line-code);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.run-form .command-field {
  min-height: 92px;
}

.search-input {
  max-width: 260px;
}

.run-lookup input {
  width: 180px;
}

.run-form textarea {
  resize: vertical;
}

.search-input:focus,
.run-form input:focus,
.run-form textarea:focus,
.run-lookup input:focus {
  border-color: var(--mc-primary);
  box-shadow: 0 0 0 3px var(--mc-primary-bg);
}

.superpower-list {
  display: grid;
  gap: 10px;
  max-height: 560px;
  overflow: auto;
  padding-right: 2px;
}

.superpower-row {
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  padding: 14px;
  cursor: pointer;
  transition: border-color 160ms ease, background 160ms ease;
}

.superpower-row:hover,
.superpower-row.selected {
  border-color: var(--mc-primary-light);
  background: var(--mc-primary-bg);
}

.row-main {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.row-main strong {
  color: var(--mc-text-primary);
  font-size: 15px;
  line-height: 1.35;
  overflow-wrap: anywhere;
}

.row-main span {
  color: var(--mc-text-secondary);
  font-size: var(--loop-font-body);
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.row-meta,
.preview-title,
.run-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
}

.row-meta {
  margin: 10px 0;
  color: var(--mc-text-secondary);
  font-size: var(--loop-font-meta);
  line-height: 1.45;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.chip,
.status-pill {
  display: inline-flex;
  align-items: center;
  max-width: 100%;
  min-height: 27px;
  border-radius: 999px;
  border: 1px solid var(--mc-border);
  background: var(--mc-bg-sunken);
  color: var(--mc-text-secondary);
  padding: 0 10px;
  font-size: var(--loop-font-meta);
  font-weight: 720;
}

.chip {
  padding-top: 3px;
  padding-bottom: 3px;
  line-height: 1.35;
  white-space: normal;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.status-pill {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.chip.required {
  border-color: var(--mc-primary-light);
  background: var(--mc-primary-bg);
  color: var(--mc-attachment-color);
}

.chip.warn,
.status-pill.warn {
  border-color: var(--mc-tool-call-border);
  background: var(--mc-tool-call-bg);
  color: var(--mc-tool-call-color);
}

.status-pill.pass {
  border-color: color-mix(in srgb, var(--mc-success) 42%, transparent);
  background: color-mix(in srgb, var(--mc-success) 14%, transparent);
  color: var(--mc-success);
}

.status-pill.danger {
  border-color: var(--mc-danger-border);
  background: var(--mc-danger-bg);
  color: var(--mc-danger);
}

.run-form {
  display: grid;
  gap: 12px;
}

.run-form label {
  display: grid;
  gap: 6px;
  color: var(--mc-text-secondary);
  font-size: var(--loop-font-body);
  font-weight: 700;
}

.action-row {
  margin-top: 14px;
}

.preview-panel {
  margin-top: 16px;
  border: 1px solid var(--mc-primary-light);
  border-radius: 8px;
  padding: 16px;
  background: var(--mc-primary-bg);
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--mc-bg-elevated) 42%, transparent);
}

.preview-title {
  margin-bottom: 10px;
}

.preview-title strong {
  min-width: 0;
  color: var(--mc-text-primary);
  font-size: 15px;
  line-height: 1.35;
  font-weight: 780;
  overflow-wrap: anywhere;
}

.preview-panel .route-confidence {
  flex: 0 0 auto;
  border-color: var(--mc-primary);
  background: var(--mc-bg-elevated);
  color: var(--mc-primary-hover);
  font-size: var(--loop-font-meta);
  font-weight: 800;
}

.preview-panel .chip.required {
  border-color: var(--mc-primary-light);
  background: var(--mc-bg-elevated);
  color: var(--mc-attachment-color);
  font-weight: 760;
}

.preview-panel .chip.warn {
  border-color: var(--mc-tool-call-border);
  background: var(--mc-tool-call-bg);
  color: var(--mc-tool-call-color);
  font-weight: 780;
}

.run-detail {
  display: grid;
  gap: 14px;
}

.run-summary {
  border-bottom: 1px solid var(--mc-border-light);
  padding-bottom: 12px;
}

.run-summary > div:first-child {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.run-summary strong {
  color: var(--mc-text-primary);
}

.run-summary span {
  color: var(--mc-text-secondary);
  font-size: var(--loop-font-body);
  line-height: var(--loop-line-body);
}

.run-id {
  overflow-wrap: anywhere;
  word-break: break-word;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.detail-grid h3,
.trace-block h3 {
  margin: 0 0 8px;
  color: var(--mc-text-secondary);
  font-size: var(--loop-font-body);
  font-weight: 800;
}

.trace-block {
  min-width: 0;
  display: grid;
  gap: 10px;
}

.step-list,
.artifact-list {
  display: grid;
  gap: 10px;
  min-width: 0;
}

.step-card,
.artifact-card {
  min-width: 0;
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  background: var(--mc-bg-muted);
  padding: 12px;
}

.step-head,
.artifact-card {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.step-head strong,
.artifact-card strong {
  color: var(--mc-text-primary);
  font-size: 14px;
  line-height: 1.4;
  overflow-wrap: anywhere;
}

.step-card p {
  margin: 8px 0;
  color: var(--mc-text-secondary);
  font-size: var(--loop-font-body);
  line-height: 1.55;
}

.artifact-card > div:first-child {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.artifact-card span {
  color: var(--mc-text-secondary);
  font-size: var(--loop-font-meta);
  font-weight: 620;
  line-height: 1.45;
}

.artifact-path {
  min-width: 0;
  max-width: 62%;
  color: var(--mc-text-secondary);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace;
  font-size: var(--loop-font-code);
  font-weight: 650;
  line-height: 1.5;
  overflow-wrap: anywhere;
  text-align: right;
}

pre {
  min-height: 132px;
  max-height: 320px;
  overflow: auto;
  margin: 0;
  border: 1px solid var(--mc-code-header-border);
  border-radius: 8px;
  background: var(--mc-code-bg);
  color: var(--mc-code-text);
  padding: 12px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace;
  font-size: var(--loop-font-code);
  line-height: var(--loop-line-code);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
  tab-size: 2;
}

.empty-cell {
  border: 1px dashed var(--mc-border);
  border-radius: 8px;
  color: var(--mc-text-secondary);
  background: var(--mc-bg-muted);
  padding: 18px;
  text-align: center;
  font-size: var(--loop-font-body);
  line-height: var(--loop-line-body);
}

@media (max-width: 1180px) {
  .loop-grid,
  .detail-grid {
    grid-template-columns: 1fr;
  }

  .superpower-list {
    max-height: none;
  }
}

@media (max-width: 760px) {
  .loop-page {
    --loop-font-title: 25px;
    --loop-font-section: 17px;
    --loop-font-body: 14px;
    --loop-font-meta: 13px;
    --loop-font-code: 13px;
  }

  .loop-page .mc-page-title {
    line-height: 1.2;
  }

  .loop-page .mc-page-desc {
    font-size: 14px;
    line-height: 1.6;
  }

  .loop-metrics {
    grid-template-columns: 1fr;
    gap: 10px;
    margin: 14px 0;
  }

  .loop-metrics > div {
    min-height: 64px;
    padding: 12px 14px;
  }

  .metric-value {
    font-size: 24px;
  }

  .loop-grid {
    gap: 14px;
  }

  .loop-section {
    padding: 14px;
  }

  .section-head {
    margin-bottom: 12px;
  }

  .section-head,
  .run-summary,
  .preview-title,
  .step-head,
  .artifact-card {
    flex-direction: column;
    align-items: stretch;
  }

  .run-meta .status-pill,
  .step-head .status-pill {
    align-self: flex-start;
    width: auto;
  }

  .artifact-path {
    max-width: none;
    text-align: left;
  }

  .preview-panel .preview-title {
    flex-direction: row;
    align-items: center;
  }

  pre {
    min-height: 112px;
    max-height: 260px;
    padding: 10px;
  }

  .search-input,
  .run-lookup input {
    max-width: none;
    width: 100%;
  }

  .header-actions,
  .action-row,
  .run-lookup {
    width: 100%;
  }

  .header-actions button,
  .action-row button,
  .run-lookup button {
    width: 100%;
    justify-content: center;
  }
}
</style>
