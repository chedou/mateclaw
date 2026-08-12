<template>
  <el-dialog
    v-model="open"
    :title="TROUBLESHOOTING_UI_LABELS.incident"
    width="min(620px, calc(100vw - 32px))"
  >
    <el-alert type="info" :closable="false" class="dialog-alert">
      该入口调用正式 Incident API 并真实创建 Diagnosis 记录；只提交现象和标识符，不接收原始日志、DQL、凭据、影响人数或调用方证据，也不会执行生产变更。
    </el-alert>
    <el-form label-position="top" @submit.prevent="$emit('submit')">
      <div class="incident-form-grid">
        <el-form-item label="故障系统" required>
          <el-input v-model="form.system" maxlength="128" placeholder="例如 CSDP" />
        </el-form-item>
        <el-form-item label="故障服务" required>
          <el-input v-model="form.service" maxlength="128" placeholder="例如 csdp-session-service" />
        </el-form-item>
        <el-form-item label="严重级别" required>
          <el-select v-model="form.severity" style="width: 100%">
            <el-option
              v-for="option in INCIDENT_SEVERITY_OPTIONS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="错误码（可选）">
          <el-input v-model="form.errorCode" maxlength="128" placeholder="例如 903001" />
        </el-form-item>
      </div>
      <el-form-item label="故障现象" required>
        <el-input
          v-model="form.title"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
          placeholder="描述用户可见现象；不要粘贴原始日志或密钥"
        />
      </el-form-item>
      <el-form-item label="Trace / PS 线索（可选）">
        <el-input v-model="form.traceId" maxlength="128" placeholder="只填写安全标识符，不粘贴链路正文" />
      </el-form-item>
      <div class="incident-route-preview" :class="routePreview.tone.toLowerCase()">
        <span>预期调查路径</span>
        <b>{{ routePreview.title }}</b>
        <p>{{ routePreview.detail }}</p>
      </div>
      <el-alert
        v-if="routePreview.tone === 'BOUNDED_DISCOVERY' && openDiscoveryReadiness"
        :type="openDiscoveryAlertType"
        :closable="false"
        show-icon
        class="dialog-alert"
        :title="openDiscoveryTitle"
      >
        <p>{{ openDiscoveryReadiness.nextAction }}</p>
        <p v-if="openDiscoveryReadiness.visiblePlanCount">
          当前系统可见计划 {{ openDiscoveryReadiness.visiblePlanCount }} /
          已配置 {{ openDiscoveryReadiness.configuredPlanCount }}
          <template v-if="!openDiscoveryReadiness.trueSourcePermitted"> · 仍仅 recorded-replay</template>
        </p>
        <ul v-if="openDiscoveryReadiness.blockers.length" class="readiness-blockers">
          <li v-for="blocker in openDiscoveryReadiness.blockers.slice(0, 4)" :key="blocker">{{ blocker }}</li>
        </ul>
      </el-alert>
      <el-checkbox v-model="form.rehearsal" class="incident-rehearsal">
        演练记录（推荐；不参与五分钟生产事件去重）
      </el-checkbox>
      <p class="form-hint">演练记录也会进入队列并明确标记；关闭演练标记后按正式事件启用五分钟幂等。两种模式都只读取证，生产处置仍由人工完成。</p>
    </el-form>
    <template #footer>
      <el-button @click="open = false">取消</el-button>
      <el-button
        type="primary"
        :loading="loading"
        :disabled="!canSubmit"
        @click="$emit('submit')"
      >创建 Diagnosis</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { OpenDiscoveryReadiness } from '@/api'
import type { FormalIncidentForm } from './incidentReport'
import { INCIDENT_SEVERITY_OPTIONS } from './intakeDialog'
import { TROUBLESHOOTING_UI_LABELS } from './workbenchView'

const props = defineProps<{
  routePreview: { tone: string; title: string; detail: string }
  loading: boolean
  canSubmit: boolean
  openDiscoveryReadiness?: OpenDiscoveryReadiness | null
}>()

const open = defineModel<boolean>({ required: true })
const form = defineModel<FormalIncidentForm>('form', { required: true })
defineEmits<{ submit: [] }>()

const openDiscoveryAlertType = computed(() => {
  const status = props.openDiscoveryReadiness?.status
  if (status === 'READY_FOR_BOUNDED_FALLBACK') return 'success'
  if (status === 'READY_FOR_REHEARSAL') return 'warning'
  return 'error'
})

const openDiscoveryTitle = computed(() => {
  switch (props.openDiscoveryReadiness?.status) {
    case 'READY_FOR_BOUNDED_FALLBACK':
      return '开放调查兜底可用（真源计划已允许）'
    case 'READY_FOR_REHEARSAL':
      return '开放调查可演练，尚未接真源'
    case 'DISABLED':
      return '开放调查未启用'
    default:
      return '开放调查尚未就绪'
  }
})
</script>

<style scoped src="./intakeDialog.css"></style>
<style scoped>
.readiness-blockers {
  margin: 8px 0 0;
  padding-left: 18px;
  line-height: 1.5;
}
</style>
