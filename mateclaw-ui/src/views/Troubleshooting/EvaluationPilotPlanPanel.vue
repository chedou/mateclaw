<template>
  <div class="pilot-plan-summary" :class="{ ready: configured }">
    <div class="pilot-plan-state">
      <i>{{ configured ? '✓' : '!' }}</i>
      <div>
        <b>{{ configured ? plan?.name : '先固定试点范围和三位负责人' }}</b>
        <span v-if="configured">
          只跟踪
          <template v-for="(module, index) in plan?.modules" :key="`${module.system}/${module.service}`">
            <strong>{{ module.system }} / {{ module.service }}</strong><template v-if="index < (plan?.modules.length || 0) - 1">、</template>
          </template>
        </span>
        <span v-else>{{ plan?.blockers.join('；') || '没有配置前，任何排障单都不会被冒充成试点样本。' }}</span>
      </div>
    </div>
    <div v-if="plan?.configured" class="pilot-plan-people">
      <span><small>二线闭环</small><b>{{ plan.secondLine?.displayName || '人员已失效' }}</b></span>
      <span><small>三线复核</small><b>{{ plan.thirdLine?.displayName || '人员已失效' }}</b></span>
      <span><small>数据取证</small><b>{{ plan.sourceOwner?.displayName || '人员已失效' }}</b></span>
      <em>v{{ plan.version }}{{ plan.enabled ? '' : ' · 已停用' }}</em>
    </div>
    <el-button v-if="canManage" plain @click="openSetup">
      {{ plan?.configured ? '修改试点设置' : '配置试点' }}
    </el-button>
    <span v-else class="pilot-plan-permission">仅工作区管理员可修改</span>
  </div>

  <div v-if="setupOpen" class="pilot-setup-panel">
    <header>
      <div>
        <b>试点设置</b>
        <span>只声明系统 / 服务范围和交接人；不改写排障结论，每次保存都新增一个不可变版本。</span>
      </div>
      <el-button text @click="setupOpen = false">收起</el-button>
    </header>

    <el-alert
      v-if="members.length < 3 && !membersLoading"
      type="warning"
      :closable="false"
      show-icon
    >
      当前工作区只有 {{ members.length }} / 3 名成员。现在先补齐成员，才能把二线、三线和数据负责人分开。
      <el-button text type="primary" @click="openMemberSettings">先去添加成员</el-button>
    </el-alert>

    <div class="pilot-setup-grid">
      <label class="pilot-field wide">
        <span>试点名称</span>
        <el-input v-model="form.name" maxlength="120" placeholder="例如：CSDP 首批试点" />
      </label>
      <label class="pilot-field enabled-field">
        <span>是否启用</span>
        <el-switch v-model="form.enabled" active-text="进入交接队列" inactive-text="暂停统计" />
      </label>

      <div class="pilot-field wide">
        <span>只跟踪哪些系统 / 服务</span>
        <div class="pilot-module-list">
          <div v-for="(module, index) in form.modules" :key="index" class="pilot-module-row">
            <el-input v-model="module.system" placeholder="系统标识，如 csdp" />
            <span>/</span>
            <el-input v-model="module.service" placeholder="服务标识，如 csdp-wechat" />
            <el-button
              text
              type="danger"
              :disabled="form.modules.length === 1"
              @click="removeModule(index)"
            >删除</el-button>
          </div>
          <el-button text type="primary" :disabled="form.modules.length >= 20" @click="addModule">
            + 添加一个系统 / 服务
          </el-button>
        </div>
      </div>

      <label v-for="role in ROLE_FIELDS" :key="role.key" class="pilot-field">
        <span>{{ role.label }}</span>
        <el-select
          v-model="form[role.key]"
          filterable
          :loading="membersLoading"
          placeholder="选择工作区成员"
        >
          <el-option
            v-for="member in members"
            :key="String(member.userId)"
            :label="memberLabel(member)"
            :value="String(member.userId)"
            :disabled="memberDisabled(role.key, member.userId)"
          />
        </el-select>
        <small>{{ role.help }}</small>
      </label>

      <label class="pilot-field wide">
        <span>本次修改原因</span>
        <el-input v-model="form.reason" maxlength="300" placeholder="说明为什么固定或调整这批范围与人员" />
      </label>
    </div>

    <footer>
      <span>{{ formIssue || '保存后立即按新版本筛选交接队列。' }}</span>
      <el-button @click="setupOpen = false">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="Boolean(formIssue)" @click="save">
        保存新版本
      </el-button>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus/es/components/message/index'
import {
  troubleshootingApi,
  workspaceTeamApi,
  type TroubleshootingPilotPlan,
} from '@/api'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import { pilotPlanReady } from './evaluationPilot'

type PilotRole = 'secondLineUserId' | 'thirdLineUserId' | 'sourceOwnerUserId'

interface WorkspacePilotMember {
  id: string | number
  userId: string | number
  username?: string | null
  nickname?: string | null
  role: string
}

const ROLE_FIELDS: ReadonlyArray<{ key: PilotRole; label: string; help: string }> = [
  {
    key: 'secondLineUserId',
    label: '二线闭环负责人',
    help: '复核候选定位，完成平台外处置并登记结果。',
  },
  {
    key: 'thirdLineUserId',
    label: '三线开发复核人',
    help: '填写人工标准答案，核对准确性和周复盘结果。',
  },
  {
    key: 'sourceOwnerUserId',
    label: '数据取证负责人',
    help: '保证真实只读查询可用，采集脱敏 Guance 样本。',
  },
]

const props = withDefaults(defineProps<{
  plan: TroubleshootingPilotPlan | null
  startOpen?: boolean
}>(), {
  startOpen: false,
})
const emit = defineEmits<{ updated: [plan: TroubleshootingPilotPlan] }>()
const router = useRouter()
const workspaceStore = useWorkspaceStore()
const members = ref<WorkspacePilotMember[]>([])
const membersLoading = ref(false)
const setupOpen = ref(false)
const autoOpenConsumed = ref(false)
const saving = ref(false)
const form = reactive({
  name: '',
  modules: [{ system: '', service: '' }],
  secondLineUserId: '',
  thirdLineUserId: '',
  sourceOwnerUserId: '',
  enabled: true,
  reason: '',
})

const canManage = computed(() => workspaceStore.can('manage:troubleshooting')
  || workspaceStore.isAtLeast('admin'))
const configured = computed(() => pilotPlanReady(props.plan))
const formIssue = computed(() => {
  if (!canManage.value) return '当前角色无权修改试点设置。'
  if (!form.name.trim()) return '请填写试点名称。'
  if (!form.modules.length) return '至少配置一个系统 / 服务。'
  const normalizedModules = form.modules.map(module => ({
    system: module.system.trim().toLocaleLowerCase(),
    service: module.service.trim().toLocaleLowerCase(),
  }))
  const stableIdentifier = /^[a-z0-9](?:[a-z0-9._-]{0,126}[a-z0-9])?$/
  if (normalizedModules.some(module => !stableIdentifier.test(module.system)
    || !stableIdentifier.test(module.service))) {
    return '系统和服务请填稳定标识，仅使用字母、数字、点、下划线或短横线。'
  }
  const moduleKeys = normalizedModules.map(module => `${module.system}\u0000${module.service}`)
  if (new Set(moduleKeys).size !== moduleKeys.length) return '系统 / 服务范围不能重复。'
  if (!membersLoading.value && members.value.length < 3) {
    return '当前未取得至少 3 名工作区成员，请先补齐成员或重试加载。'
  }
  const people = ROLE_FIELDS.map(role => form[role.key])
  if (people.some(userId => !/^\d+$/.test(userId) || userId === '0')) {
    return '请分别选择二线、三线和数据取证负责人。'
  }
  if (new Set(people).size !== people.length) return '三类职责必须由 3 名不同的工作区成员承担。'
  if (!membersLoading.value
    && people.some(userId => !members.value.some(member => String(member.userId) === userId))) {
    return '已选人员不在当前工作区，请重新选择。'
  }
  if (!form.reason.trim()) return '请填写本次固定或调整试点的原因。'
  return ''
})

async function openSetup() {
  if (!canManage.value) return
  hydrateForm()
  setupOpen.value = true
  await loadMembers()
}

watch(
  [() => props.startOpen, () => props.plan],
  ([startOpen, plan]) => {
    if (!startOpen || !plan || autoOpenConsumed.value || !canManage.value) return
    autoOpenConsumed.value = true
    void openSetup()
  },
  { immediate: true },
)

function hydrateForm() {
  const plan = props.plan
  form.name = plan?.name || ''
  form.modules = plan?.modules.length
    ? plan.modules.map(module => ({ system: module.system, service: module.service }))
    : [{ system: '', service: '' }]
  form.secondLineUserId = plan?.secondLine ? String(plan.secondLine.userId) : ''
  form.thirdLineUserId = plan?.thirdLine ? String(plan.thirdLine.userId) : ''
  form.sourceOwnerUserId = plan?.sourceOwner ? String(plan.sourceOwner.userId) : ''
  form.enabled = plan?.configured ? plan.enabled : true
  form.reason = ''
}

async function loadMembers() {
  const workspaceId = workspaceStore.currentWorkspaceId
  if (!workspaceId) {
    members.value = []
    return
  }
  membersLoading.value = true
  try {
    const response = await workspaceTeamApi.listMembers(workspaceId)
    members.value = (response.data || []) as WorkspacePilotMember[]
  } catch (error) {
    members.value = []
    ElMessage.error(`加载工作区成员失败：${errorText(error)}`)
  } finally {
    membersLoading.value = false
  }
}

function addModule() {
  if (form.modules.length >= 20) return
  form.modules.push({ system: '', service: '' })
}

function removeModule(index: number) {
  if (form.modules.length <= 1) return
  form.modules.splice(index, 1)
}

function memberLabel(member: WorkspacePilotMember) {
  const displayName = member.nickname || member.username || `成员 ${member.userId}`
  return `${displayName} · ${member.role}`
}

function memberDisabled(role: PilotRole, userId: string | number) {
  const candidate = String(userId)
  return ROLE_FIELDS.some(otherRole => otherRole.key !== role && form[otherRole.key] === candidate)
}

async function save() {
  if (formIssue.value) return
  saving.value = true
  try {
    const response = await troubleshootingApi.declarePilotPlan({
      name: form.name.trim(),
      modules: form.modules.map(module => ({
        system: module.system.trim(),
        service: module.service.trim(),
      })),
      secondLineUserId: form.secondLineUserId,
      thirdLineUserId: form.thirdLineUserId,
      sourceOwnerUserId: form.sourceOwnerUserId,
      enabled: form.enabled,
      expectedVersion: props.plan?.version || 0,
      reason: form.reason.trim(),
    })
    setupOpen.value = false
    emit('updated', response.data)
    ElMessage.success(`试点设置 v${response.data.version} 已保存，交接队列已按新范围刷新`)
  } catch (error) {
    ElMessage.error(`保存试点设置失败：${errorText(error)}`)
  } finally {
    saving.value = false
  }
}

function openMemberSettings() {
  void router.push('/settings/members')
}

function errorText(error: unknown) {
  return error instanceof Error ? error.message : String(error)
}
</script>

<style scoped>
.pilot-plan-summary {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 11px 12px;
  border: 1px solid var(--mc-status-warning-border, var(--mc-border));
  border-radius: 9px;
  background: var(--mc-status-warning-bg, var(--mc-bg-muted));
}

.pilot-plan-summary.ready {
  border-color: var(--mc-status-success-border, var(--mc-border));
  background: var(--mc-status-success-bg, var(--mc-bg-muted));
}

.pilot-plan-state {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 210px;
  flex: 1 1 280px;
}

.pilot-plan-state > i {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  flex: none;
  border-radius: 50%;
  color: var(--mc-status-warning-text, var(--mc-warning));
  background: var(--mc-bg-elevated);
  font-style: normal;
  font-size: 12px;
  font-weight: 800;
}

.pilot-plan-summary.ready .pilot-plan-state > i {
  color: var(--mc-status-success-text, var(--mc-success));
}

.pilot-plan-state > div { min-width: 0; }
.pilot-plan-state b,
.pilot-plan-state span { display: block; }
.pilot-plan-state b { font-size: 12px; }
.pilot-plan-state span {
  margin-top: 3px;
  color: var(--mc-text-secondary);
  font-size: 10px;
  line-height: 1.5;
}
.pilot-plan-state span strong { color: var(--mc-text-primary); font-weight: 650; }

.pilot-plan-people {
  display: flex;
  align-items: center;
  gap: 15px;
  flex: 1 1 380px;
}

.pilot-plan-people > span { display: grid; gap: 2px; min-width: 0; }
.pilot-plan-people small { color: var(--mc-text-tertiary); font-size: 9px; }
.pilot-plan-people b {
  overflow: hidden;
  max-width: 120px;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pilot-plan-people em,
.pilot-plan-permission {
  margin-left: auto;
  color: var(--mc-text-tertiary);
  font-size: 9px;
  font-style: normal;
  white-space: nowrap;
}

.pilot-setup-panel {
  display: grid;
  gap: 14px;
  padding: 15px;
  border: 1px solid var(--mc-border);
  border-radius: 9px;
  background: var(--mc-bg-muted);
}

.pilot-setup-panel > header,
.pilot-setup-panel > footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.pilot-setup-panel > header > div { display: grid; gap: 3px; }
.pilot-setup-panel > header b { font-size: 12px; }
.pilot-setup-panel > header span,
.pilot-setup-panel > footer > span {
  color: var(--mc-text-secondary);
  font-size: 10px;
  line-height: 1.5;
}

.pilot-setup-panel > footer {
  padding-top: 12px;
  border-top: 1px solid var(--mc-border-light);
}
.pilot-setup-panel > footer > span { margin-right: auto; }

.pilot-setup-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 13px;
}

.pilot-field {
  display: grid;
  align-content: start;
  gap: 6px;
  min-width: 0;
}

.pilot-field.wide { grid-column: 1 / -1; }
.pilot-field > span { color: var(--mc-text-primary); font-size: 10px; font-weight: 700; }
.pilot-field > small { color: var(--mc-text-tertiary); font-size: 9px; line-height: 1.45; }
.pilot-field :deep(.el-select) { width: 100%; }
.enabled-field { grid-column: 1 / -1; }

.pilot-module-list { display: grid; gap: 7px; }
.pilot-module-row {
  display: grid;
  grid-template-columns: minmax(140px, .65fr) auto minmax(180px, 1fr) auto;
  align-items: center;
  gap: 8px;
}
.pilot-module-row > span { color: var(--mc-text-tertiary); font-size: 12px; }

@media (max-width: 900px) {
  .pilot-plan-summary { align-items: flex-start; flex-wrap: wrap; }
  .pilot-plan-people { order: 3; flex-basis: 100%; }
  .pilot-setup-grid { grid-template-columns: 1fr 1fr; }
}

@media (max-width: 620px) {
  .pilot-plan-summary { align-items: stretch; flex-direction: column; }
  .pilot-plan-summary > .el-button { width: 100%; }
  .pilot-plan-people { display: grid; grid-template-columns: 1fr 1fr; }
  .pilot-plan-people em { margin-left: 0; }
  .pilot-setup-grid { grid-template-columns: 1fr; }
  .pilot-field.wide,
  .enabled-field { grid-column: auto; }
  .pilot-module-row { grid-template-columns: 1fr; }
  .pilot-module-row > span { display: none; }
  .pilot-setup-panel > footer { align-items: stretch; flex-direction: column; }
  .pilot-setup-panel > footer .el-button { width: 100%; margin-left: 0; }
}
</style>
