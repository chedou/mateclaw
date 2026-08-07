<template>
  <el-dialog
    v-model="open"
    title="创建“会话消息发送失败”排障单"
    width="min(640px, calc(100vw - 32px))"
  >
    <el-alert type="info" :closable="false" class="dialog-alert">
      这是当前优先打通的单场景竖线。创建时只锁定排查指南，不会直接给出根因；进入详情后由你显式开始三次只读取证。
    </el-alert>
    <el-form label-position="top" @submit.prevent="$emit('submit')">
      <div class="incident-form-grid">
        <el-form-item label="故障系统"><el-input v-model="form.system" disabled /></el-form-item>
        <el-form-item label="故障服务"><el-input v-model="form.service" disabled /></el-form-item>
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
        <el-form-item label="Trace / PS 线索（可选）">
          <el-input v-model="form.traceId" maxlength="128" placeholder="仅填写安全标识符" />
        </el-form-item>
        <el-form-item label="故障发生时间（可选）">
          <el-date-picker
            v-model="form.occurredAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
            placeholder="不选则取当前时间"
            clearable
            style="width:100%"
          />
          <p class="form-hint">真实 Guance 查询会围绕这个时间读取 Playbook 规定的窗口；不选择时由服务端取当前时间。</p>
        </el-form-item>
      </div>
      <el-form-item label="故障现象" required>
        <el-input
          v-model="form.title"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
          placeholder="只描述用户可见现象，不粘贴日志、DQL 或凭据"
        />
      </el-form-item>
      <el-form-item label="影响对象（可选）">
        <el-input v-model="form.customerRef" maxlength="500" placeholder="例如：马来区域客户；不填人数或原始名单" />
      </el-form-item>
      <div class="incident-route-preview scenario">
        <span>已锁定排查指南</span>
        <b>{{ MESSAGE_SEND_SCENARIO_SELECTOR }}</b>
        <p>三个步骤固定为：失败请求 → PS ID 调用链 → 成功/失败样本对比。浏览器不能指定查询或判据。</p>
      </div>
      <el-checkbox v-model="form.rehearsal" class="incident-rehearsal">
        演练记录（仅影响事件去重，不决定证据来源）
      </el-checkbox>
      <p class="form-hint">证据来源由工作区的服务端绑定决定，页面不能强制选择 Guance 或回放；执行后以详情中每条证据记录的实际来源为准。</p>
    </el-form>
    <template #footer>
      <el-button text @click="$emit('open-playbooks')">查看排查指南</el-button>
      <el-button @click="open = false">取消</el-button>
      <el-button type="primary" :loading="loading" :disabled="!canSubmit" @click="$emit('submit')">
        创建排障单
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { MESSAGE_SEND_SCENARIO_SELECTOR, type MessageSendScenarioForm } from './messageSendScenario'
import { INCIDENT_SEVERITY_OPTIONS } from './intakeDialog'

defineProps<{
  loading: boolean
  canSubmit: boolean
}>()

const open = defineModel<boolean>({ required: true })
const form = defineModel<MessageSendScenarioForm>('form', { required: true })
defineEmits<{
  submit: []
  'open-playbooks': []
}>()
</script>

<style scoped src="./intakeDialog.css"></style>
