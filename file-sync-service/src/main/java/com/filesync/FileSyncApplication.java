package com.filesync;

import com.filesync.chunking.ChunkingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({ChunkingProperties.class, com.filesync.config.UploadRateLimitProperties.class})
public class FileSyncApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileSyncApplication.class, args);
    }
}
