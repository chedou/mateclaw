/**
 * Frontend mirror of docs/intelligent-troubleshooting/l0/t7_owner_contract_intake.py.
 * Successful validation means PREPARED_NOT_EXECUTABLE only — never T7 ACCEPTED.
 */

export const T7_OWNER_CONTRACT_VERSION = 't7-owner-contract-intake.v1'
export const T7_OWNER_VALIDATION_VERSION = 't7-owner-contract-validation.v1'
/** UI 首批目标：先定 10 条。仓库正式 T7 intake 仍是 20（见 docs Python 校验器）。 */
export const T7_MIN_SELECTED = 10
export const T7_MAX_SELECTED = 30
/** 本页默认优先展示的 10 条（与 binding 对齐核对表一致）。 */
export const T7_PRIORITY_SELECTORS = [
  'csdp:IM2002',
  'csdp:IM3002',
  'csdp:101010',
  'csdp:101015',
  'csdp:401007',
  'csdp:501001',
  'csdp:Workorder_CustomerDetailFail_004',
  'csdp:Workorder_CustomerListFail_003',
  'csdp:Workorder_EmergencyCreateFail_005',
  'csdp:Workorder_UpgradeServiceFail_006',
] as const

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

export function ownerContractCompleteness(contract: OwnerContract | null): {
  filled: number
  total: number
  complete: boolean
} {
  const total = 15
  if (!contract) return { filled: 0, total, complete: false }
  const scalars = [
    contract.ownerTeam,
    contract.ownerLevel,
    contract.ownerScenario,
    contract.verifiedRuntimeService,
    contract.candidateReference,
    contract.serverQueryContractReference,
    contract.safeSearchTerm,
    contract.window,
    contract.anomalyCriterionReference,
    contract.diagnosisRuleReference,
    contract.historicalOccurredAt,
    contract.historicalSourceReference,
  ]
  const bindings = [
    contract.bindingRefs?.log_search,
    contract.bindingRefs?.log_trace_bundle,
    contract.bindingRefs?.contrast_sample,
  ]
  const filled = [...scalars, ...bindings].filter(
    value => typeof value === 'string' && fieldLooksFilled(value),
  ).length
  return { filled, total, complete: filled === total }
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
 * 为首批行生成唯一的开发侧引用 / 新 binding 名，并带上提示字段。
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
  base.candidateReference = `cand:batch10:${slug}`
  base.serverQueryContractReference = `q:batch10:${slug}:v1`
  base.anomalyCriterionReference = `crit:batch10:${slug}:v1`
  base.diagnosisRuleReference = `rule:batch10:${slug}:v1`
  base.bindingRefs = {
    log_search: `csdp-batch10-${slug}-log-search`,
    log_trace_bundle: `csdp-batch10-${slug}-trace-bundle`,
    contrast_sample: `csdp-batch10-${slug}-contrast`,
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
    return ['ownerTeam', 'historicalOccurredAt', 'historicalSourceReference']
  }
  const remaining: string[] = []
  if (!fieldLooksFilled(contract.ownerTeam)) remaining.push('ownerTeam')
  if (!fieldLooksFilled(contract.historicalOccurredAt)) remaining.push('historicalOccurredAt')
  if (!fieldLooksFilled(contract.historicalSourceReference)) remaining.push('historicalSourceReference')
  return remaining
}

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

  const ownerTeam = requireString(value.ownerTeam, `${selector}.ownerTeam`, 128, issues)
  const ownerLevel = requireString(value.ownerLevel, `${selector}.ownerLevel`, 2, issues)
  if (ownerLevel !== null && !OWNER_LEVELS.includes(ownerLevel as OwnerLevel)) {
    issues.push(`${selector}.ownerLevel must be P0, P1, or P2`)
  }
  const ownerScenario = requireString(value.ownerScenario, `${selector}.ownerScenario`, 160, issues)
  const verifiedRuntimeService = requireIdentifier(
    value.verifiedRuntimeService,
    `${selector}.verifiedRuntimeService`,
    issues,
  )
  const candidateReference = requireReference(
    value.candidateReference,
    `${selector}.candidateReference`,
    issues,
  )
  const serverQueryContractReference = requireReference(
    value.serverQueryContractReference,
    `${selector}.serverQueryContractReference`,
    issues,
  )
  const safeSearchTerm = requireIdentifier(value.safeSearchTerm, `${selector}.safeSearchTerm`, issues)
  const window = requireWindow(value.window, `${selector}.window`, issues)
  const anomalyCriterionReference = requireReference(
    value.anomalyCriterionReference,
    `${selector}.anomalyCriterionReference`,
    issues,
  )
  const diagnosisRuleReference = requireReference(
    value.diagnosisRuleReference,
    `${selector}.diagnosisRuleReference`,
    issues,
  )
  const logSearch = requireIdentifier(bindingRefs.log_search, `${selector}.bindingRefs.log_search`, issues)
  const logTraceBundle = requireIdentifier(
    bindingRefs.log_trace_bundle,
    `${selector}.bindingRefs.log_trace_bundle`,
    issues,
  )
  const contrastSample = requireIdentifier(
    bindingRefs.contrast_sample,
    `${selector}.bindingRefs.contrast_sample`,
    issues,
  )
  const historicalOccurredAt = requireOccurredAt(
    value.historicalOccurredAt,
    `${selector}.historicalOccurredAt`,
    asOf,
    issues,
  )
  const historicalSourceReference = requireReference(
    value.historicalSourceReference,
    `${selector}.historicalSourceReference`,
    issues,
  )

  if (
    ownerTeam === null
    || ownerLevel === null
    || !OWNER_LEVELS.includes(ownerLevel as OwnerLevel)
    || ownerScenario === null
    || verifiedRuntimeService === null
    || candidateReference === null
    || serverQueryContractReference === null
    || safeSearchTerm === null
    || window === null
    || anomalyCriterionReference === null
    || diagnosisRuleReference === null
    || logSearch === null
    || logTraceBundle === null
    || contrastSample === null
    || historicalOccurredAt === null
    || historicalSourceReference === null
  ) {
    return null
  }

  return {
    ownerTeam,
    ownerLevel,
    ownerScenario,
    verifiedRuntimeService,
    candidateReference,
    serverQueryContractReference,
    safeSearchTerm,
    window,
    anomalyCriterionReference,
    diagnosisRuleReference,
    log_search: logSearch,
    log_trace_bundle: logTraceBundle,
    contrast_sample: contrastSample,
    historicalOccurredAt,
    historicalSourceReference,
  }
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

  const uniqueFields = [
    'candidateReference',
    'serverQueryContractReference',
    'anomalyCriterionReference',
    'diagnosisRuleReference',
    'historicalSourceReference',
  ] as const
  for (const field of uniqueFields) {
    const values = normalizedContracts.map(contract => contract[field])
    if (new Set(values).size !== values.length) {
      return { ok: false, issues: [`${field} must be unique across selected contracts`] }
    }
  }

  const queryIdentities = normalizedContracts.map(contract => [
    contract.verifiedRuntimeService,
    contract.safeSearchTerm,
    contract.window,
    contract.log_search,
    contract.log_trace_bundle,
    contract.contrast_sample,
  ].join('\0'))
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
  return `mc-standard-query-intake:v3:${workspaceId ?? 'default'}`
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
