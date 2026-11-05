package com.filesync.chunking;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the chunking subsystem.
 *
 * <p>Bind via {@code app.chunking.*} in {@code application.yml}.</p>
 *
 * <pre>{@code
 * app:
 *   chunking:
 *     min-size: 2097152  # 2 MB minimum chunk size
 *     target-size: 4194304  # 4 MB average/target chunk size
 *     max-size: 8388608  # 8 MB maximum chunk size
 * }</pre>
 *
 * <p>Content-defined chunking is the only supported algorithm.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.chunking")
public class ChunkingProperties {

    /**
     * Minimum chunk size in bytes. CDC will not produce a chunk smaller than this
     * (except for a file that is smaller than the minimum size, in which case the
     * entire file becomes one chunk).
     * Default: 2 MB.
     */
    private int minSize = 2 * 1024 * 1024;

    /**
     * Target (average) chunk size in bytes. CDC uses this to derive the boundary
     * detection mask ({@code mask = targetSize - 1}), which produces chunks whose
     * average size converges to {@code targetSize}.
     * Default: 4 MB.
     */
    private int targetSize = 4 * 1024 * 1024;

    /**
     * Maximum chunk size in bytes. CDC forces a boundary at this point if no
     * content-defined boundary has been found.
     * Default: 8 MB.
     */
    private int maxSize = 8 * 1024 * 1024;
}
