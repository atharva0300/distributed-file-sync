package com.filesync.version.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class FileVersionChunkId implements Serializable {

    @Column(name = "version_id")
    private Long versionId;

    @Column(name = "chunk_index")
    private Integer chunkIndex;
}
