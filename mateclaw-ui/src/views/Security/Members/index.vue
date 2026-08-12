<template>
  <div class="settings-section">
    <section
      v-if="pilotReturnTo"
      class="pilot-member-handoff"
      aria-label="智能排障试点成员准备"
    >
      <div>
        <span>来自智能排障试点</span>
        <strong v-if="loading">正在核对成员数量</strong>
        <strong v-else-if="memberLoadFailed">暂时无法读取成员数量</strong>
        <strong v-else-if="!pilotMemberProgress.ready">
          还需添加 {{ pilotMemberProgress.missingCount }} 名成员
        </strong>
        <strong v-else>成员已满足试点要求</strong>
        <p v-if="!loading && memberLoadFailed">当前无法确认三人门槛，请刷新页面后再继续试点配置。</p>
        <p v-else-if="!loading && !pilotMemberProgress.ready">
          当前 Workspace 有 {{ pilotMemberProgress.memberCount }} 名成员；至少需要 3 名，才能分别指定二线、三线和数据取证负责人。
        </p>
        <p v-else-if="!loading">当前 Workspace 有 {{ pilotMemberProgress.memberCount }} 名成员，可以返回并继续分配三类负责人。</p>
      </div>
      <button class="btn-secondary" @click="returnToPilot">返回试点配置</button>
    </section>

    <div class="section-header">
      <div>
        <h2 class="section-title">{{ t('security.members.title') }}</h2>
        <p class="section-desc">{{ t('security.members.desc') }}</p>
      </div>
      <button class="btn-primary" @click="showAddDialog = true">
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
            <button class="modal-close" @click="showAddDialog = false">&times;</button>
          </div>
          <div class="modal-body">
            <div class="form-grid" style="grid-template-columns: 1fr;">
              <div class="form-group">
                <label>{{ t('security.members.addDialog.username') }} <span class="required">*</span></label>
                <input v-model.trim="newMemberForm.username" type="text" class="form-input" :placeholder="t('security.members.addDialog.usernamePlaceholder')" />
                <span class="form-hint">{{ t('security.members.addDialog.usernameHint') }}</span>
              </div>
              <div class="form-group">
                <label>{{ t('security.members.addDialog.password') }}</label>
                <input v-model="newMemberForm.password" type="password" class="form-input" :placeholder="t('security.members.addDialog.passwordPlaceholder')" />
                <span class="form-hint">{{ t('security.members.addDialog.passwordHint') }}</span>
              </div>
              <div class="form-group">
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
            <button class="btn-secondary" @click="showAddDialog = false">{{ t('security.members.actions.cancel') }}</button>
            <button class="btn-primary" @click="addMember" :disabled="!newMemberForm.username">{{ t('security.members.actions.confirm') }}</button>
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
import { buildPilotMemberProgress } from '@/views/Troubleshooting/evaluationPilot'
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
  createTime: string
}

const store = useWorkspaceStore()
const members = ref<Member[]>([])
const loading = ref(false)
const memberLoadFailed = ref(false)
const showAddDialog = ref(false)
const pilotReturnTo = computed(() => pilotMemberReturnPath(route.query.source, route.query.returnTo))
const pilotMemberProgress = computed(() => buildPilotMemberProgress(members.value.length))

const defaultForm = () => ({ username: '', password: '', nickname: '', role: 'member' })
const newMemberForm = reactive(defaultForm())

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
      password: newMemberForm.password || undefined,
      nickname: newMemberForm.nickname || undefined,
      role: newMemberForm.role,
    })
    mcToast.success(t('security.members.messages.addSuccess'))
    showAddDialog.value = false
    Object.assign(newMemberForm, defaultForm())
    await fetchMembers()
  } catch (e: any) {
    mcToast.error(e?.msg || e?.message || t('security.members.messages.addFailed'))
  }
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

.pilot-member-handoff > div {
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

.pilot-member-handoff .btn-secondary {
  flex: none;
}

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

  .pilot-member-handoff .btn-secondary {
    width: 100%;
  }
}
</style>
