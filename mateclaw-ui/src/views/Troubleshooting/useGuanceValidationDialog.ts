import { reactive, ref } from 'vue'
import type {
  EvidenceChainPreviewRequest,
  GuanceEvidenceAcceptanceChecklist,
  GuanceEvidenceAcceptanceView,
  GuanceEvidenceSpinePreview,
  GuanceEvidenceValidationReport,
} from '@/api'
import {
  normalizeEvidenceChainPreviewRequest,
  type GuanceValidationOrigin,
  type GuanceValidationSessionSnapshot,
} from './guanceOnboarding'

export const EMPTY_T7_CHECKLIST: GuanceEvidenceAcceptanceChecklist = {
  measurementAndFieldsVerified: false,
  indexVerified: false,
  psIdJoinVerified: false,
  timestampUnitVerified: false,
  timeWindowVerified: false,
  dqlLatencyReviewed: false,
  legacyRouteConflictReviewed: false,
}

export function useGuanceValidationDialog() {
  const open = ref(false)
  const validationLoading = ref(false)
  const spinePreviewLoading = ref(false)
  const acceptanceLoading = ref(false)
  const report = ref<GuanceEvidenceValidationReport | null>(null)
  const spinePreview = ref<GuanceEvidenceSpinePreview | null>(null)
  const ownerAcceptance = ref<GuanceEvidenceAcceptanceView | null>(null)
  const origin = ref<GuanceValidationOrigin | null>(null)
  const form = reactive<EvidenceChainPreviewRequest>({
    system: '',
    service: '',
    searchTerm: '',
    window: '-15m',
    occurredAt: null,
  })
  const checklist = reactive<GuanceEvidenceAcceptanceChecklist>({
    ...EMPTY_T7_CHECKLIST,
  })

  function begin(
    request: EvidenceChainPreviewRequest,
    currentOwnerAcceptance: GuanceEvidenceAcceptanceView | null,
    validationOrigin: GuanceValidationOrigin,
  ) {
    Object.assign(form, normalizeEvidenceChainPreviewRequest(request))
    report.value = null
    spinePreview.value = null
    ownerAcceptance.value = currentOwnerAcceptance
    origin.value = validationOrigin
    validationLoading.value = false
    spinePreviewLoading.value = false
    acceptanceLoading.value = false
    Object.assign(checklist, EMPTY_T7_CHECKLIST)
    open.value = true
  }

  function capture(sessionVersion: number): GuanceValidationSessionSnapshot {
    return {
      sessionVersion,
      origin: origin.value,
      request: normalizeEvidenceChainPreviewRequest(form),
    }
  }

  return {
    open,
    validationLoading,
    spinePreviewLoading,
    acceptanceLoading,
    report,
    spinePreview,
    ownerAcceptance,
    origin,
    form,
    checklist,
    begin,
    capture,
  }
}
