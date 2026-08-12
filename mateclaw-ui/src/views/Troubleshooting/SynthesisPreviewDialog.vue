<template>
  <el-drawer
    v-if="!embedded"
    v-model="visible"
    :title="TROUBLESHOOTING_UI_LABELS.historyReplay"
    :size="'var(--mc-ts-drawer-width)'"
    destroy-on-close
    class="synthesis-preview-drawer"
  >
    <SynthesisPreviewBody
      :form="form"
      :preview="preview"
      :loading="loading"
      :can-preview="canPreview"
      :evidence-steps="evidenceSteps"
      :preview-contrast-narrative="previewContrastNarrative"
      @run="runPreview"
      @close="visible = false"
    />
  </el-drawer>

  <SynthesisPreviewBody
    v-else
    embedded
    :form="form"
    :preview="preview"
    :loading="loading"
    :can-preview="canPreview"
    :evidence-steps="evidenceSteps"
    :preview-contrast-narrative="previewContrastNarrative"
    @run="runPreview"
  />
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  troubleshootingApi,
  type SopSynthesisPreview,
  type SopSynthesisPreviewRequest,
} from '@/api'
import {
  buildSynthesisEvidenceSteps,
  normalizeSynthesisPreviewRequest,
} from './synthesisPreview'
import { TROUBLESHOOTING_UI_LABELS } from './workbenchView'
import { evidenceComparisonNarrative } from './evidencePlainLanguage'
import SynthesisPreviewBody from './SynthesisPreviewBody.vue'

const props = withDefaults(defineProps<{
  modelValue?: boolean
  embedded?: boolean
}>(), {
  modelValue: false,
  embedded: false,
})
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const form = reactive<SopSynthesisPreviewRequest>({
  system: 'CSDP',
  service: 'csdp-session-service',
  searchTerm: 'message_send_failed',
  window: '-15m',
  occurredAt: null,
})
const preview = ref<SopSynthesisPreview | null>(null)
const loading = ref(false)
const safeIdentifier = /^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$/
let previewRequestVersion = 0

const sessionActive = computed(() => props.embedded || visible.value)
const canPreview = computed(() => [form.system, form.service, form.searchTerm]
  .every((value) => safeIdentifier.test(value.trim())) && Boolean(form.window))
const evidenceSteps = computed(() => preview.value
  ? buildSynthesisEvidenceSteps(preview.value)
  : [])
const previewContrastNarrative = computed(() => {
  const contrast = preview.value?.skeleton.contrast
  if (!contrast?.available) return null
  return evidenceComparisonNarrative({
    featureCode: contrast.discriminatingFeature,
    failureRequestCount: contrast.failureSampleCount,
    failureWithFeatureCount: contrast.failureMatchCount,
    normalRequestCount: contrast.successSampleCount,
    normalWithFeatureCount: contrast.successMatchCount,
  })
})

watch(
  () => [form.system, form.service, form.searchTerm, form.window, form.occurredAt],
  () => resetPreview(),
)
watch(sessionActive, (isActive) => {
  if (!isActive) resetPreview()
})

async function runPreview() {
  if (!canPreview.value) return
  const requestVersion = ++previewRequestVersion
  loading.value = true
  try {
    const { data } = await troubleshootingApi.previewSopSynthesis(
      normalizeSynthesisPreviewRequest(form),
    )
    if (requestVersion !== previewRequestVersion || !sessionActive.value) return
    preview.value = data
    ElMessage.success('历史证据已完成只读回放与确定性压缩')
  } catch (error) {
    if (requestVersion !== previewRequestVersion || !sessionActive.value) return
    preview.value = null
    ElMessage.error(error instanceof Error ? error.message : String(error))
  } finally {
    if (requestVersion === previewRequestVersion) loading.value = false
  }
}

function resetPreview() {
  previewRequestVersion += 1
  preview.value = null
  loading.value = false
}
</script>
