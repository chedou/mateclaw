<template>
  <div class="workspace">
    <section class="registry" aria-label="SOP 注册表">
      <el-table
        :data="rows"
        :aria-busy="listLoading"
        row-key="routeKey"
        height="100%"
        :row-class-name="rowClassName"
        @row-click="(row: SopSummary) => $emit('select-sop', row)"
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
        <el-table-column label="判据来源" width="126">
          <template #default="{ row }">
            <el-tag
              size="small"
              effect="plain"
              :type="knowledgeGradeTagType(row.knowledgeEvidenceGrade)"
            >{{ knowledgeEvidenceGradeLabel(row.knowledgeEvidenceGrade) }}</el-tag>
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
        <el-button type="primary" plain @click="$emit('openRegister')">注册第一条 SOP</el-button>
      </div>
    </section>

    <el-drawer
      :model-value="Boolean(selectedRouteKey)"
      :size="'var(--mc-ts-drawer-width)'"
      destroy-on-close
      class="sop-detail-drawer"
      :title="drawerTitle"
      @update:model-value="onDrawerOpenChange"
    >
      <div
        class="inspector"
        :class="{ 'is-loading': detailLoading }"
        :aria-busy="detailLoading"
        aria-label="SOP 详情检查器"
      >
        <div v-if="!selectedSop" class="inspector-empty">
          <span class="empty-mark">{ }</span>
          <strong>{{ detailLoading ? '正在加载规则详情…' : '暂无详情' }}</strong>
          <p>点选列表中的路由后，完整判据、规则与动作会显示在这里。</p>
        </div>

        <div
          v-else
          :key="selectedSop.system + ':' + selectedSop.errorCode"
          class="inspector-body"
        >
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
            title="该版本已退出命中路；替代版本只能由通过资格门禁的审核决策创建。"
          />

          <dl class="metadata">
            <div><dt>service</dt><dd>{{ selectedSop.service }}</dd></div>
            <div><dt>owner</dt><dd>{{ selectedSop.ownerTeam || '未指定' }}</dd></div>
            <div><dt>category</dt><dd>{{ selectedSop.category || '未分类' }}</dd></div>
            <div><dt>verified</dt><dd class="mono">{{ selectedSop.verified }}</dd></div>
            <div><dt>判据来源</dt><dd>{{ selectedSopSummary ? knowledgeEvidenceGradeLabel(selectedSopSummary.knowledgeEvidenceGrade) : '来源未核实' }}</dd></div>
          </dl>

          <section class="contract-health">
            <div class="section-title">
              <span>规则组成</span>
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

          <section class="structured-section">
            <div class="section-title"><span>取证步骤</span></div>
            <div
              v-for="(item, index) in selectedSop.evidenceRequests"
              :key="`ev-${index}`"
              class="step-card"
            >
              <strong>{{ index + 1 }}. {{ String(item.requestId || '未命名') }}</strong>
              <p>{{ String(item.purpose || '未填写目的') }}</p>
              <div class="step-meta">
                <span>类型 {{ String(item.signalKind || '—') }}</span>
                <span>窗口 {{ String(item.window || '—') }}</span>
                <span>{{ item.required === false ? '可选' : '必做' }}</span>
              </div>
            </div>
            <p v-if="!selectedSop.evidenceRequests.length" class="empty-line">无取证步骤</p>
          </section>

          <section class="structured-section">
            <div class="section-title"><span>异常判据</span></div>
            <div
              v-for="(item, index) in selectedSop.anomalyCriteria"
              :key="`cr-${index}`"
              class="step-card"
            >
              <strong>{{ index + 1 }}. {{ String(item.signal || '未命名信号') }}</strong>
              <p>{{ String(item.description || '未填写说明') }}</p>
              <div class="step-meta">
                <span>来自 {{ String(item.sourceRequestId || '—') }}</span>
                <span>规则 {{ criterionRuleLabel(item) }}</span>
              </div>
            </div>
            <p v-if="!selectedSop.anomalyCriteria.length" class="empty-line">无异常判据</p>
          </section>

          <section class="structured-section">
            <div class="section-title"><span>诊断结论</span></div>
            <div
              v-for="(item, index) in selectedSop.diagnosisRules"
              :key="`dr-${index}`"
              class="step-card"
            >
              <strong>{{ index + 1 }}. {{ String(item.ruleId || '未命名规则') }}</strong>
              <p>{{ String(item.rootCause || item.summary || '未填写根因') }}</p>
              <div class="step-meta">
                <span>置信度 {{ String(item.confidence || '—') }}</span>
                <span>信号 {{ Array.isArray(item.requiredSignals) ? item.requiredSignals.join(', ') : '—' }}</span>
              </div>
            </div>
            <p v-if="!selectedSop.diagnosisRules.length" class="empty-line">无诊断规则</p>
          </section>

          <section class="json-section">
            <div class="section-title">
              <span>完整 SOP JSON（高级）</span>
              <div class="json-actions">
                <el-button size="small" text @click="jsonExpanded = !jsonExpanded">
                  {{ jsonExpanded ? '收起' : '展开' }}
                </el-button>
                <el-button size="small" text @click="$emit('copyContract')">复制</el-button>
              </div>
            </div>
            <pre v-show="jsonExpanded">{{ prettyContract }}</pre>
          </section>

          <div class="review-action">
            <template v-if="selectedSop.status === 'candidate'">
              <div>
                <strong>晋升只走知识审核</strong>
                <p>请在"知识候选"核对来源、回放与 owner；旧式 candidate → approved 状态修改继续禁用。</p>
              </div>
              <el-button disabled>前往知识候选审批</el-button>
            </template>
            <template
              v-else-if="nextStatus === 'deprecated' && selectedSopSummary?.playbookVersion != null"
            >
              <div>
                <strong>版本退役只走原审核记录</strong>
                <p v-if="selectedSopSummary.reviewId">
                  当前为 Playbook v{{ selectedSopSummary.playbookVersion }}；退役需精确匹配审核版本并记录原因。
                </p>
                <p v-else>
                  这是迁移生成的 LEGACY 版本；可携精确 Playbook 版本和原因执行一次受审计退役，也可注册新的人工 source 走版本替代。
                </p>
              </div>
              <el-button
                v-if="selectedSopSummary.reviewId"
                type="danger"
                plain
                @click="$emit('openReviewForVersion')"
              >前往审核记录退役</el-button>
              <el-button
                v-else-if="selectedSopSummary.sourceOrigin === 'LEGACY'"
                type="danger"
                plain
                :loading="statusUpdating"
                @click="$emit('deprecateLegacy')"
              >退役迁移版本</el-button>
              <el-button v-else disabled>缺少可审计来源</el-button>
            </template>
            <template v-else-if="nextStatus === 'deprecated'">
              <div>
                <strong>将当前版本退出命中路</strong>
                <p>标记后该路由将退出命中路；替代版本必须重新通过版本审核流程。</p>
              </div>
              <el-button
                type="danger"
                plain
                :loading="statusUpdating"
                @click="$emit('advanceStatus')"
              >标记过期</el-button>
            </template>
            <span v-else>生命周期已结束；该版本只保留审计记录。</span>
          </div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { KnowledgeEvidenceGrade, SopEntry, SopStatus, SopSummary } from '@/api'
import { knowledgeEvidenceGradeLabel } from './formalProjection'

const jsonExpanded = ref(false)

function knowledgeGradeTagType(grade: KnowledgeEvidenceGrade) {
  if (grade === 'RECORDED_AGGREGATE') return 'success'
  if (grade === 'AUTHORED_FIXTURE') return 'warning'
  return 'info'
}

function criterionRuleLabel(item: Record<string, unknown>) {
  const rule = item.rule && typeof item.rule === 'object' && !Array.isArray(item.rule)
    ? item.rule as Record<string, unknown>
    : {}
  const kind = String(rule.kind || '—')
  const field = String(rule.field || '')
  const threshold = rule.threshold
  if (field && threshold !== undefined) return `${kind} ${field} ${threshold}`
  if (field) return `${kind} ${field}`
  return kind
}

const STATUS_LABEL: Record<SopStatus, string> = {
  candidate: '待审核',
  approved: '已生效',
  deprecated: '已过期',
}

const props = defineProps<{
  rows: SopSummary[]
  selectedRouteKey: string | null
  selectedSop: SopEntry | null
  selectedSopSummary: SopSummary | null
  listLoading: boolean
  detailLoading: boolean
  statusUpdating: boolean
  nextStatus: Exclude<SopStatus, 'candidate'> | null
  prettyContract: string
  containsManualWrite: boolean
  contractWarnings: string[]
  rowClassName: (args: { row: SopSummary }) => string
  statusTagType: (status: SopStatus) => 'warning' | 'success' | 'info'
  statusLabel: (status: SopStatus) => string
  formatTime: (value?: string | null) => string
}>()

const emit = defineEmits<{
  'select-sop': [row: SopSummary]
  'openRegister': []
  'advanceStatus': []
  'openReviewForVersion': []
  'deprecateLegacy': []
  'copyContract': []
  'clearSelection': []
}>()

const drawerTitle = computed(() => {
  if (props.selectedSop?.title) return props.selectedSop.title
  if (props.selectedRouteKey) return props.selectedRouteKey
  return '规则详情'
})

function onDrawerOpenChange(open: boolean) {
  if (!open) emit('clearSelection')
}
</script>

<style scoped>
.workspace { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.registry { flex: 1; min-width: 0; min-height: 0; position: relative; }
.route-cell { display: flex; flex-direction: column; gap: 2px; }
.route-cell strong { font: 600 12px var(--mc-mono, monospace); color: var(--el-text-color-primary); }
.route-cell span { font: 10px var(--mc-mono, monospace); color: var(--el-text-color-placeholder); }
.mono { font-family: var(--mc-mono, monospace); }
.muted { color: var(--el-text-color-secondary); font-size: 10.5px; }
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

.inspector {
  min-width: 0;
  min-height: 0;
  transition: opacity 120ms ease;
}
.inspector.is-loading { opacity: .62; pointer-events: none; }
.inspector-empty {
  min-height: 240px; display: flex; flex-direction: column; align-items: center; justify-content: center;
  padding: 28px; text-align: center; color: var(--el-text-color-secondary);
}
.inspector-empty strong { color: var(--el-text-color-primary); font-size: 13px; }
.inspector-empty p { max-width: 280px; margin: 7px 0 0; font-size: 11.5px; line-height: 1.6; }
.empty-mark { margin-bottom: 14px; font: 24px var(--mc-mono, monospace); color: var(--el-text-color-placeholder); }
.inspector-body { padding: 0 4px 12px; }
.inspector-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin-bottom: 14px; }
.eyebrow { color: var(--el-text-color-secondary); font: 10px var(--mc-mono, monospace); }
.inspector-head h2 { margin: 5px 0 4px; font-size: 17px; line-height: 1.45; }
.route-line { color: var(--mc-primary); font: 600 11.5px var(--mc-mono, monospace); }
.metadata { margin: 16px 0 0; display: grid; grid-template-columns: 1fr 1fr; border-top: 1px solid var(--el-border-color-lighter); }
.metadata div { padding: 9px 0; border-bottom: 1px solid var(--el-border-color-lighter); }
.metadata div:nth-child(odd) { padding-right: 12px; }
.metadata dt { color: var(--el-text-color-secondary); font: 10px var(--mc-mono, monospace); }
.metadata dd { margin: 4px 0 0; font-size: 11.5px; color: var(--el-text-color-primary); }
.contract-health, .json-section, .structured-section { margin-top: 18px; }
.section-title { display: flex; align-items: center; justify-content: space-between; margin-bottom: 9px; font-size: 12px; font-weight: 650; }
.json-actions { display: flex; align-items: center; gap: 2px; }
.step-card {
  margin-top: 8px;
  padding: 10px 11px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-bg-color);
}
.step-card strong { display: block; font-size: 12px; color: var(--el-text-color-primary); }
.step-card p { margin: 5px 0 0; color: var(--el-text-color-regular); font-size: 11px; line-height: 1.5; }
.step-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 12px;
  margin-top: 7px;
  color: var(--el-text-color-secondary);
  font-size: 10.5px;
}
.empty-line { margin: 6px 0 0; color: var(--el-text-color-placeholder); font-size: 11px; }
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

:deep(.el-table) {
  --el-table-row-hover-bg-color: color-mix(in srgb, var(--mc-primary) 7%, var(--el-bg-color));
}
:deep(.el-table__row) { cursor: pointer; transition: background-color 140ms ease; }
:deep(.selected-row > td.el-table__cell) {
  background: color-mix(in srgb, var(--mc-primary) 12%, var(--el-bg-color)) !important;
}
:deep(.selected-row > td:first-child) { box-shadow: inset 3px 0 0 var(--mc-primary); }

@media (prefers-reduced-motion: reduce) {
  :deep(.el-table__row), .inspector { transition: none; }
}
</style>
