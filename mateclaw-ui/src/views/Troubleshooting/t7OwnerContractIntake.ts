/**
 * Frontend mirror of docs/intelligent-troubleshooting/l0/t7_owner_contract_intake.py.
 * Successful validation means PREPARED_NOT_EXECUTABLE only — never T7 ACCEPTED.
 */

export const T7_OWNER_CONTRACT_VERSION = 't7-owner-contract-intake.v1'
export const T7_OWNER_VALIDATION_VERSION = 't7-owner-contract-validation.v1'
/** 与仓库权威 T7 intake 保持一致；不足 20 条不得显示准备完成。 */
export const T7_MIN_SELECTED = 20
export const T7_MAX_SELECTED = 30

export const OWNER_LEVELS = ['P0', 'P1', 'P2'] as const
export type OwnerLevel = (typeof OWNER_LEVELS)[number]

export type OwnerBindingRefs = {
  log_search: string
  log_trace_bundle: string
  contrast_sample: string
}

export type OwnerContract = {
  ownerTeam: string
  ownerLevel: string
  ownerScenario: string
  verifiedRuntimeService: string
  candidateReference: string
  serverQueryContractReference: string
  safeSearchTerm: string
  window: string
  anomalyCriterionReference: string
  diagnosisRuleReference: string
  bindingRefs: OwnerBindingRefs
  historicalOccurredAt: string
  historicalSourceReference: string
}

export type SourceHints = {
  levels: string[]
  sourceServices: string[]
  modules: string[]
  scenarios: string[]
  hasLogSignatureHint: boolean
  signatureErrorCodes: string[]
}

export type OwnerContractRow = {
  selectorKey: string
  preparationTier: string
  sourceHints: SourceHints
  selectedForWindow: boolean
  ownerContract: OwnerContract | null
}

export type OwnerContractDocument = {
  contractVersion: string
  authorization: Record<string, unknown>
  preparationContractVersion: string
  preparationFingerprint: string
  windowTargetRange: { minimum: number; maximum: number }
  availableOwnerCandidateCount: number
  candidateTierCounts: Record<string, number>
  contracts: OwnerContractRow[]
}

export type OwnerValidationSuccess = {
  ok: true
  contractVersion: string
  status: 'PREPARED_NOT_EXECUTABLE'
  selectedCount: number
  selectedTierCounts: {
    A_HINTED: number
    B_CONTEXT_ONLY: number
    C_SOURCE_GAPS: number
  }
  selectedSelectors: string[]
  preparationFingerprint: string
  canAcceptT7: false
  canWriteRuntimeCatalog: false
  nextAuthority: unknown
}

export type OwnerValidationFailure = {
  ok: false
  issues: string[]
}

export type OwnerValidationResult = OwnerValidationSuccess | OwnerValidationFailure

export type OwnerContractSectionKey = 'INCIDENT' | 'QUERY' | 'DECISION'

export type OwnerContractSectionProgress = {
  key: OwnerContractSectionKey
  label: string
  filled: number
  total: number
  complete: boolean
  issue?: string
}

export type OwnerContractProgress = {
  filled: number
  total: number
  complete: boolean
  sections: OwnerContractSectionProgress[]
  issues: string[]
}

const SAFE_ID = /^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$/
const SAFE_REFERENCE = /^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,255}$/
const WINDOW = /^-([1-9][0-9]{0,5})(s|m|h|d)$/
const UTC_SECONDS = /^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$/
const UNRESOLVED_PLACEHOLDER = /<(?:replace:[^<>]+|P0\|P1\|P2)>/i
const JWT = /eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}/
const FORBIDDEN_TEXT = [
  /D::/i,
  /https?:\/\//i,
  /DF-API-KEY/i,
  /\bBearer\s+[A-Za-z0-9]/i,
  /\b(?:api[_-]?key|password|authorization|access[_-]?token|secret)\s*[:=]/i,
]

const ROOT_FIELDS = [
  'contractVersion',
  'authorization',
  'preparationContractVersion',
  'preparationFingerprint',
  'windowTargetRange',
  'availableOwnerCandidateCount',
  'candidateTierCounts',
  'contracts',
] as const

const ROW_FIELDS = [
  'selectorKey',
  'preparationTier',
  'sourceHints',
  'selectedForWindow',
  'ownerContract',
] as const

const OWNER_CONTRACT_FIELDS = [
  'ownerTeam',
  'ownerLevel',
  'ownerScenario',
  'verifiedRuntimeService',
  'candidateReference',
  'serverQueryContractReference',
  'safeSearchTerm',
  'window',
  'anomalyCriterionReference',
  'diagnosisRuleReference',
  'bindingRefs',
  'historicalOccurredAt',
  'historicalSourceReference',
] as const

const CORE_SIGNALS = ['log_search', 'log_trace_bundle', 'contrast_sample'] as const

function deepEqual(a: unknown, b: unknown): boolean {
  return JSON.stringify(a) === JSON.stringify(b)
}

function sameKeys(value: unknown, expected: readonly string[]): value is Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const keys = Object.keys(value)
  return keys.length === expected.length && expected.every(key => key in value)
}

function isForbidden(value: string): boolean {
  return JWT.test(value) || FORBIDDEN_TEXT.some(pattern => pattern.test(value))
}

function requireString(value: unknown, field: string, maximum: number, issues: string[]): string | null {
  if (typeof value !== 'string') {
    issues.push(`${field} must be a string`)
    return null
  }
  const normalized = value.trim()
  if (UNRESOLVED_PLACEHOLDER.test(normalized)) {
    issues.push(`${field} contains an unresolved placeholder`)
    return null
  }
  if (
    !normalized
    || normalized.length > maximum
    || [...normalized].some(character => character.charCodeAt(0) < 32)
    || isForbidden(normalized)
  ) {
    issues.push(`${field} is blank, unsafe, or too long`)
    return null
  }
  return normalized
}

function requireIdentifier(value: unknown, field: string, issues: string[]): string | null {
  const normalized = requireString(value, field, 128, issues)
  if (normalized === null) return null
  if (!SAFE_ID.test(normalized)) {
    issues.push(`${field} must be a safe identifier`)
    return null
  }
  return normalized
}

function requireReference(value: unknown, field: string, issues: string[]): string | null {
  const normalized = requireString(value, field, 256, issues)
  if (normalized === null) return null
  if (!SAFE_REFERENCE.test(normalized)) {
    issues.push(`${field} must be a safe reference`)
    return null
  }
  return normalized
}

function requireWindow(value: unknown, field: string, issues: string[]): string | null {
  const normalized = requireString(value, field, 16, issues)
  if (normalized === null) return null
  const match = WINDOW.exec(normalized)
  if (!match) {
    issues.push(`${field} must be a bounded relative window`)
    return null
  }
  const amount = Number(match[1])
  const factor = { s: 1, m: 60, h: 3600, d: 86400 }[match[2] as 's' | 'm' | 'h' | 'd']
  if (amount * factor > 86400) {
    issues.push(`${field} exceeds 24 hours`)
    return null
  }
  return normalized
}

function requireOccurredAt(
  value: unknown,
  field: string,
  asOf: Date,
  issues: string[],
): string | null {
  const normalized = requireString(value, field, 20, issues)
  if (normalized === null) return null
  if (!UTC_SECONDS.test(normalized)) {
    issues.push(`${field} must be UTC RFC3339 whole seconds`)
    return null
  }
  const observed = Date.parse(normalized)
  if (Number.isNaN(observed)) {
    issues.push(`${field} is not a real timestamp`)
    return null
  }
  if (observed > asOf.getTime()) {
    issues.push(`${field} is in the future`)
    return null
  }
  return normalized
}

type OwnerProgressFieldKey =
  | 'ownerTeam'
  | 'ownerLevel'
  | 'ownerScenario'
  | 'verifiedRuntimeService'
  | 'candidateReference'
  | 'serverQueryContractReference'
  | 'safeSearchTerm'
  | 'window'
  | 'anomalyCriterionReference'
  | 'diagnosisRuleReference'
  | 'bindingRefs.log_search'
  | 'bindingRefs.log_trace_bundle'
  | 'bindingRefs.contrast_sample'
  | 'historicalOccurredAt'
  | 'historicalSourceReference'

type NormalizedOwnerContract = {
  ownerTeam: string
  ownerLevel: string
  ownerScenario: string
  verifiedRuntimeService: string
  candidateReference: string
  serverQueryContractReference: string
  safeSearchTerm: string
  window: string
  anomalyCriterionReference: string
  diagnosisRuleReference: string
  log_search: string
  log_trace_bundle: string
  contrast_sample: string
  historicalOccurredAt: string
  historicalSourceReference: string
}

type OwnerFieldSpec = {
  key: OwnerProgressFieldKey
  section: OwnerContractSectionKey
  label: string
  normalizedKey: keyof NormalizedOwnerContract
  uniqueAcrossSelected?: boolean
  queryIdentity?: boolean
  read: (contract: OwnerContract) => unknown
  normalize: (value: unknown, fieldPath: string, asOf: Date, issues: string[]) => string | null
}

const OWNER_SECTION_LABELS: Record<OwnerContractSectionKey, string> = {
  INCIDENT: '确认这是什么故障',
  QUERY: '确认在观测云怎么查',
  DECISION: '确认平台怎么判断',
}

function passes(check: (issues: string[]) => unknown): boolean {
  const issues: string[] = []
  const result = check(issues)
  return result !== null && issues.length === 0
}

function requireOwnerLevel(value: unknown, fieldPath: string, issues: string[]): string | null {
  const normalized = requireString(value, fieldPath, 2, issues)
  if (normalized === null) return null
  if (!OWNER_LEVELS.includes(normalized as OwnerLevel)) {
    issues.push(`${fieldPath} must be P0, P1, or P2`)
    return null
  }
  return normalized
}

/**
 * Single catalog of the 15 owner facts. Progress UI and final validation both
 * derive field order, labels, uniqueness and query identity from this list.
 */
const OWNER_FIELD_SPECS: readonly OwnerFieldSpec[] = [
  {
    key: 'ownerTeam', section: 'INCIDENT', label: '责任团队', normalizedKey: 'ownerTeam',
    read: contract => contract.ownerTeam,
    normalize: (value, path, _asOf, issues) => requireString(value, path, 128, issues),
  },
  {
    key: 'ownerLevel', section: 'INCIDENT', label: '故障等级', normalizedKey: 'ownerLevel',
    read: contract => contract.ownerLevel,
    normalize: (value, path, _asOf, issues) => requireOwnerLevel(value, path, issues),
  },
  {
    key: 'ownerScenario', section: 'INCIDENT', label: '故障场景', normalizedKey: 'ownerScenario',
    read: contract => contract.ownerScenario,
    normalize: (value, path, _asOf, issues) => requireString(value, path, 160, issues),
  },
  {
    key: 'verifiedRuntimeService', section: 'INCIDENT', label: '真实运行服务',
    normalizedKey: 'verifiedRuntimeService', queryIdentity: true,
    read: contract => contract.verifiedRuntimeService,
    normalize: (value, path, _asOf, issues) => requireIdentifier(value, path, issues),
  },
  {
    key: 'historicalOccurredAt', section: 'INCIDENT', label: '故障发生时间',
    normalizedKey: 'historicalOccurredAt',
    read: contract => contract.historicalOccurredAt,
    normalize: (value, path, asOf, issues) => requireOccurredAt(value, path, asOf, issues),
  },
  {
    key: 'historicalSourceReference', section: 'INCIDENT', label: '告警或工单号',
    normalizedKey: 'historicalSourceReference', uniqueAcrossSelected: true,
    read: contract => contract.historicalSourceReference,
    normalize: (value, path, _asOf, issues) => requireReference(value, path, issues),
  },
  {
    key: 'safeSearchTerm', section: 'QUERY', label: '安全检索键',
    normalizedKey: 'safeSearchTerm', queryIdentity: true,
    read: contract => contract.safeSearchTerm,
    normalize: (value, path, _asOf, issues) => requireIdentifier(value, path, issues),
  },
  {
    key: 'window', section: 'QUERY', label: '查询时间窗',
    normalizedKey: 'window', queryIdentity: true,
    read: contract => contract.window,
    normalize: (value, path, _asOf, issues) => requireWindow(value, path, issues),
  },
  {
    key: 'serverQueryContractReference', section: 'QUERY', label: '服务端查法编号',
    normalizedKey: 'serverQueryContractReference', uniqueAcrossSelected: true,
    read: contract => contract.serverQueryContractReference,
    normalize: (value, path, _asOf, issues) => requireReference(value, path, issues),
  },
  {
    key: 'bindingRefs.log_search', section: 'QUERY', label: '失败日志查询',
    normalizedKey: 'log_search', queryIdentity: true,
    read: contract => contract.bindingRefs?.log_search,
    normalize: (value, path, _asOf, issues) => requireIdentifier(value, path, issues),
  },
  {
    key: 'bindingRefs.log_trace_bundle', section: 'QUERY', label: '关联调用还原',
    normalizedKey: 'log_trace_bundle', queryIdentity: true,
    read: contract => contract.bindingRefs?.log_trace_bundle,
    normalize: (value, path, _asOf, issues) => requireIdentifier(value, path, issues),
  },
  {
    key: 'bindingRefs.contrast_sample', section: 'QUERY', label: '成功失败对照',
    normalizedKey: 'contrast_sample', queryIdentity: true,
    read: contract => contract.bindingRefs?.contrast_sample,
    normalize: (value, path, _asOf, issues) => requireIdentifier(value, path, issues),
  },
  {
    key: 'candidateReference', section: 'DECISION', label: '候选材料编号',
    normalizedKey: 'candidateReference', uniqueAcrossSelected: true,
    read: contract => contract.candidateReference,
    normalize: (value, path, _asOf, issues) => requireReference(value, path, issues),
  },
  {
    key: 'anomalyCriterionReference', section: 'DECISION', label: '异常判据编号',
    normalizedKey: 'anomalyCriterionReference', uniqueAcrossSelected: true,
    read: contract => contract.anomalyCriterionReference,
    normalize: (value, path, _asOf, issues) => requireReference(value, path, issues),
  },
  {
    key: 'diagnosisRuleReference', section: 'DECISION', label: '诊断规则编号',
    normalizedKey: 'diagnosisRuleReference', uniqueAcrossSelected: true,
    read: contract => contract.diagnosisRuleReference,
    normalize: (value, path, _asOf, issues) => requireReference(value, path, issues),
  },
]

const OWNER_FIELD_BY_KEY = new Map(OWNER_FIELD_SPECS.map(field => [field.key, field] as const))
const OWNER_UNIQUE_FIELDS = OWNER_FIELD_SPECS.filter(field => field.uniqueAcrossSelected)
const OWNER_QUERY_IDENTITY_FIELDS = OWNER_FIELD_SPECS.filter(field => field.queryIdentity)
export const T7_OWNER_FACT_COUNT = OWNER_FIELD_SPECS.length

export function emptyOwnerContract(): OwnerContract {
  return {
    ownerTeam: '<replace:owner-team>',
    ownerLevel: '<P0|P1|P2>',
    ownerScenario: '<replace:owner-verified-scenario>',
    verifiedRuntimeService: '<replace:runtime-service>',
    candidateReference: '<replace:candidate-reference>',
    serverQueryContractReference: '<replace:query-contract-reference>',
    safeSearchTerm: '<replace:safe-search-term>',
    window: '<replace:bounded-window>',
    anomalyCriterionReference: '<replace:criterion-reference>',
    diagnosisRuleReference: '<replace:rule-reference>',
    bindingRefs: {
      log_search: '<replace:log-search-binding>',
      log_trace_bundle: '<replace:trace-binding>',
      contrast_sample: '<replace:contrast-binding>',
    },
    historicalOccurredAt: '<replace:UTC-whole-seconds>',
    historicalSourceReference: '<replace:historical-source-reference>',
  }
}

export function cloneRecommendedWorksheet(template: OwnerContractDocument): OwnerContractDocument {
  return structuredClone(template)
}

function fieldLooksFilled(value: string): boolean {
  const trimmed = value.trim()
  return Boolean(trimmed) && !UNRESOLVED_PLACEHOLDER.test(trimmed)
}

export function ownerContractCompleteness(
  contract: OwnerContract | null,
  asOf: Date = new Date(),
): {
  filled: number
  total: number
  complete: boolean
} {
  const progress = buildOwnerContractProgress(contract, asOf)
  return { filled: progress.filled, total: progress.total, complete: progress.complete }
}

/**
 * Presents the immutable 15-field contract as three owner-facing questions.
 * This is a projection only: it does not remove or relax any validated field.
 */
export function ownerContractSectionProgress(
  contract: OwnerContract | null,
  asOf: Date = new Date(),
): OwnerContractSectionProgress[] {
  return buildOwnerContractProgress(contract, asOf).sections
}

function buildOwnerContractProgress(
  contract: OwnerContract | null,
  asOf: Date,
  sectionIssues: ReadonlyMap<OwnerContractSectionKey, string[]> = new Map(),
): OwnerContractProgress {
  const validFields = new Set<OwnerProgressFieldKey>()
  if (contract) {
    for (const field of OWNER_FIELD_SPECS) {
      if (progressFieldValid(contract, field.key, asOf)) validFields.add(field.key)
    }
  }

  const sections = (Object.keys(OWNER_SECTION_LABELS) as OwnerContractSectionKey[]).map((key) => {
    const fields = OWNER_FIELD_SPECS.filter(field => field.section === key)
    const filled = fields.filter(field => validFields.has(field.key)).length
    const issues = sectionIssues.get(key) || []
    return {
      key,
      label: OWNER_SECTION_LABELS[key],
      filled,
      total: fields.length,
      complete: filled === fields.length && issues.length === 0,
      ...(issues.length > 0 ? { issue: issues.join('；') } : {}),
    }
  })
  const invalidLabels = OWNER_FIELD_SPECS
    .filter(field => !validFields.has(field.key))
    .map(field => field.label)
  const issues = [
    ...(invalidLabels.length > 0 ? [`请检查：${invalidLabels.join('、')}`] : []),
    ...new Set([...sectionIssues.values()].flat()),
  ]
  return {
    filled: validFields.size,
    total: OWNER_FIELD_SPECS.length,
    complete: sections.every(section => section.complete),
    sections,
    issues,
  }
}

function progressFieldValid(
  contract: OwnerContract | null,
  key: OwnerProgressFieldKey,
  asOf: Date,
): boolean {
  const field = OWNER_FIELD_BY_KEY.get(key)
  return Boolean(
    contract
    && field
    && passes(issues => field.normalize(field.read(contract), field.key, asOf, issues)),
  )
}

export function ownerContractBatchProgress(
  rows: ReadonlyArray<OwnerContractRow>,
  asOf: Date = new Date(),
): Map<string, OwnerContractProgress> {
  const selected = rows.filter(row => row.selectedForWindow)
  const sectionIssues = new Map<string, Map<OwnerContractSectionKey, string[]>>()
  const addIssue = (selector: string, section: OwnerContractSectionKey, issue: string) => {
    const bySection = sectionIssues.get(selector) || new Map<OwnerContractSectionKey, string[]>()
    const issues = bySection.get(section) || []
    if (!issues.includes(issue)) issues.push(issue)
    bySection.set(section, issues)
    sectionIssues.set(selector, bySection)
  }
  const markDuplicates = (
    identity: (contract: OwnerContract) => string | null,
    section: OwnerContractSectionKey,
    issue: string,
  ) => {
    const selectorsByIdentity = new Map<string, string[]>()
    for (const row of selected) {
      if (!row.ownerContract) continue
      const value = identity(row.ownerContract)
      if (value === null) continue
      const selectors = selectorsByIdentity.get(value) || []
      selectors.push(row.selectorKey)
      selectorsByIdentity.set(value, selectors)
    }
    for (const selectors of selectorsByIdentity.values()) {
      if (selectors.length < 2) continue
      selectors.forEach(selector => addIssue(selector, section, issue))
    }
  }

  for (const definition of OWNER_UNIQUE_FIELDS) {
    markDuplicates((contract) => {
      if (!progressFieldValid(contract, definition.key, asOf)) return null
      return String(definition.read(contract)).trim()
    }, definition.section, `${definition.label}与其他条目重复`)
  }

  markDuplicates((contract) => {
    if (!OWNER_QUERY_IDENTITY_FIELDS.every(field => progressFieldValid(contract, field.key, asOf))) {
      return null
    }
    return OWNER_QUERY_IDENTITY_FIELDS
      .map(field => String(field.read(contract)).trim())
      .join('\0')
  }, 'QUERY', '与其他条目的查询方法重复')

  return new Map(selected.map(row => [
    row.selectorKey,
    buildOwnerContractProgress(
      row.ownerContract,
      asOf,
      sectionIssues.get(row.selectorKey),
    ),
  ]))
}

/** Returns the next selected row that still needs owner facts, wrapping once. */
export function nextIncompleteOwnerSelector(
  rows: ReadonlyArray<OwnerContractRow>,
  currentSelector: string,
  asOf: Date = new Date(),
): string | null {
  const selected = rows.filter(row => row.selectedForWindow)
  const progress = ownerContractBatchProgress(selected, asOf)
  if (!selected.some(row => !progress.get(row.selectorKey)?.complete)) return null

  const currentIndex = selected.findIndex(row => row.selectorKey === currentSelector)
  for (let offset = 1; offset <= selected.length; offset += 1) {
    const index = (Math.max(-1, currentIndex) + offset) % selected.length
    const row = selected[index]
    if (!progress.get(row.selectorKey)?.complete) return row.selectorKey
  }
  return null
}

export function applySourceHintsDraft(row: OwnerContractRow): OwnerContract {
  const base = row.ownerContract ? { ...row.ownerContract, bindingRefs: { ...row.ownerContract.bindingRefs } }
    : emptyOwnerContract()
  const level = row.sourceHints.levels.find(item => OWNER_LEVELS.includes(item as OwnerLevel))
  if (level) base.ownerLevel = level
  if (row.sourceHints.scenarios[0] && !fieldLooksFilled(base.ownerScenario)) {
    base.ownerScenario = row.sourceHints.scenarios[0]
  } else if (row.sourceHints.scenarios[0] && UNRESOLVED_PLACEHOLDER.test(base.ownerScenario)) {
    base.ownerScenario = row.sourceHints.scenarios[0]
  }
  if (row.sourceHints.signatureErrorCodes[0]) {
    base.safeSearchTerm = row.sourceHints.signatureErrorCodes[0]
  }
  if (row.sourceHints.sourceServices[0] && SAFE_ID.test(row.sourceHints.sourceServices[0])) {
    if (!fieldLooksFilled(base.verifiedRuntimeService) || UNRESOLVED_PLACEHOLDER.test(base.verifiedRuntimeService)) {
      base.verifiedRuntimeService = row.sourceHints.sourceServices[0]
    }
  }
  return base
}

/** selector → 建议 runtime service（仍须 Owner 核实；禁止复用 pilot binding 名） */
const SERVICE_DRAFT_BY_SELECTOR: Record<string, string> = {
  'csdp:IM2002': 'csdp-session-service',
  'csdp:IM3002': 'csdp-session-service',
  'csdp:101010': 'csdp-task',
  'csdp:101015': 'csdp-task',
  'csdp:401007': 'csdp-task',
  'csdp:501001': 'csdp-task',
  'csdp:Workorder_CustomerDetailFail_004': 'csdp-wechat',
  'csdp:Workorder_CustomerListFail_003': 'csdp-wechat',
  'csdp:Workorder_EmergencyCreateFail_005': 'csdp-wechat',
  'csdp:Workorder_UpgradeServiceFail_006': 'csdp-wechat',
}

export function selectorSlug(selectorKey: string): string {
  return selectorKey
    .replace(/^csdp:/, '')
    .replace(/[^A-Za-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .toLowerCase()
}

/**
 * 为正式首批行生成唯一的开发侧引用 / 新 binding 草稿名，并带上提示字段。
 * 不填 historicalOccurredAt / historicalSourceReference（必须 Owner 用真实告警）。
 * 绝不写入 csdp-message-send-* / csdp-cti-* / csdp-itgw-*。
 */
export function applyDeveloperDraft(row: OwnerContractRow, options?: {
  ownerTeam?: string
  window?: string
}): OwnerContract {
  const slug = selectorSlug(row.selectorKey)
  const base = applySourceHintsDraft(row)
  if (options?.ownerTeam) base.ownerTeam = options.ownerTeam
  else if (!fieldLooksFilled(base.ownerTeam)) base.ownerTeam = 'CSDP'

  const serviceDraft = SERVICE_DRAFT_BY_SELECTOR[row.selectorKey]
  if (serviceDraft) base.verifiedRuntimeService = serviceDraft

  base.window = options?.window || '-6h'
  base.candidateReference = `cand:t7-draft:${slug}`
  base.serverQueryContractReference = `q:t7-draft:${slug}:v1`
  base.anomalyCriterionReference = `crit:t7-draft:${slug}:v1`
  base.diagnosisRuleReference = `rule:t7-draft:${slug}:v1`
  base.bindingRefs = {
    log_search: `csdp-t7-draft-${slug}-log-search`,
    log_trace_bundle: `csdp-t7-draft-${slug}-trace-bundle`,
    contrast_sample: `csdp-t7-draft-${slug}-contrast`,
  }
  // Keep historical fields for Owner — do not invent alert times.
  if (!fieldLooksFilled(base.historicalOccurredAt)) {
    base.historicalOccurredAt = '<replace:UTC-whole-seconds>'
  }
  if (!fieldLooksFilled(base.historicalSourceReference)) {
    base.historicalSourceReference = '<replace:historical-source-reference>'
  }
  return base
}

export function applyFirstBatchDeveloperDrafts(
  document: OwnerContractDocument,
  options?: { ownerTeam?: string; window?: string },
): number {
  let updated = 0
  for (const row of document.contracts) {
    if (!row.selectedForWindow) continue
    row.ownerContract = applyDeveloperDraft(row, options)
    updated += 1
  }
  return updated
}

export function ownerRemainingFields(contract: OwnerContract | null): string[] {
  if (!contract) {
    return [...OWNER_CONTRACT_FIELDS]
  }
  const values: Record<(typeof OWNER_CONTRACT_FIELDS)[number], unknown> = {
    ownerTeam: contract.ownerTeam,
    ownerLevel: contract.ownerLevel,
    ownerScenario: contract.ownerScenario,
    verifiedRuntimeService: contract.verifiedRuntimeService,
    candidateReference: contract.candidateReference,
    serverQueryContractReference: contract.serverQueryContractReference,
    safeSearchTerm: contract.safeSearchTerm,
    window: contract.window,
    anomalyCriterionReference: contract.anomalyCriterionReference,
    diagnosisRuleReference: contract.diagnosisRuleReference,
    bindingRefs: contract.bindingRefs,
    historicalOccurredAt: contract.historicalOccurredAt,
    historicalSourceReference: contract.historicalSourceReference,
  }
  return OWNER_CONTRACT_FIELDS.filter((field) => {
    if (field === 'bindingRefs') {
      return CORE_SIGNALS.some(signal => !fieldLooksFilled(contract.bindingRefs?.[signal] || ''))
    }
    const value = values[field]
    return typeof value !== 'string' || !fieldLooksFilled(value)
  })
}

function normalizeOwnerContract(
  value: unknown,
  selector: string,
  asOf: Date,
  issues: string[],
): NormalizedOwnerContract | null {
  if (!sameKeys(value, OWNER_CONTRACT_FIELDS)) {
    issues.push(`${selector} ownerContract fields are invalid`)
    return null
  }
  const bindingRefs = value.bindingRefs
  if (!sameKeys(bindingRefs, CORE_SIGNALS)) {
    issues.push(`${selector}.bindingRefs must contain the three core signals`)
    return null
  }

  const ownerContract = value as OwnerContract
  const normalized: Partial<NormalizedOwnerContract> = {}
  for (const field of OWNER_FIELD_SPECS) {
    const result = field.normalize(
      field.read(ownerContract),
      `${selector}.${field.key}`,
      asOf,
      issues,
    )
    if (result !== null) normalized[field.normalizedKey] = result
  }
  if (OWNER_FIELD_SPECS.some(field => normalized[field.normalizedKey] == null)) {
    return null
  }
  return normalized as NormalizedOwnerContract
}

export function validateOwnerInput(
  document: unknown,
  template: OwnerContractDocument,
  asOf: Date = new Date(),
): OwnerValidationResult {
  const issues: string[] = []
  if (!sameKeys(document, ROOT_FIELDS)) {
    return { ok: false, issues: ['owner input root fields are invalid'] }
  }

  for (const field of ROOT_FIELDS) {
    if (field === 'contracts') continue
    if (!deepEqual(document[field], template[field as keyof OwnerContractDocument])) {
      return { ok: false, issues: [`owner input ${field} is stale or tampered`] }
    }
  }

  const rawContracts = document.contracts
  const expectedContracts = template.contracts
  if (!Array.isArray(rawContracts) || !Array.isArray(expectedContracts)) {
    return { ok: false, issues: ['owner input contracts must be an array'] }
  }
  if (rawContracts.length !== expectedContracts.length) {
    return { ok: false, issues: ['owner input must retain every current candidate row'] }
  }

  const expectedBySelector = new Map(expectedContracts.map(row => [row.selectorKey, row]))
  const actualBySelector = new Map<string, OwnerContractRow>()

  for (const row of rawContracts) {
    if (!sameKeys(row, ROW_FIELDS)) {
      return { ok: false, issues: ['owner input row fields are invalid'] }
    }
    const selector = row.selectorKey
    if (typeof selector !== 'string' || !expectedBySelector.has(selector)) {
      return { ok: false, issues: ['owner input contains an unknown selector'] }
    }
    if (actualBySelector.has(selector)) {
      return { ok: false, issues: ['owner input contains a duplicate selector'] }
    }
    const expected = expectedBySelector.get(selector)!
    if (
      row.preparationTier !== expected.preparationTier
      || !deepEqual(row.sourceHints, expected.sourceHints)
    ) {
      return { ok: false, issues: [`${selector} preparation hints are stale or tampered`] }
    }
    if (typeof row.selectedForWindow !== 'boolean') {
      return { ok: false, issues: [`${selector} selectedForWindow must be boolean`] }
    }
    actualBySelector.set(selector, row as OwnerContractRow)
  }

  if (actualBySelector.size !== expectedBySelector.size) {
    return { ok: false, issues: ['owner input candidate membership is stale'] }
  }

  const selected: string[] = []
  const normalizedContracts: NormalizedOwnerContract[] = []

  for (const expected of expectedContracts) {
    const selector = expected.selectorKey
    const row = actualBySelector.get(selector)!
    if (!row.selectedForWindow) {
      if (row.ownerContract !== null) {
        return { ok: false, issues: [`${selector} unselected row must not carry ownerContract`] }
      }
      continue
    }
    selected.push(selector)
    const normalized = normalizeOwnerContract(row.ownerContract, selector, asOf, issues)
    if (normalized) normalizedContracts.push(normalized)
  }

  if (issues.length > 0) {
    return { ok: false, issues }
  }

  const maximum = Math.min(T7_MAX_SELECTED, expectedContracts.length)
  if (selected.length < T7_MIN_SELECTED || selected.length > maximum) {
    return {
      ok: false,
      issues: [`owner input requires ${T7_MIN_SELECTED} to ${maximum} selected contracts`],
    }
  }

  for (const field of OWNER_UNIQUE_FIELDS) {
    const values = normalizedContracts.map(contract => contract[field.normalizedKey])
    if (new Set(values).size !== values.length) {
      return { ok: false, issues: [`${field.normalizedKey} must be unique across selected contracts`] }
    }
  }

  const queryIdentities = normalizedContracts.map(contract => OWNER_QUERY_IDENTITY_FIELDS
    .map(field => contract[field.normalizedKey])
    .join('\0'))
  if (new Set(queryIdentities).size !== queryIdentities.length) {
    return { ok: false, issues: ['query semantics must be unique across selected contracts'] }
  }

  const selectedTierCounts = {
    A_HINTED: 0,
    B_CONTEXT_ONLY: 0,
    C_SOURCE_GAPS: 0,
  }
  for (const selector of selected) {
    const tier = actualBySelector.get(selector)?.preparationTier
    if (tier === 'A_HINTED' || tier === 'B_CONTEXT_ONLY' || tier === 'C_SOURCE_GAPS') {
      selectedTierCounts[tier] += 1
    }
  }

  return {
    ok: true,
    contractVersion: T7_OWNER_VALIDATION_VERSION,
    status: 'PREPARED_NOT_EXECUTABLE',
    selectedCount: selected.length,
    selectedTierCounts,
    selectedSelectors: selected,
    preparationFingerprint: template.preparationFingerprint,
    canAcceptT7: false,
    canWriteRuntimeCatalog: false,
    nextAuthority: template.authorization.nextAuthority,
  }
}

export function draftStorageKey(workspaceId: string | number | null | undefined): string {
  return `mc-standard-query-intake:v4:${workspaceId ?? 'default'}`
}

export function downloadOwnerDocument(document: OwnerContractDocument, filename = 'standard-query-intake.local.json') {
  const blob = new Blob([`${JSON.stringify(document, null, 2)}\n`], {
    type: 'application/json;charset=utf-8',
  })
  const url = URL.createObjectURL(blob)
  const anchor = window.document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.click()
  URL.revokeObjectURL(url)
}
