package vip.mate.troubleshooting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Frozen membership of the 146 reviewed D1 error-code selectors. */
@Component
public final class KnowledgeEvidenceSelectorInventory {

    static final String RESOURCE =
            "troubleshooting/knowledge/csdp-d1-error-code-selectors.json";
    static final String CONTRACT_VERSION =
            "csdp-d1-error-code-selector-inventory.v1";
    static final int EXPECTED_SELECTOR_COUNT = 146;

    private static final Pattern SELECTOR = Pattern.compile("csdp:[A-Za-z0-9_]+");
    private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");

    private final Set<String> selectors;

    public KnowledgeEvidenceSelectorInventory(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper is required");
        }
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        try (InputStream input = resource.getInputStream()) {
            InventoryDocument document = objectMapper.readValue(
                    input, InventoryDocument.class);
            selectors = validate(document);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "the frozen D1 selector inventory cannot be loaded", failure);
        }
    }

    public boolean contains(String selector) {
        return selector != null && selectors.contains(selector);
    }

    public int size() {
        return selectors.size();
    }

    private static Set<String> validate(InventoryDocument document) {
        if (document == null
                || !CONTRACT_VERSION.equals(document.contractVersion())
                || document.source() == null || document.source().isBlank()
                || document.sourceSha256() == null
                || !SHA_256.matcher(document.sourceSha256()).matches()
                || document.selectors() == null
                || document.selectors().size() != EXPECTED_SELECTOR_COUNT) {
            throw new IllegalStateException(
                    "the frozen D1 selector inventory contract is invalid");
        }
        if (document.selectors().stream().anyMatch(value -> value == null
                || !SELECTOR.matcher(value).matches())) {
            throw new IllegalStateException(
                    "the frozen D1 selector inventory membership is invalid");
        }
        Set<String> unique = Set.copyOf(document.selectors());
        if (unique.size() != EXPECTED_SELECTOR_COUNT) {
            throw new IllegalStateException(
                    "the frozen D1 selector inventory membership is invalid");
        }
        return unique;
    }

    private record InventoryDocument(
            String contractVersion,
            String source,
            String sourceSha256,
            List<String> selectors) {
    }
}
