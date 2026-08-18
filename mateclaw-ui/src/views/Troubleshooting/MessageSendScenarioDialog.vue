<template>
  <el-drawer
    v-model="open"
    title="消息发送失败"
    :size="'var(--mc-ts-drawer-width)'"
    destroy-on-close
  >
    <el-alert type="info" :closable="false" class="dialog-alert">
      这是已登记的标准场景：系统和服务已锁定。建单后进详情，再显式开始只读取证。
      不会改生产。
    </el-alert>
    <el-alert type="warning" :closable="false" class="dialog-alert">
      当前仅支持演练：D20 场景级数据绑定与负责人验收尚未完成，不会借用服务级验收生成正式单。
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
          <p class="form-hint">真实查询会围绕这个时间读取标准方案规定的窗口；不选则由服务端取当前时间。</p>
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
      <el-form-item label="影响对象（可选）">
        <el-input v-model="form.customerRef" maxlength="500" placeholder="例如：马来区域客户；不填人数或原始名单" />
      </el-form-item>
      <div class="incident-route-preview scenario">
        <span>这次怎么查</span>
        <b>{{ MESSAGE_SEND_SCENARIO_SELECTOR }}</b>
        <p>三个步骤固定为：失败请求 → PS ID 调用链 → 成功/失败样本对比。页面不能指定查询或判据。</p>
      </div>
      <el-checkbox v-model="form.rehearsal" class="incident-rehearsal" disabled>
        演练记录（完成 D20 后才可开放正式建单）
      </el-checkbox>
      <p class="form-hint">
        建单后进入详情，按五问推进。证据来源由工作区绑定决定，页面不能强制选择数据源。
      </p>
    </el-form>
    <template #footer>
      <el-button text @click="$emit('open-playbooks')">查看排查指南</el-button>
      <el-button @click="open = false">取消</el-button>
      <el-button type="primary" :loading="loading" :disabled="!canSubmit" @click="$emit('submit')">
        生成演练排障单
      </el-button>
    </template>
  </el-drawer>
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
