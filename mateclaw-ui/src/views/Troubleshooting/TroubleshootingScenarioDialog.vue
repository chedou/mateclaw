<template>
  <el-dialog
    v-model="visible"
    :title="TROUBLESHOOTING_UI_LABELS.scenarioPicker"
    width="min(720px, calc(100vw - 32px))"
    class="troubleshooting-scenario-dialog"
  >
    <p class="scenario-intro">从业务场景进入同一个智能排障工作台；每个场景继续遵守自己的数据与权限边界。</p>
    <div class="scenario-grid">
      <button
        v-for="(scenario, index) in WORKBENCH_TROUBLESHOOTING_SCENARIOS"
        :key="scenario.command"
        type="button"
        class="scenario-card"
        :disabled="isDisabled(scenario)"
        @click="select(scenario)"
      >
        <span class="scenario-index">0{{ index + 1 }}</span>
        <span class="scenario-copy">
          <b>{{ scenario.label }}</b>
          <small>{{ scenario.description }}</small>
          <em>{{ scenario.outcome }}{{ scenario.manageOnly ? ' · 管理员' : '' }}</em>
        </span>
        <span class="scenario-arrow">→</span>
      </button>
    </div>
    <el-alert type="info" :closable="false" class="scenario-boundary">
      部署拓扑拨测当前复用既有 Guance 只读分析接口，结果不落为 Diagnosis，也不执行任何生产变更。
    </el-alert>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import {
  TROUBLESHOOTING_UI_LABELS,
  WORKBENCH_TROUBLESHOOTING_SCENARIOS,
  type TroubleshootingScenarioCommand,
  type TroubleshootingScenarioDefinition,
} from './workbenchView'

const props = defineProps<{
  modelValue: boolean
  canOperate: boolean
  canManage: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  select: [command: TroubleshootingScenarioCommand]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

function isDisabled(scenario: TroubleshootingScenarioDefinition): boolean {
  return scenario.manageOnly ? !props.canManage : !props.canOperate
}

function select(scenario: TroubleshootingScenarioDefinition) {
  if (isDisabled(scenario)) return
  visible.value = false
  emit('select', scenario.command)
}
</script>

<style scoped>
.scenario-intro { margin:0 0 16px; color:#667085; font-size:13px; line-height:1.7; }
.scenario-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:12px; }
.scenario-card { display:grid; grid-template-columns:auto minmax(0,1fr) auto; align-items:start; gap:12px; min-height:156px; padding:20px; border:1px solid #dfe4ec; border-radius:14px; color:#172033; background:#fff; text-align:left; cursor:pointer; transition:border-color .16s,box-shadow .16s,transform .16s; }
.scenario-card:hover:not(:disabled) { border-color:#8fa7ff; box-shadow:0 12px 30px rgba(47,92,245,.1); transform:translateY(-1px); }
.scenario-card:disabled { opacity:.5; cursor:not-allowed; }
.scenario-index { display:grid; place-items:center; width:30px; height:30px; border-radius:9px; color:#2f5cf5; background:#eff4ff; font:700 10px ui-monospace,monospace; }
.scenario-copy { display:flex; flex-direction:column; min-width:0; }
.scenario-copy b { font-size:16px; }
.scenario-copy small { margin-top:8px; color:#667085; font-size:12px; line-height:1.6; }
.scenario-copy em { align-self:flex-start; margin-top:13px; padding:3px 7px; border-radius:10px; color:#475467; background:#f2f4f7; font-size:10px; font-style:normal; font-weight:700; }
.scenario-arrow { color:#98a2b3; font-size:18px; }
.scenario-boundary { margin-top:14px; }
@media(max-width:640px){.scenario-grid{grid-template-columns:1fr}.scenario-card{min-height:136px}}
</style>
