import { describe, expect, it } from 'vitest'
import {
  WORKBENCH_CAPABILITY_GROUPS,
  legacyEvidenceSynthesisLocation,
  normalizeWorkbenchOverlayCapability,
  safeTroubleshootingReturnPath,
  workbenchOverlayLocation,
} from '../workbenchCapabilityMenu'

describe('troubleshooting secondary navigation information architecture', () => {
  it('keeps data-source validation inside the evidence setup navigation', () => {
    const items = WORKBENCH_CAPABILITY_GROUPS.flatMap(group => group.items)

    expect(WORKBENCH_CAPABILITY_GROUPS.map(group => group.label)).toEqual([
      '配置与接入',
      '复盘与沉淀',
    ])
    expect(items.map(item => item.command)).toEqual([
      'observability-assets',
      'playbooks',
      'ledger',
      'case-knowledge',
    ])
    expect(items.find(item => item.command === 'guance')).toBeUndefined()
  })

  it('redirects the legacy synthesis deep link into diagnosis evaluation', () => {
    expect(legacyEvidenceSynthesisLocation(
      '/troubleshooting?view=detail&diagnosisId=diag-1',
    )).toEqual({
      path: '/troubleshooting',
      query: {
        view: 'detail',
        diagnosisId: 'diag-1',
        capability: 'ledger',
        focus: 'evidence-synthesis',
      },
    })
  })

  it('no longer offers a way to construct a link into the retired catalog page', async () => {
    // 「查询规则说明书」和取证接入调的是同一组接口、渲染同一份数据，只是一个能改
    // 一个只能看，于是配一条规则要在两页之间来回跳。规格已折进取证接入页。
    //
    // 这条断言钉的是「没有人能再造出指向它的链接」——路由里保留的重定向是给旧书签
    // 兜底的，不是给代码继续用的。留着一个 location 构造器，跳转迟早会长回来。
    const menu = await import('../workbenchCapabilityMenu') as Record<string, unknown>

    expect(menu.evidenceCatalogLocation).toBeUndefined()
    expect(menu.normalizeEvidenceCatalogTab).toBeUndefined()
    expect(menu.EVIDENCE_CATALOG_DESTINATIONS).toBeUndefined()
  })

  it('only accepts a local troubleshooting page as the return target', () => {
    expect(safeTroubleshootingReturnPath('/troubleshooting?view=list')).toBe(
      '/troubleshooting?view=list',
    )
    expect(safeTroubleshootingReturnPath('https://example.com')).toBeNull()
    expect(safeTroubleshootingReturnPath('//example.com/troubleshooting')).toBeNull()
    expect(safeTroubleshootingReturnPath('/troubleshooting/evidence-catalog?tab=routes')).toBeNull()
    expect(safeTroubleshootingReturnPath('/troubleshooting/sops')).toBeNull()
    expect(safeTroubleshootingReturnPath('/troubleshooting-other')).toBeNull()
  })

  it('keeps the legacy Guance overlay deep link only as a compatibility action', () => {
    expect(normalizeWorkbenchOverlayCapability('guance')).toBe('guance')
    expect(normalizeWorkbenchOverlayCapability(['ledger'])).toBe('ledger')
    expect(normalizeWorkbenchOverlayCapability('case-knowledge')).toBe('case-knowledge')
    expect(normalizeWorkbenchOverlayCapability('synthesis')).toBeNull()
    expect(normalizeWorkbenchOverlayCapability('unknown')).toBeNull()
  })

  it('returns from a management page to the same diagnosis before opening an overlay', () => {
    expect(workbenchOverlayLocation(
      'ledger',
      '/troubleshooting?view=detail&diagnosisId=diag-1',
    )).toEqual({
      path: '/troubleshooting',
      query: {
        view: 'detail',
        diagnosisId: 'diag-1',
        capability: 'ledger',
      },
    })

    expect(workbenchOverlayLocation('guance', '/troubleshooting/sops')).toEqual({
      path: '/troubleshooting',
      query: {
        view: 'list',
        capability: 'guance',
      },
    })
  })
})
