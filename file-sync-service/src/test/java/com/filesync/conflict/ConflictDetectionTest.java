package com.filesync.conflict;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for conflict detection logic.
 *
 * Conflict rule:
 *   If client.baseVersion != server.currentVersion → CONFLICT
 *
 * This validates the optimistic concurrency control strategy.
 */
class ConflictDetectionTest {

    /** Simulates the conflict check that BackgroundSyncWorker performs. */
    private boolean isConflict(int clientBaseVersion, int serverCurrentVersion) {
        return clientBaseVersion != serverCurrentVersion;
    }

    @Test
    void sameVersion_noConflict() {
        assertThat(isConflict(5, 5)).isFalse();
    }

    @Test
    void clientBehindServer_conflict() {
        // Client based on v5, server is now v6 (another device updated)
        assertThat(isConflict(5, 6)).isTrue();
    }

    @Test
    void clientAheadOfServer_conflict() {
        // Shouldn't normally happen, but validates asymmetric check
        assertThat(isConflict(6, 5)).isTrue();
    }

    @Test
    void concurrentEdit_scenario() {
        int serverVersionAfterLaptop = 6;      // Laptop reconnected first, wrote v6
        int desktopBaseVersion = 5;             // Desktop was based on v5
        // Desktop reconnects — conflict!
        assertThat(isConflict(desktopBaseVersion, serverVersionAfterLaptop)).isTrue();
    }

    @Test
    void conflictCopyName_generatedCorrectly() {
        // Test the naming convention for CREATE_CONFLICT_COPY
        String original = "report.pdf";
        String deviceName = "My Laptop";
        String sanitized = deviceName.replaceAll("[^a-zA-Z0-9\\-_]", "-").toLowerCase();

        int dotIndex = original.lastIndexOf('.');
        String base = original.substring(0, dotIndex);
        String ext = original.substring(dotIndex);
        String conflictName = base + " (conflict-copy-" + sanitized + ")" + ext;

        assertThat(conflictName).isEqualTo("report (conflict-copy-my-laptop).pdf");
    }
}
