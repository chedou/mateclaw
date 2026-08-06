import { describe, expect, it } from 'vitest'
import type { EvidenceQueryCatalog, EvidenceQueryContract } from '@/api'
import {
  bindingStatusLabel,
  catalogSummary,
  contractMatches,
  directTrialBlockReason,
  moveOrderedItem,
  routeOriginLabel,
  runtimeStateLabel,
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
    })).toContain('系统观测资产')
  })
})
