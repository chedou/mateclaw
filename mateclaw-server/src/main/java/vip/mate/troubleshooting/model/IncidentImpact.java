package vip.mate.troubleshooting.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import vip.mate.troubleshooting.TroubleshootingEvidenceSanitizer;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Structured incident impact carried by the authoritative Diagnosis aggregate.
 *
 * <p>Legacy payloads stored impact as a string. The deserializer accepts that
 * shape as a function scope with all measured facts unknown, while every new
 * serialization emits the structured object. Counts and a non-UNKNOWN blast
 * radius are accepted only with safe evidence references.</p>
 */
@JsonDeserialize(using = IncidentImpact.Deserializer.class)
public record IncidentImpact(
        String functionScope,
        Integer affectedCustomers,
        Integer affectedUsers,
        BlastRadius blastRadius,
        List<String> evidenceRefs,
        Instant observedAt,
        String note) {

    private static final Set<String> JSON_FIELDS = Set.of(
            "functionScope",
            "affectedCustomers",
            "affectedUsers",
            "blastRadius",
            "evidenceRefs",
            "observedAt",
            "note");

    public IncidentImpact {
        functionScope = normalizedText(functionScope, "待确认");
        note = normalizedText(note, "");
        if (affectedCustomers != null && affectedCustomers < 0) {
            throw new IllegalArgumentException("affectedCustomers cannot be negative");
        }
        if (affectedUsers != null && affectedUsers < 0) {
            throw new IllegalArgumentException("affectedUsers cannot be negative");
        }
        blastRadius = blastRadius == null ? BlastRadius.UNKNOWN : blastRadius;
        evidenceRefs = validateEvidenceRefs(evidenceRefs);
        if ((affectedCustomers != null
                || affectedUsers != null
                || blastRadius != BlastRadius.UNKNOWN)
                && evidenceRefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "measured impact counts or blastRadius require evidenceRefs");
        }
        if ((affectedCustomers != null || affectedUsers != null) && observedAt == null) {
            throw new IllegalArgumentException(
                    "precise impact counts require observedAt");
        }
    }

    public static IncidentImpact unknown(String functionScope) {
        return new IncidentImpact(
                functionScope, null, null, BlastRadius.UNKNOWN, List.of(), null, "");
    }

    public boolean hasMeasuredFacts() {
        return affectedCustomers != null
                || affectedUsers != null
                || blastRadius != BlastRadius.UNKNOWN;
    }

    private static List<String> validateEvidenceRefs(List<String> values) {
        List<String> refs = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (String value : values == null ? List.<String>of() : values) {
            String ref = value == null ? "" : value.trim();
            if (!ref.equals(value)
                    || !TroubleshootingEvidenceSanitizer.isSafeEvidenceId(ref)) {
                throw new IllegalArgumentException(
                        "evidenceRefs must contain safe canonical query ids");
            }
            if (!unique.add(ref)) {
                throw new IllegalArgumentException(
                        "duplicate evidenceRefs are not allowed: " + ref);
            }
            refs.add(ref);
        }
        return List.copyOf(refs);
    }

    private static String normalizedText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** Accepts the v1.3-v1.5 string shape and the v1.6 object shape. */
    public static final class Deserializer extends JsonDeserializer<IncidentImpact> {

        @Override
        public IncidentImpact deserialize(
                JsonParser parser,
                DeserializationContext context) throws IOException {
            JsonNode node = parser.getCodec().readTree(parser);
            if (node == null || node.isNull()) {
                return IncidentImpact.unknown("待确认");
            }
            if (node.isTextual()) {
                return IncidentImpact.unknown(node.textValue());
            }
            if (!node.isObject()) {
                throw mapping(parser, "impact must be a string or object");
            }
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String field = names.next();
                if (!JSON_FIELDS.contains(field)) {
                    throw mapping(parser, "unknown impact field: " + field);
                }
            }
            try {
                return new IncidentImpact(
                        text(node, "functionScope", "待确认", parser),
                        integer(node, "affectedCustomers", parser),
                        integer(node, "affectedUsers", parser),
                        radius(node, parser),
                        refs(node, parser),
                        instant(node, parser),
                        text(node, "note", "", parser));
            } catch (IllegalArgumentException invalid) {
                throw mapping(parser, invalid.getMessage());
            }
        }

        private String text(
                JsonNode node,
                String field,
                String fallback,
                JsonParser parser) throws JsonMappingException {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                return fallback;
            }
            if (!value.isTextual()) {
                throw mapping(parser, "impact." + field + " must be a string");
            }
            return value.textValue();
        }

        private Integer integer(
                JsonNode node,
                String field,
                JsonParser parser) throws JsonMappingException {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                return null;
            }
            if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                throw mapping(parser, "impact." + field + " must be a 32-bit integer");
            }
            return value.intValue();
        }

        private BlastRadius radius(
                JsonNode node,
                JsonParser parser) throws JsonMappingException {
            String value = text(node, "blastRadius", "UNKNOWN", parser);
            try {
                return BlastRadius.valueOf(value);
            } catch (IllegalArgumentException invalid) {
                throw mapping(parser, "impact.blastRadius is unsupported: " + value);
            }
        }

        private List<String> refs(
                JsonNode node,
                JsonParser parser) throws JsonMappingException {
            JsonNode values = node.get("evidenceRefs");
            if (values == null || values.isNull()) {
                return List.of();
            }
            if (!values.isArray()) {
                throw mapping(parser, "impact.evidenceRefs must be an array");
            }
            List<String> result = new ArrayList<>();
            for (JsonNode value : values) {
                if (!value.isTextual()) {
                    throw mapping(parser, "impact.evidenceRefs must contain strings");
                }
                result.add(value.textValue());
            }
            return result;
        }

        private Instant instant(
                JsonNode node,
                JsonParser parser) throws JsonMappingException {
            JsonNode value = node.get("observedAt");
            if (value == null || value.isNull()) {
                return null;
            }
            try {
                return parser.getCodec().treeToValue(value, Instant.class);
            } catch (IOException | RuntimeException invalid) {
                throw mapping(
                        parser,
                        "impact.observedAt must be a supported instant representation");
            }
        }

        private JsonMappingException mapping(JsonParser parser, String message) {
            return JsonMappingException.from(parser, message);
        }
    }
}
