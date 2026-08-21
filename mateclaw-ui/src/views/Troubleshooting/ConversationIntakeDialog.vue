<template>
  <el-drawer
    v-model="open"
    title="对话发起排障"
    :size="'var(--mc-ts-drawer-width)'"
    destroy-on-close
    @closed="resetLocal"
  >
    <div class="conv-guide">
      <p><strong>直接粘贴完整告警</strong>，系统会识别服务、错误码和发生时间，匹配已审核的排障方法并开始只读调查。</p>
      <p class="conv-hint">资料不足时系统会继续追问；有错误码请保留在告警原文中。</p>
    </div>

    <div class="conv-mode">
      <el-checkbox
        v-model="rehearsal"
        disabled
      >演练模式（当前对话入口仅支持演练）</el-checkbox>
      <span>正式通用调查请使用「新建排障单」；这里不会把演练冒充成正式结果。</span>
    </div>

    <div ref="threadEl" class="conv-thread" aria-live="polite">
      <div v-if="!messages.length" class="conv-empty">
        例如先发：「CSDP 消息发不出去了」或直接贴告警摘要。
      </div>
      <article
        v-for="(item, index) in messages"
        :key="`${item.role}-${index}`"
        class="conv-bubble"
        :class="item.role"
      >
        <span class="conv-role">{{ item.role === 'user' ? '你' : 'MateClaw' }}</span>
        <pre>{{ item.text }}</pre>
      </article>
      <article v-if="pendingText" class="conv-bubble user pending">
        <span class="conv-role">你 · 发送中</span>
        <pre>{{ pendingText }}</pre>
      </article>
    </div>

    <el-form class="conv-composer" @submit.prevent="send">
      <el-input
        v-model="draft"
        type="textarea"
        :rows="4"
        maxlength="4000"
        show-word-limit
        :disabled="loading || followUpEnded"
        :placeholder="composerPlaceholder"
        @keydown.enter.exact.prevent="send"
      />
    </el-form>

    <template #footer>
      <el-button text @click="$emit('switch-form')">改用表单填写</el-button>
      <el-button :disabled="loading" @click="open = false">关闭</el-button>
      <el-button
        v-if="diagnosisId"
        @click="goDiagnosisDetail"
      >查看排障详情</el-button>
      <el-button
        v-if="!followUpEnded"
        type="primary"
        :loading="loading"
        :disabled="!canSend"
        @click="send"
      >发送</el-button>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { troubleshootingApi } from '@/api'
import type { ConversationTurnResult } from '@/api'
import { projectRetryableTroubleshootingTurn } from './diagnosisFollowUpContext'

type ChatRole = 'user' | 'assistant'
type ChatMessage = { role: ChatRole; text: string }

const open = defineModel<boolean>({ required: true })
const props = defineProps<{
  originChatConversationId: string | null
  agentId?: string
  activeDiagnosisId?: string | null
  activeIntakeConversationId?: string | null
  persistedMessages?: Array<{
    conversationId?: string
    role?: string
    content?: string
    metadata?: any
  }>
}>()
const router = useRouter()
const emit = defineEmits<{
  'switch-form': []
  ready: [payload: {
    diagnosisId: string
    conversationId: string
    created: boolean | null
    rehearsal: boolean
    originChatConversationId: string | null
  }]
  ended: [payload: {
    diagnosisId: string
    originChatConversationId: string | null
  }]
  persisted: [chatConversationId: string, completed?: () => void]
}>()

const draft = ref('')
const loading = ref(false)
const conversationId = ref<string | null>(null)
const diagnosisId = ref<string | null>(null)
const followUpEnded = ref(false)
const messages = ref<ChatMessage[]>([])
const pendingText = ref<string | null>(null)
const threadEl = ref<HTMLElement | null>(null)
const rehearsal = ref(true)
let requestGeneration = 0
let retryTurn: { text: string | null; diagnosisId: string | null; clientTurnId: string } | null = null

const canSend = computed(() =>
  !loading.value && !followUpEnded.value && draft.value.trim().length > 0,
)
const composerPlaceholder = computed(() => diagnosisId.value
  ? '可问原因、证据、未知和下一步；补充材料请以“补充证据：”开头；输入“结束排障”退出'
  : '输入这一轮要补充的内容，Enter 发送（Shift+Enter 换行）')

watch(open, (value) => {
  if (value) resetLocal()
})

watch(() => props.originChatConversationId, (value, previous) => {
  if (open.value && value !== previous) resetLocal()
})

watch(() => props.persistedMessages, () => {
  if (open.value) restorePersistedMessages()
}, { deep: true })

function restorePersistedMessages() {
  const origin = props.originChatConversationId
  const persisted = (props.persistedMessages ?? [])
    .filter(item => item.conversationId === origin
      && item.metadata?.type === 'troubleshooting_transcript'
      && (item.role === 'user' || item.role === 'assistant'))
  messages.value = persisted
    .map(item => ({ role: item.role as ChatRole, text: item.content ?? '' }))
    .filter(item => item.text.length > 0)
  const recoverable = projectRetryableTroubleshootingTurn(
    props.persistedMessages ?? [], origin ?? '',
  )
  if (recoverable) {
    retryTurn = {
      text: null,
      diagnosisId: props.activeDiagnosisId ?? null,
      clientTurnId: recoverable.clientTurnId,
    }
  } else if (!loading.value) {
    retryTurn = null
  }
}

function resetLocal() {
  requestGeneration += 1
  draft.value = ''
  loading.value = false
  conversationId.value = props.activeIntakeConversationId ?? null
  diagnosisId.value = props.activeDiagnosisId ?? null
  followUpEnded.value = false
  pendingText.value = null
  restorePersistedMessages()
  rehearsal.value = true
}

async function refreshPersisted(chatConversationId: string | null) {
  if (!chatConversationId) return
  await new Promise<void>((resolve) => emit('persisted', chatConversationId, resolve))
  pendingText.value = null
  restorePersistedMessages()
}

async function scrollToBottom() {
  await nextTick()
  const el = threadEl.value
  if (el) el.scrollTop = el.scrollHeight
}

async function send() {
  const text = draft.value.trim()
  if (!canSend.value) return
  const generation = ++requestGeneration
  const originChatConversationId = props.originChatConversationId ?? null
  const transcriptTarget = originChatConversationId && props.agentId
    ? { chatConversationId: originChatConversationId, agentId: props.agentId }
    : {}
  const originDiagnosisId = diagnosisId.value
  const clientTurnId = retryTurn?.diagnosisId === originDiagnosisId
      && (retryTurn.text === null || retryTurn.text === text)
    ? retryTurn.clientTurnId
    : (typeof crypto !== 'undefined' && crypto.randomUUID
        ? crypto.randomUUID()
        : `turn-${Date.now()}-${Math.random().toString(36).slice(2, 12)}`)
  retryTurn = { text, diagnosisId: originDiagnosisId, clientTurnId }
  pendingText.value = text
  draft.value = ''
  await scrollToBottom()
  loading.value = true
  try {
    if (originDiagnosisId) {
      const { data } = await troubleshootingApi.diagnosisFollowUp(originDiagnosisId, {
        text,
        clientTurnId,
        ...transcriptTarget,
      })
      if (!open.value || requestGeneration !== generation) return
      retryTurn = null
      await refreshPersisted(originChatConversationId)
      followUpEnded.value = data.status === 'ENDED'
      if (followUpEnded.value) emit('ended', {
        diagnosisId: originDiagnosisId,
        originChatConversationId,
      })
    } else {
      const { data } = await troubleshootingApi.conversationTurn({
        conversationId: conversationId.value,
        clientTurnId,
        ...transcriptTarget,
        text,
        rehearsal: rehearsal.value,
      })
      if (!open.value || requestGeneration !== generation) return
      retryTurn = null
      applyTurn(data, originChatConversationId)
      await refreshPersisted(originChatConversationId)
    }
  } catch (error: any) {
    if (!open.value || requestGeneration !== generation) return
    ElMessage.error(error?.message || '对话发起失败')
    await refreshPersisted(originChatConversationId)
  } finally {
    if (open.value && requestGeneration === generation) {
      loading.value = false
      await scrollToBottom()
    }
  }
}

function applyTurn(
  data: ConversationTurnResult,
  originChatConversationId: string | null,
) {
  conversationId.value = data.conversationId
  rehearsal.value = data.rehearsal
  if (data.status === 'READY' && data.diagnosisId) {
    diagnosisId.value = data.diagnosisId
    emit('ready', {
      diagnosisId: data.diagnosisId,
      conversationId: data.conversationId,
      created: data.created,
      rehearsal: data.rehearsal,
      originChatConversationId,
    })
  }
}

function goDiagnosisDetail() {
  if (!diagnosisId.value) return
  open.value = false
  router.push({
    path: '/troubleshooting',
    query: { view: 'detail', diagnosisId: diagnosisId.value },
  })
}
</script>

<style scoped>
.conv-guide {
  margin: 0 0 14px;
  padding: 12px 14px;
  border: 1px solid var(--mc-border);
  border-radius: var(--mc-radius-sm, 8px);
  background: var(--mc-bg-muted);
}
.conv-guide p {
  margin: 0;
  color: var(--mc-text-secondary);
  font-size: 13px;
  line-height: 1.65;
}
.conv-hint {
  margin-top: 8px !important;
  color: var(--mc-text-tertiary) !important;
  font-size: 12px !important;
}
.conv-mode {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: -4px 0 14px;
  padding: 10px 14px;
  border: 1px solid var(--mc-border);
  border-radius: var(--mc-radius-sm, 8px);
  background: var(--mc-bg);
}
.conv-mode span {
  padding-left: 24px;
  color: var(--mc-text-tertiary);
  font-size: 11px;
  line-height: 1.5;
}
.conv-thread {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: min(48vh, 420px);
  overflow: auto;
  padding: 4px 2px 12px;
}
.conv-empty {
  padding: 28px 12px;
  color: var(--mc-text-tertiary);
  font-size: 13px;
  text-align: center;
  line-height: 1.6;
}
.conv-bubble {
  max-width: 92%;
  padding: 10px 12px;
  border: 1px solid var(--mc-border);
  border-radius: 12px;
  background: var(--mc-bg);
}
.conv-bubble.user {
  align-self: flex-end;
  border-color: color-mix(in srgb, var(--mc-primary) 28%, var(--mc-border));
  background: color-mix(in srgb, var(--mc-primary) 8%, var(--mc-bg));
}
.conv-bubble.assistant {
  align-self: flex-start;
  background: var(--mc-bg-muted);
}
.conv-role {
  display: block;
  margin-bottom: 4px;
  color: var(--mc-text-tertiary);
  font-size: 11px;
  font-weight: 700;
}
.conv-bubble pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font: 13px/1.6 inherit;
  color: var(--mc-text-primary);
}
.conv-composer {
  margin-top: 8px;
}
</style>
