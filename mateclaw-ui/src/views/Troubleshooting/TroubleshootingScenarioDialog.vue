<template>
  <el-drawer
    v-model="visible"
    :title="TROUBLESHOOTING_UI_LABELS.scenarioPicker"
    :size="'var(--mc-ts-drawer-width)'"
    destroy-on-close
    class="troubleshooting-scenario-drawer"
  >
    <p class="scenario-intro">
      只有确认是下面这类<strong>已登记问题</strong>时才选这里。
      不确定？回去粘贴告警即可，系统会自动匹配标准方案。
    </p>

    <section v-if="knownScenarios.length" class="scenario-section" aria-label="已登记场景">
      <h3>已登记场景</h3>
      <div class="scenario-list">
        <button
          v-for="scenario in knownScenarios"
          :key="scenario.command"
          type="button"
          class="scenario-row"
          @click="select(scenario)"
        >
          <span class="scenario-copy">
            <b>{{ scenario.label }}</b>
            <small>{{ scenario.description }}</small>
          </span>
          <span class="scenario-meta">
            <em>{{ scenario.outcome }}</em>
            <span class="scenario-arrow" aria-hidden="true">→</span>
          </span>
        </button>
      </div>
    </section>

    <section v-if="adminScenarios.length" class="scenario-section" aria-label="管理员专项">
      <h3>管理员专项</h3>
      <div class="scenario-list">
        <button
          v-for="scenario in adminScenarios"
          :key="scenario.command"
          type="button"
          class="scenario-row admin"
          @click="select(scenario)"
        >
          <span class="scenario-copy">
            <b>{{ scenario.label }}</b>
            <small>{{ scenario.description }}</small>
          </span>
          <span class="scenario-meta">
            <em>{{ scenario.outcome }}</em>
            <span class="scenario-arrow" aria-hidden="true">→</span>
          </span>
        </button>
      </div>
    </section>

    <p v-if="!knownScenarios.length && !adminScenarios.length" class="scenario-empty">
      当前账号没有可发起的已登记场景权限。
    </p>

    <p class="scenario-boundary">全程只读取证，不会改生产。</p>

    <template #footer>
      <el-button v-if="canOperate" type="primary" plain @click="backToIncident">
        返回粘贴告警
      </el-button>
      <el-button @click="visible = false">取消</el-button>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import {
  TROUBLESHOOTING_UI_LABELS,
  workbenchSecondaryScenarios,
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
  'back-to-incident': []
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const secondaryScenarios = computed(() =>
  workbenchSecondaryScenarios(props.canOperate, props.canManage),
)
const knownScenarios = computed(() =>
  secondaryScenarios.value.filter(scenario => scenario.group === 'known'),
)
const adminScenarios = computed(() =>
  secondaryScenarios.value.filter(scenario => scenario.group === 'admin'),
)

function select(scenario: TroubleshootingScenarioDefinition) {
  visible.value = false
  emit('select', scenario.command)
}

function backToIncident() {
  visible.value = false
  emit('back-to-incident')
}
</script>

<style scoped>
.scenario-intro {
  margin: 0 0 18px;
  color: var(--mc-text-secondary);
  font-size: 13px;
  line-height: 1.7;
}
.scenario-section + .scenario-section {
  margin-top: 22px;
}
.scenario-section h3 {
  margin: 0 0 10px;
  color: var(--mc-text-tertiary);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.04em;
}
.scenario-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.scenario-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 14px;
  width: 100%;
  padding: 14px 16px;
  border: 1px solid var(--mc-border);
  border-radius: var(--mc-radius-md);
  color: var(--mc-text-primary);
  background: var(--mc-bg);
  text-align: left;
  cursor: pointer;
  transition: border-color .16s, box-shadow .16s;
}
.scenario-row:hover {
  border-color: var(--mc-primary);
  box-shadow: var(--mc-shadow-soft);
}
.scenario-row.admin {
  background: var(--mc-bg-muted);
}
.scenario-copy {
  display: flex;
  flex-direction: column;
  min-width: 0;
  gap: 6px;
}
.scenario-copy b {
  font-size: 15px;
}
.scenario-copy small {
  color: var(--mc-text-secondary);
  font-size: 12px;
  line-height: 1.55;
}
.scenario-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
  flex-shrink: 0;
}
.scenario-meta em {
  padding: 2px 7px;
  border-radius: 999px;
  color: var(--mc-text-secondary);
  background: var(--mc-bg-muted);
  font-size: 10px;
  font-style: normal;
  font-weight: 700;
  white-space: nowrap;
}
.scenario-arrow {
  color: var(--mc-text-tertiary);
  font-size: 16px;
}
.scenario-empty {
  margin: 24px 0 0;
  color: var(--mc-text-tertiary);
  font-size: 13px;
}
.scenario-boundary {
  margin: 18px 0 0;
  color: var(--mc-text-tertiary);
  font-size: 12px;
  line-height: 1.5;
}
</style>
