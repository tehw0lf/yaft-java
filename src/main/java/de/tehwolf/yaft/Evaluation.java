package de.tehwolf.yaft;

import java.lang.System.Logger.Level;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single definition of YaFT's evaluation rules.
 *
 * <p>Providers call {@link #evaluate(Feature, Instant)} rather than
 * implementing the logic themselves, so every provider -- and every YaFT port
 * that mirrors this class -- agrees on the same answer. The rules are SPEC
 * R3-R13 of <a href="https://github.com/tehw0lf/yaft-conformance">yaft-conformance</a>.
 */
public final class Evaluation {

    private static final System.Logger LOG = System.getLogger("de.tehwolf.yaft");

    /**
     * RFC 3339 with an explicit offset ({@code Z} or {@code ±hh:mm}).
     *
     * <p>A bare date such as {@code 2026-09-18} or a timestamp without an
     * offset is rejected: languages disagree on how to read them, so a feature
     * would flip at a different instant depending on which port evaluated it.
     * {@code \d} only matches ASCII digits in Java, as in JavaScript.
     */
    private static final Pattern RFC3339_WITH_OFFSET = Pattern.compile(
            "^(\\d{4})-(\\d{2})-(\\d{2})[Tt](\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d+))?(?:([Zz])|([+-])(\\d{2}):(\\d{2}))$");

    private Evaluation() {}

    /**
     * Decides whether a feature is on at the instant {@code now}.
     *
     * <ul>
     *   <li>A missing feature is off.</li>
     *   <li>Only the exact string {@code "true"} is on.</li>
     *   <li>Unset or unparseable bounds are ignored, never an error.</li>
     *   <li>The window is half-open, {@code [activeAt, disabledAt)}: at exactly
     *       {@code activeAt} the feature is on, at exactly {@code disabledAt}
     *       it is off.</li>
     *   <li>{@code activeAt} after {@code disabledAt} is not special-cased; the
     *       window simply never opens.</li>
     * </ul>
     *
     * @param feature the feature, or {@code null}
     * @param now the instant to evaluate at
     * @return whether the feature is on at {@code now}
     */
    public static boolean evaluate(Feature feature, Instant now) {
        Objects.requireNonNull(now, "now");
        if (feature == null) return false;

        if (!"true".equals(feature.value())) return false;

        Optional<Instant> activeAt = parseTimestamp(feature.activeAt());
        if (activeAt.isPresent() && now.isBefore(activeAt.get())) return false;

        Optional<Instant> disabledAt = parseTimestamp(feature.disabledAt());
        if (disabledAt.isPresent() && !now.isBefore(disabledAt.get())) return false;

        return true;
    }

    /**
     * Parses an RFC 3339 timestamp with an offset.
     *
     * <p>Returns empty for anything unset, malformed, out of range or in
     * another format. Callers treat that as "no bound". A malformed value is
     * logged as a warning but never throws, so a bad timestamp in the backend
     * cannot take an application down.
     *
     * <p>Fractional seconds are truncated to milliseconds, the precision the
     * TypeScript reference evaluates at, so both agree on every boundary.
     *
     * @param value the timestamp, or {@code null}
     * @return the instant, or empty if {@code value} is not a valid bound
     */
    public static Optional<Instant> parseTimestamp(String value) {
        if (value == null || value.isEmpty()) return Optional.empty();

        Matcher m = RFC3339_WITH_OFFSET.matcher(value);
        if (!m.matches()) {
            warn(value, "expected RFC 3339 with an offset (e.g. 2026-09-18T15:00:00Z)");
            return Optional.empty();
        }

        int year = Integer.parseInt(m.group(1));
        int month = Integer.parseInt(m.group(2));
        int day = Integer.parseInt(m.group(3));
        int hour = Integer.parseInt(m.group(4));
        int minute = Integer.parseInt(m.group(5));
        int second = Integer.parseInt(m.group(6));

        // Range-checked by hand rather than left to the parser (R11): the
        // rules must reject exactly the values every other port rejects, and
        // a lenient parser elsewhere rolls 2027-02-30 over into March.
        if (!isRealDate(year, month, day) || hour > 23 || minute > 59 || second > 60) {
            warn(value, "not a valid date or time");
            return Optional.empty();
        }
        // RFC 3339 permits a leap second, but not every language can represent
        // one, so it is ignored everywhere (R12).
        if (second == 60) {
            warn(value, "leap seconds are not supported");
            return Optional.empty();
        }

        int offsetSeconds = 0;
        if (m.group(8) == null) {
            int offsetHours = Integer.parseInt(m.group(10));
            int offsetMinutes = Integer.parseInt(m.group(11));
            if (offsetHours > 23 || offsetMinutes > 59) {
                warn(value, "not a valid offset");
                return Optional.empty();
            }
            int sign = m.group(9).equals("-") ? -1 : 1;
            offsetSeconds = sign * (offsetHours * 3600 + offsetMinutes * 60);
        }

        int millis = 0;
        String fraction = m.group(7);
        if (fraction != null) {
            millis = Integer.parseInt((fraction + "00").substring(0, 3));
        }

        // The offset is applied, not stripped (R13). Computed on the epoch
        // second directly because ZoneOffset stops at ±18:00.
        long epochSecond = LocalDateTime.of(year, month, day, hour, minute, second)
                        .toEpochSecond(ZoneOffset.UTC)
                - offsetSeconds;
        return Optional.of(Instant.ofEpochSecond(epochSecond, millis * 1_000_000L));
    }

    private static boolean isRealDate(int year, int month, int day) {
        if (month < 1 || month > 12 || day < 1) return false;
        return day <= YearMonth.of(year, month).lengthOfMonth();
    }

    private static void warn(String value, String reason) {
        LOG.log(Level.WARNING, "YaFT: ignoring \"{0}\", {1}", value, reason);
    }
}
