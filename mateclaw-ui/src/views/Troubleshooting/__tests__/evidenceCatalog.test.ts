import { describe, expect, it } from 'vitest'
import type { EvidenceQueryCatalog, EvidenceQueryContract, ObservabilityAsset } from '@/api'
import {
  bindingStatusLabel,
  buildModuleToolSetups,
  catalogSummary,
  contractMatches,
  directTrialBlockReason,
  findModuleAsset,
  listSetupModules,
  mergeObservabilityAssetContractOptions,
  moduleNextAction,
  observabilityAssetDraftReadiness,
  moveOrderedItem,
  routeOriginLabel,
  runtimeStateLabel,
  signalKindLabel,
} from '../evidenceCatalog'

const contract: EvidenceQueryContract = {
  contractRef: 'csdp-message-send-log-search',
  signalKind: 'log_search',
  scenario: '会话消息发送失败',
  question: '哪些失败请求需要继续追踪？',
  summary: 'CSDP SendMsg 失败日志检索',
  adapter: 'guance',
  namespace: 'L',
  fixedConditions: ['日志源=csp-rpc-msg'],
  endpoint: {
    operationKind: 'DF_QUERY_DATA_V1',
    method: 'POST',
    path: '/api/v1/df/query_data_v1',
    qtype: 'dql',
  },
  parameters: [{
    name: 'window',
    source: 'EVIDENCE_REQUEST',
    required: false,
    description: '查询时间窗口',
  }],
  canonicalOutputs: ['match_count', 'ps_id'],
  budget: {
    queryCount: 1,
    maxRows: 1,
    requestLimit: 2,
    timeoutMs: 45000,
    maxPointCount: null,
    intervalSeconds: null,
    seriesLimit: null,
    alignTime: null,
    disableSampling: null,
    timeZone: null,
  },
  route: {
    origin: 'DEPLOYMENT',
    platforms: ['guance'],
    explicitlyDisabled: false,
    updatedBy: null,
    reason: null,
    updatedAt: null,
  },
  binding: {
    status: 'READY_FOR_VALIDATION',
    bindingRef: 'csdp-message-send-log-search',
    lastObservedAt: null,
    detail: 'ready',
  },
  runnable: true,
  blockers: [],
}

const catalog: EvidenceQueryCatalog = {
  contractVersion: 'evidence-query-catalog.v1',
  workspaceId: 1,
  sources: [{
    platform: 'guance',
    status: 'READY',
    verified: false,
    endpointStatus: 'CONFIGURED',
    credentialStatus: 'CONFIGURED',
    supportedSignals: ['log_search'],
    detail: 'ready',
  }],
  systems: [{
    system: 'CSDP',
    modules: [{
      service: 'csdp-session-service',
      status: 'READY',
      runnableContracts: 1,
      blockers: [],
      acceptance: {
        status: 'NOT_ACCEPTED',
        currentBindingFingerprint: 'fingerprint',
        acceptedBy: null,
        acceptedAt: null,
        blockers: [],
      },
      contracts: [contract],
    }],
  }],
}

const deploymentAsset: ObservabilityAsset = {
  assetId: null,
  origin: 'DEPLOYMENT',
  workspaceId: 1,
  system: 'csdp',
  service: 'csdp-session-service',
  displayName: 'csdp-session-service',
  platform: 'guance',
  environment: null,
  region: null,
  cluster: null,
  namespace: null,
  enabled: true,
  signalBindings: { log_search: 'csdp-message-send-log-search' },
  parameters: {},
  version: 0,
  changedBy: null,
  reason: '随部署提供的兼容绑定',
  changedAt: null,
}

describe('evidence query catalog presentation', () => {
  it('summarizes systems, modules, contracts and runnable contracts', () => {
    expect(catalogSummary(catalog)).toEqual({
      systems: 1,
      modules: 1,
      contracts: 1,
      runnable: 1,
    })
  })

  it('searches developer language and technical contract identifiers', () => {
    expect(contractMatches(contract, '失败请求')).toBe(true)
    expect(contractMatches(contract, 'log_search')).toBe(true)
    expect(contractMatches(contract, 'csp-rpc-msg')).toBe(true)
    expect(contractMatches(contract, '数据库')).toBe(false)
  })

  it('uses plain-language labels for route and binding states', () => {
    expect(routeOriginLabel('WORKSPACE')).toBe('Workspace 声明')
    expect(routeOriginLabel('DEPLOYMENT')).toBe('部署默认')
    expect(routeOriginLabel('UNCONFIGURED')).toBe('未配置')
    expect(bindingStatusLabel('READY_FOR_VALIDATION')).toBe('可联调')
    expect(bindingStatusLabel('CANONICAL_RESULT_OBSERVED')).toBe('已观测到规范证据')
    expect(runtimeStateLabel('CONFIGURED')).toBe('已配置')
    expect(runtimeStateLabel('MISSING')).toBe('未配置')
  })

  it('lists setup modules from catalog and assets', () => {
    expect(listSetupModules(catalog, [deploymentAsset])).toEqual([
      expect.objectContaining({
        system: 'CSDP',
        service: 'csdp-session-service',
        asset: expect.objectContaining({ origin: 'DEPLOYMENT' }),
      }),
    ])
  })

  it('builds per-tool checklists for a module', () => {
    const tools = buildModuleToolSetups({
      options: [{
        contractRef: 'csdp-message-send-log-search',
        signalKind: 'log_search',
        scenario: '会话消息发送失败',
        question: '哪些失败请求需要继续追踪？',
        summary: '检索失败请求',
        requiredAssetParameters: [],
      }],
      module: catalog.systems[0].modules[0],
      asset: { ...deploymentAsset, origin: 'WORKSPACE', version: 1, environment: 'prd' },
      sourceReady: true,
    })
    expect(tools[0]).toMatchObject({
      signalKind: 'log_search',
      enabled: true,
      status: 'READY',
    })
    expect(tools[0].checklist.map(item => item.key)).toEqual([
      'enable', 'workspace', 'params', 'route', 'source', 'trial',
    ])
    expect(signalKindLabel('log_search')).toBe('日志检索')
  })

  it('tells operators the next action for a module', () => {
    expect(moduleNextAction(catalog.systems[0].modules[0], null, true)).toMatchObject({
      code: 'CONFIGURE_ASSET',
      primaryCta: 'asset',
    })
    expect(moduleNextAction(
      catalog.systems[0].modules[0],
      { ...deploymentAsset, origin: 'WORKSPACE', version: 1, environment: 'prd' },
      true,
    )).toMatchObject({
      code: 'ACCEPTANCE',
      primaryCta: 'acceptance',
    })
    expect(findModuleAsset([deploymentAsset], 'CSDP', 'csdp-session-service')?.origin)
      .toBe('DEPLOYMENT')
  })

  it('keeps approved catalog rules editable when the asset option projection is empty', () => {
    expect(mergeObservabilityAssetContractOptions([], [contract])).toEqual([{
      contractRef: 'csdp-message-send-log-search',
      signalKind: 'log_search',
      scenario: '会话消息发送失败',
      question: '哪些失败请求需要继续追踪？',
      summary: 'CSDP SendMsg 失败日志检索',
      requiredAssetParameters: [],
    }])
  })

  it('keeps a non-empty asset option projection authoritative', () => {
    const assetOption = {
      contractRef: 'asset-approved-rule',
      signalKind: 'service_health',
      scenario: '服务状态检查',
      question: '服务当前是否健康？',
      summary: '资产服务允许绑定的规则',
      requiredAssetParameters: ['service'],
    }

    expect(mergeObservabilityAssetContractOptions([assetOption], [contract]))
      .toEqual([assetOption])
  })

  it('keeps route priority explicit and leaves boundary moves unchanged', () => {
    expect(moveOrderedItem(['guance', 'recorded-replay'], 1, -1))
      .toEqual(['recorded-replay', 'guance'])
    expect(moveOrderedItem(['guance', 'recorded-replay'], 0, -1))
      .toEqual(['guance', 'recorded-replay'])
  })

  it('allows only runnable direct Guance queries without previous-evidence inputs', () => {
    expect(directTrialBlockReason(contract)).toBe('')
    expect(directTrialBlockReason({
      ...contract,
      signalKind: 'log_trace_bundle',
      parameters: [{
        name: 'ps_id',
        source: 'PREVIOUS_EVIDENCE',
        required: true,
        description: '由前一步失败日志提取',
      }],
    })).toContain('运行完整证据链')
    expect(directTrialBlockReason({ ...contract, runnable: false }))
      .toContain('当前不可运行')
    expect(directTrialBlockReason({
      ...contract,
      parameters: [{
        name: 'deployment',
        source: 'EVIDENCE_REQUEST_TARGET',
        required: true,
        description: '错误标记的资源参数',
      }],
    })).toContain('取证接入')
    expect(directTrialBlockReason(contract, deploymentAsset))
      .toContain('接管为 Workspace 模块配置')
  })

  it('explains every owner-provided field still missing before asset takeover', () => {
    expect(observabilityAssetDraftReadiness({
      system: 'csdp',
      service: 'csdp-session-service',
      displayName: 'CSDP 会话服务',
      environment: '',
      enabled: true,
      contractRefs: {
        k8s_workload_health: 'csdp-k8s-workload-health',
        monitor_event_scan: 'csdp-monitor-event-scan',
      },
      parameterValues: {
        namespace: '',
        deployment: '',
        monitor_checker: '',
      },
      requiredAssetParameters: ['namespace', 'deployment', 'monitor_checker'],
      reason: '',
    })).toEqual({
      ready: false,
      missing: [
        '环境',
        'Kubernetes Namespace',
        'Kubernetes Deployment',
        '观测云监控规则标识（monitor_checker）',
        '变更原因',
      ],
    })
  })

  it('allows takeover only after scope, rules, resource identifiers and reason are complete', () => {
    expect(observabilityAssetDraftReadiness({
      system: 'csdp',
      service: 'csdp-session-service',
      displayName: 'CSDP 会话服务',
      environment: 'test-environment',
      enabled: true,
      contractRefs: {
        k8s_workload_health: 'csdp-k8s-workload-health',
        monitor_event_scan: 'csdp-monitor-event-scan',
      },
      parameterValues: {
        namespace: 'test-namespace',
        deployment: 'test-deployment',
        monitor_checker: 'test-monitor-checker',
      },
      requiredAssetParameters: ['namespace', 'deployment', 'monitor_checker'],
      reason: '登记首个只读取证资产',
    })).toEqual({ ready: true, missing: [] })
  })

  it('treats a cleared query-rule selection as unbound instead of crashing', () => {
    expect(observabilityAssetDraftReadiness({
      system: 'csdp',
      service: 'csdp-session-service',
      displayName: 'CSDP 会话服务',
      environment: 'prd',
      enabled: true,
      contractRefs: {
        log_search: 'csdp-message-send-log-search',
        error_log_scan: undefined,
      },
      parameterValues: {},
      requiredAssetParameters: [],
      reason: '接入 CSDP SendMsg 首条真实只读取证链路',
    })).toEqual({ ready: true, missing: [] })
  })
})
