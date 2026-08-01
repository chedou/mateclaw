<template>
  <el-dialog :model-value="modelValue" title="关闭归档" width="480px" @update:model-value="$emit('update:modelValue', $event)">
    <el-form label-position="top">
      <el-form-item label="关闭结论"><el-select v-model="form.outcome" style="width: 100%"><el-option label="RECOVERED · 已恢复" value="RECOVERED" /><el-option label="FALSE_POSITIVE · 误报" value="FALSE_POSITIVE" /><el-option label="TRANSFERRED_OUT · 转出处置" value="TRANSFERRED_OUT" /><el-option label="UNRESOLVED · 未解决" value="UNRESOLVED" /></el-select></el-form-item>
      <el-form-item label="关闭摘要"><el-input v-model="form.summary" type="textarea" :rows="3" /></el-form-item>
      <el-form-item v-if="form.outcome === 'RECOVERED'"><el-checkbox v-model="form.recoveryVerified">已验证恢复</el-checkbox></el-form-item>
      <el-form-item label="对 Playbook 的反馈（可选）"><el-input v-model="form.sopFeedback" type="textarea" :rows="2" /></el-form-item>
      <el-form-item><el-checkbox v-model="form.createKnowledgeCandidate">生成知识候选</el-checkbox><p class="form-hint">候选只会被记录并进入发布链路；当前没有独立审核状态，更不会覆盖已批准 Playbook。</p></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="loading" :disabled="!form.summary" @click="submit">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus/es/components/message/index'
import { troubleshootingApi, type ClosureOutcome } from '@/api'

const props = defineProps<{
  modelValue: boolean
  diagnosisId: string | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'submitted'): void
}>()

const form = reactive<{
  outcome: ClosureOutcome
  summary: string
  recoveryVerified: boolean
  sopFeedback: string
  createKnowledgeCandidate: boolean
}>({
  outcome: 'RECOVERED',
  summary: '',
  recoveryVerified: false,
  sopFeedback: '',
  createKnowledgeCandidate: true,
})
const loading = ref(false)

watch(() => props.modelValue, (open) => {
  if (open) {
    form.outcome = 'RECOVERED'
    form.summary = ''
    form.recoveryVerified = false
    form.sopFeedback = ''
    form.createKnowledgeCandidate = true
  }
})

function errorText(error: unknown) { return error instanceof Error ? error.message : String(error) }

async function submit() {
  if (!props.diagnosisId) return
  loading.value = true
  try {
    await troubleshootingApi.close(props.diagnosisId, {
      outcome: form.outcome,
      summary: form.summary,
      recoveryVerified: form.outcome === 'RECOVERED' && form.recoveryVerified,
      sopFeedback: form.sopFeedback || null,
      createKnowledgeCandidate: form.createKnowledgeCandidate,
    })
    ElMessage.success('诊断已关闭归档')
    emit('update:modelValue', false)
    emit('submitted')
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.form-hint { margin: 4px 0 0; color: var(--mc-text-secondary); font-size: 10px; }
</style>
