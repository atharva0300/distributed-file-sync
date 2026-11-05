package com.filesync.db;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
class StatusConstraintTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void chunkWithUnknownStatusIsRejected() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO chunks (hash, size, storage_key, status, created_at) "
                        + "VALUES (?, 1, 'k', 'BOGUS', now())", "f".repeat(64)));
    }

    @Test
    void chunkStatusesUsedByGcLifecycleAreAccepted() {
        for (String s : new String[]{"STAGING", "ACTIVE", "DELETING"}) {
            String hash = (s.charAt(0) + "a").repeat(32);
            jdbc.update("INSERT INTO chunks (hash, size, storage_key, status, created_at) "
                    + "VALUES (?, 1, 'k', ?, now())", hash, s);
            jdbc.update("DELETE FROM chunks WHERE hash = ?", hash);
        }
    }
}
