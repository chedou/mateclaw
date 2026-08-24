import type { ConclusionType, DiagnosisStatus, ProjectionNextStep } from '@/api'
import type { DiagnosisPerspective } from './diagnosisPerspective'

export type ProvenanceTone = 'observed' | 'reported' | 'replay' | 'rehearsal' | 'fixture'

export type ProvenanceChip = {
  key: string
  label: string
  tone: ProvenanceTone
}

/** First-screen chips; never infer from Chinese free text. */
export function diagnosisProvenanceChips(input: {
  evidenceBasis: 'OBSERVED' | 'REPORTED' | 'RECORDED_REPLAY'
  fixtureMode: boolean
  rehearsal: boolean
}): ProvenanceChip[] {
  const chips: ProvenanceChip[] = []
  if (input.evidenceBasis === 'OBSERVED') {
    chips.push({ key: 'basis', label: '只读数据源观测', tone: 'observed' })
  } else if (input.evidenceBasis === 'REPORTED') {
    chips.push({ key: 'basis', label: '告警上报 · 非上游根因证明', tone: 'reported' })
  } else {
    chips.push({ key: 'basis', label: '录制回放 · 非现场真源', tone: 'replay' })
  }
  if (input.fixtureMode && input.evidenceBasis !== 'RECORDED_REPLAY') {
    chips.push({ key: 'fixture', label: 'Fixture 模式', tone: 'fixture' })
  }
  if (input.rehearsal) {
    chips.push({ key: 'rehearsal', label: '演练单 · 不计入正式验收', tone: 'rehearsal' })
  }
  return chips
}

export function diagnosisUnknownImpactCopy(): {
  statement: string
  conservativeAction: string
} {
  return {
    statement: '影响范围尚未确认',
    conservativeAction: '影响未确认时不要自行定因；先补问客户/业务范围，或升级三线带着现有线索继续查。',
  }
}

export function diagnosisSupportHandoffCopy(input: {
  conclusionType: ConclusionType
  impactKnown: boolean
}): string {
  if (!input.impactKnown) {
    return '影响未确认：建议升级三线（或先补问影响范围），二线不要按猜测处置。'
  }
  if (input.conclusionType === 'LOCATED') {
    return '先由三线复核定位，再由授权人员在平台外处置。'
  }
  if (input.conclusionType === 'INSUFFICIENT_EVIDENCE') {
    return '需要升级三线或数据负责人补齐证据，二线不要自行定因。'
  }
  if (input.conclusionType === 'EXCLUDED') {
    return '这是排除不是定位：升级三线继续查，勿把已排除方向当成根因。'
  }
  return '需要升级三线，已有线索会随排障单一起交接，不必从头排查。'
}

const FORBIDDEN_ROOT_CAUSE_WORDS = ['根因已找到', '已定位到根因', '根因定位完成'] as const

/** Guardrails for copy that must not appear on non-LOCATED heroes. */
export function diagnosisConclusionCopyViolations(
  conclusionType: ConclusionType,
  texts: ReadonlyArray<string>,
): string[] {
  if (conclusionType === 'LOCATED') return []
  const blob = texts.join('\n')
  return FORBIDDEN_ROOT_CAUSE_WORDS.filter((word) => blob.includes(word))
}

export type NextStepPrimaryAction = 'confirm' | 'transfer' | 'close' | 'evaluate' | null

export function diagnosisNextStepPanel(input: {
  perspective: DiagnosisPerspective
  status: DiagnosisStatus | null
  conclusionType: ConclusionType
  nextStep: ProjectionNextStep | null
  canOperate: boolean
  canTransfer: boolean
  canClose: boolean
  canEvaluate: boolean
}): {
  title: string
  detail: string
  boundary: string | null
  primaryAction: NextStepPrimaryAction
  blocker: string | null
} {
  const next = input.nextStep
  const boundary = next?.capabilityBoundary?.trim() || null
  const nextTitle = next?.label?.trim() || ''
  const nextDetail = next?.text?.trim() || ''

  if (input.perspective === 'support') {
    const primaryAction: NextStepPrimaryAction = input.canTransfer ? 'transfer' : null
    return {
      title: nextTitle || '先确认告警和影响，再完成升级交接',
      detail: nextDetail
        || '二线不在这里确认根因。请核对服务、时间和影响范围，并把已有线索一起交给三线。',
      boundary,
      primaryAction,
      blocker: primaryAction
        ? null
        : '当前账号没有转派权限：请联系有转派权限的负责人完成升级交接。',
    }
  }

  let primaryAction: NextStepPrimaryAction = null
  let blocker: string | null = null

  switch (input.status) {
    case 'READY_FOR_HUMAN':
      if (input.conclusionType === 'LOCATED' && input.canOperate) {
        primaryAction = 'confirm'
      } else if (input.canTransfer) {
        primaryAction = 'transfer'
      } else if (input.conclusionType === 'LOCATED' && !input.canOperate) {
        blocker = '当前账号没有确认权限：请有 operate 权限的三线复核后确认，或转给有转派权限的负责人。'
      } else {
        blocker = '当前账号没有转派权限：请联系有权限的负责人继续调查。'
      }
      break
    case 'CONFIRMED':
      primaryAction = input.canClose ? 'close' : null
      if (!primaryAction) {
        blocker = '当前账号没有关闭权限：请有权限的负责人登记平台外处置结果。'
      }
      break
    case 'CLOSED':
      primaryAction = input.canEvaluate ? 'evaluate' : null
      if (!primaryAction) {
        blocker = '当前账号没有评估权限：请有管理权限的负责人纳入试点评估。'
      }
      break
    case 'NEEDS_INVESTIGATION':
    case 'TRANSFERRED':
      primaryAction = input.canTransfer ? 'transfer' : null
      break
    default:
      break
  }

  const readyForHumanFallback = input.conclusionType === 'LOCATED'
    ? {
        title: '先判断：你是否认可这个定位？',
        detail: input.canTransfer
          ? '认可就确认；不认可或还需要专业判断，就转给其他人继续查。两种选择都不会修改生产环境。'
          : '认可就确认；不认可或还需要专业判断，就先不要确认，联系有转派权限的负责人继续查。',
      }
    : {
        title: input.conclusionType === 'INSUFFICIENT_EVIDENCE'
          ? '先补证据，再继续调查'
          : '把当前线索转给负责人继续查',
        detail: '当前还没有形成明确原因，只能继续调查或转人工，不能当作已定位。',
      }

  const fallbackByStatus: Record<DiagnosisStatus, { title: string; detail: string }> = {
    READY_FOR_HUMAN: readyForHumanFallback,
    NEEDS_INVESTIGATION: {
      title: '先补证据，不要确认',
      detail: '页面已经说明缺什么。补齐数据源、查询规则或现场信息后，再重新形成可复核结论。',
    },
    CONFIRMED: {
      title: '去平台外完成处置，回来登记结果',
      detail: '修复、放行或回滚由授权人员执行。完成后登记是否恢复、实际原因和对排障方法的反馈。',
    },
    TRANSFERRED: {
      title: '等待接手人继续处理并回填结果',
      detail: '转派信息和已有证据已经保留，接手人不需要从头查；处理完成后仍要登记真实结果。',
    },
    CLOSED: {
      title: '结果已登记，下一步验证这套方法是否真的有效',
      detail: '把这张单纳入试点评估：保存脱敏证据、人工确认的标准答案和原来人工定位所需时间。',
    },
  }

  const fallback = input.status
    ? fallbackByStatus[input.status]
    : { title: '等待排障状态加载', detail: '状态加载完成后，页面会告诉你下一步由谁做什么。' }

  return {
    title: nextTitle || fallback.title,
    detail: nextDetail || fallback.detail,
    boundary,
    primaryAction,
    blocker,
  }
}
