package vip.mate.troubleshooting.evidence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceSpineTimingsTest {

    @Test
    void exposesOnlyCompleteApplicationSideMeasurementsAsAggregatable() {
        EvidenceSpineTimings timings = new EvidenceSpineTimings(5L, 7L, 11L, 4L);

        assertThat(timings.complete()).isTrue();
        assertThat(timings.evidenceAcquisitionDurationMs()).isEqualTo(23L);
        assertThat(timings.compressionDurationMs()).isEqualTo(4L);
        assertThat(timings.measuredWorkDurationMs()).isEqualTo(27L);
        assertThat(EvidenceSpineTimings.unmeasured().complete()).isFalse();
        assertThat(EvidenceSpineTimings.unmeasured().evidenceAcquisitionDurationMs())
                .isNull();
    }

    @Test
    void rejectsNegativeOrOutOfOrderMeasurements() {
        assertThatThrownBy(() -> new EvidenceSpineTimings(-1L, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        assertThatThrownBy(() -> new EvidenceSpineTimings(null, 2L, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("log search");
        assertThatThrownBy(() -> new EvidenceSpineTimings(1L, null, null, 2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trace");
    }
}
