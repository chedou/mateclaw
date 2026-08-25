<template>
  <section class="evidence-settings" v-loading="loading">
    <header class="settings-head">
      <div>
        <h2>{{ sourceTitle }}</h2>
        <p>
          {{ sourceDescription }}
          <template v-if="view && view.origin === 'DEPLOYMENT'">
            当前本 Workspace 还没有自己的配置，下面显示的是部署环境（application.yml / 环境变量）的默认值。
          </template>
        </p>
      </div>
      <el-tag v-if="view" :type="view.origin === 'WORKSPACE' ? 'success' : 'info'" effect="plain" size="small">
        {{ view.origin === 'WORKSPACE' ? '本 Workspace 配置' : '继承部署默认值' }}
      </el-tag>
    </header>

    <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" class="settings-alert" />

    <el-alert
      v-if="view && !isOnlineConfigurable"
      type="info"
      :closable="false"
      show-icon
      title="该数据源暂不支持在线修改"
      description="当前页面只展示接入状态。它由部署配置或专用适配器管理，如需修改，请由管理员更新对应的数据连接。"
      class="settings-alert"
    />

    <el-form v-if="view && isOnlineConfigurable" label-position="top" class="settings-form">
      <div v-if="isGuance" class="settings-grid">
        <el-form-item label="观测云（Guance）">
          <el-switch v-model="form.guanceEnabled" active-text="启用" inactive-text="停用" />
          <small class="field-hint">关闭后本 Workspace 不会再向观测云发起任何取证请求。</small>
        </el-form-item>
      </div>

      <div v-else-if="isReplay" class="settings-grid">
        <el-form-item label="受控回放（Recorded Replay）">
          <el-switch v-model="form.replayEnabled" active-text="启用" inactive-text="停用" />
          <small class="field-hint">脱敏样本回放，用于演示与联调，结论会标注为非真实观测云。</small>
        </el-form-item>
      </div>

      <template v-if="isGuance && form.guanceEnabled">
        <el-form-item label="观测云 API 地址">
          <el-input v-model="form.guanceBaseUrl" placeholder="https://openapi.guance.com" />
          <small class="field-hint">
            只填到域名和端口，不要带查询路径。内网地址还需要部署环境把该主机加进
            <code>MATECLAW_SECURITY_SSRF_ALLOWLIST</code>，否则保存会被出站防护拒绝。
          </small>
        </el-form-item>

        <el-form-item label="观测云 API Key">
          <el-input
            v-model="form.guanceApiKey"
            type="password"
            show-password
            :placeholder="keyPlaceholder"
          />
          <!--
            密钥只写不读：服务端不会把它回传给浏览器，所以这里留空表示「保持不变」，
            而不是「清空」。清空需要显式点下面那个按钮，避免改个地址就把凭据抹了。
          -->
          <small class="field-hint">
            {{ view.guanceApiKeyPresent
              ? `已保存密钥 ${view.guanceApiKeyMask}；留空表示保持不变。`
              : '尚未保存密钥。' }}
            <el-button
              v-if="view.guanceApiKeyPresent && !clearKey"
              type="danger"
              text
              size="small"
              @click="clearKey = true"
            >清除已保存的密钥</el-button>
            <template v-else-if="clearKey">
              <b class="clearing">保存后将清除已存密钥。</b>
              <el-button type="primary" text size="small" @click="clearKey = false">取消清除</el-button>
            </template>
          </small>
        </el-form-item>

        <el-form-item v-if="showInsecureToggle" label="允许 http 明文端点">
          <el-switch v-model="form.guanceAllowInsecureHttp" />
          <small class="field-hint">
            仅限内网自建观测云确无 TLS 时使用；开启后 API Key 会以明文经过网络。
          </small>
        </el-form-item>
      </template>

      <el-form-item label="变更说明">
        <el-input v-model="form.changeReason" maxlength="200" show-word-limit placeholder="例如：接入内网观测云试点" />
        <small class="field-hint">会记入审计，便于日后追溯是谁在什么时候改了取证配置。</small>
      </el-form-item>

      <footer class="settings-footer">
        <span class="footer-note">{{ formIssue || footerHint }}</span>
        <el-button :disabled="saving" @click="reset">重置</el-button>
        <el-button type="primary" :loading="saving" :disabled="Boolean(formIssue)" @click="save">
          保存设置
        </el-button>
      </footer>
    </el-form>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus/es/components/message/index'
import { vLoading } from 'element-plus/es/components/loading/index'
import { troubleshootingApi } from '@/api'
import type { EvidenceSettingsView } from '@/api/troubleshooting-contracts'

const emit = defineEmits<{ (event: 'saved', value: EvidenceSettingsView): void }>()
const props = defineProps<{ platform: string }>()

const loading = ref(false)
const saving = ref(false)
const error = ref('')
const view = ref<EvidenceSettingsView | null>(null)
const clearKey = ref(false)

const normalizedPlatform = computed(() => props.platform.trim().toLowerCase())
const isGuance = computed(() => normalizedPlatform.value === 'guance')
const isReplay = computed(() => normalizedPlatform.value === 'recorded-replay')
const isOnlineConfigurable = computed(() => isGuance.value || isReplay.value)
const sourceTitle = computed(() => {
  if (isGuance.value) return '观测云连接配置'
  if (isReplay.value) return '受控回放配置'
  return `${props.platform} 数据源`
})
const sourceDescription = computed(() => {
  if (isGuance.value) return '配置观测云 API 地址与凭据，供系统执行真实的只读日志查询。保存后对下一次调查生效。'
  if (isReplay.value) return '控制是否允许使用脱敏样本进行演示和联调；回放结果不会被标记为生产真实证据。'
  return '查看这个数据源当前的接入状态和管理方式。'
})

const form = reactive({
  guanceEnabled: false,
  guanceBaseUrl: '',
  guanceApiKey: '',
  guanceAllowInsecureHttp: false,
  replayEnabled: false,
  agentEnabled: false,
  changeReason: '',
})

const keyPlaceholder = computed(() =>
  view.value?.guanceApiKeyPresent ? '留空则保持已保存的密钥不变' : '粘贴观测云 API Key',
)

// 明文端点这个开关平时不该出现——它是个降级选项，摆在眼前会被顺手打开。
// 只有已经存着 http 地址，或者当前正填着 http 地址时才显示。
const showInsecureToggle = computed(() =>
  form.guanceAllowInsecureHttp || form.guanceBaseUrl.trim().toLowerCase().startsWith('http://'),
)

const formIssue = computed(() => {
  if (!isGuance.value) return ''
  if (!form.guanceEnabled) return ''
  const baseUrl = form.guanceBaseUrl.trim()
  if (!baseUrl) return '启用观测云需要填写 API 地址。'
  if (!/^https?:\/\//i.test(baseUrl)) return 'API 地址需要以 http:// 或 https:// 开头。'
  if (baseUrl.toLowerCase().startsWith('http://') && !form.guanceAllowInsecureHttp) {
    return 'http 明文地址需要显式勾选「允许 http 明文端点」。'
  }
  const keyWillExist = clearKey.value
    ? false
    : Boolean(form.guanceApiKey.trim()) || Boolean(view.value?.guanceApiKeyPresent)
  if (!keyWillExist) return '启用观测云需要一个 API Key。'
  return ''
})

const footerHint = computed(() =>
  isReplay.value
    ? '保存后立即影响后续演示与联调，不影响已完成的调查记录。'
    : view.value?.origin === 'DEPLOYMENT'
    ? '首次保存会为本 Workspace 建立独立配置，之后不再跟随部署默认值变化。'
    : '保存后立即生效；修改地址会让已有的 T7 验收失效，需要重新验收。',
)

function applyView(next: EvidenceSettingsView) {
  view.value = next
  form.guanceEnabled = next.guanceEnabled
  form.guanceBaseUrl = next.guanceBaseUrl || ''
  form.guanceApiKey = ''
  form.guanceAllowInsecureHttp = next.guanceAllowInsecureHttp
  form.replayEnabled = next.replayEnabled
  form.agentEnabled = next.agentEnabled
  form.changeReason = ''
  clearKey.value = false
}

function errorText(failure: unknown): string {
  if (failure && typeof failure === 'object' && 'response' in failure) {
    const response = (failure as { response?: { data?: { message?: string } } }).response
    if (response?.data?.message) return response.data.message
  }
  return failure instanceof Error ? failure.message : String(failure)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const response = await troubleshootingApi.evidenceSettings()
    applyView(response.data)
  } catch (failure) {
    error.value = `读取数据源设置失败：${errorText(failure)}`
  } finally {
    loading.value = false
  }
}

function reset() {
  if (view.value) applyView(view.value)
}

async function save() {
  if (!view.value || formIssue.value) return
  saving.value = true
  try {
    const typed = form.guanceApiKey.trim()
    const response = await troubleshootingApi.saveEvidenceSettings({
      guanceEnabled: form.guanceEnabled,
      guanceBaseUrl: form.guanceBaseUrl.trim() || null,
      // 三态：清除传空串，填了传新值，都没有就整个不传，服务端保持原样。
      guanceApiKey: clearKey.value ? '' : typed || undefined,
      guanceAllowInsecureHttp: form.guanceAllowInsecureHttp,
      replayEnabled: form.replayEnabled,
      agentEnabled: form.agentEnabled,
      expectedVersion: view.value.version,
      changeReason: form.changeReason.trim() || null,
    })
    applyView(response.data)
    emit('saved', response.data)
    ElMessage.success(`数据源设置已保存（v${response.data.version}）`)
  } catch (failure) {
    // 版本冲突不能当成普通失败一带而过：别人已经写过了，重试只会覆盖对方。
    const text = errorText(failure)
    if (/version|冲突|conflict/i.test(text)) {
      ElMessage.warning('设置已被其他人修改，已为你重新载入最新值，请确认后再保存。')
      await load()
    } else {
      ElMessage.error(`保存数据源设置失败：${text}`)
    }
  } finally {
    saving.value = false
  }
}

onMounted(load)

defineExpose({ reload: load })
</script>

<style scoped>
.evidence-settings {
  padding: 20px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-bg-color);
  margin-bottom: 16px;
}

.settings-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.settings-head h2 {
  margin: 0 0 4px;
  font-size: 16px;
  font-weight: 600;
}

.settings-head p {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.settings-alert {
  margin-bottom: 16px;
}

.settings-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 0 24px;
}

.field-hint {
  display: block;
  width: 100%;
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.field-hint code {
  padding: 1px 4px;
  border-radius: 3px;
  background: var(--el-fill-color-light);
  font-size: 11px;
}

.clearing {
  color: var(--el-color-danger);
}

.settings-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.footer-note {
  flex: 1;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
