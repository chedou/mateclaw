<template>
  <el-dialog
    v-model="open"
    title="创建部署拓扑拨测 Diagnosis"
    width="min(620px, calc(100vw - 32px))"
  >
    <el-alert type="info" :closable="false" class="dialog-alert">
      先由服务端锁定已审核启用的部署拓扑 Scenario Playbook 并创建 Diagnosis；此时不调用模型、不执行拨测，也不提前判断网络根因。
    </el-alert>
    <el-form label-position="top" @submit.prevent="$emit('submit')">
      <div class="incident-form-grid">
        <el-form-item label="故障系统" required>
          <el-input v-model="form.system" maxlength="128" placeholder="必须与已审核 Scenario Playbook 的系统一致" />
        </el-form-item>
        <el-form-item label="故障服务" required>
          <el-input v-model="form.service" maxlength="128" placeholder="例如 csp-prm-miniapp" />
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
        <el-form-item label="Trace / PS 线索（可选）">
          <el-input v-model="form.traceId" maxlength="128" placeholder="只填写安全标识符" />
        </el-form-item>
      </div>
      <el-form-item label="故障现象" required>
        <el-input
          v-model="form.title"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
          placeholder="描述需要通过部署拓扑拨测核查的用户可见现象"
        />
      </el-form-item>
      <div class="incident-route-preview scenario">
        <span>服务端权威选择器</span>
        <b>{{ selector }}</b>
        <p>浏览器不能指定 Playbook 版本、Tool Key 或查询参数；服务端找不到精确权威版本时会 fail-closed。</p>
      </div>
      <el-checkbox v-model="form.rehearsal" class="incident-rehearsal">
        演练记录（推荐；每次生成独立 Diagnosis）
      </el-checkbox>
      <p class="form-hint">关闭演练标记后，相同系统、服务与现象在五分钟窗口内会复用既有 Diagnosis。创建成功后再选择 Workspace 拓扑并执行只读拨测。</p>
    </el-form>
    <template #footer>
      <el-button text @click="$emit('open-playbooks')">查看排障规则库</el-button>
      <el-button @click="open = false">取消</el-button>
      <el-button type="primary" :loading="loading" :disabled="!canSubmit" @click="$emit('submit')">
        创建并选择拓扑
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import type { DeploymentTopologyScenarioForm } from './deploymentTopologyScenario'
import { INCIDENT_SEVERITY_OPTIONS } from './intakeDialog'

defineProps<{
  selector: string
  loading: boolean
  canSubmit: boolean
}>()

const open = defineModel<boolean>({ required: true })
const form = defineModel<DeploymentTopologyScenarioForm>('form', { required: true })
defineEmits<{
  submit: []
  'open-playbooks': []
}>()
</script>

<style scoped src="./intakeDialog.css"></style>
