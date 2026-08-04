import type {
  EvidenceQueryCatalog,
  EvidenceQueryContract,
  EvidenceRouteOrigin,
} from '@/api'

export function catalogSummary(catalog: EvidenceQueryCatalog | null) {
  const systems = catalog?.systems.length ?? 0
  const modules = catalog?.systems.reduce(
    (total, system) => total + system.modules.length, 0,
  ) ?? 0
  const contracts = catalog?.systems.reduce(
    (total, system) => total + system.modules.reduce(
      (moduleTotal, module) => moduleTotal + module.contracts.length, 0,
    ), 0,
  ) ?? 0
  const runnable = catalog?.systems.reduce(
    (total, system) => total + system.modules.reduce(
      (moduleTotal, module) => moduleTotal + module.runnableContracts, 0,
    ), 0,
  ) ?? 0
  return { systems, modules, contracts, runnable }
}

export function contractMatches(contract: EvidenceQueryContract, keyword: string): boolean {
  const normalized = keyword.trim().toLowerCase()
  if (!normalized) return true
  return [
    contract.contractRef,
    contract.signalKind,
    contract.scenario,
    contract.question,
    contract.summary,
    contract.adapter,
    contract.namespace,
    ...contract.fixedConditions,
    ...contract.canonicalOutputs,
  ].some(value => value.toLowerCase().includes(normalized))
}

export function routeOriginLabel(origin: EvidenceRouteOrigin): string {
  return {
    WORKSPACE: 'Workspace 声明',
    DEPLOYMENT: '部署默认',
    UNCONFIGURED: '未配置',
  }[origin]
}

export function bindingStatusLabel(status: string): string {
  return {
    NOT_ROUTED: '未路由',
    UNAUTHORIZED: '未授权绑定',
    INVALID_BINDING: '绑定无效',
    READY_FOR_VALIDATION: '可联调',
    CANONICAL_RESULT_OBSERVED: '已观测到规范证据',
  }[status] ?? status
}

export function parameterSourceLabel(source: string): string {
  return {
    INCIDENT_OR_CURRENT_TIME: '故障时间 / 当前时间',
    EVIDENCE_REQUEST: '证据请求',
    PREVIOUS_EVIDENCE: '前一步证据',
    INCIDENT: '排障事件',
    SYSTEM_ASSET: '系统观测资产',
    EVIDENCE_REQUEST_TARGET: '排查指南的证据目标',
  }[source] ?? source
}

export function acceptanceStatusLabel(status: string): string {
  return {
    ACCEPTED: '已验收',
    NOT_ACCEPTED: '待验收',
    STALE: '验收已过期',
    BLOCKED: '暂不可验收',
    UNAVAILABLE: '状态不可用',
  }[status] ?? status
}

export function runtimeStateLabel(status: string): string {
  return {
    CONFIGURED: '已配置',
    MISSING: '未配置',
    NOT_REPORTED: '该适配器未提供',
  }[status] ?? status
}

export function moveOrderedItem<T>(items: T[], index: number, offset: -1 | 1): T[] {
  const target = index + offset
  if (index < 0 || index >= items.length || target < 0 || target >= items.length) {
    return [...items]
  }
  const moved = [...items]
  const current = moved[index]
  moved[index] = moved[target]
  moved[target] = current
  return moved
}
