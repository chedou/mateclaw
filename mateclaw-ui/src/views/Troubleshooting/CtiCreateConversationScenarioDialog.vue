<template>
  <el-drawer
    v-model="open"
    title="创建会话失败"
    :size="'var(--mc-ts-drawer-width)'"
    destroy-on-close
  >
    <el-alert type="info" :closable="false" class="dialog-alert">
      这是 CSDP 已登记场景：按标准方法查失败记录、关联调用，并对照成功样本。
      会生成或复用同一张排障单；全程只读。
    </el-alert>
    <el-alert type="warning" :closable="false" class="dialog-alert">
      当前仅支持演练：CTI 场景的 D20 场景级数据绑定与负责人验收尚未完成，平台不会把服务级验收冒充本场景的正式授权。
    </el-alert>
    <el-form label-position="top" @submit.prevent="$emit('submit')">
      <div class="incident-form-grid">
        <el-form-item label="故障系统"><el-input :model-value="CTI_CREATE_CONVERSATION_SCENARIO.system" disabled /></el-form-item>
        <el-form-item label="故障服务"><el-input :model-value="CTI_CREATE_CONVERSATION_SCENARIO.service" disabled /></el-form-item>
        <el-form-item label="严重级别" required>
          <el-select v-model="form.severity" style="width:100%">
            <el-option
              v-for="option in INCIDENT_SEVERITY_OPTIONS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="Trace / 关联 ID（可选）">
          <el-input v-model="form.traceId" maxlength="128" placeholder="不知道可留空，系统会从失败日志中提取" />
        </el-form-item>
        <el-form-item label="故障发生时间（建议填）">
          <el-date-picker
            v-model="form.occurredAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
            placeholder="例如 2026-08-07 17:24:00"
            clearable
            style="width:100%"
          />
          <p class="form-hint">不选则由服务端取当前时间；历史告警应填精确时间，否则可能查不到保留期内证据。</p>
        </el-form-item>
      </div>
      <el-form-item label="故障现象" required>
        <el-input
          v-model="form.title"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
          placeholder="粘贴或描述用户可见现象；不要粘贴日志、DQL 或密钥"
        />
      </el-form-item>
      <el-form-item label="影响范围 / 告警线索（可选）">
        <el-input v-model="form.customerRef" maxlength="500" placeholder="例如：集群 sz4-s-zaibei，告警数量 3" />
      </el-form-item>
      <div class="incident-route-preview scenario">
        <span>这次怎么查</span>
        <b>{{ CTI_CREATE_CONVERSATION_SCENARIO.selector }}</b>
        <p>失败记录 → 关联调用链 → 成功/失败对照；页面不能修改查询、证据源或判据。</p>
      </div>
      <el-checkbox v-model="form.rehearsal" class="incident-rehearsal" disabled>
        演练记录（完成 D20 后才可开放正式建单）
      </el-checkbox>
      <p class="form-hint">建单后进入详情，按五问推进：发生了什么 → 怎么查 → 查到什么 → 说明什么 → 下一步怎么办。</p>
    </el-form>
    <template #footer>
      <el-button text @click="$emit('open-playbooks')">查看排查指南</el-button>
      <el-button @click="open = false">取消</el-button>
      <el-button type="primary" :loading="loading" :disabled="!canSubmit" @click="$emit('submit')">生成演练排障单</el-button>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import {
  CTI_CREATE_CONVERSATION_SCENARIO,
  type CtiCreateConversationScenarioForm,
} from './ctiCreateConversationScenario'
import { INCIDENT_SEVERITY_OPTIONS } from './intakeDialog'

defineProps<{ loading: boolean; canSubmit: boolean }>()
const open = defineModel<boolean>({ required: true })
const form = defineModel<CtiCreateConversationScenarioForm>('form', { required: true })
defineEmits<{ submit: []; 'open-playbooks': [] }>()
</script>

<style scoped src="./intakeDialog.css"></style>
