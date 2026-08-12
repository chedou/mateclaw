package vip.mate.troubleshooting.evidence;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceTemplateParameterPolicyTest {

    @Test
    void omitsOptionalSectionsWhenValueAbsent() {
        String template = "{ `service` = '{{service}}'"
                + "{{?error_code}} AND `@code` = {{error_code}}{{/error_code}}"
                + "{{?search_term}} AND query_string(`message`, \"{{search_term}}\"){{/search_term}} }";

        String rendered = EvidenceTemplateParameterPolicy.applyOptionalSections(
                template, name -> "service".equals(name));

        assertThat(rendered).isEqualTo("{ `service` = '{{service}}' }");
    }

    @Test
    void keepsOptionalSectionsWhenValuePresent() {
        String template = "{ `df_status` IN ['warning']"
                + "{{?monitor_checker}} AND `df_monitor_checker_name` = '{{monitor_checker}}'"
                + "{{/monitor_checker}} }";

        String rendered = EvidenceTemplateParameterPolicy.applyOptionalSections(
                template, Set.of("monitor_checker")::contains);

        assertThat(rendered).isEqualTo(
                "{ `df_status` IN ['warning']"
                        + " AND `df_monitor_checker_name` = '{{monitor_checker}}' }");
    }

    @Test
    void extractsPlaceholdersInsideOptionalSections() {
        assertThat(EvidenceTemplateParameterPolicy.placeholders(List.of(
                "{{?error_code}} AND `@code` = {{error_code}}{{/error_code}}")))
                .containsExactly("error_code");
        assertThat(EvidenceTemplateParameterPolicy.optionalParameterNames(List.of(
                "{{?error_code}}x{{/error_code}}{{?search_term}}y{{/search_term}}")))
                .containsExactlyInAnyOrder("error_code", "search_term");
    }

    @Test
    void rejectsUnresolvedOptionalSections() {
        assertThatThrownBy(() -> EvidenceTemplateParameterPolicy.applyOptionalSections(
                "{{?error_code}} leftover", name -> false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unresolved optional");
    }
}
