<template>
  <div class="chain">
    <!-- the shape of the reasoning, before the detail -->
    <div class="tally">
      <div class="tg">
        <span class="tn">{{ diagnosis.evidence.length }}</span><span class="tl">项证据</span>
      </div>
      <div class="tg">
        <span class="tn">{{ counts.total }}</span><span class="tl">条判据</span>
        <span class="tsplit">
          <span v-if="counts.satisfied" class="tchip sat">{{ counts.satisfied }} 成立</span>
          <span v-if="counts.excluded" class="tchip exc">{{ counts.excluded }} 已排除</span>
          <span v-if="counts.unevaluated" class="tchip unk">{{ counts.unevaluated }} 无法求值</span>
        </span>
      </div>
      <div class="tg">
        <span class="tn" :class="diagnosis.abstained ? 'abst' : 'win'">
          {{ firedRule ? firedRule.ruleId : (diagnosis.abstained ? '弃权' : '—') }}
        </span>
        <span class="tl">{{ diagnosis.abstained ? '未产出根因' : '采纳结论' }}</span>
      </div>
    </div>

    <el-alert v-if="derivation && !derivation.faithful" type="warning" :closable="false" class="drift">
      <template #title>推导无法忠实还原</template>
      {{ derivation.note }}
    </el-alert>

    <!-- 1. evidence -->
    <section class="link">
      <div class="lrail"><span class="lnode">1</span><span class="lconn" /></div>
      <div class="lbody">
        <header class="lhd">
          <span class="lt">证据采集</span>
          <span class="lf">EvidenceResult · 高亮字段被成立判据读取</span>
        </header>

        <article
          v-for="ev in diagnosis.evidence"
          :key="ev.queryId"
          :id="`ev-${ev.queryId}`"
          class="ev"
          :class="[ev.status.toLowerCase(), { dim: !feedsASatisfiedCriterion(ev.queryId) }]"
        >
          <div class="evh">
            <span class="qid">{{ ev.queryId }}</span>
            <span class="ns">{{ ev.namespace }}::</span>
            <span class="evsum">{{ ev.summary }}</span>
            <span class="evst" :class="ev.status">{{ ev.status }}</span>
          </div>

          <div class="obsline">
            <template v-if="observedEntries(ev).length">
              <span
                v-for="[field, value] in observedEntries(ev)"
                :key="field"
                class="ob"
                :class="{ used: readByFiringCriterion(ev.queryId, field) }"
              >{{ field }} <b>{{ value }}</b></span>
            </template>
            <span v-else class="ob empty">observed 为空 · 取证失败</span>
          </div>

          <button class="qtoggle" type="button" @click="toggle(ev.queryId)">
            <span class="caret" :class="{ open: expanded.has(ev.queryId) }">›</span>
            查看查询与采集来源
          </button>
          <div v-if="expanded.has(ev.queryId)" class="qbox">
            <pre class="q">{{ ev.query || '（SOP 未记录查询语句）' }}</pre>
            <div class="qmeta">
              <span>source {{ ev.source }}</span>
              <span>collectedAt {{ ev.collectedAt }}</span>
            </div>
          </div>
        </article>
      </div>
    </section>

    <!-- 2. criteria -->
    <section class="link">
      <div class="lrail"><span class="lnode">2</span><span class="lconn" /></div>
      <div class="lbody">
        <header class="lhd">
          <span class="lt">判据求值 → 信号</span>
          <span class="lf">纯 Java pattern-matching · 零 LLM · 代入运算由服务端渲染</span>
        </header>

        <div v-loading="loading">
          <article
            v-for="c in orderedCriteria"
            :key="c.signal"
            :id="`cr-${c.signal}`"
            class="cr"
            :class="c.outcome.toLowerCase()"
          >
            <div class="crtop">
              <span class="crm">{{ OUTCOME_GLYPH[c.outcome] }}</span>
              <span class="crsig">{{ c.signal }}</span>
              <span class="crstate" :class="c.outcome.toLowerCase()">{{ OUTCOME_LABEL[c.outcome] }}</span>
              <span class="crsrc">
                {{ c.kind }} · 源
                <a @click="jump(`ev-${c.sourceRequestId}`, c.sourceRequestId)">{{ c.sourceRequestId }}</a>
              </span>
            </div>
            <div class="calc">
              <div class="cline"><span class="cl">判据</span><span class="cval">{{ c.expression }}</span></div>
              <div class="cline subst" :class="c.outcome.toLowerCase()">
                <span class="cl">代入</span><span class="cval">{{ c.substitution }}</span>
              </div>
            </div>
            <p v-if="c.description" class="crd">{{ c.description }}</p>
            <p v-if="c.outcome === 'EXCLUDED'" class="crnote exc">
              判据<b>已求值为假</b>：依赖它的候选结论是被证据排除的，可以不再怀疑。
            </p>
            <p v-if="c.outcome === 'UNEVALUATED'" class="crnote unk">
              <b>未验证 ≠ 已排除。</b>源证据缺失，判据没有执行；依赖该信号的结论既没被证实、也没被排除，
              补齐 <code>{{ c.sourceRequestId }}</code> 后可能改变。
            </p>
          </article>

          <p v-if="derivation && !derivation.criteria.length" class="empty-note">
            该 SOP 未定义任何判据。
          </p>
        </div>
      </div>
    </section>

    <!-- 3. rules -->
    <section class="link">
      <div class="lrail"><span class="lnode">3</span><span class="lconn" /></div>
      <div class="lbody">
        <header class="lhd">
          <span class="lt">规则匹配 · 含反事实</span>
          <span class="lf">首条 requiredSignals 全部成立的规则胜出 · 点信号可定位判据</span>
        </header>

        <article
          v-for="r in derivation?.rules ?? []"
          :key="r.ruleId"
          class="rule"
          :class="{ fired: r.fired }"
        >
          <div class="rh">
            <span class="rid">{{ r.ruleId }}</span>
            <span class="rbadge" :class="ruleBadgeClass(r)">{{ ruleBadgeText(r) }}</span>
            <span class="rconf">confidence {{ r.confidence }}</span>
          </div>
          <div class="rreq">
            requiredSignals
            <span
              v-for="s in r.requiredSignals"
              :key="s"
              class="rsig"
              :class="signalClass(s)"
              @click="jump(`cr-${s}`, s)"
            >{{ signalGlyph(s) }} {{ s }}</span>
          </div>
          <p class="rout"><b>{{ r.rootCause }}</b></p>

          <p v-if="!r.fired && r.unsatisfiedByExclusion.length" class="why exc">
            <span class="tag">已排除</span>
            缺少 <b>{{ r.unsatisfiedByExclusion.join(' / ') }}</b> —— 判据已求值为假，此结论确已被证据排除。
          </p>
          <p v-if="!r.fired && r.unsatisfiedByGap.length" class="why unk">
            <span class="tag">未验证</span>
            缺少 <b>{{ r.unsatisfiedByGap.join(' / ') }}</b> —— 源证据取证失败，判据无法求值。
            此结论<b>没有被排除</b>，补齐证据后可能成立。
          </p>
          <p v-if="!r.fired && r.undefinedSignals.length" class="why unk">
            <span class="tag">无采集</span>
            信号 <b>{{ r.undefinedSignals.join(' / ') }}</b> 在本 SOP 中没有对应判据，因此永远不会成立
            —— 知识缺口，建议补 SOP。
          </p>
        </article>

        <p v-if="derivation && !derivation.rules.length" class="empty-note">该 SOP 未定义结论规则。</p>
      </div>
    </section>

    <!-- 4. conclusion -->
    <section class="link">
      <div class="lrail">
        <span class="lnode final" :class="{ abst: diagnosis.abstained }">
          {{ diagnosis.abstained ? '!' : '✓' }}
        </span>
      </div>
      <div class="lbody">
        <header class="lhd">
          <span class="lt">{{ diagnosis.abstained ? '弃权结论' : '根因结论' }}</span>
          <span class="lf">rootCause + confidence</span>
        </header>
        <div class="concl" :class="{ abst: diagnosis.abstained }">
          <p class="rc">{{ diagnosis.rootCause }}</p>
          <p class="sum">{{ diagnosis.summary }}</p>
          <p v-if="diagnosis.abstained" class="abst-why">
            系统未强凑结论。恢复动作也随之为空——契约保证弃权时不输出任何处置建议。
          </p>
        </div>
      </div>
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
const OUTCOME_GLYPH: Record<CriterionOutcome, string> = {
  SATISFIED: '✓',
  EXCLUDED: '✗',
  UNEVALUATED: '?',
}
/** Satisfied first, then the untested ones — those are what the operator can act on. */
const OUTCOME_ORDER: Record<CriterionOutcome, number> = {
  SATISFIED: 0,
  UNEVALUATED: 1,
  EXCLUDED: 2,
}

const derivation = ref<DiagnosisDerivation | null>(null)
const loading = ref(false)
const expanded = ref(new Set<string>())

const orderedCriteria = computed(() =>
  [...(derivation.value?.criteria ?? [])].sort(
    (a, b) => OUTCOME_ORDER[a.outcome] - OUTCOME_ORDER[b.outcome],
  ),
)

const counts = computed(() => {
  const criteria = derivation.value?.criteria ?? []
  return {
    total: criteria.length,
    satisfied: criteria.filter((c) => c.outcome === 'SATISFIED').length,
    excluded: criteria.filter((c) => c.outcome === 'EXCLUDED').length,
    unevaluated: criteria.filter((c) => c.outcome === 'UNEVALUATED').length,
  }
})

const firedRule = computed(() => derivation.value?.rules.find((r) => r.fired) ?? null)

const outcomeBySignal = computed(() => {
  const map = new Map<string, CriterionOutcome>()
  for (const c of derivation.value?.criteria ?? []) map.set(c.signal, c.outcome)
  return map
})

function signalClass(signal: string) {
  const outcome = outcomeBySignal.value.get(signal)
  if (outcome === 'SATISFIED') return 'ok'
  if (outcome === 'UNEVALUATED') return 'unk'
  if (outcome === 'EXCLUDED') return 'no'
  return 'undef'
}

function signalGlyph(signal: string) {
  const outcome = outcomeBySignal.value.get(signal)
  return outcome ? OUTCOME_GLYPH[outcome] : '∅'
}

function ruleBadgeClass(rule: RuleEvaluation) {
  if (rule.fired) return 'fired'
  return rule.unsatisfiedByGap.length || rule.undefinedSignals.length ? 'unk' : 'exc'
}

function ruleBadgeText(rule: RuleEvaluation) {
  if (rule.fired) return '命中 · 采纳此结论'
  return rule.unsatisfiedByGap.length || rule.undefinedSignals.length
    ? '未命中 · 未验证'
    : '未命中 · 已排除'
}

function observedEntries(ev: EvidenceResult): [string, unknown][] {
  return Object.entries(ev.observed ?? {})
}

/** Which evidence rows carried the conclusion, per the server's own evaluation. */
function feedsASatisfiedCriterion(queryId: string): boolean {
  return (derivation.value?.criteria ?? []).some(
    (c) => c.sourceRequestId === queryId && c.outcome === 'SATISFIED',
  )
}

/**
 * Highlights a field when a satisfied criterion on this row mentions it. The
 * server sends the rendered expression rather than the parsed field list, so
 * this matches on the expression text — good enough to draw attention, and it
 * cannot disagree with the verdict, which the server owns.
 */
function readByFiringCriterion(queryId: string, field: string): boolean {
  return (derivation.value?.criteria ?? []).some(
    (c) =>
      c.sourceRequestId === queryId &&
      c.outcome === 'SATISFIED' &&
      c.expression.includes(field),
  )
}

function toggle(queryId: string) {
  const next = new Set(expanded.value)
  next.has(queryId) ? next.delete(queryId) : next.add(queryId)
  expanded.value = next
}

function jump(elementId: string, evidenceId?: string) {
  if (evidenceId && elementId.startsWith('ev-')) {
    const next = new Set(expanded.value)
    next.add(evidenceId)
    expanded.value = next
  }
  requestAnimationFrame(() => {
    const el = document.getElementById(elementId)
    if (!el) return
    el.scrollIntoView({ block: 'center', behavior: 'smooth' })
    el.classList.add('flash')
    setTimeout(() => el.classList.remove('flash'), 1300)
  })
}

async function load(diagnosisId: string) {
  loading.value = true
  derivation.value = null
  try {
    const { data } = await troubleshootingApi.derivation(diagnosisId)
    derivation.value = data
  } finally {
    loading.value = false
  }
}

watch(() => props.diagnosis.diagnosisId, (id) => id && load(id), { immediate: true })
</script>

<style scoped>
.chain { display: flex; flex-direction: column; }

.tally {
  display: flex; gap: 0; flex-wrap: wrap; align-items: center;
  background: var(--el-bg-color); border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px; padding: 10px 15px; margin-bottom: 12px;
}
.tg { display: flex; align-items: baseline; gap: 7px; padding-right: 18px; margin-right: 18px;
  border-right: 1px solid var(--el-border-color-lighter); }
.tg:last-child { border-right: none; margin-right: 0; padding-right: 0; }
.tn { font-family: var(--mc-mono, monospace); font-size: 17px; font-weight: 700;
  color: var(--el-text-color-primary); font-variant-numeric: tabular-nums; }
.tn.win { color: var(--el-color-success); font-size: 13px; }
.tn.abst { color: var(--el-color-warning); font-size: 13px; }
.tl { font-size: 11px; color: var(--el-text-color-secondary); }
.tsplit { display: flex; gap: 6px; align-items: center; }
.tchip { font-family: var(--mc-mono, monospace); font-size: 10.5px; font-weight: 700;
  padding: 2px 8px; border-radius: 5px; }
.tchip.sat { background: var(--el-color-success-light-9); color: var(--el-color-success); }
.tchip.exc { background: var(--el-fill-color); color: var(--el-text-color-secondary); }
.tchip.unk { background: var(--el-color-warning-light-9); color: var(--el-color-warning); }

.drift { margin-bottom: 12px; }

.link { display: flex; gap: 13px; padding-bottom: 11px; }
.link:last-child { padding-bottom: 0; }
.lrail { display: flex; flex-direction: column; align-items: center; flex-shrink: 0; width: 28px; }
.lnode {
  width: 28px; height: 28px; border-radius: 9px; display: flex; align-items: center;
  justify-content: center; font-family: var(--mc-mono, monospace); font-size: 11px;
  font-weight: 700; background: var(--el-color-primary-light-9); color: var(--el-color-primary);
}
.lnode.final { background: var(--el-color-success); color: #fff; }
.lnode.final.abst { background: var(--el-color-warning); }
.lconn { flex: 1; width: 2px; background: var(--el-border-color-lighter); margin: 3px 0; border-radius: 2px; }
.lbody { flex: 1; min-width: 0; }
.lhd { display: flex; align-items: baseline; gap: 8px; margin-bottom: 7px; flex-wrap: wrap; }
.lt { font-size: 12.5px; font-weight: 650; color: var(--el-text-color-primary); }
.lf { font-family: var(--mc-mono, monospace); font-size: 10px; color: var(--el-text-color-placeholder); }

/* evidence */
.ev { border: 1px solid var(--el-border-color-lighter); border-radius: 8px;
  background: var(--el-bg-color); overflow: hidden; scroll-margin-top: 20px;
  transition: box-shadow 0.18s, border-color 0.18s; }
.ev + .ev { margin-top: 7px; }
.ev.anomaly { border-color: var(--el-color-danger-light-5); }
.ev.missing { border-color: var(--el-color-warning-light-5); }
.ev.dim { opacity: 0.66; }
.evh { padding: 8px 11px; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.qid { font-family: var(--mc-mono, monospace); font-size: 10px; font-weight: 700;
  color: var(--el-color-primary); background: var(--el-color-primary-light-9);
  border-radius: 4px; padding: 2px 6px; }
.ns { font-family: var(--mc-mono, monospace); font-size: 10px; color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light); border-radius: 4px; padding: 2px 6px; }
.evsum { font-size: 12px; font-weight: 600; color: var(--el-text-color-primary); }
.evst { font-size: 9.5px; font-weight: 700; font-family: var(--mc-mono, monospace);
  padding: 2px 7px; border-radius: 4px; margin-left: auto; }
.evst.ANOMALY { background: var(--el-color-danger-light-9); color: var(--el-color-danger); }
.evst.NORMAL { background: var(--el-color-success-light-9); color: var(--el-color-success); }
.evst.MISSING { background: var(--el-color-warning-light-9); color: var(--el-color-warning); }
.obsline { padding: 0 11px 9px; display: flex; gap: 6px; flex-wrap: wrap; }
.ob { font-family: var(--mc-mono, monospace); font-size: 10.5px;
  border: 1px solid var(--el-border-color-lighter); border-radius: 5px; padding: 2px 7px;
  background: var(--el-fill-color-lighter); color: var(--el-text-color-regular); }
.ob b { color: var(--el-text-color-primary); }
.ob.used { border-color: var(--el-color-primary-light-5); background: var(--el-color-primary-light-9);
  color: var(--el-color-primary); }
.ob.used b { color: var(--el-color-primary); }
.ob.empty { border-style: dashed; color: var(--el-color-warning); border-color: var(--el-color-warning-light-5); }
.qtoggle { border: none; background: transparent; color: var(--el-text-color-secondary);
  font-family: var(--mc-mono, monospace); font-size: 10px; cursor: pointer;
  padding: 5px 11px; display: flex; align-items: center; gap: 6px; width: 100%;
  border-top: 1px solid var(--el-border-color-lighter); }
.qtoggle:hover { color: var(--el-color-primary); background: var(--el-fill-color-lighter); }
.caret { display: inline-block; transition: transform 0.18s; }
.caret.open { transform: rotate(90deg); }
.qbox { border-top: 1px solid var(--el-border-color-lighter); }
.q { margin: 0; background: #0e1420; color: #c9d6ef; font-family: var(--mc-mono, monospace);
  font-size: 10.5px; line-height: 1.7; padding: 9px 11px; overflow-x: auto; white-space: pre; }
.qmeta { padding: 6px 11px; background: var(--el-fill-color-lighter);
  font-family: var(--mc-mono, monospace); font-size: 10px;
  color: var(--el-text-color-secondary); display: flex; gap: 12px; flex-wrap: wrap; }

/* criteria */
.cr { border: 1px solid var(--el-border-color-lighter); border-radius: 8px;
  background: var(--el-bg-color); padding: 10px 12px; scroll-margin-top: 20px;
  transition: box-shadow 0.18s, border-color 0.18s; }
.cr + .cr { margin-top: 7px; }
.cr.satisfied { border-color: var(--el-color-success-light-7); }
.cr.excluded { opacity: 0.84; }
.cr.unevaluated { border-color: var(--el-color-warning-light-5);
  background: var(--el-color-warning-light-9); }
.crtop { display: flex; align-items: center; gap: 9px; flex-wrap: wrap; }
.crm { width: 20px; height: 20px; border-radius: 6px; display: flex; align-items: center;
  justify-content: center; font-family: var(--mc-mono, monospace); font-size: 12px;
  font-weight: 700; flex-shrink: 0; background: var(--el-fill-color);
  color: var(--el-text-color-secondary); }
.cr.satisfied .crm { background: var(--el-color-success); color: #fff; }
.cr.unevaluated .crm { background: var(--el-color-warning); color: #fff; }
.crsig { font-family: var(--mc-mono, monospace); font-size: 12px; font-weight: 700;
  color: var(--el-text-color-primary); }
.cr.satisfied .crsig { color: var(--el-color-success); }
.cr.unevaluated .crsig { color: var(--el-color-warning); }
.crstate { font-size: 9.5px; font-weight: 700; font-family: var(--mc-mono, monospace);
  padding: 2px 7px; border-radius: 4px; background: var(--el-fill-color);
  color: var(--el-text-color-secondary); }
.crstate.satisfied { background: var(--el-color-success-light-9); color: var(--el-color-success); }
.crstate.unevaluated { background: var(--el-color-warning); color: #fff; }
.crsrc { font-family: var(--mc-mono, monospace); font-size: 10px;
  color: var(--el-text-color-placeholder); margin-left: auto; }
.crsrc a { color: var(--el-color-primary); cursor: pointer; }
.crsrc a:hover { text-decoration: underline; }
.calc { margin-top: 9px; border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px; overflow: hidden; }
.cline { display: grid; grid-template-columns: 52px 1fr; font-family: var(--mc-mono, monospace);
  font-size: 11px; align-items: baseline; }
.cline + .cline { border-top: 1px solid var(--el-border-color-lighter); }
.cl { background: var(--el-fill-color-lighter); color: var(--el-text-color-placeholder);
  padding: 5px 8px; font-size: 9.5px; text-align: right; }
.cval { padding: 5px 10px; color: var(--el-text-color-regular); overflow-x: auto;
  white-space: pre-wrap; word-break: break-word; }
.cline.subst .cval { font-weight: 600; color: var(--el-text-color-primary); }
.cline.subst.satisfied .cval { color: var(--el-color-success); }
.cline.subst.unevaluated .cval { color: var(--el-color-warning); }
.crd { margin: 8px 0 0; font-size: 11.5px; color: var(--el-text-color-secondary); line-height: 1.5; }
.crnote { margin: 8px 0 0; font-size: 11.5px; line-height: 1.55; border-radius: 6px; padding: 7px 10px; }
.crnote.exc { background: var(--el-fill-color-lighter); color: var(--el-text-color-secondary); }
.crnote.unk { background: var(--el-bg-color); border: 1px solid var(--el-color-warning-light-5);
  color: var(--el-color-warning); }
.crnote code { font-family: var(--mc-mono, monospace); font-size: 10px;
  background: var(--el-fill-color); border-radius: 3px; padding: 1px 4px; }

/* rules */
.rule { border: 1px solid var(--el-border-color-lighter); border-radius: 8px;
  background: var(--el-bg-color); padding: 11px 13px; }
.rule + .rule { margin-top: 7px; }
.rule.fired { border-color: var(--el-color-success-light-7); background: var(--el-color-success-light-9); }
.rh { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.rid { font-family: var(--mc-mono, monospace); font-size: 10.5px; font-weight: 700;
  color: var(--el-text-color-regular); }
.rconf { font-family: var(--mc-mono, monospace); font-size: 10.5px;
  color: var(--el-text-color-placeholder); margin-left: auto; }
.rbadge { font-size: 9.5px; font-weight: 700; font-family: var(--mc-mono, monospace);
  padding: 2px 7px; border-radius: 4px; }
.rbadge.fired { background: var(--el-color-success); color: #fff; }
.rbadge.exc { background: var(--el-fill-color); color: var(--el-text-color-secondary); }
.rbadge.unk { background: var(--el-color-warning-light-9); color: var(--el-color-warning); }
.rreq { margin-top: 8px; display: flex; gap: 5px; flex-wrap: wrap; align-items: center;
  font-family: var(--mc-mono, monospace); font-size: 10px; color: var(--el-text-color-placeholder); }
.rsig { font-family: var(--mc-mono, monospace); font-size: 10.5px; font-weight: 600;
  padding: 2px 8px; border-radius: 5px; cursor: pointer; border: 1px solid transparent; }
.rsig:hover { border-color: var(--el-color-primary); }
.rsig.ok { background: var(--el-color-success-light-9); color: var(--el-color-success); }
.rsig.no { background: var(--el-fill-color); color: var(--el-text-color-secondary); }
.rsig.unk, .rsig.undef { background: var(--el-color-warning-light-9); color: var(--el-color-warning); }
.rout { margin: 9px 0 0; font-size: 12px; color: var(--el-text-color-regular); line-height: 1.5; }
.rout b { color: var(--el-text-color-primary); }
.why { margin: 9px 0 0; border-radius: 6px; padding: 8px 11px; font-size: 11.5px; line-height: 1.55; }
.why.exc { background: var(--el-fill-color-lighter); border: 1px solid var(--el-border-color-lighter);
  color: var(--el-text-color-secondary); }
.why.unk { background: var(--el-color-warning-light-9); border: 1px solid var(--el-color-warning-light-7);
  color: var(--el-color-warning); }
.why b { color: var(--el-text-color-primary); }
.why.unk b { color: var(--el-color-warning); }
.tag { font-family: var(--mc-mono, monospace); font-size: 9.5px; font-weight: 700;
  padding: 1px 6px; border-radius: 4px; background: var(--el-bg-color);
  border: 1px solid currentColor; margin-right: 6px; white-space: nowrap; }

.empty-note { font-size: 11.5px; color: var(--el-text-color-secondary); line-height: 1.6;
  background: var(--el-fill-color-lighter); border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px; padding: 9px 11px; margin: 0; }

.concl { border: 1px solid var(--el-color-success-light-7); background: var(--el-color-success-light-9);
  border-radius: 8px; padding: 11px 13px; }
.concl.abst { border-color: var(--el-color-warning-light-7); background: var(--el-color-warning-light-9); }
.rc { margin: 0; font-size: 13px; font-weight: 650; color: var(--el-text-color-primary); line-height: 1.5; }
.sum { margin: 5px 0 0; font-size: 12px; color: var(--el-text-color-secondary); line-height: 1.55; }
.abst-why { margin: 8px 0 0; font-size: 11.5px; color: var(--el-color-warning); line-height: 1.55; }

:deep(.flash) { box-shadow: 0 0 0 3px var(--el-color-primary-light-8); border-color: var(--el-color-primary); }
</style>
