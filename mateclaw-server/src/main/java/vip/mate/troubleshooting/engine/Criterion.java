package vip.mate.troubleshooting.engine;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * Closed, data-only anomaly rule vocabulary for the deterministic hit path.
 *
 * <p>No implementation may call an LLM or an external system. Evidence is
 * collected before evaluation and supplied as a normalized map.</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Criterion.NumericGte.class, name = "numeric_gte"),
        @JsonSubTypes.Type(value = Criterion.MissingOrLte.class, name = "missing_or_lte"),
        @JsonSubTypes.Type(value = Criterion.RatioOfSumGt.class, name = "ratio_of_sum_gt"),
        @JsonSubTypes.Type(value = Criterion.MultipleGt.class, name = "multiple_gt"),
        @JsonSubTypes.Type(value = Criterion.ContainsAndIn.class, name = "contains_and_in"),
        @JsonSubTypes.Type(value = Criterion.BooleanEquals.class, name = "boolean_equals")
})
public sealed interface Criterion permits Criterion.NumericGte,
        Criterion.MissingOrLte,
        Criterion.RatioOfSumGt,
        Criterion.MultipleGt,
        Criterion.ContainsAndIn,
        Criterion.BooleanEquals {

    record NumericGte(String field, double threshold) implements Criterion {
        public NumericGte {
            field = required(field, "field");
        }
    }

    record MissingOrLte(String presenceField, String field, double threshold) implements Criterion {
        public MissingOrLte {
            presenceField = required(presenceField, "presenceField");
            field = required(field, "field");
        }
    }

    record RatioOfSumGt(
            String numeratorField,
            String addendField,
            double threshold) implements Criterion {
        public RatioOfSumGt {
            numeratorField = required(numeratorField, "numeratorField");
            addendField = required(addendField, "addendField");
        }
    }

    record MultipleGt(String field, String baselineField, double multiplier) implements Criterion {
        public MultipleGt {
            field = required(field, "field");
            baselineField = required(baselineField, "baselineField");
            if (multiplier <= 0) {
                throw new IllegalArgumentException("multiplier must be positive");
            }
        }
    }

    record ContainsAndIn(
            String containsField,
            String substring,
            String membershipField,
            List<String> acceptedValues) implements Criterion {
        public ContainsAndIn {
            containsField = required(containsField, "containsField");
            substring = required(substring, "substring");
            membershipField = required(membershipField, "membershipField");
            acceptedValues = List.copyOf(acceptedValues == null ? List.of() : acceptedValues);
            if (acceptedValues.isEmpty()) {
                throw new IllegalArgumentException("acceptedValues must not be empty");
            }
            acceptedValues.forEach(value -> required(value, "acceptedValues item"));
        }
    }

    record BooleanEquals(String field, boolean expected) implements Criterion {
        public BooleanEquals {
            field = required(field, "field");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
