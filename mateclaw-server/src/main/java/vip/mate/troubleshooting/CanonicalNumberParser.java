package vip.mate.troubleshooting;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Parses canonical evidence numbers, including the platform's persisted Long form.
 *
 * <p>The platform ObjectMapper deliberately writes {@link Long} values as decimal
 * strings to protect JavaScript precision. String compatibility is therefore
 * limited to that exact representation; it does not perform general coercion.</p>
 */
public final class CanonicalNumberParser {

    private static final Pattern CANONICAL_INTEGER =
            Pattern.compile("0|-?[1-9][0-9]*");

    private CanonicalNumberParser() {
    }

    public static Long parseExactLong(Object raw) {
        String decimal;
        if (raw instanceof Number value) {
            decimal = String.valueOf(value);
        } else if (raw instanceof String value
                && value.length() <= 20
                && CANONICAL_INTEGER.matcher(value).matches()) {
            decimal = value;
        } else {
            return null;
        }
        try {
            return new BigDecimal(decimal).longValueExact();
        } catch (ArithmeticException | NumberFormatException invalid) {
            return null;
        }
    }

    /**
     * Accepts ordinary in-memory numbers and only exact integer strings emitted
     * by the Long serializer after an aggregate persistence round trip.
     */
    public static Double parseFiniteNonNegativeDouble(Object raw) {
        double value;
        if (raw instanceof Number number) {
            value = number.doubleValue();
        } else {
            Long persistedLong = parseExactLong(raw);
            if (persistedLong == null) {
                return null;
            }
            value = persistedLong.doubleValue();
        }
        return Double.isFinite(value) && value >= 0 ? value : null;
    }
}
