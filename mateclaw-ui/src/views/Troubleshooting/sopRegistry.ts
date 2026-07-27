import type { SopEntry, SopStatus } from '@/api'

const REQUIRED_TEXT_FIELDS = ['sopId', 'system', 'errorCode', 'service', 'title'] as const
const CONTRACT_ARRAY_FIELDS = [
  'evidenceRequests', 'anomalyCriteria', 'diagnosisRules', 'actions',
] as const

type JsonObject = Record<string, unknown>

/**
 * Parses one create-only SOP contract and pins its review-safe initial state.
 * The backend repeats this check; the client validation exists for useful
 * author feedback, never as the security boundary.
 */
export function parseCandidateSopJson(source: string): SopEntry {
  let value: unknown
  try {
    value = JSON.parse(source)
  } catch (error) {
    const detail = error instanceof Error ? error.message : 'unknown JSON error'
    throw new Error(`JSON 格式错误：${detail}`)
  }
  if (!isObject(value)) {
    throw new Error('请输入单个 SOP 对象；批量导入需要逐条审核，不能提交数组')
  }

  for (const field of REQUIRED_TEXT_FIELDS) {
    requiredText(value, field)
  }
  const status = optionalText(value.status)?.toLowerCase() || 'candidate'
  if (status !== 'candidate') {
    throw new Error('新 SOP 必须以 candidate 注册，审核通过需要单独推进')
  }
  if (value.verified !== undefined && value.verified !== false) {
    throw new Error('新 SOP 必须 verified=false；只有审核通过动作可以置为 true')
  }

  const arrays = Object.fromEntries(CONTRACT_ARRAY_FIELDS.map((field) => {
    const items = value[field] ?? []
    if (!Array.isArray(items) || items.some((item) => !isObject(item))) {
      throw new Error(`${field} 必须是对象数组`)
    }
    return [field, items]
  })) as Pick<SopEntry, typeof CONTRACT_ARRAY_FIELDS[number]>

  return {
    sopId: requiredText(value, 'sopId'),
    contractVersion: optionalText(value.contractVersion) || 'sop.v1',
    system: requiredText(value, 'system'),
    errorCode: requiredText(value, 'errorCode'),
    service: requiredText(value, 'service'),
    title: requiredText(value, 'title'),
    cause: optionalText(value.cause) || '',
    category: optionalText(value.category) || '',
    ownerTeam: optionalText(value.ownerTeam),
    status: 'candidate',
    verified: false,
    ...arrays,
  }
}

export function nextSopStatus(status: SopStatus): Exclude<SopStatus, 'candidate'> | null {
  if (status === 'candidate') return 'approved'
  if (status === 'approved') return 'deprecated'
  return null
}

function isObject(value: unknown): value is JsonObject {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function requiredText(value: JsonObject, field: typeof REQUIRED_TEXT_FIELDS[number]): string {
  const text = optionalText(value[field])
  if (!text) throw new Error(`${field} 不能为空`)
  return text
}

function optionalText(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value.trim() : null
}
