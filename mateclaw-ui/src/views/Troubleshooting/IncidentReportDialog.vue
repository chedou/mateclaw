<template>
  <el-drawer
    v-model="open"
    :title="TROUBLESHOOTING_UI_LABELS.incident"
    :size="'var(--mc-ts-drawer-width)'"
    destroy-on-close
  >
    <div class="launch-guide">
      <p class="launch-lead">把告警里的系统、服务和现象填进来即可。不要贴日志、DQL 或密钥。</p>
    </div>

    <el-form label-position="top" @submit.prevent="$emit('submit')">
      <div class="incident-form-grid">
        <el-form-item label="哪个系统" required>
          <el-input v-model="form.system" maxlength="128" placeholder="例如 CSDP" />
        </el-form-item>
        <el-form-item label="哪个服务" required>
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
        <el-form-item label="错误码（有就填）">
          <el-input v-model="form.errorCode" maxlength="128" placeholder="例如 904003；没有可留空" />
        </el-form-item>
        <el-form-item label="故障发生时间（有就填）">
          <el-date-picker
            v-model="form.occurredAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
            placeholder="不填则按当前时间调查"
            style="width: 100%"
          />
          <span class="incident-field-hint">历史告警请填写原始时间，系统会据此确定取证窗口。</span>
        </el-form-item>
      </div>
      <el-form-item label="故障现象" required>
        <el-input
          v-model="form.title"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
          placeholder="粘贴告警里用户可见的现象，一两句话即可"
        />
      </el-form-item>
      <el-form-item label="Trace / 关联 ID（可选）">
        <el-input v-model="form.traceId" maxlength="128" placeholder="只填标识符，不要粘贴链路正文" />
      </el-form-item>
      <div class="incident-route-preview" :class="routePreview.tone.toLowerCase()">
        <span>系统会怎么查</span>
        <b>{{ routePreview.title }}</b>
        <p>{{ routePreview.detail }}</p>
      </div>
      <el-alert
        v-if="routePreview.tone === 'BOUNDED_DISCOVERY' && openDiscoveryReadiness"
        :type="readinessPresentation?.alertType"
        :closable="false"
        show-icon
        class="dialog-alert"
        :title="readinessPresentation?.title"
      >
        <p>{{ readinessPresentation?.detail }}</p>
      </el-alert>
      <section class="incident-mode" aria-labelledby="incident-mode-title">
        <div class="incident-mode-head">
          <b id="incident-mode-title">这次要怎么查</b>
          <span>{{ form.rehearsal ? '当前为演练' : '当前为正式排障' }}</span>
        </div>
        <el-radio-group v-model="form.rehearsal" class="incident-mode-options">
          <el-radio-button :value="false">真实告警 · 正式只读调查</el-radio-button>
          <el-radio-button :value="true">试用演练</el-radio-button>
        </el-radio-group>
        <p>{{ incidentModeDetail }}</p>
      </section>
    </el-form>

    <template #footer>
      <el-button text @click="$emit('pick-conversation')">改用对话补问</el-button>
      <el-button text @click="$emit('pick-scenario')">这是已登记场景？</el-button>
      <el-button @click="open = false">取消</el-button>
      <el-button
        type="primary"
        :loading="loading"
        :disabled="!canSubmit"
        @click="$emit('submit')"
      >{{ form.rehearsal ? '生成演练单' : '开始正式只读调查' }}</el-button>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { OpenDiscoveryReadiness } from '@/api'
import type { FormalIncidentForm } from './incidentReport'
import { openDiscoveryReadinessPresentation } from './formalProjection'
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
defineEmits<{ submit: []; 'pick-scenario': []; 'pick-conversation': [] }>()

const readinessPresentation = computed(() => props.openDiscoveryReadiness
  ? openDiscoveryReadinessPresentation(props.openDiscoveryReadiness)
  : null)

const incidentModeDetail = computed(() => form.value.rehearsal
  ? '仅用于熟悉流程，不占用正式排障的去重窗口，也不计入正式验收。'
  : '面向真实告警，只读查询已接入的数据源；证据不足时会停止并说明还缺什么，不会修改生产。')
</script>

<style scoped src="./intakeDialog.css"></style>
<style scoped>
.launch-guide {
  margin: 0 0 16px;
  padding: 14px 16px;
  border: 1px solid var(--mc-border);
  border-radius: var(--mc-radius-sm, 8px);
  background: var(--mc-bg-muted);
}
.launch-lead {
  margin: 0;
  color: var(--mc-text-primary);
  font-size: 13px;
  line-height: 1.65;
}
.launch-checklist {
  margin: 10px 0 0;
  padding-left: 18px;
  color: var(--mc-text-secondary);
  font-size: 12px;
  line-height: 1.7;
}
.incident-field-hint {
  display: block;
  margin-top: 6px;
  color: var(--mc-text-tertiary);
  font-size: 11px;
  line-height: 1.5;
}
.incident-mode {
  margin-top: 16px;
  padding: 14px 16px;
  border: 1px solid var(--mc-border);
  border-radius: var(--mc-radius-sm, 8px);
  background: var(--mc-bg-muted);
}
.incident-mode-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}
.incident-mode-head b { color: var(--mc-text-primary); font-size: 13px; }
.incident-mode-head span { color: var(--mc-text-tertiary); font-size: 11px; }
.incident-mode-options {
  display: flex;
  margin-top: 10px;
}
.incident-mode-options :deep(.el-radio-button) { flex: 1; }
.incident-mode-options :deep(.el-radio-button__inner) { width: 100%; }
.incident-mode p {
  margin: 10px 0 0;
  color: var(--mc-text-secondary);
  font-size: 12px;
  line-height: 1.6;
}
</style>
