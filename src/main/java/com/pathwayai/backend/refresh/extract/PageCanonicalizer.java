package com.pathwayai.backend.refresh.extract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * Reduces a fetched page to the text that matters, so that a change in navigation, cookie banners, advertising,
 * timestamps or scripts does not look like a change in tuition or requirements. The hash is of the canonical text,
 * not of the raw bytes. {@link #VERSION} changes whenever these rules change; the monitor then re-baselines the
 * hash instead of reporting every page as changed.
 */
@Component
public class PageCanonicalizer {

    public static final String VERSION = "canon-1";

    /** The text of a page plus its structured-data blocks, extracted before scripts were removed. */
    public record CanonicalPage(String title, String text, String hash, List<String> jsonLd) {
    }

    private static final String NOISE_SELECTORS = String.join(",",
            "script", "style", "noscript", "template", "svg", "iframe", "form", "nav", "header", "footer", "aside",
            "[role=navigation]", "[role=banner]", "[role=contentinfo]", "[aria-hidden=true]", "[hidden]");
    private static final Pattern NOISY_NAME = Pattern.compile(
            "(?i)cookie|consent|gdpr|banner|advert|promo|breadcrumb|share|social|newsletter|skip-?link|popup|modal");
    private static final Pattern VOLATILE = Pattern.compile(
            "(?i)(last (updated|modified)|updated on|page last|published on|date modified)\\s*[:\\-]?\\s*[^.\\n]{0,40}"
                    + "|©\\s*\\d{4}[^.\\n]{0,60}|copyright\\s*©?\\s*\\d{4}[^.\\n]{0,60}");

    public CanonicalPage canonicalize(String content, String contentType) {
        if (contentType != null && (contentType.contains("json") || contentType.equals("text/plain")
                || contentType.contains("xml") && !contentType.contains("html"))) {
            String text = content.strip();
            return new CanonicalPage("", text, sha256(text), List.of());
        }
        Document doc = Jsoup.parse(content);
        List<String> jsonLd = new ArrayList<>();
        for (Element e : doc.select("script[type=application/ld+json]")) {
            jsonLd.add(e.data());
        }
        String title = doc.title().strip();
        doc.select(NOISE_SELECTORS).remove();
        for (Element e : new ArrayList<>(doc.select("[class],[id]"))) {
            if (NOISY_NAME.matcher(e.className() + " " + e.id()).find()) {
                e.remove();
            }
        }
        Element main = doc.selectFirst("main, [role=main], article");
        Element root = main != null ? main : doc.body();
        String text = root == null ? "" : root.text();
        text = VOLATILE.matcher(text).replaceAll(" ").replaceAll("\\s+", " ").strip();
        return new CanonicalPage(title, text, sha256(text), jsonLd);
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
