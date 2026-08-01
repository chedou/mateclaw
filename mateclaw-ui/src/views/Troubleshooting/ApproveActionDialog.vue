<template>
  <el-dialog :model-value="modelValue" title="批准生产写操作" width="480px" @update:model-value="$emit('update:modelValue', $event)">
    <el-alert type="warning" :closable="false" class="dialog-alert">批准只推进状态机，MateClaw 不执行任何操作。变更须由授权人员在平台外完成。</el-alert>
    <el-form label-position="top"><el-form-item label="批准理由（审计依据）"><el-input v-model="form.reason" type="textarea" :rows="3" /></el-form-item></el-form>
    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button type="warning" :loading="loading" :disabled="!form.reason" @click="submit">批准（不执行）</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus/es/components/message/index'
import { troubleshootingApi, type RecommendedAction } from '@/api'

const props = defineProps<{
  modelValue: boolean
  diagnosisId: string | null
  targetAction: RecommendedAction | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'submitted'): void
}>()

const form = reactive({ reason: '' })
const loading = ref(false)

watch(() => props.modelValue, (open) => {
  if (open) {
    form.reason = ''
  }
})

function errorText(error: unknown) { return error instanceof Error ? error.message : String(error) }

async function submit() {
  if (!props.diagnosisId || !props.targetAction) return
  loading.value = true
  try {
    await troubleshootingApi.approveAction(
      props.diagnosisId, props.targetAction.actionId, { reason: form.reason },
    )
    ElMessage.success('已批准；系统未执行生产变更')
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
.dialog-alert { margin-bottom: 14px; }
</style>
