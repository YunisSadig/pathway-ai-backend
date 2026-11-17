package com.pathwayai.backend.provider.universitypages;

import com.pathwayai.backend.refresh.fetch.PageFetcher;
import com.pathwayai.backend.refresh.extract.PageCanonicalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.stereotype.Component;

/** Read-only source investigation through the same crawl boundaries as production synchronization. */
@Component
@ConditionalOnNotWebApplication
@ConditionalOnProperty(name = "app.source-audit.url")
public class UniversitySourceAuditCli implements ApplicationRunner {
    private final PageFetcher fetcher;
    private final String url;
    private final String output;

    public UniversitySourceAuditCli(PageFetcher fetcher, @Value("${app.source-audit.url}") String url,
                                   @Value("${app.source-audit.output:target/source-audit.html}") String output) {
        this.fetcher = fetcher;
        this.url = url;
        this.output = output;
    }

    @Override public void run(ApplicationArguments args) throws Exception {
        var urls = url.startsWith("file:") ? Files.readAllLines(Path.of(url.substring(5))) : java.util.List.of(url);
        for (String entry : urls) if (!entry.isBlank() && !entry.startsWith("#")) audit(entry.strip(), urls.size() > 1);
    }

    private void audit(String entry, boolean batch) throws Exception {
        var response = fetcher.fetch(entry, null, null);
        System.out.println("SOURCE_AUDIT " + response.kind() + " status=" + response.httpStatus() + " final=" + response.finalUrl()
                + " error=" + response.error());
        if (response.kind() != PageFetcher.Kind.OK) return;
        Path path = batch ? Path.of(output, PageCanonicalizer.sha256(entry).substring(0, 12) + ".html") : Path.of(output);
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        Files.writeString(path, response.body());
        System.out.println("EVIDENCE " + entry + " -> " + path);
        if (response.contentType() != null && response.contentType().contains("html")) {
            var doc = Jsoup.parse(response.body(), response.finalUrl());
            System.out.println("TITLE " + doc.title());
            System.out.println("HEADINGS " + doc.select("h1,h2,h3").eachText());
            doc.select("a[href]").stream().filter(a -> a.absUrl("href").contains("opintopolku.fi")
                    || a.absUrl("href").contains("studyinfo.fi"))
                    .forEach(a -> System.out.println("STUDYINFO " + a.text() + " " + a.absUrl("href")));
        }
    }
}
