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
    </div>

    <el-form class="conv-composer" @submit.prevent="send">
      <el-input
        v-model="draft"
        type="textarea"
        :rows="4"
        maxlength="4000"
        show-word-limit
        :disabled="loading || !!diagnosisId"
        placeholder="输入这一轮要补充的内容，Enter 发送（Shift+Enter 换行）"
        @keydown.enter.exact.prevent="send"
      />
    </el-form>

    <template #footer>
      <el-button text @click="$emit('switch-form')">改用表单填写</el-button>
      <el-button @click="open = false">关闭</el-button>
      <el-button
        v-if="diagnosisId"
        type="primary"
        @click="goDiagnosisDetail"
      >查看排障详情</el-button>
      <el-button
        v-else
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

type ChatRole = 'user' | 'assistant'
type ChatMessage = { role: ChatRole; text: string }

const open = defineModel<boolean>({ required: true })
const router = useRouter()
const emit = defineEmits<{
  'switch-form': []
  ready: [payload: { diagnosisId: string; created: boolean | null; rehearsal: boolean }]
}>()

const draft = ref('')
const loading = ref(false)
const conversationId = ref<string | null>(null)
const diagnosisId = ref<string | null>(null)
const messages = ref<ChatMessage[]>([])
const threadEl = ref<HTMLElement | null>(null)
const rehearsal = ref(true)

const canSend = computed(() =>
  !loading.value && !diagnosisId.value && draft.value.trim().length > 0,
)

watch(open, (value) => {
  if (value) resetLocal()
})

function resetLocal() {
  draft.value = ''
  loading.value = false
  conversationId.value = null
  diagnosisId.value = null
  messages.value = []
  rehearsal.value = true
}

async function scrollToBottom() {
  await nextTick()
  const el = threadEl.value
  if (el) el.scrollTop = el.scrollHeight
}

async function send() {
  const text = draft.value.trim()
  if (!canSend.value) return
  messages.value.push({ role: 'user', text })
  draft.value = ''
  await scrollToBottom()
  loading.value = true
  try {
    const { data } = await troubleshootingApi.conversationTurn({
      conversationId: conversationId.value,
      text,
      rehearsal: rehearsal.value,
    })
    applyTurn(data)
  } catch (error: any) {
    ElMessage.error(error?.message || '对话发起失败')
    messages.value.push({
      role: 'assistant',
      text: '这一轮没有收下，请检查内容后重试。不要粘贴密钥或原始日志。',
    })
  } finally {
    loading.value = false
    await scrollToBottom()
  }
}

function applyTurn(data: ConversationTurnResult) {
  conversationId.value = data.conversationId
  rehearsal.value = data.rehearsal
  messages.value.push({ role: 'assistant', text: data.prompt })
  if (data.status === 'READY' && data.diagnosisId) {
    diagnosisId.value = data.diagnosisId
    emit('ready', {
      diagnosisId: data.diagnosisId,
      created: data.created,
      rehearsal: data.rehearsal,
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
