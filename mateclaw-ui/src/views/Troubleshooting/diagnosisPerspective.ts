export type DiagnosisPerspective = 'developer' | 'support'

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

type DiagnosisPerspectiveHeroInput = {
  perspective: DiagnosisPerspective
  conclusionType: 'LOCATED' | 'HYPOTHESIS' | 'EXCLUDED' | 'INSUFFICIENT_EVIDENCE'
  rootCause: string | null
  headline: string
  narrative: string
  candidateCount: number
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
      ? `尚未定位唯一根因，已找到 ${input.candidateCount} 类候选线索`
      : input.rootCause || '尚未定位唯一根因，当前已有候选方向'
    return { title, summary: input.narrative }
  }
  if (input.conclusionType === 'EXCLUDED') {
    return { title: '已排除部分方向，尚未找到根因', summary: input.narrative }
  }
  return { title: '证据不足，暂时无法判断根因', summary: input.narrative }
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
