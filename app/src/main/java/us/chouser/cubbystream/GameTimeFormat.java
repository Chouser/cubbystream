package us.chouser.cubbystream;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Formats absolute game timestamps (epoch millis, e.g. GameState.scheduledStartMs
 * or GameState.nextGameStartMs) for display.
 *
 * Deliberately the *only* place in the app that converts an absolute instant to a
 * local clock time. Everything upstream (schedule queries, game selection) works
 * in absolute/UTC time; this class does the one legitimate conversion to the
 * viewer's own device timezone, at the point of display — since "what time should
 * I tune in" should always answer in the viewer's own local time, and should
 * self-adjust automatically if they're traveling.
 */
public class GameTimeFormat {

    private static final DateTimeFormatter TIME_ONLY =
            DateTimeFormatter.ofPattern("h:mm", Locale.getDefault());
    private static final DateTimeFormatter DATE_AND_TIME =
            DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm", Locale.getDefault());
    private static final DateTimeFormatter DATE_ONLY =
            DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault());

    /** e.g. "7:10" — used when the game is later today. AM/PM omitted; first-pitch
     *  times are unambiguously afternoon/evening in practice. */
    public static String formatTimeOnly(long epochMs) {
        if (epochMs <= 0) return "";
        return Instant.ofEpochMilli(epochMs)
                .atZone(ZoneId.systemDefault())
                .format(TIME_ONLY);
    }

    /** e.g. "Fri, Jul 17 at 7:15" — used when the game isn't today. */
    public static String formatDateAndTime(long epochMs) {
        if (epochMs <= 0) return "";
        return Instant.ofEpochMilli(epochMs)
                .atZone(ZoneId.systemDefault())
                .format(DATE_AND_TIME);
    }

    /** e.g. "Sun, Jul 12" — date only, no time. Used for labeling a completed game. */
    public static String formatDateOnly(long epochMs) {
        if (epochMs <= 0) return "";
        return Instant.ofEpochMilli(epochMs)
                .atZone(ZoneId.systemDefault())
                .format(DATE_ONLY);
    }

    /** Whether epochMs falls on the same local calendar day as referenceMs. */
    public static boolean isSameLocalDay(long epochMs, long referenceMs) {
        ZoneId zone = ZoneId.systemDefault();
        return Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
                .equals(Instant.ofEpochMilli(referenceMs).atZone(zone).toLocalDate());
    }

    /**
     * Picks the right format automatically: time-only if epochMs is today
     * (relative to now), otherwise full date + time.
     */
    public static String formatSmart(long epochMs) {
        if (epochMs <= 0) return "";
        return isSameLocalDay(epochMs, System.currentTimeMillis())
                ? formatTimeOnly(epochMs)
                : formatDateAndTime(epochMs);
    }
}
