package vip.mate.troubleshooting.persistence;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.troubleshooting.evidence.GuanceEvidenceAcceptance;
import vip.mate.troubleshooting.evidence.GuanceEvidenceAcceptanceStore;
import vip.mate.troubleshooting.evidence.MybatisGuanceEvidenceAcceptanceStore;
import vip.mate.troubleshooting.model.TroubleshootingGuanceEvidenceAcceptanceEntity;
import vip.mate.troubleshooting.repository.TroubleshootingGuanceEvidenceAcceptanceMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MybatisGuanceEvidenceAcceptanceStoreTest {

    private static final Instant NOW = Instant.parse("2026-07-29T08:00:00Z");
    private static final String SCOPE = "a".repeat(64);

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(
                assistant,
                TroubleshootingGuanceEvidenceAcceptanceEntity.class);
    }

    @Test
    void roundTripsAnImmutableSecretFreeAcceptanceAndReusesTheFingerprint() {
        TroubleshootingGuanceEvidenceAcceptanceMapper mapper =
                mock(TroubleshootingGuanceEvidenceAcceptanceMapper.class);
        AtomicReference<TroubleshootingGuanceEvidenceAcceptanceEntity> row =
                new AtomicReference<>();
        when(mapper.selectOne(any())).thenAnswer(invocation -> row.get());
        when(mapper.insert(any(
                TroubleshootingGuanceEvidenceAcceptanceEntity.class)))
                .thenAnswer(invocation -> {
                    TroubleshootingGuanceEvidenceAcceptanceEntity entity =
                            invocation.getArgument(0);
                    entity.setId(1L);
                    row.set(entity);
                    return 1;
                });
        MybatisGuanceEvidenceAcceptanceStore store = store(mapper);

        GuanceEvidenceAcceptanceStore.StoredAcceptance first =
                store.saveOrGet(7L, SCOPE, acceptance());
        GuanceEvidenceAcceptanceStore.StoredAcceptance retry =
                store.saveOrGet(7L, SCOPE, acceptance());

        assertThat(first.created()).isTrue();
        assertThat(retry.created()).isFalse();
        assertThat(retry.acceptance()).isEqualTo(first.acceptance());
        assertThat(row.get().getBindingFingerprint()).isEqualTo("b".repeat(64));
        assertThat(row.get().getAggregateJson())
                .doesNotContain(
                        "ps-message-001",
                        "message_send_failed",
                        "runtime-secret",
                        "L::logs");
        verify(mapper, times(1)).insert(any(
                TroubleshootingGuanceEvidenceAcceptanceEntity.class));
    }

    @Test
    void returnsTheWinnerAfterAUniqueFingerprintRace() {
        TroubleshootingGuanceEvidenceAcceptanceMapper mapper =
                mock(TroubleshootingGuanceEvidenceAcceptanceMapper.class);
        AtomicReference<TroubleshootingGuanceEvidenceAcceptanceEntity> winner =
                new AtomicReference<>();
        when(mapper.selectOne(any()))
                .thenReturn(null)
                .thenAnswer(invocation -> winner.get());
        when(mapper.insert(any(
                TroubleshootingGuanceEvidenceAcceptanceEntity.class)))
                .thenAnswer(invocation -> {
                    GuanceEvidenceAcceptance acceptance = acceptance();
                    ObjectMapper objectMapper =
                            new ObjectMapper().findAndRegisterModules();
                    TroubleshootingGuanceEvidenceAcceptanceEntity entity =
                            invocation.getArgument(0);
                    entity.setAggregateJson(
                            objectMapper.writeValueAsString(acceptance));
                    winner.set(entity);
                    throw new DataIntegrityViolationException(
                            "simulated unique-key race");
                });

        GuanceEvidenceAcceptanceStore.StoredAcceptance result =
                store(mapper).saveOrGet(7L, SCOPE, acceptance());

        assertThat(result.created()).isFalse();
        assertThat(result.acceptance()).isEqualTo(acceptance());
    }

    @Test
    void duplicateRecoveryRunsOutsideAnAbortedDatabaseTransaction()
            throws Exception {
        Transactional boundary = MybatisGuanceEvidenceAcceptanceStore.class
                .getMethod(
                        "saveOrGet",
                        long.class,
                        String.class,
                        GuanceEvidenceAcceptance.class)
                .getAnnotation(Transactional.class);

        assertThat(boundary)
                .isNotNull()
                .extracting(Transactional::propagation)
                .isEqualTo(Propagation.NOT_SUPPORTED);
    }

    private MybatisGuanceEvidenceAcceptanceStore store(
            TroubleshootingGuanceEvidenceAcceptanceMapper mapper) {
        return new MybatisGuanceEvidenceAcceptanceStore(
                mapper, new ObjectMapper().findAndRegisterModules());
    }

    private GuanceEvidenceAcceptance acceptance() {
        return new GuanceEvidenceAcceptance(
                "t7-012345678901234567890123",
                "CSDP",
                "session-svc",
                "b".repeat(64),
                new GuanceEvidenceAcceptance.Checklist(
                        true, true, true, true, true, true, true),
                new GuanceEvidenceAcceptance.ValidationFacts(
                        4,
                        3,
                        "c".repeat(64),
                        12,
                        20,
                        40,
                        NOW),
                "owner@example.com",
                NOW);
    }
}
