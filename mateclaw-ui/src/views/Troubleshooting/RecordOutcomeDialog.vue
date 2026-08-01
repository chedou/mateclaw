<template>
  <el-dialog :model-value="modelValue" title="登记外部处置结果" width="480px" @update:model-value="$emit('update:modelValue', $event)">
    <el-form label-position="top">
      <el-form-item label="处置结果"><el-select v-model="form.outcome" style="width: 100%"><el-option label="SUCCEEDED · 成功" value="SUCCEEDED" /><el-option label="FAILED · 失败" value="FAILED" /><el-option label="SKIPPED · 未执行" value="SKIPPED" /></el-select></el-form-item>
      <el-form-item label="结果说明"><el-input v-model="form.notes" type="textarea" :rows="3" /></el-form-item>
      <el-form-item><el-checkbox v-model="form.recoveryVerified" :disabled="form.outcome !== 'SUCCEEDED'">已验证故障恢复</el-checkbox></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="loading" :disabled="!form.notes" @click="submit">登记</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus/es/components/message/index'
import { troubleshootingApi, type ActionOutcomeStatus, type RecommendedAction } from '@/api'

const props = defineProps<{
  modelValue: boolean
  diagnosisId: string | null
  targetAction: RecommendedAction | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'submitted'): void
}>()

const form = reactive<{ outcome: ActionOutcomeStatus; notes: string; recoveryVerified: boolean }>({
  outcome: 'SUCCEEDED',
  notes: '',
  recoveryVerified: false,
})
const loading = ref(false)

watch(() => props.modelValue, (open) => {
  if (open) {
    form.outcome = 'SUCCEEDED'
    form.notes = ''
    form.recoveryVerified = false
  }
})

function errorText(error: unknown) { return error instanceof Error ? error.message : String(error) }

async function submit() {
  if (!props.diagnosisId || !props.targetAction) return
  loading.value = true
  try {
    await troubleshootingApi.recordOutcome(props.diagnosisId, props.targetAction.actionId, {
      outcome: form.outcome,
      notes: form.notes,
      recoveryVerified: form.outcome === 'SUCCEEDED' && form.recoveryVerified,
    })
    ElMessage.success('已登记平台外处置结果')
    emit('update:modelValue', false)
    emit('submitted')
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    loading.value = false
  }
}
</script>
