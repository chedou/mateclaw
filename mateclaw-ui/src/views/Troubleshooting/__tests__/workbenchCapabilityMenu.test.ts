import { describe, expect, it } from 'vitest'
import {
  EVIDENCE_CATALOG_DESTINATIONS,
  WORKBENCH_CAPABILITY_GROUPS,
  evidenceCatalogLocation,
  normalizeEvidenceCatalogTab,
  safeTroubleshootingReturnPath,
} from '../workbenchCapabilityMenu'

describe('workbench capability panel information architecture', () => {
  it('groups all six management capabilities and keeps evidence catalog expandable', () => {
    const items = WORKBENCH_CAPABILITY_GROUPS.flatMap(group => group.items)

    expect(items.map(item => item.command)).toEqual([
      'playbooks',
      'evidence-catalog',
      'guance',
      'synthesis',
      'ledger',
      'case-knowledge',
    ])
    expect(items.find(item => item.command === 'evidence-catalog')?.expandable).toBe(true)
    expect(items.find(item => item.command === 'guance')?.label).toBe('观测云接入与验收')
  })

  it('offers a stable deep link for each evidence catalog workspace', () => {
    expect(EVIDENCE_CATALOG_DESTINATIONS.map(item => item.tab)).toEqual([
      'systems',
      'contracts',
      'routes',
      'acceptance',
    ])

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
    expect(safeTroubleshootingReturnPath('/troubleshooting-other')).toBeNull()
  })
})
