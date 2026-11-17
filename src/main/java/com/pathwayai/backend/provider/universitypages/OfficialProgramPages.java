package com.pathwayai.backend.provider.universitypages;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;

/** Reviewed associations, never a slug generator. A match must also pass a live, guarded fetch. */
public final class OfficialProgramPages {
    public static final String VERSION = "university-links-1";
    private static final Map<String, String> DOMAINS = Map.of(
            "aalto-university", "www.aalto.fi", "university-of-helsinki", "www.helsinki.fi",
            "university-of-oulu", "www.oulu.fi");

    public record Match(String oid, String university, String name, String level, String url,
                        String heading, String qualification) {
        public Match {
            if (!universityOwned(university, url) || !List.of("BACHELOR", "MASTER").contains(level)
                    || oid == null || name == null || heading == null || qualification == null) {
                throw new IllegalArgumentException("Incomplete or non-university program association");
            }
        }

        public void verify(String finalUrl, String contentType, String html) {
            if (!url.equals(finalUrl) || contentType == null || !contentType.contains("html")) {
                throw new IllegalArgumentException("Destination moved or did not return HTML; review required");
            }
            var doc = Jsoup.parse(html);
            if (doc.select("h1").size() != 1 || !heading.equals(doc.select("h1").text().strip())
                    || !doc.text().contains(qualification)) {
                throw new IllegalArgumentException("Program heading or awarded qualification does not match");
            }
        }
    }

    public static boolean universityOwned(String university, String url) {
        if (url == null) return false;
        try {
            URI uri = URI.create(url);
            return "https".equals(uri.getScheme()) && DOMAINS.containsKey(university)
                    && DOMAINS.get(university).equals(uri.getHost()) && uri.getUserInfo() == null
                    && uri.getPort() == -1 && uri.getQuery() == null && uri.getFragment() == null
                    && uri.getPath() != null && (uri.getPath().startsWith("/en/study-options/")
                    || uri.getPath().startsWith("/en/apply/masters-")
                    || uri.getPath().startsWith("/en/degree-programmes/"));
        } catch (IllegalArgumentException e) { return false; }
    }

    public static boolean studyinfo(String url) {
        if (url == null) return false;
        try { return "opintopolku.fi".equals(URI.create(url).getHost()); }
        catch (IllegalArgumentException e) { return false; }
    }

    public static final List<Match> REVIEWED = List.of(
            new Match("1.2.246.562.13.00000000000000011018", "aalto-university",
                    "Civil Engineering, Master of Science (Technology) (2 yrs)", "MASTER",
                    "https://www.aalto.fi/en/study-options/civil-engineering-master-of-science-technology",
                    "Civil Engineering, Master of Science (Technology)", "Master of Science (Technology)"),
            new Match("1.2.246.562.13.00000000000000012103", "aalto-university",
                    "Energy Production, Economics and Sustainability, Master of Science (Technology) (2 yrs)", "MASTER",
                    "https://www.aalto.fi/en/study-options/energy-production-economics-and-sustainability-master-of-science-technology",
                    "Energy Production, Economics and Sustainability, Master of Science (Technology)", "Master of Science (Technology)"),
            new Match("1.2.246.562.13.00000000000000000923", "university-of-oulu",
                    "Architecture, Master of Science (Architecture) (2 years)", "MASTER",
                    "https://www.oulu.fi/en/apply/masters-architecture", "Master's in Architecture", "Master of Science (Architecture)"),
            new Match("1.2.246.562.13.00000000000000000916", "university-of-oulu",
                    "Biochemistry, Master of Science (2 years)", "MASTER",
                    "https://www.oulu.fi/en/apply/masters-biochemistry", "Master's in Biochemistry", "Master of Science"),
            new Match("1.2.246.562.13.00000000000000008015", "university-of-oulu",
                    "Biomedical Engineering, Master of Science (Technology) (2 years)", "MASTER",
                    "https://www.oulu.fi/en/apply/masters-biomedical-engineering", "Master's in Biomedical Engineering", "Master of Science (Technology)")
    );

    private OfficialProgramPages() { }
}
