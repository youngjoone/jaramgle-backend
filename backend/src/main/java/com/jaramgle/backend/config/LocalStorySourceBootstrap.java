package com.jaramgle.backend.config;

import com.jaramgle.backend.service.publicdata.LocalStorySourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class LocalStorySourceBootstrap implements ApplicationRunner {

    private final LocalStorySourceService localStorySourceService;

    @Value("${local.public-data.sync-on-startup:${LOCAL_PUBLIC_DATA_SYNC_ON_STARTUP:false}}")
    private boolean syncOnStartup;

    @Override
    public void run(ApplicationArguments args) {
        if (!syncOnStartup) {
            return;
        }
        for (String region : localStorySourceService.supportedRegions()) {
            if (localStorySourceService.hasActiveSources(region)) {
                log.info("Local story source table already has active sources for {}. Startup sync skipped.", region);
                continue;
            }
            log.info("Local story source table is empty for {}. Running one-time public data sync.", region);
            localStorySourceService.syncFromPublicData(region);
        }
    }
}
