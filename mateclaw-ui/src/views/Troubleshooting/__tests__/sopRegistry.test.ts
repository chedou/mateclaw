import { describe, expect, it } from 'vitest'
import type { SopSummary } from '@/api'
import { findScopedSopSummary, nextSopStatus, parseCandidateSopJson } from '../sopRegistry'

const base = {
  sopId: 'sop-903001-v2',
  system: 'CSDP',
  errorCode: '903001',
  service: 'order-svc',
  title: '订单服务 Mongo 连接池耗尽',
}

describe('parseCandidateSopJson', () => {
  it('normalizes a new draft into the only legal initial state', () => {
    const parsed = parseCandidateSopJson(JSON.stringify(base))

    expect(parsed).toMatchObject({
      ...base,
      contractVersion: 'sop.v1',
      status: 'candidate',
      verified: false,
    })
    expect(parsed.evidenceRequests).toEqual([])
    expect(parsed.anomalyCriteria).toEqual([])
    expect(parsed.diagnosisRules).toEqual([])
    expect(parsed.actions).toEqual([])
  })

  it('rejects payloads that try to bypass review', () => {
    expect(() => parseCandidateSopJson(JSON.stringify({
      ...base,
      status: 'approved',
      verified: true,
    }))).toThrow(/candidate/)
    expect(() => parseCandidateSopJson(JSON.stringify({
      ...base,
      status: 'candidate',
      verified: true,
    }))).toThrow(/verified=false/)
  })

  it('rejects arrays, missing route fields, and non-object contract items', () => {
    expect(() => parseCandidateSopJson('[]')).toThrow(/单个 SOP 对象/)
    expect(() => parseCandidateSopJson(JSON.stringify({ ...base, errorCode: '' })))
      .toThrow(/errorCode/)
    expect(() => parseCandidateSopJson(JSON.stringify({
      ...base,
      evidenceRequests: ['not-an-object'],
    }))).toThrow(/evidenceRequests/)
  })
})

describe('nextSopStatus', () => {
  it('only permits the forward review lifecycle', () => {
    expect(nextSopStatus('candidate')).toBe('approved')
    expect(nextSopStatus('approved')).toBe('deprecated')
    expect(nextSopStatus('deprecated')).toBeNull()
  })
})

describe('findScopedSopSummary', () => {
  it('selects only the exact system and service requested by an onboarding deep link', () => {
    const rows = [
      { ...base, routeKey: 'csdp:903001', operational: true },
      { ...base, routeKey: 'csdp:904003', errorCode: '904003', service: 'csdp-wechat', operational: true },
    ] as unknown as SopSummary[]

    expect(findScopedSopSummary(rows, 'csdp', 'CSDP-WECHAT')?.routeKey).toBe('csdp:904003')
    expect(findScopedSopSummary(rows, 'CSDP', 'missing-service')).toBeNull()
  })
})
