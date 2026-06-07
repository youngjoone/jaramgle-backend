package com.jaramgle.backend.config;

import com.jaramgle.backend.service.publicdata.BusanAttractionSourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class BusanStorySourceBootstrap implements ApplicationRunner {

    private final BusanAttractionSourceService busanAttractionSourceService;

    @Value("${busan.public-data.sync-on-startup:${BUSAN_PUBLIC_DATA_SYNC_ON_STARTUP:true}}")
    private boolean syncOnStartup;

    @Override
    public void run(ApplicationArguments args) {
        if (!syncOnStartup) {
            return;
        }
        if (busanAttractionSourceService.hasActiveSources()) {
            return;
        }

        log.info("Busan story source table is empty. Running one-time public data sync.");
        busanAttractionSourceService.syncFromPublicData();
    }
}
