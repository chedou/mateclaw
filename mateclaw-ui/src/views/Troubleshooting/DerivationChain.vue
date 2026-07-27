<template>
  <div v-loading="loading" class="chain">
    <p v-if="!isDeterministic" class="fallback">
      未命中路由由只读 Agent 给出假设，<b>没有确定性判定链</b>。下面是它引用过的取证结果，
      未被引用的证据不参与结论。
    </p>

    <p v-else-if="derivation && !derivation.faithful" class="unfaithful">
      {{ derivation.note ?? 'SOP 自本次诊断后已变更，下面的判定链无法还原当时的推导。' }}
    </p>

    <!-- 第一步：取到了什么证据 -->
    <section class="step">
      <div class="snum">第一步 · 取到了什么证据</div>
      <div v-for="ev in diagnosis.evidence" :key="ev.queryId" class="ev">
        <span class="dot" :class="ev.status" />
        <div class="evmain">
          <div class="evline">
            <template v-if="observedEntries(ev).length">
              <span v-for="[k, v] in observedEntries(ev)" :key="k" class="pair">
                <span class="evk">{{ k }}</span>=<span class="evv">{{ v }}</span>
              </span>
            </template>
            <span v-else class="evnone">未取到数据</span>
          </div>
          <div class="evmeta">{{ ev.queryId }} · {{ ev.status }} · {{ ev.source }}</div>
        </div>
      </div>
      <p v-if="!diagnosis.evidence.length" class="empty">本次没有取证记录。</p>
    </section>

    <!-- 第二步：判据怎么算的 -->
    <section v-if="isDeterministic" class="step">
      <div class="snum">
        第二步 · 判据怎么算的
        <span class="tally">
          成立 {{ counts.satisfied }} · 已排除 {{ counts.excluded }} · 无法求值 {{ counts.unevaluated }}
        </span>
      </div>
      <article v-for="c in orderedCriteria" :key="c.signal" class="cr" :class="c.outcome">
        <div class="crtop">
          <span class="crst">{{ OUTCOME_LABEL[c.outcome] }}</span>
          <span class="crname">{{ c.signal }}</span>
          <span class="crkind">{{ c.kind }}</span>
        </div>
        <div class="calc">{{ c.substitution || c.expression }}</div>
        <div class="crwhy">
          <template v-if="c.outcome === 'UNEVALUATED'">
            证据 {{ c.sourceRequestId }} 为 {{ c.evidenceStatus }}，<b>该假设从未被检验</b>——不等于已排除。
          </template>
          <template v-else>{{ c.description }}</template>
        </div>
      </article>
      <p v-if="!orderedCriteria.length" class="empty">该 SOP 没有定义判据。</p>
    </section>

    <!-- 第三步：规则怎么裁决的 -->
    <section v-if="isDeterministic" class="step">
      <div class="snum">第三步 · 规则怎么裁决的</div>
      <article
        v-for="r in derivation?.rules ?? []"
        :key="r.ruleId"
        class="rule"
        :class="{ hit: r.fired }"
      >
        <div class="rtop">
          <span class="rid">{{ r.ruleId }}</span>
          <span class="rname">{{ r.rootCause }}</span>
          <span class="rtag" :class="ruleBadgeClass(r)">{{ ruleBadgeText(r) }}</span>
        </div>
        <div class="rwhy">
          <template v-if="r.fired">
            需要 <code v-for="s in r.requiredSignals" :key="s">{{ s }}</code> 全部成立，本次全部成立。
            反事实：任一条不成立，结论会退到下一条规则。
          </template>
          <template v-else>
            <span v-if="r.unsatisfiedByExclusion.length">
              <code v-for="s in r.unsatisfiedByExclusion" :key="s">{{ s }}</code>
              求值为假——<b>真的排除了</b>。
            </span>
            <span v-if="r.unsatisfiedByGap.length">
              <code v-for="s in r.unsatisfiedByGap" :key="s">{{ s }}</code>
              缺证据——<b>假设未被检验</b>，不等于排除。
            </span>
            <span v-if="r.undefinedSignals.length">
              <code v-for="s in r.undefinedSignals" :key="s">{{ s }}</code>
              没有任何判据产出——这是 SOP 本身的缺口。
            </span>
          </template>
        </div>
      </article>
      <p v-if="!(derivation?.rules ?? []).length" class="empty">该 SOP 没有定义规则。</p>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  troubleshootingApi,
  type CriterionOutcome,
  type Diagnosis,
  type DiagnosisDerivation,
  type EvidenceResult,
  type RuleEvaluation,
} from '@/api'

const props = defineProps<{ diagnosis: Diagnosis }>()

const OUTCOME_LABEL: Record<CriterionOutcome, string> = {
  SATISFIED: '成立',
  EXCLUDED: '已排除',
  UNEVALUATED: '无法求值',
}
/** Satisfied first, then the untested ones — those are what the operator can act on. */
const OUTCOME_ORDER: Record<CriterionOutcome, number> = {
  SATISFIED: 0,
  UNEVALUATED: 1,
  EXCLUDED: 2,
}

const derivation = ref<DiagnosisDerivation | null>(null)
const loading = ref(false)
const isDeterministic = computed(() => props.diagnosis.routeMode === 'DETERMINISTIC')
let loadRevision = 0

const orderedCriteria = computed(() =>
  [...(derivation.value?.criteria ?? [])].sort(
    (a, b) => OUTCOME_ORDER[a.outcome] - OUTCOME_ORDER[b.outcome],
  ),
)

const counts = computed(() => {
  const criteria = derivation.value?.criteria ?? []
  return {
    satisfied: criteria.filter((c) => c.outcome === 'SATISFIED').length,
    excluded: criteria.filter((c) => c.outcome === 'EXCLUDED').length,
    unevaluated: criteria.filter((c) => c.outcome === 'UNEVALUATED').length,
  }
})

function ruleBadgeClass(rule: RuleEvaluation) {
  if (rule.fired) return 'fired'
  return rule.unsatisfiedByGap.length || rule.undefinedSignals.length ? 'unk' : 'exc'
}

function ruleBadgeText(rule: RuleEvaluation) {
  if (rule.fired) return '命中'
  return rule.unsatisfiedByGap.length || rule.undefinedSignals.length ? '未验证' : '已排除'
}

function observedEntries(ev: EvidenceResult): [string, unknown][] {
  return Object.entries(ev.observed ?? {})
}

async function load(diagnosisId: string) {
  const revision = ++loadRevision
  loading.value = true
  derivation.value = null
  try {
    const { data } = await troubleshootingApi.derivation(diagnosisId)
    if (revision === loadRevision) derivation.value = data
  } finally {
    if (revision === loadRevision) loading.value = false
  }
}

watch(
  () => [props.diagnosis.diagnosisId, props.diagnosis.routeMode] as const,
  ([id, routeMode]) => {
    if (routeMode === 'DETERMINISTIC') {
      void load(id)
    } else {
      loadRevision += 1
      derivation.value = null
      loading.value = false
    }
  },
  { immediate: true },
)
</script>

<style scoped>
.chain {
  --mono: 'SF Mono', 'JetBrains Mono', ui-monospace, Menlo, Consolas, monospace;
  min-height: 40px;
}

.fallback,
.unfaithful {
  margin: 0 0 14px;
  padding: 9px 12px;
  border-radius: 8px;
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
}
.unfaithful {
  color: var(--el-color-warning);
  background: var(--el-color-warning-light-9);
  border-color: var(--el-color-warning-light-7);
}

.step {
  padding: 14px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}
.step:last-child {
  border-bottom: 0;
  padding-bottom: 0;
}
.snum {
  display: flex;
  align-items: baseline;
  gap: 10px;
  font-size: 11px;
  letter-spacing: 0.09em;
  text-transform: uppercase;
  color: var(--el-text-color-placeholder);
  margin-bottom: 10px;
}
.tally {
  margin-left: auto;
  font-family: var(--mono);
  letter-spacing: 0;
  text-transform: none;
}
.empty {
  margin: 0;
  font-size: 12.5px;
  color: var(--el-text-color-placeholder);
}

/* 证据 */
.ev {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  padding: 6px 0;
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex: none;
  margin-top: 7px;
  background: var(--el-text-color-placeholder);
}
.dot.NORMAL { background: var(--el-color-success); }
.dot.ANOMALY { background: var(--el-color-danger); }
.evmain { min-width: 0; }
.evline {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  font-family: var(--mono);
  font-size: 12px;
}
.evk { color: var(--el-text-color-regular); }
.evv { color: var(--el-text-color-primary); font-weight: 600; }
.evnone {
  font-family: var(--mono);
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.evmeta {
  font-family: var(--mono);
  font-size: 11px;
  color: var(--el-text-color-placeholder);
  margin-top: 2px;
}

/* 判据 */
.cr {
  padding: 9px 12px;
  border-radius: 8px;
  border: 1px solid var(--el-border-color-lighter);
  background: var(--el-fill-color-lighter);
  margin-bottom: 8px;
}
.cr.SATISFIED {
  border-color: var(--el-color-danger-light-7);
  background: var(--el-color-danger-light-9);
}
.cr.EXCLUDED {
  border-color: var(--el-color-success-light-7);
  background: var(--el-color-success-light-9);
}
/* A dashed border says "this one never closed" at a glance. */
.cr.UNEVALUATED { border-style: dashed; }
.crtop {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.crst { font-size: 11px; font-weight: 700; }
.cr.SATISFIED .crst { color: var(--el-color-danger); }
.cr.EXCLUDED .crst { color: var(--el-color-success); }
.cr.UNEVALUATED .crst { color: var(--el-text-color-placeholder); }
.crname {
  font-family: var(--mono);
  font-size: 12px;
  color: var(--el-text-color-regular);
}
.crkind {
  margin-left: auto;
  font-family: var(--mono);
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}
.calc {
  margin-top: 5px;
  font-family: var(--mono);
  font-size: 12.5px;
  color: var(--el-text-color-primary);
  overflow-x: auto;
}
.crwhy {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

/* 规则 */
.rule {
  padding: 10px 13px;
  border-radius: 8px;
  border: 1px solid var(--el-border-color-lighter);
  margin-bottom: 8px;
}
.rule.hit {
  border-color: var(--el-color-primary-light-7);
  background: var(--el-color-primary-light-9);
}
.rtop {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.rid {
  font-family: var(--mono);
  font-size: 11.5px;
  color: var(--el-text-color-placeholder);
}
.rname { font-size: 13px; font-weight: 600; color: var(--el-text-color-primary); }
.rtag {
  margin-left: auto;
  font-size: 11px;
  padding: 1.5px 7px;
  border-radius: 5px;
  border: 1px solid var(--el-border-color);
  color: var(--el-text-color-secondary);
}
.rtag.fired { border-color: var(--el-color-primary-light-5); color: var(--el-color-primary); }
.rtag.unk { border-style: dashed; }
.rwhy {
  margin-top: 5px;
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--el-text-color-regular);
}
.rwhy span + span { margin-left: 6px; }
.rwhy code {
  font-family: var(--mono);
  font-size: 11.5px;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  padding: 0.5px 4px;
  margin-right: 3px;
}
</style>
