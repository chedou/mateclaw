<template>
  <div class="pilot-plan-summary" :class="{ ready: configured }">
    <div class="pilot-plan-state">
      <i>{{ configured ? '✓' : '!' }}</i>
      <div>
        <b>{{ configured ? plan?.name : '先固定试点范围和三位负责人' }}</b>
        <span v-if="configured">
          当前批次 v{{ plan?.version }} 只跟踪
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
      <em>{{ plan.enabled ? '接收新单' : '已停用' }}</em>
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
        <span>只声明系统 / 服务范围和交接人；每次保存开启一个新批次，不改写结论，也不把历史排障单追溯纳入。</span>
      </div>
      <el-button text @click="setupOpen = false">收起</el-button>
    </header>

    <section class="pilot-preparation" aria-label="开始试点前，两件事可以并行准备">
      <header>
        <div>
          <small>开始试点前</small>
          <b>两件事可以并行准备</b>
        </div>
        <span>团队管理员准备成员，Guance Owner 同时登记真实查法；不必互相等待。</span>
      </header>
      <div class="pilot-preparation-grid">
        <article :class="{ ready: pilotTeamReadiness.ready && !membersLoadFailed }">
          <i>1</i>
          <div>
            <div class="pilot-preparation-title">
              <b>补齐试点成员</b>
              <em v-if="membersLoading">正在读取</em>
              <em v-else-if="membersLoadFailed">读取失败</em>
              <em v-else-if="pilotTeamReadiness.ready">人员已就绪</em>
              <em v-else>还未就绪</em>
            </div>
            <p v-if="membersLoading">正在核对当前成员与角色。</p>
            <p v-else-if="membersLoadFailed">暂时无法读取工作区成员，不能确认三人门槛。</p>
            <p v-else-if="pilotTeamReadiness.ready">
              已有 {{ pilotTeamReadiness.operatorCount }} 名成员能推进排障，其中
              {{ pilotTeamReadiness.adminCount }} 名能维护评估。
            </p>
            <p v-else>
              当前共 {{ pilotTeamReadiness.memberCount }} 名成员：
              {{ pilotTeamReadiness.operatorCount }} 名能推进排障，{{ pilotTeamReadiness.adminCount }} 名能维护评估。
              需要 3 名可操作成员，其中至少 2 名管理员或所有者。
            </p>
            <div class="pilot-preparation-actions">
              <el-button v-if="membersLoadFailed" text type="primary" @click="loadMembers">重新读取</el-button>
              <el-button
                v-else-if="!pilotTeamReadiness.ready && canManageMembers"
                text
                type="primary"
                @click="openMemberSettings"
              >去补齐成员与角色</el-button>
              <span v-else-if="!pilotTeamReadiness.ready">当前账号不能调整成员，请联系工作区管理员。</span>
              <span v-else>现在可以在下方分配三类负责人。</span>
            </div>
          </div>
        </article>

        <article>
          <i>2</i>
          <div>
            <div class="pilot-preparation-title">
              <b>登记真实查法</b>
              <em>待 Owner 核实</em>
            </div>
            <p>逐条确认首批 20 条重点故障在观测云怎么查、查哪些字段、怎样判断。</p>
            <div class="pilot-preparation-actions">
              <el-button text type="primary" @click="openOwnerContract">去登记真实查法</el-button>
              <span>登记材料不等于 T7 验收；正式完成数仍由服务端目录和 Owner 验收决定。</span>
            </div>
          </div>
        </article>
      </div>
    </section>

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
        <div v-if="scopeSuggestions.length" class="pilot-scope-suggestions">
          <div class="pilot-scope-suggestion-head">
            <div>
              <b>从最近正式排障单选择</b>
              <small>只读取非演练记录，只列出可直接保存的稳定标识；选择后只会填入范围，不会自动保存。</small>
            </div>
            <span>最近 {{ scopeSuggestions.length }} 个范围</span>
          </div>
          <div class="pilot-scope-suggestion-list">
            <button
              v-for="suggestion in scopeSuggestions"
              :key="`${suggestion.system}/${suggestion.service}`"
              type="button"
              :class="{ selected: scopeSuggestionSelected(suggestion) }"
              :aria-pressed="scopeSuggestionSelected(suggestion)"
              :disabled="!scopeSuggestionSelected(suggestion) && form.modules.length >= 20"
              @click="addScopeSuggestion(suggestion)"
            >
              <span>
                <b>{{ suggestion.system }} / {{ suggestion.service }}</b>
                <small>{{ suggestion.formalCount }} 张正式排障单</small>
              </span>
              <em>{{ scopeSuggestionSelected(suggestion) ? '已选择' : '加入范围' }}</em>
            </button>
          </div>
        </div>
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
            :disabled="memberDisabled(role, member)"
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
      <span>{{ formIssue || '保存后，之后新建且命中范围的正式单才进入这个批次。' }}</span>
      <el-button @click="setupOpen = false">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="Boolean(formIssue)" @click="save">
        保存新版本
      </el-button>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus/es/components/message/index'
import {
  troubleshootingApi,
  workspaceTeamApi,
  type TroubleshootingPilotPlan,
} from '@/api'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import {
  buildPilotTeamReadiness,
  pilotMemberCanOwnResponsibility,
  pilotPlanReady,
  pilotScopeIsSaveable,
  pilotScopeKey,
  type PilotResponsibility,
  type PilotScopeSuggestion,
} from './evaluationPilot'
import {
  pilotMemberSettingsLocation,
  t7OwnerContractLocation,
} from './workbenchCapabilityMenu'

type PilotRole = 'secondLineUserId' | 'thirdLineUserId' | 'sourceOwnerUserId'

interface WorkspacePilotMember {
  id: string | number
  userId: string | number
  username?: string | null
  nickname?: string | null
  role: string
  active?: boolean | null
}

interface PilotRoleField {
  key: PilotRole
  responsibility: PilotResponsibility
  label: string
  help: string
  roleIssue: string
}

const ROLE_FIELDS: ReadonlyArray<PilotRoleField> = [
  {
    key: 'secondLineUserId',
    responsibility: 'SECOND_LINE',
    label: '二线闭环负责人',
    help: '复核候选定位，完成平台外处置并登记结果；需要成员、管理员或所有者角色。',
    roleIssue: '二线闭环负责人需要成员、管理员或所有者角色。',
  },
  {
    key: 'thirdLineUserId',
    responsibility: 'THIRD_LINE',
    label: '三线开发复核人',
    help: '填写人工标准答案，核对准确性和周复盘结果；需要管理员或所有者角色。',
    roleIssue: '三线开发复核人需要管理员或所有者角色。',
  },
  {
    key: 'sourceOwnerUserId',
    responsibility: 'SOURCE_OWNER',
    label: '数据取证负责人',
    help: '保证真实只读查询可用，采集脱敏 Guance 样本；需要管理员或所有者角色。',
    roleIssue: '数据取证负责人需要管理员或所有者角色。',
  },
]

const props = withDefaults(defineProps<{
  plan: TroubleshootingPilotPlan | null
  startOpen?: boolean
  scopeSuggestions?: PilotScopeSuggestion[]
}>(), {
  startOpen: false,
  scopeSuggestions: () => [],
})
const emit = defineEmits<{ updated: [plan: TroubleshootingPilotPlan] }>()
const route = useRoute()
const router = useRouter()
const workspaceStore = useWorkspaceStore()
const members = ref<WorkspacePilotMember[]>([])
const membersLoading = ref(false)
const membersLoadFailed = ref(false)
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
const canManageMembers = computed(() => workspaceStore.can('manage:settings'))
const configured = computed(() => pilotPlanReady(props.plan))
const pilotTeamReadiness = computed(() => buildPilotTeamReadiness(members.value))
const formIssue = computed(() => {
  if (!canManage.value) return '当前角色无权修改试点设置。'
  if (membersLoading.value) return '正在读取工作区成员，请稍候。'
  if (membersLoadFailed.value) return '工作区成员读取失败，请重试后再保存。'
  if (!form.name.trim()) return '请填写试点名称。'
  if (!form.modules.length) return '至少配置一个系统 / 服务。'
  if (form.modules.some(module => !pilotScopeIsSaveable(module))) {
    return '系统和服务请填稳定标识，仅使用字母、数字、点、下划线或短横线。'
  }
  const moduleKeys = form.modules.map(pilotScopeKey)
  if (new Set(moduleKeys).size !== moduleKeys.length) return '系统 / 服务范围不能重复。'
  if (!pilotTeamReadiness.value.ready) {
    return '试点需要 3 名能操作排障的成员，其中至少 2 名管理员或所有者。'
  }
  const people = ROLE_FIELDS.map(role => form[role.key])
  if (people.some(userId => !/^\d+$/.test(userId) || userId === '0')) {
    return '请分别选择二线、三线和数据取证负责人。'
  }
  if (new Set(people).size !== people.length) return '三类职责必须由 3 名不同的工作区成员承担。'
  if (people.some(userId => !members.value.some(member => String(member.userId) === userId))) {
    return '已选人员不在当前工作区，请重新选择。'
  }
  const incompatibleRole = ROLE_FIELDS.find((role) => {
    const member = members.value.find(item => String(item.userId) === form[role.key])
    return member && !pilotMemberCanOwnResponsibility(role.responsibility, member)
  })
  if (incompatibleRole) return incompatibleRole.roleIssue
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
    membersLoadFailed.value = true
    return
  }
  membersLoading.value = true
  membersLoadFailed.value = false
  try {
    const response = await workspaceTeamApi.listMembers(workspaceId)
    members.value = (response.data || []) as WorkspacePilotMember[]
  } catch (error) {
    members.value = []
    membersLoadFailed.value = true
    ElMessage.error(`加载工作区成员失败：${errorText(error)}`)
  } finally {
    membersLoading.value = false
  }
}

function addModule() {
  if (form.modules.length >= 20) return
  form.modules.push({ system: '', service: '' })
}

function scopeSuggestionSelected(suggestion: PilotScopeSuggestion) {
  const suggestionKey = pilotScopeKey(suggestion)
  return form.modules.some(module => pilotScopeKey(module) === suggestionKey)
}

function addScopeSuggestion(suggestion: PilotScopeSuggestion) {
  if (scopeSuggestionSelected(suggestion)) return
  const emptyIndex = form.modules.findIndex(module => !module.system.trim() && !module.service.trim())
  const module = { system: suggestion.system, service: suggestion.service }
  if (emptyIndex >= 0) {
    form.modules.splice(emptyIndex, 1, module)
    return
  }
  if (form.modules.length >= 20) return
  form.modules.push(module)
}

function removeModule(index: number) {
  if (form.modules.length <= 1) return
  form.modules.splice(index, 1)
}

function memberLabel(member: WorkspacePilotMember) {
  const displayName = member.nickname || member.username || `成员 ${member.userId}`
  return `${displayName} · ${member.role}${member.active === true ? '' : ' · 账号不可用'}`
}

function memberDisabled(role: PilotRoleField, member: WorkspacePilotMember) {
  if (!pilotMemberCanOwnResponsibility(role.responsibility, member)) return true
  const candidate = String(member.userId)
  return ROLE_FIELDS.some(otherRole => otherRole.key !== role.key && form[otherRole.key] === candidate)
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
    ElMessage.success(`试点批次 v${response.data.version} 已保存；之后新建且命中范围的正式单才会进入`)
  } catch (error) {
    ElMessage.error(`保存试点设置失败：${errorText(error)}`)
  } finally {
    saving.value = false
  }
}

function openMemberSettings() {
  if (!canManageMembers.value) return
  void router.push(pilotMemberSettingsLocation(route.fullPath))
}

function openOwnerContract() {
  void router.push(t7OwnerContractLocation(route.fullPath))
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

.pilot-preparation {
  display: grid;
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  background: var(--mc-bg-elevated);
}

.pilot-preparation > header {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 16px;
}

.pilot-preparation > header > div { display: grid; gap: 2px; }
.pilot-preparation > header small {
  color: var(--mc-primary);
  font-size: 9px;
  font-weight: 750;
}
.pilot-preparation > header b { font-size: 12px; }
.pilot-preparation > header > span {
  color: var(--mc-text-tertiary);
  font-size: 9px;
  line-height: 1.45;
  text-align: right;
}

.pilot-preparation-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.pilot-preparation-grid > article {
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr);
  gap: 9px;
  padding: 10px;
  border: 1px solid var(--mc-status-warning-border, var(--mc-border-light));
  border-radius: 8px;
  background: var(--mc-status-warning-bg, var(--mc-bg-muted));
}

.pilot-preparation-grid > article.ready {
  border-color: var(--mc-status-success-border, var(--mc-border-light));
  background: var(--mc-status-success-bg, var(--mc-bg-muted));
}

.pilot-preparation-grid > article > i {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  color: var(--mc-bg-elevated);
  background: var(--mc-primary);
  font-size: 9px;
  font-style: normal;
  font-weight: 800;
}

.pilot-preparation-grid > article > div { display: grid; gap: 5px; min-width: 0; }
.pilot-preparation-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.pilot-preparation-title b { font-size: 10px; }
.pilot-preparation-title em {
  color: var(--mc-text-tertiary);
  font-size: 8px;
  font-style: normal;
  white-space: nowrap;
}
.pilot-preparation-grid p {
  margin: 0;
  color: var(--mc-text-secondary);
  font-size: 9px;
  line-height: 1.5;
}
.pilot-preparation-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 24px;
}
.pilot-preparation-actions > span {
  color: var(--mc-text-tertiary);
  font-size: 8px;
  line-height: 1.4;
}

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
.pilot-scope-suggestions {
  display: grid;
  gap: 8px;
  padding: 10px 0 11px;
  border-top: 1px solid var(--mc-border-light);
  border-bottom: 1px solid var(--mc-border-light);
}

.pilot-scope-suggestion-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.pilot-scope-suggestion-head > div { display: grid; gap: 2px; }
.pilot-scope-suggestion-head b { font-size: 10px; }
.pilot-scope-suggestion-head small,
.pilot-scope-suggestion-head > span {
  color: var(--mc-text-tertiary);
  font-size: 9px;
  line-height: 1.45;
}
.pilot-scope-suggestion-head > span { white-space: nowrap; }

.pilot-scope-suggestion-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px;
}

.pilot-scope-suggestion-list > button {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-width: 0;
  padding: 8px 9px;
  border: 1px solid var(--mc-border-light);
  border-radius: 7px;
  color: inherit;
  background: var(--mc-bg-elevated);
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.pilot-scope-suggestion-list > button:hover {
  border-color: color-mix(in srgb, var(--mc-primary) 42%, var(--mc-border));
}

.pilot-scope-suggestion-list > button.selected {
  border-color: var(--mc-status-success-border, var(--mc-border));
  background: var(--mc-status-success-bg, var(--mc-bg-muted));
}

.pilot-scope-suggestion-list > button:disabled { cursor: not-allowed; opacity: .55; }
.pilot-scope-suggestion-list > button > span { display: grid; gap: 2px; min-width: 0; }
.pilot-scope-suggestion-list b {
  overflow: hidden;
  font: 650 10px var(--mc-mono, monospace);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pilot-scope-suggestion-list small { color: var(--mc-text-tertiary); font-size: 9px; }
.pilot-scope-suggestion-list em {
  flex: none;
  color: var(--mc-primary);
  font-size: 9px;
  font-style: normal;
  font-weight: 700;
}
.pilot-scope-suggestion-list > button.selected em { color: var(--mc-status-success-text, var(--mc-success)); }

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
  .pilot-preparation > header { align-items: flex-start; flex-direction: column; gap: 4px; }
  .pilot-preparation > header > span { text-align: left; }
}

@media (max-width: 620px) {
  .pilot-plan-summary { align-items: stretch; flex-direction: column; }
  .pilot-plan-summary > .el-button { width: 100%; }
  .pilot-plan-people { display: grid; grid-template-columns: 1fr 1fr; }
  .pilot-plan-people em { margin-left: 0; }
  .pilot-setup-grid { grid-template-columns: 1fr; }
  .pilot-preparation-grid { grid-template-columns: 1fr; }
  .pilot-scope-suggestion-list { grid-template-columns: 1fr; }
  .pilot-field.wide,
  .enabled-field { grid-column: auto; }
  .pilot-module-row { grid-template-columns: 1fr; }
  .pilot-module-row > span { display: none; }
  .pilot-setup-panel > footer { align-items: stretch; flex-direction: column; }
  .pilot-setup-panel > footer .el-button { width: 100%; margin-left: 0; }
}
</style>
