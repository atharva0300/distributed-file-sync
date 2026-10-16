package com.filesync.conflict.repository;

import com.filesync.conflict.entity.Conflict;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConflictRepository extends JpaRepository<Conflict, Long> {
    @Query("SELECT c FROM Conflict c WHERE c.file.user.id = :userId AND c.status = :status")
    List<Conflict> findByUserIdAndStatus(@org.springframework.data.repository.query.Param("userId") Long userId, @org.springframework.data.repository.query.Param("status") String status);

    @Query("SELECT c FROM Conflict c WHERE c.operation.id = :operationId AND c.file.id = :fileId AND c.status = :status")
    java.util.Optional<Conflict> findByOperationIdAndFileIdAndStatus(@org.springframework.data.repository.query.Param("operationId") Long operationId, @org.springframework.data.repository.query.Param("fileId") Long fileId, @org.springframework.data.repository.query.Param("status") String status);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Conflict c WHERE c.id = :id AND c.status = 'OPEN'")
    java.util.Optional<Conflict> findByIdAndStatusOpenForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE Conflict c SET c.status = 'RESOLVED', c.resolution = :resolution, c.resolvedAt = CURRENT_TIMESTAMP WHERE c.id = :id AND c.status = 'OPEN'")
    int resolveConflictConditionally(@org.springframework.data.repository.query.Param("id") Long id, @org.springframework.data.repository.query.Param("resolution") String resolution);
}
