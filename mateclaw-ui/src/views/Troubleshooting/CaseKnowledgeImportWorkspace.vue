<template>
  <CapabilityWorkspaceShell
    eyebrow="复盘与沉淀"
    :title="TROUBLESHOOTING_UI_LABELS.caseKnowledge"
    description="将已完成的排障记录沉淀为可检索、可复用的脱敏案例。"
    :refresh-loading="knowledgeBasesLoading"
    @back="$emit('back')"
    @refresh="$emit('refresh')"
  >
    <div class="case-knowledge-workspace">
      <el-alert type="info" :closable="false" class="workspace-alert">
        已关闭的排障单会沉淀为案例；未闭环记录只标记为“调查记录”，不会作为根因依据。
      </el-alert>

      <section class="import-section">
        <header>
          <span>导入设置</span>
          <h2>选择知识库和案例范围</h2>
          <p>从最新排障单开始导入；重复执行会复用同一版本，不会生成重复案例。</p>
        </header>

        <el-form class="import-form" label-position="top" @submit.prevent="$emit('submit')">
          <el-form-item label="目标知识库" required>
            <el-select
              v-model="form.knowledgeBaseId"
              :loading="knowledgeBasesLoading"
              filterable
              placeholder="选择当前工作区的知识库"
              style="width:100%"
            >
              <el-option
                v-for="kb in knowledgeBases"
                :key="String(kb.id)"
                :label="`${kb.name} · ${kb.pageCount ?? 0} 页 / ${kb.rawCount ?? 0} 份素材`"
                :value="String(kb.id)"
                :disabled="kb.status !== 'active'"
              />
            </el-select>
            <p v-if="!knowledgeBasesLoading && !knowledgeBases.length" class="form-hint">
              当前工作区还没有知识库，请先新建一个。
            </p>
          </el-form-item>

          <el-form-item label="最多导入条数">
            <el-input-number
              v-model="form.limit"
              :min="1"
              :max="MAX_CASE_KNOWLEDGE_IMPORT_LIMIT"
              :step="10"
            />
            <p class="form-hint">单次只处理最近的有界数量，便于核对结果。</p>
          </el-form-item>
        </el-form>

        <div class="import-actions">
          <el-button text @click="$emit('manage-wiki')">管理 Wiki 知识库</el-button>
          <el-button
            type="primary"
            :loading="loading"
            :disabled="!canSubmit"
            @click="$emit('submit')"
          >导入历史案例</el-button>
        </div>
      </section>

      <section v-if="result" class="case-knowledge-result" aria-live="polite">
        <span>最近一次执行</span>
        <h2>{{ caseKnowledgeImportSummary(result) }}</h2>
        <p :class="vectorStatus.tone">{{ vectorStatus.text }}</p>
        <small>不写入原始日志、DQL、观测载荷或凭据；只有“向量已就绪”的案例能够参与语义检索。</small>
      </section>
    </div>
  </CapabilityWorkspaceShell>
</template>

<script setup lang="ts">
import type { HistoricalCaseKnowledgeImportResult } from '@/api'
import CapabilityWorkspaceShell from './CapabilityWorkspaceShell.vue'
import {
  MAX_CASE_KNOWLEDGE_IMPORT_LIMIT,
  caseKnowledgeImportSummary,
} from './caseKnowledgeImport'
import { TROUBLESHOOTING_UI_LABELS } from './workbenchView'

type KnowledgeBaseOption = {
  id: string | number
  name: string
  status: string
  pageCount?: number
  rawCount?: number
}

defineProps<{
  knowledgeBases: KnowledgeBaseOption[]
  knowledgeBasesLoading: boolean
  result: HistoricalCaseKnowledgeImportResult | null
  vectorStatus: { tone: 'success' | 'warning'; text: string }
  loading: boolean
  canSubmit: boolean
}>()

const form = defineModel<{ knowledgeBaseId: string; limit: number }>('form', { required: true })

defineEmits<{
  back: []
  refresh: []
  submit: []
  'manage-wiki': []
}>()
</script>

<style scoped>
.case-knowledge-workspace { max-width:920px; }
.workspace-alert { margin-bottom:24px; }
.import-section,.case-knowledge-result { padding:22px 4px; border-top:1px solid var(--mc-border-light); }
.import-section>header span,.case-knowledge-result>span { color:var(--mc-primary); font-size:10px; font-weight:800; letter-spacing:.1em; text-transform:uppercase; }
.import-section>header h2,.case-knowledge-result>h2 { margin:5px 0 6px; color:var(--mc-text-primary); font-size:17px; letter-spacing:-.02em; }
.import-section>header p { margin:0; color:var(--mc-text-secondary); font-size:12px; line-height:1.6; }
.import-form { display:grid; grid-template-columns:minmax(0,1fr) 220px; gap:18px; margin-top:24px; }
.form-hint { margin:7px 0 0; color:var(--mc-text-tertiary); font-size:11px; line-height:1.55; }
.import-actions { display:flex; align-items:center; justify-content:flex-end; gap:8px; padding-top:4px; }
.case-knowledge-result { margin-top:12px; }
.case-knowledge-result>p { margin:10px 0; font-size:12px; line-height:1.6; }
.case-knowledge-result>p.success { color:var(--green); }
.case-knowledge-result>p.warning { color:var(--amber); }
.case-knowledge-result>small { display:block; color:var(--mc-text-secondary); font-size:11px; line-height:1.6; }
@media(max-width:760px){.import-form{grid-template-columns:1fr}.import-actions{align-items:stretch;flex-direction:column-reverse}.import-actions :deep(.el-button){width:100%;margin-left:0}}
</style>
