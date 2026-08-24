import { createApp, nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const getDiagnosis = vi.fn()
const getConversationMode = vi.fn()

vi.mock('@/api', () => ({
  troubleshootingApi: {
    get: (diagnosisId: string) => getDiagnosis(diagnosisId),
    conversationMode: (conversationId: string) => getConversationMode(conversationId),
    conversationTurn: vi.fn(),
    diagnosisFollowUp: vi.fn(),
  },
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}))

describe('ConversationIntakeDialog mode restoration', () => {
  beforeEach(() => {
    getDiagnosis.mockReset()
    getConversationMode.mockReset()
  })

  it('restores a formal diagnosis as formal instead of silently changing it to rehearsal', async () => {
    getDiagnosis.mockResolvedValue({
      data: {
        diagnosis: { rehearsal: false },
        version: 1,
        created: true,
        pilotPlanVersion: null,
      },
    })
    const { text, unmount } = await render({
      activeDiagnosisId: 'diag-formal-1',
      activeIntakeConversationId: null,
    })

    expect(getDiagnosis).toHaveBeenCalledWith('diag-formal-1')
    expect(text()).toContain('面向真实告警')
    expect(text()).not.toContain('仅用于熟悉流程')
    unmount()
  })

  it('keeps a new ordinary conversation in the explicit default rehearsal mode', async () => {
    const { text, unmount } = await render({
      activeDiagnosisId: null,
      activeIntakeConversationId: null,
    })

    expect(getDiagnosis).not.toHaveBeenCalled()
    expect(text()).toContain('仅用于熟悉流程')
    unmount()
  })

  it('restores an incomplete formal intake from the server instead of browser defaults', async () => {
    getConversationMode.mockResolvedValue({
      data: {
        conversationId: 'conv-formal-awaiting',
        intakeSessionId: 'intake-formal-awaiting',
        status: 'AWAITING_INPUT',
        rehearsal: false,
      },
    })
    const { text, unmount } = await render({
      activeDiagnosisId: null,
      activeIntakeConversationId: 'conv-formal-awaiting',
    })

    expect(getConversationMode).toHaveBeenCalledWith('conv-formal-awaiting')
    expect(getDiagnosis).not.toHaveBeenCalled()
    expect(text()).toContain('面向真实告警')
    expect(text()).not.toContain('仅用于熟悉流程')
    unmount()
  })

  it('falls back to the saved diagnosis mode when a legacy intake has no mode fact', async () => {
    getConversationMode.mockRejectedValue(new Error('legacy mode unavailable'))
    getDiagnosis.mockResolvedValue({
      data: {
        diagnosis: { rehearsal: false },
        version: 1,
        created: true,
        pilotPlanVersion: null,
      },
    })
    const { text, unmount } = await render({
      activeDiagnosisId: 'diag-legacy-formal',
      activeIntakeConversationId: 'conv-legacy-intake',
    })

    expect(getConversationMode).toHaveBeenCalledWith('conv-legacy-intake')
    expect(getDiagnosis).toHaveBeenCalledWith('diag-legacy-formal')
    expect(text()).toContain('面向真实告警')
    expect(text()).not.toContain('无法从服务端确认已锁定模式')
    unmount()
  })
})

async function render(input: {
  activeDiagnosisId: string | null
  activeIntakeConversationId: string | null
}) {
  const Dialog = (await import('../ConversationIntakeDialog.vue')).default
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp(Dialog, {
    modelValue: true,
    originChatConversationId: 'chat-1',
    agentId: 'agent-1',
    activeDiagnosisId: input.activeDiagnosisId,
    activeIntakeConversationId: input.activeIntakeConversationId,
    persistedMessages: [],
  })
  app.mount(host)
  await nextTick()
  await Promise.resolve()
  await nextTick()
  return {
    text: () => host.textContent ?? '',
    unmount: () => {
      app.unmount()
      host.remove()
    },
  }
}
