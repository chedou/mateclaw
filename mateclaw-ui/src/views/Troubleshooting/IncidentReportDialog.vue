<template>
  <el-drawer
    v-model="open"
    :title="TROUBLESHOOTING_UI_LABELS.incident"
    :size="'var(--mc-ts-drawer-width)'"
    destroy-on-close
  >
    <div class="launch-guide">
      <p class="launch-lead">
        把告警原样整理进来即可。你只需要系统、服务和现象；可选填错误码与时间线索。
      </p>
      <ul class="launch-checklist">
        <li>不要粘贴原始日志、DQL 或密钥</li>
        <li>会生成或复用<strong>同一张排障单</strong></li>
        <li>全程只读，不会改生产</li>
      </ul>
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
        :type="openDiscoveryAlertType"
        :closable="false"
        show-icon
        class="dialog-alert"
        :title="openDiscoveryTitle"
      >
        <p>{{ openDiscoveryReadiness.nextAction }}</p>
        <p v-if="openDiscoveryReadiness.configuredAgentId">
          排障员工：
          {{ openDiscoveryReadiness.configuredAgentName || openDiscoveryReadiness.configuredAgentId }}
          <template v-if="openDiscoveryReadiness.agentBindingSource">
            · {{ openDiscoveryReadiness.agentBindingSource === 'WORKSPACE' ? '工作区绑定' : openDiscoveryReadiness.agentBindingSource === 'CONFIG' ? '进程配置' : '未绑定' }}
          </template>
        </p>
        <p v-if="openDiscoveryReadiness.visiblePlanCount">
          当前系统可见计划 {{ openDiscoveryReadiness.visiblePlanCount }} /
          已配置 {{ openDiscoveryReadiness.configuredPlanCount }}
          <template v-if="!openDiscoveryReadiness.trueSourcePermitted"> · 仍仅回放证据</template>
        </p>
        <ul v-if="openDiscoveryReadiness.blockers.length" class="readiness-blockers">
          <li v-for="blocker in openDiscoveryReadiness.blockers.slice(0, 4)" :key="blocker">{{ blocker }}</li>
        </ul>
      </el-alert>
      <el-checkbox v-model="form.rehearsal" class="incident-rehearsal">
        标记为演练（推荐试用；不占用生产去重窗口）
      </el-checkbox>
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
      >生成排障单</el-button>
    </template>
  </el-drawer>
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
defineEmits<{ submit: []; 'pick-scenario': []; 'pick-conversation': [] }>()

const openDiscoveryAlertType = computed(() => {
  const status = props.openDiscoveryReadiness?.status
  if (status === 'READY_FOR_BOUNDED_FALLBACK') return 'success'
  if (status === 'READY_FOR_REHEARSAL') return 'warning'
  return 'error'
})

const openDiscoveryTitle = computed(() => {
  switch (props.openDiscoveryReadiness?.status) {
    case 'READY_FOR_BOUNDED_FALLBACK':
      return '没有标准方案时，可用受限只读调查（真源已允许）'
    case 'READY_FOR_REHEARSAL':
      return '没有标准方案时，可先演练；尚未接真源'
    case 'DISABLED':
      return '没有标准方案时的兜底调查未启用'
    default:
      return '没有标准方案时的兜底调查尚未就绪'
  }
})
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
.readiness-blockers {
  margin: 8px 0 0;
  padding-left: 18px;
  line-height: 1.5;
}
.incident-field-hint {
  display: block;
  margin-top: 6px;
  color: var(--mc-text-tertiary);
  font-size: 11px;
  line-height: 1.5;
}
</style>
