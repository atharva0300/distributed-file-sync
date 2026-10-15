package com.filesync.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Explicit JPA repository and entity scanning configuration.
 */
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(basePackages = {
        "com.filesync.auth.repository",
        "com.filesync.device.repository",
        "com.filesync.file.repository",
        "com.filesync.chunk.repository",
        "com.filesync.conflict.repository",
        "com.filesync.sync.repository",
        "com.filesync.upload.repository",
        "com.filesync.version.repository"
})
@EntityScan(basePackages = {
        "com.filesync.auth.entity",
        "com.filesync.device.entity",
        "com.filesync.file.entity",
        "com.filesync.chunk.entity",
        "com.filesync.conflict.entity",
        "com.filesync.sync.entity",
        "com.filesync.upload.entity",
        "com.filesync.version.entity"
})
public class DataConfig {
}
