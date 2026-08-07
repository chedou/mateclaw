import { describe, expect, it } from 'vitest'
import compatibilitySource from '../troubleshooting.ts?raw'
import clientSource from '../troubleshooting-client.ts?raw'
import contractSource from '../troubleshooting-contracts.ts?raw'

describe('the troubleshooting frontend API boundary', () => {
  it('keeps domain contracts transport-free', () => {
    expect(contractSource).toContain('export interface StoredDiagnosis')
    expect(contractSource).not.toContain("from 'axios'")
    expect(contractSource).not.toContain('createTroubleshootingApi')
    expect(contractSource).not.toMatch(/\bhttp\.(get|post|put|delete)\b/)
  })

  it('keeps transport construction in the client module', () => {
    expect(clientSource).toContain("import type { AxiosInstance } from 'axios'")
    expect(clientSource).toContain("from './troubleshooting-contracts'")
    expect(clientSource).toContain('export const createTroubleshootingApi')
    expect(clientSource).not.toMatch(/^export interface /m)
  })

  it('preserves the original public import surface', () => {
    expect(compatibilitySource).toContain("export * from './troubleshooting-contracts'")
    expect(compatibilitySource).toContain("export { createTroubleshootingApi } from './troubleshooting-client'")
    expect(compatibilitySource).toContain("export type { TroubleshootingApi } from './troubleshooting-client'")
  })
})
