package com.filesync.version.repository;

import com.filesync.version.entity.FileVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FileVersionRepository extends JpaRepository<FileVersion, Long> {
    Optional<FileVersion> findByFileIdAndVersionNumber(Long fileId, int versionNumber);
    List<FileVersion> findByFileIdOrderByVersionNumberDesc(Long fileId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "DELETE FROM file_versions " +
            "WHERE id IN ( " +
            "  SELECT id FROM ( " +
            "    SELECT id, ROW_NUMBER() OVER(PARTITION BY file_id ORDER BY version_number DESC) as rn " +
            "    FROM file_versions " +
            "  ) sub WHERE sub.rn > :maxVersions " +
            ") AND id NOT IN (SELECT current_version_id FROM files WHERE current_version_id IS NOT NULL)", nativeQuery = true)
    int deleteOldVersions(@org.springframework.data.repository.query.Param("maxVersions") int maxVersions);

    Optional<FileVersion> findByUploadSessionId(Long uploadSessionId);

    /** Distinct chunk hashes of a version, without materialising the chunk entities. */
    @org.springframework.data.jpa.repository.Query(
            "SELECT DISTINCT c.hash FROM FileVersionChunk fvc JOIN fvc.chunk c WHERE fvc.fileVersion.id = :versionId")
    List<String> findChunkHashesByVersionId(@org.springframework.data.repository.query.Param("versionId") Long versionId);
}
