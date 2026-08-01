<template>
  <el-dialog :model-value="modelValue" title="结构化转派" width="460px" @update:model-value="$emit('update:modelValue', $event)">
    <el-form label-position="top">
      <el-form-item label="目标团队"><el-input v-model="form.targetTeam" placeholder="如 DBA 组" /></el-form-item>
      <el-form-item label="转派说明"><el-input v-model="form.note" type="textarea" :rows="3" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="loading" :disabled="!form.targetTeam || !form.note" @click="submit">转派</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus/es/components/message/index'
import { troubleshootingApi } from '@/api'

const props = defineProps<{
  modelValue: boolean
  diagnosisId: string | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'submitted'): void
}>()

const form = reactive({ targetTeam: '', note: '' })
const loading = ref(false)

watch(() => props.modelValue, (open) => {
  if (open) {
    form.targetTeam = ''
    form.note = ''
  }
})

function errorText(error: unknown) { return error instanceof Error ? error.message : String(error) }

async function submit() {
  if (!props.diagnosisId) return
  loading.value = true
  try {
    await troubleshootingApi.transfer(props.diagnosisId, { ...form })
    ElMessage.success('已完成结构化转派')
    emit('update:modelValue', false)
    emit('submitted')
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    loading.value = false
  }
}
</script>
