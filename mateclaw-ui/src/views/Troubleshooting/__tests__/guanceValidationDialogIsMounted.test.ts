import { describe, expect, it } from 'vitest'
import formalWorkbenchSource from '../FormalWorkbench.vue?raw'
import guanceValidationDialogSource from '../GuanceValidationDialog.vue?raw'

describe('the Guance validation dialog boundary', () => {
  it('remains mounted by the formal workbench', () => {
    expect(formalWorkbenchSource)
      .toContain("import GuanceValidationDialog from './GuanceValidationDialog.vue'")
    expect(formalWorkbenchSource).toMatch(/<GuanceValidationDialog[\s>]/)
  })

  it('emits commands instead of calling the observability API itself', () => {
    expect(guanceValidationDialogSource).toContain("$emit('validate')")
    expect(guanceValidationDialogSource).toContain("$emit('preview-spine')")
    expect(guanceValidationDialogSource).toContain("$emit('accept')")
    expect(guanceValidationDialogSource).not.toContain('troubleshootingApi')
  })
})
