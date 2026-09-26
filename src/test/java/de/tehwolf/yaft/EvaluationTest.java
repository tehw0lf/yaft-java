package de.tehwolf.yaft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Timestamp edges the conformance suite leaves open. */
class EvaluationTest {

    @Test
    void truncatesFractionalSecondsToMillisecondsLikeTheReference() {
        // JavaScript's Date.parse drops everything after the third digit. A
        // port keeping nanoseconds would flip half a millisecond later.
        assertEquals(
                Optional.of(Instant.parse("2026-09-18T12:00:00.123Z")),
                Evaluation.parseTimestamp("2026-09-18T12:00:00.123999999Z"));
        assertEquals(
                Optional.of(Instant.parse("2026-09-18T12:00:00.500Z")),
                Evaluation.parseTimestamp("2026-09-18T12:00:00.5Z"));

        Feature f = new Feature("f", "true", "", "2026-09-18T12:00:00.0009Z", List.of());
        assertFalse(Evaluation.evaluate(f, Instant.parse("2026-09-18T12:00:00Z")));
    }

    @Test
    void appliesOffsetsBeyondTheEighteenHoursZoneOffsetAllows() {
        // Date.parse accepts any hh:mm offset up to 23:59; java.time's
        // ZoneOffset would throw at +19:00.
        assertEquals(
                Optional.of(Instant.parse("2026-09-18T00:00:00Z")),
                Evaluation.parseTimestamp("2026-09-18T20:00:00+20:00"));
    }

    @Test
    void ignoresOutOfRangeOffsets() {
        assertEquals(Optional.empty(), Evaluation.parseTimestamp("2026-09-18T12:00:00+24:00"));
        assertEquals(Optional.empty(), Evaluation.parseTimestamp("2026-09-18T12:00:00+02:60"));
    }

    @Test
    void ignoresNonAsciiDigits() {
        // "٢٠٢٦" is 2026 in Arabic-Indic digits; \d must not match it.
        assertEquals(Optional.empty(), Evaluation.parseTimestamp("٢٠٢٦-09-18T12:00:00Z"));
    }

    @Test
    void requiresAnInstant() {
        assertThrows(NullPointerException.class, () -> Evaluation.evaluate(new Feature("f", "true"), null));
    }

    @Test
    void neverThrowsForAMalformedFeature() {
        Feature f = new Feature(null, "true", "\u0000", "9999-99-99T99:99:99Z", null);
        assertTrue(Evaluation.evaluate(f, Instant.EPOCH));
    }
}
