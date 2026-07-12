package us.chouser.cubbystream;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Single source of truth for all available {@link AdDetector} implementations.
 *
 * <p>To add a new detector, add one line to {@link #ALL}.  No other file needs
 * to change — {@link us.chouser.cubbystream.SettingsSheet} builds its spinner
 * from this list, and {@link us.chouser.cubbystream.MainActivity} uses
 * {@link #forKey} to instantiate detectors by key.
 */
public final class DetectorRegistry {

    private DetectorRegistry() {}

    /** Ordered list of all available detectors, in spinner display order. */
    public static final List<Entry> ALL = Collections.unmodifiableList(Arrays.asList(
            new Entry(ClassicMidBandEnergyDetector.ALGORITHM_KEY, ClassicMidBandEnergyDetector::new),
            new Entry(MidBandEnergyDetector.ALGORITHM_KEY, MidBandEnergyDetector::new),
            new Entry(GeneratedDetector.ALGORITHM_KEY, GeneratedDetector::new),
            new Entry(NoOpDetector.ALGORITHM_KEY, NoOpDetector::new)
    ));

    /**
     * Instantiate a fresh detector for the given algorithm key.
     * Falls back to {@link NoOpDetector} if the key is unrecognised.
     */
    public static AdDetector forKey(String key) {
        for (Entry e : ALL) {
            if (e.key.equals(key)) return e.factory.get();
        }
        return new NoOpDetector();
    }

    // -------------------------------------------------------------------------

    /** Pairs an algorithm key with a zero-argument factory for that detector. */
    public static final class Entry {
        public final String             key;
        public final Supplier<AdDetector> factory;

        public Entry(String key, Supplier<AdDetector> factory) {
            this.key     = key;
            this.factory = factory;
        }

        /**
         * Display name, sourced from the detector itself so it stays in sync
         * with {@link AdDetector#getDisplayName()} automatically.
         */
        public String displayName() {
            return factory.get().getDisplayName();
        }
    }
}
