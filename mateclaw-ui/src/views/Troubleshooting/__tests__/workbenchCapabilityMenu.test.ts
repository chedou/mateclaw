import { describe, expect, it } from 'vitest'
import {
  EVIDENCE_CATALOG_DESTINATIONS,
  WORKBENCH_CAPABILITY_GROUPS,
  evidenceCatalogLocation,
  legacyEvidenceSynthesisLocation,
  normalizeEvidenceCatalogTab,
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
    expect(items.find(item => item.command === 'evidence-catalog')).toBeUndefined()
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

  it('keeps catalog as a secondary deep link while setup is the primary entry', () => {
    expect(EVIDENCE_CATALOG_DESTINATIONS).toEqual([])

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
