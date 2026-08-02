<template>
  <div v-loading="loading" class="prov">
    <p v-if="loadError" class="err">{{ loadError }}</p>

    <template v-else-if="provenance">
      <!-- 谁在指挥 -->
      <section class="blk">
        <h4>指挥这次调查的知识</h4>
        <p v-if="!provenance.knowledge.readable" class="warn">
          {{ provenance.knowledge.note }}
        </p>
        <dl v-else class="kv">
          <dt>Playbook</dt>
          <dd>
            {{ provenance.knowledge.title ?? provenance.knowledge.selectorKey }}
            <code>{{ provenance.knowledge.selectorKey }}</code>
            <span class="ver">v{{ provenance.knowledge.playbookVersion }}（冻结）</span>
          </dd>
          <dt>知识来源</dt>
          <dd>
            <span class="origin" :class="{ manual: isHandWritten }">
              {{ originLabel }}
            </span>
            <em v-if="isHandWritten">
              手写夹具与真实归纳在注册表里长得一样；这里点出来，是因为在用它下结论的地方这个区别最要紧。
            </em>
          </dd>
          <dt>责任团队</dt>
          <dd>{{ provenance.knowledge.ownerTeam ?? '未记录' }}</dd>
          <dt>审核状态</dt>
          <dd>{{ provenance.knowledge.operational ? '已审核，可用于处置建议' : '仍为草案，仅影子比对' }}</dd>
        </dl>
      </section>

      <!-- 谁去取的证 -->
      <section class="blk">
        <h4>
          实际取证的适配器
          <span class="tally">回答 {{ answeredCount }} · 未取到 {{ missingCount }}</span>
        </h4>
        <table class="tbl">
          <thead>
            <tr><th>取证请求</th><th>信号</th><th>适配器</th><th>结果</th><th>是否被引用</th></tr>
          </thead>
          <tbody>
            <tr v-for="c in provenance.collectors" :key="c.requestId" :class="c.status">
              <td><code>{{ c.requestId }}</code></td>
              <td>{{ c.signalKind }}</td>
              <td>{{ c.adapter }}</td>
              <td>{{ STATUS_LABEL[c.status] }}</td>
              <td>
                <!--
                  null 与 false 必须分开显示。null = 本路径不维护引用清单；
                  false = 取到了但没支撑结论。混显会把前者读成后者。
                -->
                <span v-if="c.cited === null" class="na">本路径不维护引用清单</span>
                <span v-else-if="c.cited">已引用</span>
                <span v-else class="na">未被引用</span>
              </td>
            </tr>
          </tbody>
        </table>
        <p v-if="!provenance.collectors.length" class="na">本次没有取证记录。</p>
      </section>

      <!-- 怎么推理的 -->
      <section class="blk">
        <h4>推理方式</h4>
        <dl class="kv">
          <dt>路由</dt>
          <dd>
            {{ provenance.reasoning.routeMode }} ·
            {{ provenance.reasoning.investigationMode }} ·
            {{ provenance.reasoning.routeAuthority }}
          </dd>
          <dt>模型参与</dt>
          <dd>
            <template v-if="provenance.reasoning.modelInvoked">
              {{ provenance.reasoning.modelIdentity }}
            </template>
            <b v-else class="none">否——全程零模型调用</b>
          </dd>
          <dt>成立的判据</dt>
          <dd>{{ provenance.reasoning.signalsSatisfied }} 条</dd>
        </dl>
      </section>

      <!-- 刻意没有动用什么 -->
      <section class="blk neg">
        <h4>刻意没有动用</h4>
        <p class="lead">
          这个产品的安全论证大部分由否定句构成。下面每一条都是可以去核对的机制，不是一句安心话。
        </p>
        <ul>
          <li v-for="a in provenance.abstentions" :key="a.capability">
            <b>{{ a.capability }}</b><span class="dash">——</span>{{ a.reason }}
          </li>
        </ul>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { troubleshootingApi, type InvestigationProvenance } from '@/api'

const props = defineProps<{ diagnosisId: string }>()

const STATUS_LABEL: Record<string, string> = {
  NORMAL: '正常',
  ANOMALY: '异常',
  MISSING: '未取到',
}

const provenance = ref<InvestigationProvenance | null>(null)
const loading = ref(false)
const loadError = ref('')
let loadRevision = 0

const isHandWritten = computed(() => {
  const origin = provenance.value?.knowledge.origin
  return origin != null && origin.toUpperCase().startsWith('MANUAL')
})

const originLabel = computed(() => {
  const origin = provenance.value?.knowledge.origin
  if (origin == null) return '未知（冻结版本读不到）'
  return isHandWritten.value ? `手写（${origin}）` : `归纳产出（${origin}）`
})

const answeredCount = computed(
  () => provenance.value?.collectors.filter((c) => c.answered).length ?? 0,
)
const missingCount = computed(
  () => provenance.value?.collectors.filter((c) => !c.answered).length ?? 0,
)

async function load(diagnosisId: string) {
  const revision = ++loadRevision
  loading.value = true
  provenance.value = null
  loadError.value = ''
  try {
    const { data } = await troubleshootingApi.provenance(diagnosisId)
    if (revision === loadRevision) provenance.value = data
  } catch {
    if (revision === loadRevision) {
      loadError.value = '暂时读不到这次调查的参与者清单。这里不做推测——宁可空着，也不猜谁参与过。'
    }
  } finally {
    if (revision === loadRevision) loading.value = false
  }
}

watch(() => props.diagnosisId, (id) => { if (id) void load(id) }, { immediate: true })
</script>

<style scoped>
.prov { font-size: 13px; line-height: 1.7; }
.blk { margin-bottom: 18px; }
.blk h4 { margin: 0 0 8px; font-size: 13px; font-weight: 600; }
.tally { margin-left: 8px; font-weight: 400; color: #909399; font-size: 12px; }
.kv { display: grid; grid-template-columns: 88px 1fr; gap: 4px 12px; margin: 0; }
.kv dt { color: #909399; }
.kv dd { margin: 0; }
.kv dd em { display: block; color: #909399; font-size: 12px; font-style: normal; }
code { background: rgba(127, 127, 127, 0.12); padding: 0 4px; border-radius: 3px; }
.ver { margin-left: 6px; color: #909399; font-size: 12px; }
.origin.manual { color: #e6a23c; font-weight: 600; }
.tbl { width: 100%; border-collapse: collapse; }
.tbl th, .tbl td { text-align: left; padding: 5px 8px; border-bottom: 1px solid rgba(127, 127, 127, 0.18); }
.tbl th { color: #909399; font-weight: 500; }
.tbl tr.MISSING td { color: #909399; }
.na { color: #909399; }
.none { color: #67c23a; }
.warn { color: #e6a23c; }
.err { color: #909399; }
.neg { border-left: 3px solid #67c23a; padding-left: 12px; }
.neg .lead { margin: 0 0 6px; color: #909399; font-size: 12px; }
.neg ul { margin: 0; padding-left: 16px; }
.neg li { margin-bottom: 4px; }
.dash { color: #909399; }
</style>
