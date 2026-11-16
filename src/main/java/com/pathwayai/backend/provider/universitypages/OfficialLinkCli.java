package com.pathwayai.backend.provider.universitypages;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = "app.official-links.enabled", havingValue = "true")
public class OfficialLinkCli implements ApplicationRunner {
    private final OfficialLinkBackfill backfill;
    private final boolean commit;

    public OfficialLinkCli(OfficialLinkBackfill backfill, @Value("${app.official-links.commit:false}") boolean commit) {
        this.backfill = backfill;
        this.commit = commit;
    }

    @Override public void run(ApplicationArguments args) {
        var results = backfill.run(commit);
        System.out.println("OFFICIAL_LINK_REPORT " + JsonMapper.builder().build().writeValueAsString(results));
        System.out.println("Mode: " + (commit ? "COMMIT" : "DRY RUN (no writes)"));
    }
}
