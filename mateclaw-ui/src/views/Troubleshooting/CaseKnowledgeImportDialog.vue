<template>
  <el-dialog
    v-model="open"
    :title="TROUBLESHOOTING_UI_LABELS.caseKnowledge"
    width="min(680px, calc(100vw - 32px))"
  >
    <el-alert type="info" :closable="false" class="dialog-alert">
      把已有排障单确定性转换为脱敏案例快照，写入现有 Wiki 知识库。未闭环案例只标记为“调查记录”，不作为根因依据。
    </el-alert>
    <el-form label-position="top" @submit.prevent="$emit('submit')">
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
          当前工作区还没有知识库，请先到 Wiki 新建一个。
        </p>
      </el-form-item>
      <el-form-item label="最多导入条数">
        <el-input-number
          v-model="form.limit"
          :min="1"
          :max="MAX_CASE_KNOWLEDGE_IMPORT_LIMIT"
          :step="10"
        />
        <p class="form-hint">从最新排障单开始；重复执行会复用同一版本的案例页面和原始素材。</p>
      </el-form-item>
    </el-form>

    <div v-if="result" class="case-knowledge-result">
      <b>{{ caseKnowledgeImportSummary(result) }}</b>
      <p :class="vectorStatus.tone">{{ vectorStatus.text }}</p>
      <small>入库内容不包含原始日志、DQL、观测载荷或凭据；语义检索只对“向量已就绪”的案例生效。</small>
    </div>

    <template #footer>
      <el-button text @click="$emit('manage-wiki')">管理 Wiki 知识库</el-button>
      <el-button @click="open = false">关闭</el-button>
      <el-button
        type="primary"
        :loading="loading"
        :disabled="!canSubmit"
        @click="$emit('submit')"
      >导入历史案例</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import type { HistoricalCaseKnowledgeImportResult } from '@/api'
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

const open = defineModel<boolean>({ required: true })
const form = defineModel<{ knowledgeBaseId: string; limit: number }>('form', { required: true })

defineEmits<{
  submit: []
  'manage-wiki': []
}>()
</script>

<style scoped src="./intakeDialog.css"></style>
<style scoped>
.case-knowledge-result { margin-top:14px; padding:13px; border:1px solid var(--line); border-radius:var(--mc-radius-xs); background:var(--mc-bg-elevated); }
.case-knowledge-result>b { font-size:var(--mc-text-sm); }
.case-knowledge-result>p { margin:8px 0; font-size:var(--mc-text-xs); line-height:1.6; }
.case-knowledge-result>p.success { color:var(--green); }
.case-knowledge-result>p.warning { color:var(--amber); }
.case-knowledge-result>small { display:block; color:var(--muted); font-size:var(--mc-text-xs); line-height:1.6; }
</style>
