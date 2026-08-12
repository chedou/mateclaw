import type {
  BaselineEvaluationRun,
  DiagnosisSummary,
  EvidenceEvaluationSample,
} from '@/api'

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

const STAGE_COPY: Record<EvaluationPilotStage, {
  stageLabel: string
  ownerLabel: string
  nextAction: string
}> = {
  NEEDS_CLOSURE: {
    stageLabel: '待登记结果',
    ownerLabel: '二线 / 三线',
    nextAction: '复核候选定位，完成平台外处置后登记结果并关闭排障单。',
  },
  NEEDS_REAL_SAMPLE: {
    stageLabel: '待采集真源样本',
    ownerLabel: '系统 / Guance 负责人',
    nextAction: '重新执行已审核的只读查询，仅保存脱敏 Guance 证据样本。',
  },
  NEEDS_REFERENCE: {
    stageLabel: '待填人工标准答案',
    ownerLabel: '三线复核人',
    nextAction: '记录正确排查步骤；有工单或群聊时间戳时，一并登记原来人工定位耗时。',
  },
  NEEDS_BASELINE: {
    stageLabel: '待跑影子基线',
    ownerLabel: '试点管理员',
    nextAction: '用冻结证据运行单模型基线，分开核对准确性和机器耗时。',
  },
  BASELINE_BLOCKED: {
    stageLabel: '影子运行需复核',
    ownerLabel: '模型 / 试点管理员',
    nextAction: '上次影子运行没有形成可评估结果；先核对模型配置或校验失败原因。',
  },
  ACCURACY_ONLY: {
    stageLabel: '仅准确性样本',
    ownerLabel: '周复盘负责人',
    nextAction: '这个冻结版本没有人工耗时，只能验证“准不准”，不进入省时对照；下一条真实样本必须补齐耗时依据。',
  },
  READY_FOR_REVIEW: {
    stageLabel: '可进入周复盘',
    ownerLabel: '二线 + 三线 + Owner',
    nextAction: '一起复盘判断是否准确、人工与机器耗时，以及最终处置是否真正解决问题。',
  },
}

/**
 * Builds the promotion hand-off queue from persisted facts only.
 * Rehearsals, Replay and fixture samples never enter the queue.
 */
export function buildEvaluationPilotQueue(
  diagnoses: ReadonlyArray<DiagnosisSummary>,
  samples: ReadonlyArray<EvidenceEvaluationSample>,
  runs: ReadonlyArray<BaselineEvaluationRun>,
): EvaluationPilotQueueRow[] {
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
        sampleId: sample?.sampleId || null,
      }
    })
    .sort((left, right) => {
      const stageOrder = stageRank(left.stage) - stageRank(right.stage)
      if (stageOrder !== 0) return stageOrder
      return sortableTime(right.updatedAt) - sortableTime(left.updatedAt)
    })
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
