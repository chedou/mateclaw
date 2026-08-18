<template>
  <el-drawer
    v-model="open"
    title="部署拓扑拨测"
    :size="'var(--mc-ts-drawer-width)'"
    destroy-on-close
  >
    <el-alert type="info" :closable="false" class="dialog-alert">
      管理员专项：先建排障单并锁定拨测方法，再建单后选拓扑做只读拨测。
      此时不调用模型、不执行拨测。
    </el-alert>
    <el-alert type="warning" :closable="false" class="dialog-alert">
      当前仅支持演练：D20 场景级数据绑定与负责人验收尚未完成，服务端会拒绝正式场景请求。
    </el-alert>
    <el-form label-position="top" @submit.prevent="$emit('submit')">
      <div class="incident-form-grid">
        <el-form-item label="故障系统" required>
          <el-input v-model="form.system" maxlength="128" placeholder="必须与已审核场景方案的系统一致" />
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
        <span>这次怎么查</span>
        <b>{{ selector }}</b>
        <p>页面不能指定方案版本或查询参数；服务端找不到精确权威版本时会明确拒绝。</p>
      </div>
      <el-checkbox v-model="form.rehearsal" class="incident-rehearsal" disabled>
        演练记录（完成 D20 后才可开放正式建单）
      </el-checkbox>
      <p class="form-hint">
        创建演练单后再选择拓扑并执行只读拨测；页面不会把未验收的场景标为正式排障。
      </p>
    </el-form>
    <template #footer>
      <el-button text @click="$emit('open-playbooks')">查看排障规则库</el-button>
      <el-button @click="open = false">取消</el-button>
      <el-button type="primary" :loading="loading" :disabled="!canSubmit" @click="$emit('submit')">
        生成演练单并选拓扑
      </el-button>
    </template>
  </el-drawer>
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
