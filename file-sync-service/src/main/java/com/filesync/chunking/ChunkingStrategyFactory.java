package com.filesync.chunking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Factory that returns the configured {@link ChunkingStrategy} based on
 * {@link ChunkingProperties}.
 *
 * <p>Creating a new strategy instance per chunking operation ensures thread safety:
 * each caller gets its own instance with independent rolling-hash state.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChunkingStrategyFactory {

    private final ChunkingProperties properties;

    /**
     * Returns a fresh {@link ChunkingStrategy} for the currently configured mode.
     *
     * <p>For {@code CDC} mode, validates that {@code targetSize} is a power of two.
     * If not, logs a warning and falls back to the nearest smaller power of two.</p>
     */
    public ChunkingStrategy getStrategy() {
        int target = nearestPowerOfTwo(properties.getTargetSize());
        if (target != properties.getTargetSize()) {
            log.warn("CDC targetSize {} is not a power of two; using {} instead",
                    properties.getTargetSize(), target);
        }
        return new ContentDefinedChunkingStrategy(
                properties.getMinSize(),
                target,
                properties.getMaxSize());
    }

    /**
     * Returns the configured target/average chunk size.
     * For CDC: the average chunk size.
     */
    public int getTargetSize() {
        return properties.getTargetSize();
    }

    /**
     * Returns the configured minimum chunk size (CDC only).
     */
    public int getMinSize() {
        return properties.getMinSize();
    }

    /**
     * Returns the configured maximum chunk size (CDC only).
     */
    public int getMaxSize() {
        return properties.getMaxSize();
    }

    /**
     * Returns the nearest power of two that is &lt;= {@code n}.
     * Required because the Rabin boundary mask ({@code n - 1}) only works for powers of two.
     */
    private static int nearestPowerOfTwo(int n) {
        if (n <= 0) return 1;
        if ((n & (n - 1)) == 0) return n; // already a power of two
        // Integer.highestOneBit returns the highest power of two <= n
        return Integer.highestOneBit(n);
    }
}
