package tech.anvilcoder.feed;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A sink that remembers. Without one, "log ONCE per process" and "one warning per drop event naming the
 * count" are claims no test can hold anybody to — the PHP suite learned that in task 2 and this is its
 * counterpart.
 *
 * <p>{@link #logger()} hands out ONE {@link FeedLogger}, because {@code once()} is keyed per instance;
 * {@link #secondInstance()} exists for the one test that proves "once per process" survives a host that
 * news up a second logger.
 */
final class RecordingLogger {

    private final List<String> lines = new CopyOnWriteArrayList<>();

    private final FeedLogger logger = FeedLogger.delegatingTo((level, message) -> lines.add(message));

    FeedLogger logger() {
        return logger;
    }

    FeedLogger secondInstance() {
        return FeedLogger.delegatingTo((level, message) -> lines.add(message));
    }

    List<String> lines() {
        return List.copyOf(lines);
    }

    /** Every written line that contains the needle, case-insensitively. */
    List<String> matching(String needle) {
        String wanted = needle.toLowerCase(Locale.ROOT);
        return lines.stream().filter(line -> line.toLowerCase(Locale.ROOT).contains(wanted)).toList();
    }

    void clear() {
        lines.clear();
    }
}
