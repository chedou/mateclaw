package vip.mate.troubleshooting.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.audit.service.AuditEventService;
import vip.mate.common.net.SsrfProperties;
import vip.mate.system.service.SettingCrypto;
import vip.mate.troubleshooting.agent.TroubleshootingAgentProperties;
import vip.mate.troubleshooting.model.TroubleshootingEvidenceSettingsEntity;
import vip.mate.troubleshooting.repository.TroubleshootingEvidenceSettingsMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Moving the Guance credential out of yml and into a row an owner can edit
 * removes three protections the yml placement gave for free: the key was
 * unreadable from the product, the endpoint was chosen by whoever could deploy,
 * and no concurrent writer existed. These tests pin the replacements.
 */
class WorkspaceEvidenceSettingsServiceTest {

    private static final long WORKSPACE_ID = 42L;
    /** RFC 2606 reserves .invalid, so this never resolves and never leaves the box. */
    private static final String ENDPOINT = "https://guance.example.invalid";

    private TroubleshootingEvidenceSettingsMapper mapper;
    private EvidenceProperties deploymentDefaults;
    private TroubleshootingAgentProperties agentDefaults;
    private SettingCrypto crypto;
    private SsrfProperties ssrfProperties;
    private AuditEventService auditEvents;
    private WorkspaceEvidenceSettingsService service;

    @BeforeEach
    void setUp() {
        mapper = mock(TroubleshootingEvidenceSettingsMapper.class);
        deploymentDefaults = new EvidenceProperties();
        agentDefaults = new TroubleshootingAgentProperties();
        crypto = new SettingCrypto("unit-test-key");
        ssrfProperties = new SsrfProperties();
        auditEvents = mock(AuditEventService.class);
        service = new WorkspaceEvidenceSettingsService(
                mapper, deploymentDefaults, agentDefaults, crypto,
                ssrfProperties, auditEvents, new ObjectMapper());
    }

    private EvidenceSettingsUpdate enabledUpdate(String baseUrl, String apiKey, int expectedVersion) {
        return new EvidenceSettingsUpdate(
                true, baseUrl, apiKey, false, false, false, expectedVersion, "pilot onboarding");
    }

    private TroubleshootingEvidenceSettingsEntity storedRow(String encryptedKey, int version) {
        TroubleshootingEvidenceSettingsEntity row = new TroubleshootingEvidenceSettingsEntity();
        row.setWorkspaceId(WORKSPACE_ID);
        row.setGuanceEnabled(true);
        row.setGuanceBaseUrl(ENDPOINT);
        row.setGuanceApiKey(encryptedKey);
        row.setGuanceAllowInsecureHttp(false);
        row.setReplayEnabled(false);
        row.setAgentEnabled(false);
        row.setVersion(version);
        return row;
    }

    // ---- fallback: an install that predates this table must not change ----

    @Test
    void aWorkspaceWithNoRowStillInheritsTheDeploymentConfiguration() {
        when(mapper.findByWorkspace(WORKSPACE_ID)).thenReturn(null);
        deploymentDefaults.getGuance().setEnabled(true);
        deploymentDefaults.getGuance().setBaseUrl(ENDPOINT);
        deploymentDefaults.getGuance().setApiKey("from-yml");
        deploymentDefaults.getRecordedReplay().setEnabled(true);
        agentDefaults.setEnabled(true);

        EffectiveEvidenceSettings effective = service.effective(WORKSPACE_ID);

        assertThat(effective.origin()).isEqualTo(EffectiveEvidenceSettings.Origin.DEPLOYMENT);
        assertThat(effective.guanceEnabled()).isTrue();
        assertThat(effective.guanceApiKey()).isEqualTo("from-yml");
        assertThat(effective.replayEnabled()).isTrue();
        assertThat(effective.agentEnabled()).isTrue();
        assertThat(effective.guanceCallable()).isTrue();
    }

    @Test
    void aWorkspaceRowOverridesTheDeploymentConfiguration() {
        deploymentDefaults.getGuance().setEnabled(false);
        when(mapper.findByWorkspace(WORKSPACE_ID))
                .thenReturn(storedRow(crypto.encrypt("workspace-key"), 3));

        EffectiveEvidenceSettings effective = service.effective(WORKSPACE_ID);

        assertThat(effective.origin()).isEqualTo(EffectiveEvidenceSettings.Origin.WORKSPACE);
        assertThat(effective.guanceEnabled()).isTrue();
        assertThat(effective.guanceApiKey()).isEqualTo("workspace-key");
    }

    // ---- the credential ----

    @Test
    void theStoredCredentialIsEncryptedRatherThanWrittenAsTyped() {
        when(mapper.findByWorkspace(WORKSPACE_ID)).thenReturn(null);

        service.save(WORKSPACE_ID, enabledUpdate(ENDPOINT, "super-secret-key", 0), "owner@example.com");

        ArgumentCaptor<TroubleshootingEvidenceSettingsEntity> saved =
                ArgumentCaptor.forClass(TroubleshootingEvidenceSettingsEntity.class);
        verify(mapper).insert(saved.capture());
        String stored = saved.getValue().getGuanceApiKey();
        assertThat(stored).isNotNull().doesNotContain("super-secret-key");
        assertThat(crypto.isEncrypted(stored)).isTrue();
        assertThat(crypto.decrypt(stored)).isEqualTo("super-secret-key");
    }

    @Test
    void theCredentialIsNeverReturnedToTheBrowserEvenToTheOwnerWhoTypedIt() {
        when(mapper.findByWorkspace(WORKSPACE_ID))
                .thenReturn(storedRow(crypto.encrypt("super-secret-key"), 1));

        EvidenceSettingsView view = service.view(WORKSPACE_ID);

        assertThat(view.guanceApiKeyPresent()).isTrue();
        assertThat(view.guanceApiKeyMask()).isEqualTo("****-key");
        assertThat(view.toString()).doesNotContain("super-secret-key");
    }

    @Test
    void omittingTheKeyKeepsTheStoredOneSoTheUrlCanBeEditedWithoutRetypingIt() {
        String encrypted = crypto.encrypt("existing-key");
        when(mapper.findByWorkspace(WORKSPACE_ID)).thenReturn(storedRow(encrypted, 1));
        when(mapper.updateIfVersionMatches(any(), anyInt(), anyInt())).thenReturn(1);

        service.save(WORKSPACE_ID, enabledUpdate(ENDPOINT, null, 1), "owner@example.com");

        ArgumentCaptor<TroubleshootingEvidenceSettingsEntity> saved =
                ArgumentCaptor.forClass(TroubleshootingEvidenceSettingsEntity.class);
        verify(mapper).updateIfVersionMatches(saved.capture(), eq(1), eq(2));
        assertThat(saved.getValue().getGuanceApiKey()).isEqualTo(encrypted);
    }

    @Test
    void anEmptyKeyClearsTheCredentialRatherThanBeingTreatedAsUnchanged() {
        when(mapper.findByWorkspace(WORKSPACE_ID))
                .thenReturn(storedRow(crypto.encrypt("existing-key"), 1));
        when(mapper.updateIfVersionMatches(any(), anyInt(), anyInt())).thenReturn(1);
        EvidenceSettingsUpdate clearing = new EvidenceSettingsUpdate(
                false, ENDPOINT, "", false, false, false, 1, "rotate out");

        service.save(WORKSPACE_ID, clearing, "owner@example.com");

        ArgumentCaptor<TroubleshootingEvidenceSettingsEntity> saved =
                ArgumentCaptor.forClass(TroubleshootingEvidenceSettingsEntity.class);
        verify(mapper).updateIfVersionMatches(saved.capture(), anyInt(), anyInt());
        assertThat(saved.getValue().getGuanceApiKey()).isNull();
    }

    @Test
    void aCredentialThatCannotBeDecryptedLeavesTheSourceUnusableInsteadOfThrowing() {
        // What a rotated MATECLAW_SETTING_KEY looks like from here.
        when(mapper.findByWorkspace(WORKSPACE_ID))
                .thenReturn(storedRow("enc:v1:not-a-real-envelope", 1));

        EffectiveEvidenceSettings effective = service.effective(WORKSPACE_ID);

        assertThat(effective.guanceApiKey()).isNull();
        assertThat(effective.guanceCallable())
                .as("an unreadable key must not read as a usable source")
                .isFalse();
    }

    @Test
    void askingWhetherASourceIsOnDoesNotReachTheStoredCredential() {
        // Readiness inspection reports NOT_INSPECTED until the asset scope is
        // authorized, and callers ask this type for enablement long before they
        // are entitled to the key. Resolving it eagerly would quietly break that
        // ordering, so the credential must stay untouched until asked for.
        AtomicInteger reads = new AtomicInteger();
        SettingCrypto counting = new SettingCrypto("unit-test-key") {
            @Override
            public String decrypt(String value) {
                reads.incrementAndGet();
                return super.decrypt(value);
            }
        };
        WorkspaceEvidenceSettingsService lazyService = new WorkspaceEvidenceSettingsService(
                mapper, deploymentDefaults, agentDefaults, counting,
                ssrfProperties, auditEvents, new ObjectMapper());
        when(mapper.findByWorkspace(WORKSPACE_ID))
                .thenReturn(storedRow(counting.encrypt("live-key"), 1));

        EffectiveEvidenceSettings effective = lazyService.effective(WORKSPACE_ID);
        assertThat(effective.guanceEnabled()).isTrue();
        assertThat(effective.guanceBaseUrl()).isNotBlank();

        assertThat(reads.get())
                .as("enablement and endpoint must not decrypt the credential")
                .isZero();

        assertThat(effective.guanceApiKey()).isEqualTo("live-key");
        assertThat(reads.get()).isEqualTo(1);
    }

    // ---- the endpoint: SSRF is the new exposure this change creates ----

    @Test
    void anOwnerCannotPointEvidenceCollectionAtLoopback() {
        when(mapper.findByWorkspace(WORKSPACE_ID)).thenReturn(null);

        assertThatThrownBy(() ->
                service.save(WORKSPACE_ID, enabledUpdate("https://127.0.0.1:9529", "k", 0), "owner"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("SSRF blocked");
        verify(mapper, never()).insert(any(TroubleshootingEvidenceSettingsEntity.class));
    }

    @Test
    void theCloudMetadataEndpointStaysBlockedEvenWhenTheDeploymentAllowlistedIt() {
        when(mapper.findByWorkspace(WORKSPACE_ID)).thenReturn(null);
        ssrfProperties.setSsrfAllowlist(List.of("169.254.169.254"));

        assertThatThrownBy(() -> service.save(
                WORKSPACE_ID, enabledUpdate("https://169.254.169.254/", "k", 0), "owner"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("metadata");
    }

    @Test
    void anOnPremGuanceOnAPrivateAddressWorksOnceTheDeploymentAllowlistsThatHost() {
        when(mapper.findByWorkspace(WORKSPACE_ID)).thenReturn(null);
        ssrfProperties.setSsrfAllowlist(List.of("10.0.0.9"));

        service.save(WORKSPACE_ID, enabledUpdate("https://10.0.0.9:9529", "k", 0), "owner");

        verify(mapper).insert(any(TroubleshootingEvidenceSettingsEntity.class));
    }

    @Test
    void theEndpointIsValidatedAgainAtCallTimeBecauseDnsCanChangeAfterTheWriteSucceeded() {
        assertThatThrownBy(() -> service.assertReachableEndpoint("https://127.0.0.1:9529"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void formalEndpointValidationFailsClosedWhenDnsCannotBeResolved() {
        assertThatCode(() -> service.assertReachableEndpoint(
                        "https://formal-guance-does-not-resolve.invalid"))
                .as("legacy rehearsal validation keeps its historical fail-open behavior")
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> service.assertReachableEndpointStrict(
                        "https://formal-guance-does-not-resolve.invalid"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("DNS");
    }

    @Test
    void plainHttpIsRejectedUnlessItWasExplicitlyAllowed() {
        when(mapper.findByWorkspace(WORKSPACE_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.save(
                WORKSPACE_ID, enabledUpdate("http://guance.example.invalid", "k", 0), "owner"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");

        EvidenceSettingsUpdate allowed = new EvidenceSettingsUpdate(
                true, "http://guance.example.invalid", "k", true, false, false, 0, null);
        service.save(WORKSPACE_ID, allowed, "owner");
        verify(mapper).insert(any(TroubleshootingEvidenceSettingsEntity.class));
    }

    @Test
    void aTrailingSlashIsNormalizedRatherThanDoubledAgainstTheQueryPath() {
        when(mapper.findByWorkspace(WORKSPACE_ID)).thenReturn(null);

        service.save(WORKSPACE_ID, enabledUpdate(ENDPOINT + "///", "k", 0), "owner");

        ArgumentCaptor<TroubleshootingEvidenceSettingsEntity> saved =
                ArgumentCaptor.forClass(TroubleshootingEvidenceSettingsEntity.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getGuanceBaseUrl()).isEqualTo(ENDPOINT);
    }

    // ---- refusing to enable a source that cannot work ----

    @Test
    void guanceCannotBeSwitchedOnWithoutAnEndpoint() {
        when(mapper.findByWorkspace(WORKSPACE_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.save(WORKSPACE_ID, enabledUpdate(null, "k", 0), "owner"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base URL");
    }

    @Test
    void guanceCannotBeSwitchedOnWithoutACredential() {
        when(mapper.findByWorkspace(WORKSPACE_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.save(WORKSPACE_ID, enabledUpdate(ENDPOINT, null, 0), "owner"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("API key");
    }

    // ---- concurrent owners ----

    @Test
    void aConcurrentEditIsRejectedRatherThanOverwritingTheOtherOwnersCredential() {
        when(mapper.findByWorkspace(WORKSPACE_ID))
                .thenReturn(storedRow(crypto.encrypt("existing"), 5));
        when(mapper.updateIfVersionMatches(any(), anyInt(), anyInt())).thenReturn(0);

        assertThatThrownBy(() ->
                service.save(WORKSPACE_ID, enabledUpdate(ENDPOINT, "mine", 4), "second-owner"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reload");
    }

    @Test
    void claimingAVersionOnAWorkspaceThatHasNoRowIsRefused() {
        when(mapper.findByWorkspace(WORKSPACE_ID)).thenReturn(null);

        assertThatThrownBy(() ->
                service.save(WORKSPACE_ID, enabledUpdate(ENDPOINT, "k", 7), "owner"))
                .isInstanceOf(IllegalStateException.class);
        verify(mapper, never()).insert(any(TroubleshootingEvidenceSettingsEntity.class));
    }

    // ---- audit ----

    @Test
    void theAuditRecordSaysTheKeyChangedWithoutRecordingTheKey() {
        when(mapper.findByWorkspace(WORKSPACE_ID)).thenReturn(null);

        service.save(WORKSPACE_ID, enabledUpdate(ENDPOINT, "super-secret-key", 0), "owner@example.com");

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditEvents).record(
                eq("CREATE"), eq("TROUBLESHOOTING_EVIDENCE_SETTINGS"), anyString(),
                anyString(), detail.capture(), eq(WORKSPACE_ID));
        assertThat(detail.getValue())
                .contains("\"apiKeyChanged\":true")
                .contains("\"apiKeyPresent\":true")
                .doesNotContain("super-secret-key");
    }

    @Test
    void aRejectedSubmissionIsNotAudited() {
        when(mapper.findByWorkspace(WORKSPACE_ID)).thenReturn(null);

        assertThatThrownBy(() ->
                service.save(WORKSPACE_ID, enabledUpdate("https://127.0.0.1", "k", 0), "owner"))
                .isInstanceOf(SecurityException.class);

        verify(auditEvents, never()).record(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyLong());
    }
}
