package com.filesync.file.repository;

import com.filesync.file.entity.FileEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FileRepository extends JpaRepository<FileEntry, Long> {

    // List root-level files for a user (no parent folder)
    List<FileEntry> findByUserIdAndParentFolderIsNullAndDeletedFalse(Long userId);

    // List files within a folder
    List<FileEntry> findByUserIdAndParentFolderIdAndDeletedFalse(Long userId, Long parentFolderId);

    // Find file by ID with ownership check
    Optional<FileEntry> findByIdAndUserIdAndDeletedFalse(Long id, Long userId);

    // Find by name within parent folder or root
    Optional<FileEntry> findByUserIdAndParentFolderIdAndNameAndDeletedFalse(Long userId, Long parentFolderId, String name);
    Optional<FileEntry> findByUserIdAndParentFolderIsNullAndNameAndDeletedFalse(Long userId, String name);

    // Check for name conflict within same folder
    boolean existsByUserIdAndParentFolderIdAndNameAndDeletedFalse(
            Long userId, Long parentFolderId, String name);

    boolean existsByUserIdAndParentFolderIsNullAndNameAndDeletedFalse(
            Long userId, String name);

    @Query("SELECT f FROM FileEntry f WHERE f.user.id = :userId AND f.id = :id")
    Optional<FileEntry> findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM FileEntry f WHERE f.id = :id AND f.user.id = :userId AND f.deleted = false")
    Optional<FileEntry> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);
}
