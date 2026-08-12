<template>
  <div class="settings-section">
    <section
      v-if="pilotReturnTo"
      class="pilot-member-handoff"
      aria-label="智能排障试点成员准备"
    >
      <div class="pilot-member-copy">
        <span>来自智能排障试点</span>
        <strong v-if="loading">正在核对成员数量</strong>
        <strong v-else-if="memberLoadFailed">暂时无法读取成员数量</strong>
        <strong v-else-if="!pilotTeamReadiness.ready">成员或角色还未满足试点要求</strong>
        <strong v-else>角色已经满足试点要求</strong>
        <p v-if="!loading && memberLoadFailed">当前无法确认三人门槛，请刷新页面后再继续试点配置。</p>
        <p v-else-if="!loading && !pilotTeamReadiness.ready">
          当前 Workspace 共 {{ pilotTeamReadiness.memberCount }} 名成员，其中
          {{ pilotTeamReadiness.operatorCount }} 名能推进排障、{{ pilotTeamReadiness.adminCount }} 名能维护评估。
          试点需要 3 名能操作排障的成员，其中至少 2 名具有管理员或所有者角色。
        </p>
        <p v-else-if="!loading">当前角色结构可以完成三类职责，可以返回并继续分配负责人。</p>
        <div v-if="!loading && !memberLoadFailed && !pilotTeamReadiness.ready" class="pilot-repair-plan">
          <b>照着补齐即可：</b>
          <span v-if="pilotRepairPlan.addAdminCount">
            新增 {{ pilotRepairPlan.addAdminCount }} 名管理员
          </span>
          <span v-if="pilotRepairPlan.addMemberCount">
            新增 {{ pilotRepairPlan.addMemberCount }} 名二线成员
          </span>
          <span v-if="pilotRepairPlan.promoteAdminCount">
            将 {{ pilotRepairPlan.promoteAdminCount }} 名现有成员调整为管理员
          </span>
        </div>
      </div>
      <div class="pilot-member-actions">
        <button
          v-if="!loading && pilotRepairPlan.addAdminCount"
          class="btn-primary"
          @click="openAddMemberDialog('admin')"
        >添加管理员</button>
        <button
          v-if="!loading && pilotRepairPlan.addMemberCount"
          class="btn-secondary"
          @click="openAddMemberDialog('member')"
        >添加二线成员</button>
        <button class="btn-secondary" @click="returnToPilot">返回试点配置</button>
      </div>
    </section>

    <div class="section-header">
      <div>
        <h2 class="section-title">{{ t('security.members.title') }}</h2>
        <p class="section-desc">{{ t('security.members.desc') }}</p>
      </div>
      <button class="btn-primary" @click="openAddMemberDialog('member')">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
        </svg>
        {{ t('security.members.addMember') }}
      </button>
    </div>

    <!-- Members Table -->
    <div class="rules-table-wrapper">
      <div v-if="loading" class="empty-state">{{ t('security.members.loading') }}</div>
      <div v-else-if="members.length === 0" class="empty-state">{{ t('security.members.noMembers') }}</div>
      <table v-else class="rules-table">
        <thead>
          <tr>
            <th>{{ t('security.members.columns.user') }}</th>
            <th>{{ t('security.members.columns.role') }}</th>
            <th>{{ t('security.members.columns.joined') }}</th>
            <th style="width: 80px;">{{ t('security.members.columns.actions') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="member in members" :key="member.id">
            <td>
              <div class="member-info">
                <div class="member-avatar">{{ (member.username || member.userId + '').charAt(0).toUpperCase() }}</div>
                <div class="member-detail">
                  <span class="member-name">{{ member.nickname || member.username || ('User #' + member.userId) }}</span>
                  <span v-if="member.username && member.nickname" class="member-username">@{{ member.username }}</span>
                </div>
              </div>
            </td>
            <td>
              <select
                :value="member.role"
                @change="updateRole(member, ($event.target as HTMLSelectElement).value)"
                :disabled="member.role === 'owner'"
                class="config-select"
              >
                <option value="owner" disabled>{{ t('security.members.roles.owner') }}</option>
                <option value="admin">{{ t('security.members.roles.admin') }}</option>
                <option value="member">{{ t('security.members.roles.member') }}</option>
                <option value="viewer">{{ t('security.members.roles.viewer') }}</option>
              </select>
            </td>
            <td class="date-cell">{{ formatDate(member.createTime) }}</td>
            <td>
              <div class="action-btns">
                <button
                  v-if="member.role !== 'owner'"
                  class="action-btn danger"
                  @click="removeMember(member)"
                  :title="t('security.members.actions.remove')"
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <polyline points="3 6 5 6 21 6"/>
                    <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/>
                    <path d="M10 11v6"/><path d="M14 11v6"/>
                  </svg>
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Add Member Dialog -->
    <Teleport to="body">
      <div v-if="showAddDialog" class="modal-overlay">
        <div class="modal">
          <div class="modal-header">
            <h3>{{ t('security.members.addDialog.title') }}</h3>
            <button class="modal-close" @click="closeAddMemberDialog">&times;</button>
          </div>
          <div class="modal-body">
            <div class="form-grid" style="grid-template-columns: 1fr;">
              <div class="account-mode-selector" role="group" aria-label="选择成员账号来源">
                <button
                  type="button"
                  :class="{ active: accountMode === 'existing' }"
                  @click="accountMode = 'existing'"
                >
                  <b>加入已有账号</b>
                  <small>只关联现有 MateClaw 用户，不修改密码</small>
                </button>
                <button
                  type="button"
                  :class="{ active: accountMode === 'new' }"
                  :disabled="!canCreateAccounts"
                  :title="canCreateAccounts ? '' : '只有全局管理员可以创建 MateClaw 账号'"
                  @click="accountMode = 'new'"
                >
                  <b>新建账号并加入</b>
                  <small v-if="canCreateAccounts">仅当这位同事还没有平台账号时使用</small>
                  <small v-else>请联系全局管理员创建账号后，再使用已有账号加入</small>
                </button>
              </div>
              <div class="form-group">
                <label>{{ accountMode === 'existing' ? '已有账号用户名' : '新账号用户名' }} <span class="required">*</span></label>
                <input v-model.trim="newMemberForm.username" type="text" class="form-input" placeholder="输入准确的 MateClaw 用户名" />
                <span class="form-hint" v-if="accountMode === 'existing'">找不到账号时会明确报错，不会自动创建。</span>
                <span class="form-hint" v-else>创建后会立即加入当前工作区；请通过安全渠道交付初始密码。</span>
              </div>
              <div v-if="accountMode === 'new'" class="form-group">
                <label>{{ t('security.members.addDialog.password') }} <span class="required">*</span></label>
                <input v-model="newMemberForm.password" type="password" class="form-input" :placeholder="t('security.members.addDialog.passwordPlaceholder')" />
                <span class="form-hint">新账号必须设置初始密码；已有账号的密码绝不会在这里修改。</span>
              </div>
              <div v-if="accountMode === 'new'" class="form-group">
                <label>{{ t('security.members.addDialog.nickname') }}</label>
                <input v-model.trim="newMemberForm.nickname" type="text" class="form-input" :placeholder="t('security.members.addDialog.nicknamePlaceholder')" />
              </div>
              <div class="form-group">
                <label>{{ t('security.members.addDialog.role') }}</label>
                <select v-model="newMemberForm.role" class="form-input">
                  <option value="admin">{{ t('security.members.roles.admin') }}</option>
                  <option value="member">{{ t('security.members.roles.member') }}</option>
                  <option value="viewer">{{ t('security.members.roles.viewer') }}</option>
                </select>
              </div>
            </div>
          </div>
          <div class="modal-footer">
            <button class="btn-secondary" @click="closeAddMemberDialog">{{ t('security.members.actions.cancel') }}</button>
            <button class="btn-primary" @click="addMember" :disabled="addMemberDisabled">{{ t('security.members.actions.confirm') }}</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, reactive, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { workspaceTeamApi } from '@/api/index'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import {
  buildPilotTeamReadiness,
  buildPilotTeamRepairPlan,
} from '@/views/Troubleshooting/evaluationPilot'
import { pilotMemberReturnPath } from '@/views/Troubleshooting/workbenchCapabilityMenu'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()

interface Member {
  id: number
  workspaceId: number
  userId: number
  username?: string
  nickname?: string
  role: string
  active?: boolean | null
  createTime: string
}

const store = useWorkspaceStore()
const members = ref<Member[]>([])
const loading = ref(false)
const memberLoadFailed = ref(false)
const showAddDialog = ref(false)
const pilotReturnTo = computed(() => pilotMemberReturnPath(route.query.source, route.query.returnTo))
const pilotTeamReadiness = computed(() => buildPilotTeamReadiness(members.value))
const pilotRepairPlan = computed(() => buildPilotTeamRepairPlan(pilotTeamReadiness.value))

const defaultForm = () => ({ username: '', password: '', nickname: '', role: 'member' })
const newMemberForm = reactive(defaultForm())
const accountMode = ref<'existing' | 'new'>('existing')
const canCreateAccounts = computed(() => store.isGlobalAdmin)
const addMemberDisabled = computed(() => !newMemberForm.username
  || (accountMode.value === 'new' && !newMemberForm.password))

onMounted(() => {
  fetchMembers()
})

async function fetchMembers() {
  const wsId = store.currentWorkspaceId
  if (!wsId) {
    members.value = []
    memberLoadFailed.value = true
    return
  }
  loading.value = true
  memberLoadFailed.value = false
  try {
    const res: any = await workspaceTeamApi.listMembers(wsId)
    members.value = res.data || []
  } catch (e: any) {
    memberLoadFailed.value = true
    mcToast.error(e.message)
  } finally {
    loading.value = false
  }
}

async function addMember() {
  const wsId = store.currentWorkspaceId
  if (!wsId || !newMemberForm.username) return
  try {
    await workspaceTeamApi.addMember(wsId, {
      username: newMemberForm.username,
      createUser: accountMode.value === 'new',
      password: accountMode.value === 'new' ? newMemberForm.password : undefined,
      nickname: accountMode.value === 'new' ? newMemberForm.nickname || undefined : undefined,
      role: newMemberForm.role,
    })
    mcToast.success(t('security.members.messages.addSuccess'))
    closeAddMemberDialog()
    await fetchMembers()
  } catch (e: any) {
    mcToast.error(e?.msg || e?.message || t('security.members.messages.addFailed'))
  }
}

function openAddMemberDialog(role: 'admin' | 'member' | 'viewer' = 'member') {
  accountMode.value = 'existing'
  Object.assign(newMemberForm, defaultForm(), { role })
  showAddDialog.value = true
}

function closeAddMemberDialog() {
  showAddDialog.value = false
  accountMode.value = 'existing'
  Object.assign(newMemberForm, defaultForm())
}

function returnToPilot() {
  if (!pilotReturnTo.value) return
  void router.push(pilotReturnTo.value)
}

async function updateRole(member: Member, role: string) {
  const wsId = store.currentWorkspaceId
  if (!wsId) return
  try {
    await workspaceTeamApi.updateMemberRole(wsId, member.userId, role)
    member.role = role
    mcToast.success(t('security.members.messages.updateSuccess'))
  } catch {
    mcToast.error(t('security.members.messages.updateFailed'))
  }
}

async function removeMember(member: Member) {
  const wsId = store.currentWorkspaceId
  if (!wsId) return
  if (!confirm(t('security.members.messages.removeConfirm'))) return
  try {
    await workspaceTeamApi.removeMember(wsId, member.userId)
    mcToast.success(t('security.members.messages.removeSuccess'))
    fetchMembers()
  } catch {
    mcToast.error(t('security.members.messages.removeFailed'))
  }
}

function formatDate(dateStr: string) {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleDateString()
}
</script>

<style>
@import '../shared.css';
</style>

<style scoped>
.pilot-member-handoff {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 22px;
  padding: 14px 16px;
  border-left: 3px solid var(--mc-primary, #D97757);
  background: var(--mc-bg-muted);
}

.pilot-member-copy {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.pilot-member-handoff span {
  color: var(--mc-primary, #D97757);
  font-size: 11px;
  font-weight: 700;
}

.pilot-member-handoff strong {
  color: var(--mc-text-primary);
  font-size: 15px;
}

.pilot-member-handoff p {
  margin: 0;
  color: var(--mc-text-secondary);
  font-size: 12px;
  line-height: 1.55;
}

.pilot-repair-plan {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 7px;
}

.pilot-repair-plan b {
  color: var(--mc-text-primary);
  font-size: 11px;
}

.pilot-repair-plan span {
  padding: 3px 7px;
  border: 1px solid var(--mc-border);
  border-radius: 999px;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-secondary);
  font-size: 10px;
}

.pilot-member-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 7px;
  flex: none;
}

.pilot-member-actions .btn-primary,
.pilot-member-actions .btn-secondary {
  flex: none;
}

.account-mode-selector {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.account-mode-selector > button {
  display: grid;
  gap: 3px;
  padding: 11px 12px;
  border: 1px solid var(--mc-border);
  border-radius: var(--mc-radius-sm);
  background: var(--mc-bg-elevated);
  color: var(--mc-text-secondary);
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.account-mode-selector > button.active {
  border-color: var(--mc-primary, #D97757);
  background: rgba(217, 119, 87, 0.08);
}

.account-mode-selector > button:disabled {
  cursor: not-allowed;
  opacity: .55;
}

.account-mode-selector b { color: var(--mc-text-primary); font-size: 12px; }
.account-mode-selector small { font-size: 10px; line-height: 1.45; }

.member-info {
  display: flex;
  align-items: center;
  gap: 10px;
}

.member-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: rgba(217, 119, 87, 0.12);
  color: var(--mc-primary, #D97757);
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
  font-size: 14px;
  flex-shrink: 0;
}

.member-detail {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.member-name {
  font-weight: 500;
  color: var(--mc-text-primary);
  font-size: 14px;
}

.member-username {
  font-size: 12px;
  color: var(--mc-text-tertiary);
}

.date-cell {
  color: var(--mc-text-tertiary);
  font-size: 13px;
}

.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.required {
  color: var(--mc-danger, #e74c3c);
}

.form-hint {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: var(--mc-text-tertiary);
}

@media (max-width: 680px) {
  .pilot-member-handoff {
    align-items: stretch;
    flex-direction: column;
  }

  .pilot-member-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .pilot-member-actions .btn-primary,
  .pilot-member-actions .btn-secondary {
    width: 100%;
  }

  .account-mode-selector { grid-template-columns: 1fr; }
}
</style>
