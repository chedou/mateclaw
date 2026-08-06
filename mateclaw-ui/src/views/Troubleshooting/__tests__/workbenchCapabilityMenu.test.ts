import { describe, expect, it } from 'vitest'
import {
  EVIDENCE_CATALOG_DESTINATIONS,
  WORKBENCH_CAPABILITY_GROUPS,
  evidenceCatalogLocation,
  normalizeEvidenceCatalogTab,
  normalizeWorkbenchOverlayCapability,
  safeTroubleshootingReturnPath,
  workbenchOverlayLocation,
} from '../workbenchCapabilityMenu'

describe('troubleshooting secondary navigation information architecture', () => {
  it('keeps data-source validation inside the evidence catalog navigation', () => {
    const items = WORKBENCH_CAPABILITY_GROUPS.flatMap(group => group.items)

    expect(WORKBENCH_CAPABILITY_GROUPS.map(group => group.label)).toEqual([
      '配置与接入',
      '验证与演练',
      '复盘与沉淀',
    ])
    expect(items.map(item => item.command)).toEqual([
      'playbooks',
      'evidence-catalog',
      'synthesis',
      'ledger',
      'case-knowledge',
    ])
    expect(items.find(item => item.command === 'guance')).toBeUndefined()
  })

  it('offers a stable deep link for each evidence catalog workspace', () => {
    expect(EVIDENCE_CATALOG_DESTINATIONS.map(item => item.tab)).toEqual([
      'systems',
      'assets',
      'contracts',
      'routes',
      'acceptance',
    ])
    expect(EVIDENCE_CATALOG_DESTINATIONS.find(item => item.tab === 'acceptance')?.label)
      .toBe('数据源联调')

    expect(evidenceCatalogLocation(
      'contracts',
      '/troubleshooting?view=detail&diagnosisId=diag-1',
    )).toEqual({
      path: '/troubleshooting/evidence-catalog',
      query: {
        tab: 'contracts',
        returnTo: '/troubleshooting?view=detail&diagnosisId=diag-1',
      },
    })
  })

  it('normalizes invalid tab values without trusting arbitrary query values', () => {
    expect(normalizeEvidenceCatalogTab('routes')).toBe('routes')
    expect(normalizeEvidenceCatalogTab('assets')).toBe('assets')
    expect(normalizeEvidenceCatalogTab(['acceptance'])).toBe('acceptance')
    expect(normalizeEvidenceCatalogTab('unknown')).toBe('systems')
    expect(normalizeEvidenceCatalogTab(null)).toBe('systems')
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
