export type DiagnosisPerspective = 'developer' | 'support'
type DiagnosisConclusionType = 'LOCATED' | 'HYPOTHESIS' | 'EXCLUDED' | 'INSUFFICIENT_EVIDENCE'
type DiagnosisLifecycleStatus = 'READY_FOR_HUMAN' | 'NEEDS_INVESTIGATION' | 'CONFIRMED' | 'TRANSFERRED' | 'CLOSED'
type DiagnosisEvidenceBasis = 'OBSERVED' | 'REPORTED' | 'RECORDED_REPLAY'

type DiagnosisIncidentBriefInput = {
  system: string
  service: string
  title: string
  severity: string
  errorCode: string | null
  occurredAt: string
}

export type DiagnosisProblemBrief = {
  title: string
  scope: string
  occurredAt: string
  alertSignal: string
}

/**
 * The detail page is developer-first. The query value changes presentation
 * only; it never grants a role or changes the underlying Diagnosis facts.
 */
export function normalizeDiagnosisPerspective(value: unknown): DiagnosisPerspective {
  return value === 'support' ? 'support' : 'developer'
}

export function diagnosisPerspectiveLabel(perspective: DiagnosisPerspective): string {
  return perspective === 'support' ? '二线保障视角' : '三线开发视角'
}

/**
 * The first screen starts from the incident itself. Missing fields stay visible
 * as missing instead of being inferred from a conclusion or evidence text.
 */
export function diagnosisProblemBrief(
  incident: DiagnosisIncidentBriefInput,
  fallbackProblem: string,
): DiagnosisProblemBrief {
  const title = incident.title.trim() || fallbackProblem.trim() || '故障现象未记录'
  const system = incident.system.trim() || '系统未记录'
  const service = incident.service.trim() || '服务未记录'
  const occurredAt = incident.occurredAt
    ? incident.occurredAt.replace('T', ' ').replace(/\.\d+Z?$/, '').slice(0, 19)
    : '未记录'
  const signals = [
    incident.severity.trim() || null,
    incident.errorCode?.trim() ? `错误码 ${incident.errorCode.trim()}` : '无明确错误码',
  ].filter((value): value is string => Boolean(value))

  return {
    title,
    scope: `${system} / ${service}`,
    occurredAt,
    alertSignal: signals.join(' · '),
  }
}

type DiagnosisPerspectiveHeroInput = {
  perspective: DiagnosisPerspective
  conclusionType: DiagnosisConclusionType
  rootCause: string | null
  headline: string
  narrative: string
  candidateCount: number
}

export function diagnosisDecisionStatusLabel(
  status: DiagnosisLifecycleStatus,
  conclusionType: DiagnosisConclusionType,
  fallbackLabel: string,
): string {
  if (status === 'CONFIRMED' && conclusionType !== 'LOCATED') return '候选方向已人工确认'
  if (status === 'CONFIRMED') return '根因已人工确认'
  return fallbackLabel
}

export function diagnosisRootCauseAnswer(input: {
  conclusionType: DiagnosisConclusionType
  rootCause: string | null
  headline: string
}): string {
  if (input.conclusionType === 'LOCATED') {
    return input.rootCause?.trim() || input.headline.trim() || '根因已定位，但结论文案未记录'
  }
  if (input.conclusionType === 'INSUFFICIENT_EVIDENCE') return '证据不足，尚无法判断'
  return '尚未查明'
}

export function diagnosisConfirmedCandidateGuidance(input: {
  status: DiagnosisLifecycleStatus
  conclusionType: DiagnosisConclusionType
  nextStep: string
}): { title: string; detail: string } | null {
  if (input.status !== 'CONFIRMED' || input.conclusionType === 'LOCATED') return null
  return {
    title: '继续核实未确认的根因，再登记真实结果',
    detail: `当前只是人工接受了候选方向，不等于根因定位完成。${input.nextStep || '请继续补齐能够解释上游失败原因的证据。'}`,
  }
}

export function diagnosisPerspectiveHero(input: DiagnosisPerspectiveHeroInput) {
  if (input.perspective === 'support') {
    const title = {
      LOCATED: '已找到可复核的故障原因',
      HYPOTHESIS: '已找到线索，需要三线继续确认',
      EXCLUDED: '已排除部分方向，仍需三线继续调查',
      INSUFFICIENT_EVIDENCE: '暂时无法判断，先补齐证据',
    }[input.conclusionType]
    return { title, summary: input.narrative }
  }
  if (input.conclusionType === 'LOCATED') {
    return { title: input.rootCause || input.headline, summary: input.narrative }
  }
  if (input.conclusionType === 'HYPOTHESIS') {
    const title = input.candidateCount > 1
      ? `发现 ${input.candidateCount} 类故障线索，尚未定位唯一根因`
      : '已确认故障发生点，尚未找到根因'
    return { title, summary: input.narrative }
  }
  if (input.conclusionType === 'EXCLUDED') {
    return { title: '已排除部分方向，尚未找到根因', summary: input.narrative }
  }
  return { title: '证据不足，暂时无法判断根因', summary: input.narrative }
}

export function diagnosisDeveloperExplanation(input: {
  conclusionType: DiagnosisConclusionType
  evidenceBasis: DiagnosisEvidenceBasis
  keyEvidence: string | null
  rootCause: string | null
  candidateCount: number
}): {
  state: string
  known: string
  unknown: string
  reason: string
} {
  const state = {
    LOCATED: '已找到可复核根因',
    HYPOTHESIS: '尚未找到根因',
    EXCLUDED: '尚未找到根因',
    INSUFFICIENT_EVIDENCE: '暂时无法判断',
  }[input.conclusionType]

  const known = (input.conclusionType === 'LOCATED' ? input.rootCause : input.keyEvidence)
    || input.keyEvidence
    || input.rootCause
    || (input.conclusionType === 'INSUFFICIENT_EVIDENCE'
      ? '当前没有取得能够支持结论的有效证据。'
      : '系统已经保留本次排障中能够复核的事实。')

  const unknown = (() => {
    if (input.conclusionType === 'LOCATED') return '实际处置结果以及故障是否已经恢复，仍需人工确认。'
    if (input.candidateCount > 1) return '还不能确认哪一类线索是主因，也不能确认最终责任组件。'
    if (input.conclusionType === 'EXCLUDED') return '已排除的方向之外，真正导致故障的原因仍未确认。'
    if (input.conclusionType === 'INSUFFICIENT_EVIDENCE') return '缺少能够支持或排除候选方向的证据。'
    return '当前证据只能说明故障在哪里发生，还不能解释为什么发生。'
  })()

  const reasonByBasis: Record<DiagnosisEvidenceBasis, string> = {
    OBSERVED: '系统读取了本次只读证据，只保留被数据支持的部分；没有证据的部分不会补猜。',
    REPORTED: '这条判断来自告警原文。告警能说明直接失败点，但不能证明更上游的原因。',
    RECORDED_REPLAY: '系统按同一规则重放了录制样本。结果可以复核，但不等同于本次故障现场的实时取证。',
  }

  return { state, known, unknown, reason: reasonByBasis[input.evidenceBasis] }
}

export function diagnosisSupportAction(
  conclusionType: DiagnosisPerspectiveHeroInput['conclusionType'],
): string {
  return {
    LOCATED: '保留本次证据并交给三线复核定位；处置由授权人员在平台外执行。',
    HYPOTHESIS: '把告警、影响范围和现有线索一起升级三线；不要根据候选方向直接处置。',
    EXCLUDED: '保留已排除的方向并升级三线继续调查，避免接手人重复排查。',
    INSUFFICIENT_EVIDENCE: '确认告警时间、服务和影响范围后升级三线，并请数据负责人补齐缺失证据。',
  }[conclusionType]
}
