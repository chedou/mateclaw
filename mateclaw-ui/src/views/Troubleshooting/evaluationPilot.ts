import type {
  BaselineEvaluationRun,
  DiagnosisSummary,
  EvidenceEvaluationSample,
  TroubleshootingPilotPlan,
} from '@/api'
import { ROLE_LEVEL, type WorkspaceRole } from '@/composables/capabilities'

export type EvaluationPilotStage =
  | 'NEEDS_CLOSURE'
  | 'NEEDS_REAL_SAMPLE'
  | 'NEEDS_REFERENCE'
  | 'NEEDS_BASELINE'
  | 'BASELINE_BLOCKED'
  | 'ACCURACY_ONLY'
  | 'READY_FOR_REVIEW'

export interface EvaluationPilotQueueRow {
  diagnosisId: string
  system: string
  service: string
  errorCode: string | null
  updatedAt: string
  stage: EvaluationPilotStage
  stageLabel: string
  ownerLabel: string
  nextAction: string
  sampleId: string | null
}

export type PilotWorkbenchPromptKind =
  | 'SETUP'
  | 'CREATE_FORMAL'
  | 'CONTINUE_DIAGNOSIS'
  | 'HANDOFF_EVALUATION'

export interface PilotWorkbenchPrompt {
  kind: PilotWorkbenchPromptKind
  step: 1 | 2 | 3
  title: string
  detail: string
  ownerLabel: string
  actionLabel: string
  diagnosisId: string | null
  scope: TroubleshootingPilotPlan['modules'][number] | null
}

export type PilotResponsibility = 'SECOND_LINE' | 'THIRD_LINE' | 'SOURCE_OWNER'

export interface PilotTeamReadiness {
  memberCount: number
  operatorCount: number
  adminCount: number
  missingOperatorCount: number
  missingAdminCount: number
  ready: boolean
}

export interface PilotTeamRepairPlan {
  addAdminCount: number
  addMemberCount: number
  promoteAdminCount: number
}

export interface PilotWorkspaceMemberAccess {
  role?: string | null
  active?: boolean | null
}

export interface PilotScopeSuggestion {
  system: string
  service: string
  formalCount: number
  latestAt: string
}

const PILOT_REQUIRED_OPERATOR_COUNT = 3
const PILOT_REQUIRED_ADMIN_COUNT = 2
const STABLE_PILOT_IDENTIFIER = /^[a-z0-9](?:[a-z0-9._-]{0,126}[a-z0-9])?$/

export function pilotScopeKey(scope: Pick<DiagnosisSummary, 'system' | 'service'>) {
  return `${normalizeScopePart(scope.system)}\u0000${normalizeScopePart(scope.service)}`
}

export function pilotScopeIsSaveable(scope: Pick<DiagnosisSummary, 'system' | 'service'>) {
  return STABLE_PILOT_IDENTIFIER.test(normalizeScopePart(scope.system))
    && STABLE_PILOT_IDENTIFIER.test(normalizeScopePart(scope.service))
}

/**
 * Mirrors only the minimum Workspace roles required by the three pilot duties.
 * The backend RoleCapabilities and endpoint guards remain authoritative.
 */
export function pilotMemberCanOwnResponsibility(
  responsibility: PilotResponsibility,
  member: PilotWorkspaceMemberAccess,
) {
  if (member.active !== true) return false
  const minimumRole: WorkspaceRole = responsibility === 'SECOND_LINE' ? 'member' : 'admin'
  return workspaceRoleLevel(member.role) >= ROLE_LEVEL[minimumRole]
}

export function buildPilotTeamReadiness(
  members: ReadonlyArray<PilotWorkspaceMemberAccess>,
): PilotTeamReadiness {
  const operatorCount = members.filter(member =>
    pilotMemberCanOwnResponsibility('SECOND_LINE', member)).length
  const adminCount = members.filter(member =>
    pilotMemberCanOwnResponsibility('THIRD_LINE', member)).length
  const missingOperatorCount = Math.max(0, PILOT_REQUIRED_OPERATOR_COUNT - operatorCount)
  const missingAdminCount = Math.max(0, PILOT_REQUIRED_ADMIN_COUNT - adminCount)
  return {
    memberCount: members.length,
    operatorCount,
    adminCount,
    missingOperatorCount,
    missingAdminCount,
    ready: missingOperatorCount === 0 && missingAdminCount === 0,
  }
}

/**
 * Converts the overlapping operator/admin thresholds into a minimum sequence
 * an administrator can actually carry out. A newly added admin satisfies both
 * thresholds, so it must not be counted again as a plain member.
 */
export function buildPilotTeamRepairPlan(
  readiness: PilotTeamReadiness,
): PilotTeamRepairPlan {
  const addAdminCount = Math.min(
    readiness.missingOperatorCount,
    readiness.missingAdminCount,
  )
  return {
    addAdminCount,
    addMemberCount: Math.max(0, readiness.missingOperatorCount - addAdminCount),
    promoteAdminCount: Math.max(0, readiness.missingAdminCount - addAdminCount),
  }
}

function workspaceRoleLevel(role: string | null | undefined) {
  const normalized = role?.trim().toLowerCase() as WorkspaceRole | undefined
  return normalized ? ROLE_LEVEL[normalized] ?? 0 : 0
}

/**
 * Derives selectable pilot scopes from formal Diagnosis summaries already
 * loaded by the evaluation workspace. Choosing one only fills the setup form;
 * declaring a new immutable pilot version remains an explicit admin action.
 */
export function buildPilotScopeSuggestions(
  diagnoses: ReadonlyArray<Pick<DiagnosisSummary, 'system' | 'service' | 'rehearsal' | 'updateTime'>>,
): PilotScopeSuggestion[] {
  const suggestions = new Map<string, PilotScopeSuggestion>()

  diagnoses
    .filter(diagnosis => !diagnosis.rehearsal && pilotScopeIsSaveable(diagnosis))
    .forEach((diagnosis) => {
      const system = diagnosis.system.trim()
      const service = diagnosis.service.trim()
      if (!system || !service) return

      const key = pilotScopeKey({ system, service })
      const existing = suggestions.get(key)
      if (!existing) {
        suggestions.set(key, {
          system,
          service,
          formalCount: 1,
          latestAt: diagnosis.updateTime,
        })
        return
      }

      existing.formalCount += 1
      if (sortableTime(diagnosis.updateTime) > sortableTime(existing.latestAt)) {
        existing.system = system
        existing.service = service
        existing.latestAt = diagnosis.updateTime
      }
    })

  return [...suggestions.values()].sort((left, right) => {
    const countOrder = right.formalCount - left.formalCount
    if (countOrder !== 0) return countOrder
    const timeOrder = sortableTime(right.latestAt) - sortableTime(left.latestAt)
    if (timeOrder !== 0) return timeOrder
    return `${normalizeScopePart(left.system)}/${normalizeScopePart(left.service)}`
      .localeCompare(`${normalizeScopePart(right.system)}/${normalizeScopePart(right.service)}`)
  })
}

const STAGE_COPY: Record<EvaluationPilotStage, {
  stageLabel: string
  nextAction: string
}> = {
  NEEDS_CLOSURE: {
    stageLabel: '待登记结果',
    nextAction: '复核候选定位，完成平台外处置后登记结果并关闭排障单。',
  },
  NEEDS_REAL_SAMPLE: {
    stageLabel: '待采集真源样本',
    nextAction: '重新执行已审核的只读查询，仅保存脱敏 Guance 证据样本。',
  },
  NEEDS_REFERENCE: {
    stageLabel: '待填人工标准答案',
    nextAction: '记录正确排查步骤；有工单或群聊时间戳时，一并登记原来人工定位耗时。',
  },
  NEEDS_BASELINE: {
    stageLabel: '待跑影子基线',
    nextAction: '用冻结证据运行单模型基线，分开核对准确性和机器耗时。',
  },
  BASELINE_BLOCKED: {
    stageLabel: '影子运行需复核',
    nextAction: '上次影子运行没有形成可评估结果；先核对模型配置或校验失败原因。',
  },
  ACCURACY_ONLY: {
    stageLabel: '仅准确性样本',
    nextAction: '这个冻结版本没有人工耗时，只能验证“准不准”，不进入省时对照；下一条真实样本必须补齐耗时依据。',
  },
  READY_FOR_REVIEW: {
    stageLabel: '可进入周复盘',
    nextAction: '一起复盘判断是否准确、人工与机器耗时，以及最终处置是否真正解决问题。',
  },
}

/**
 * Builds the promotion hand-off queue from persisted facts only.
 * Only Diagnoses frozen into the current immutable plan revision are admitted.
 * Rehearsals, historical rows, previous revisions, Replay and fixture samples
 * never enter the current cohort.
 */
export function buildEvaluationPilotQueue(
  diagnoses: ReadonlyArray<DiagnosisSummary>,
  samples: ReadonlyArray<EvidenceEvaluationSample>,
  runs: ReadonlyArray<BaselineEvaluationRun>,
  plan: TroubleshootingPilotPlan | null,
): EvaluationPilotQueueRow[] {
  if (!pilotPlanReady(plan)) return []

  const latestSampleByDiagnosis = new Map<string, EvidenceEvaluationSample>()
  samples
    .filter(sample => sample.sourcePlatform === 'GUANCE' && !sample.diagnosisFixtureMode)
    .forEach((sample) => {
      const existing = latestSampleByDiagnosis.get(sample.diagnosisId)
      if (!existing || compareSamples(sample, existing) > 0) {
        latestSampleByDiagnosis.set(sample.diagnosisId, sample)
      }
    })

  const runsBySample = new Map<string, BaselineEvaluationRun[]>()
  runs
    .filter(run => run.sourcePlatform === 'GUANCE')
    .filter(run => !run.evidenceFixtureMode && !run.diagnosisFixtureMode)
    .forEach((run) => {
      const existing = runsBySample.get(run.sampleId) || []
      existing.push(run)
      runsBySample.set(run.sampleId, existing)
    })

  return diagnoses
    .filter(diagnosis => !diagnosis.rehearsal)
    .filter(diagnosis => matchesPilotEnrollment(diagnosis, plan))
    .map((diagnosis) => {
      const sample = latestSampleByDiagnosis.get(diagnosis.diagnosisId) || null
      const stage = pilotStage(diagnosis, sample, sample ? runsBySample.get(sample.sampleId) || [] : [])
      return {
        diagnosisId: diagnosis.diagnosisId,
        system: diagnosis.system,
        service: diagnosis.service,
        errorCode: diagnosis.errorCode,
        updatedAt: diagnosis.updateTime,
        stage,
        ...STAGE_COPY[stage],
        ownerLabel: stageOwner(stage, plan),
        sampleId: sample?.sampleId || null,
      }
    })
    .sort((left, right) => {
      const stageOrder = stageRank(left.stage) - stageRank(right.stage)
      if (stageOrder !== 0) return stageOrder
      return sortableTime(right.updatedAt) - sortableTime(left.updatedAt)
    })
}

/**
 * Projects the pilot into one workbench action that every troubleshooting
 * viewer can understand. It deliberately uses only the viewer-safe pilot plan
 * and Diagnosis summaries; evaluation evidence stays on the admin surface.
 */
export function buildPilotWorkbenchPrompt(
  diagnoses: ReadonlyArray<DiagnosisSummary>,
  plan: TroubleshootingPilotPlan | null,
): PilotWorkbenchPrompt {
  if (!pilotPlanReady(plan)) {
    const configuredButUnavailable = Boolean(plan?.configured)
    return {
      kind: 'SETUP',
      step: 1,
      title: configuredButUnavailable
        ? '试点配置需要管理员处理'
        : '先固定首批范围和三位负责人',
      detail: configuredButUnavailable
        ? '已有试点配置暂未就绪。管理员检查是否启用、调查范围和三位负责人。'
        : '试点尚未启用。管理员先选定系统、服务和二线、三线、数据取证负责人。',
      ownerLabel: '工作区管理员',
      actionLabel: '配置试点',
      diagnosisId: null,
      scope: null,
    }
  }

  const scopedFormal = diagnoses
    .filter(diagnosis => !diagnosis.rehearsal)
    .filter(diagnosis => matchesPilotEnrollment(diagnosis, plan))
    .sort((left, right) => sortableTime(right.updateTime) - sortableTime(left.updateTime))
  const pending = scopedFormal.find(diagnosis => diagnosis.status !== 'CLOSED')

  if (pending) {
    return {
      kind: 'CONTINUE_DIAGNOSIS',
      step: 2,
      title: '今天先推进这张正式排障单',
      detail: `排障单 ${pending.diagnosisId} 尚未登记最终结果；先完成复核、平台外处置和关闭。`,
      ownerLabel: plan.secondLine.displayName,
      actionLabel: '打开这张排障单',
      diagnosisId: pending.diagnosisId,
      scope: scopedModule(pending, plan),
    }
  }

  const closed = scopedFormal[0]
  if (closed) {
    return {
      kind: 'HANDOFF_EVALUATION',
      step: 3,
      title: '正式排障已闭环，进入试点评估接力',
      detail: `排障单 ${closed.diagnosisId} 已关闭；评估页会根据真源样本、人工答案和影子运行的已保存状态，再指出唯一下一步。`,
      ownerLabel: `${plan.sourceOwner.displayName}、${plan.thirdLine.displayName}`,
      actionLabel: '进入试点评估',
      diagnosisId: closed.diagnosisId,
      scope: scopedModule(closed, plan),
    }
  }

  return {
    kind: 'CREATE_FORMAL',
    step: 2,
    title: '试点已就绪，发起第一张正式排障单',
    detail: `使用试点范围内的真实告警新建正式排障单，并关闭“演练模式”。保存 v${plan.version} 前的历史单不会补算进来。`,
    ownerLabel: plan.secondLine.displayName,
    actionLabel: '发起首张正式排障',
    diagnosisId: null,
    scope: plan.modules[0] || null,
  }
}

export function pilotPlanReady(
  plan: TroubleshootingPilotPlan | null,
): plan is TroubleshootingPilotPlan & {
  secondLine: NonNullable<TroubleshootingPilotPlan['secondLine']>
  thirdLine: NonNullable<TroubleshootingPilotPlan['thirdLine']>
  sourceOwner: NonNullable<TroubleshootingPilotPlan['sourceOwner']>
} {
  return Boolean(plan?.configured
    && plan.enabled
    && plan.modules.length
    && plan.secondLine
    && plan.thirdLine
    && plan.sourceOwner
    && !plan.blockers.length)
}

export function matchesPilotScope(
  diagnosis: Pick<DiagnosisSummary, 'system' | 'service'>,
  plan: TroubleshootingPilotPlan | null,
) {
  if (!pilotPlanReady(plan)) return false
  const diagnosisKey = pilotScopeKey(diagnosis)
  return plan.modules.some(module => pilotScopeKey(module) === diagnosisKey)
}

/**
 * A formal Diagnosis belongs to the current pilot cohort only when the backend
 * froze this exact immutable plan revision at creation time. Scope matching is
 * retained as a defensive consistency check; historical rows with no frozen
 * version are suggestions, never pilot outcomes.
 */
export function matchesPilotEnrollment(
  diagnosis: Pick<DiagnosisSummary, 'system' | 'service' | 'pilotPlanVersion'>,
  plan: TroubleshootingPilotPlan | null,
) {
  return pilotPlanReady(plan)
    && diagnosis.pilotPlanVersion === plan.version
    && matchesPilotScope(diagnosis, plan)
}

function stageOwner(
  stage: EvaluationPilotStage,
  plan: TroubleshootingPilotPlan & {
    secondLine: NonNullable<TroubleshootingPilotPlan['secondLine']>
    thirdLine: NonNullable<TroubleshootingPilotPlan['thirdLine']>
    sourceOwner: NonNullable<TroubleshootingPilotPlan['sourceOwner']>
  },
) {
  if (stage === 'NEEDS_CLOSURE') return plan.secondLine.displayName
  if (stage === 'NEEDS_REAL_SAMPLE') return plan.sourceOwner.displayName
  if (stage === 'READY_FOR_REVIEW') {
    return [plan.secondLine, plan.thirdLine, plan.sourceOwner]
      .map(member => member.displayName)
      .join('、')
  }
  return plan.thirdLine.displayName
}

function normalizeScopePart(value: string) {
  return value.trim().toLowerCase()
}

function scopedModule(
  diagnosis: Pick<DiagnosisSummary, 'system' | 'service'>,
  plan: TroubleshootingPilotPlan & { modules: TroubleshootingPilotPlan['modules'] },
) {
  const diagnosisKey = pilotScopeKey(diagnosis)
  return plan.modules.find(module => pilotScopeKey(module) === diagnosisKey) || null
}

function pilotStage(
  diagnosis: DiagnosisSummary,
  sample: EvidenceEvaluationSample | null,
  runs: ReadonlyArray<BaselineEvaluationRun>,
): EvaluationPilotStage {
  if (diagnosis.status !== 'CLOSED') return 'NEEDS_CLOSURE'
  if (!sample) return 'NEEDS_REAL_SAMPLE'
  if (sample.referenceStatus !== 'READY_FOR_EVALUATION') return 'NEEDS_REFERENCE'

  const usefulRun = runs.some(run => run.status === 'SCORED' || run.status === 'ABSTAINED')
  const blockedRun = runs.some(run => run.status === 'MODEL_REJECTED'
    || run.status === 'VALIDATION_REJECTED'
    || run.quality.classification === 'TECHNICAL_FAILURE')
  if (!sample.humanBaseline) return 'ACCURACY_ONLY'
  if (usefulRun) return 'READY_FOR_REVIEW'
  if (blockedRun) return 'BASELINE_BLOCKED'
  return 'NEEDS_BASELINE'
}

function compareSamples(left: EvidenceEvaluationSample, right: EvidenceEvaluationSample) {
  if (left.captureRevision !== right.captureRevision) {
    return left.captureRevision - right.captureRevision
  }
  return sortableTime(left.capturedAt) - sortableTime(right.capturedAt)
}

function sortableTime(value: string) {
  const parsed = Date.parse(value)
  return Number.isFinite(parsed) ? parsed : 0
}

function stageRank(stage: EvaluationPilotStage) {
  const order: EvaluationPilotStage[] = [
    'NEEDS_CLOSURE',
    'NEEDS_REAL_SAMPLE',
    'NEEDS_REFERENCE',
    'NEEDS_BASELINE',
    'BASELINE_BLOCKED',
    'ACCURACY_ONLY',
    'READY_FOR_REVIEW',
  ]
  return order.indexOf(stage)
}
