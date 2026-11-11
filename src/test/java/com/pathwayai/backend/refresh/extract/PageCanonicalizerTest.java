package com.pathwayai.backend.refresh.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathwayai.backend.refresh.support.FakeTransport;
import org.junit.jupiter.api.Test;

class PageCanonicalizerTest {

    final PageCanonicalizer canonicalizer = new PageCanonicalizer();

    String hash(String html) {
        return canonicalizer.canonicalize(html, "text/html").hash();
    }

    @Test
    void changesToNavigationBannersAndFootersDoNotChangeTheHash() {
        String a = FakeTransport.page("Tuition is 100 EUR per year.", "Home About Contact");
        String b = FakeTransport.page("Tuition is 100 EUR per year.", "Totally different menu, cookies and adverts 2027");
        assertThat(hash(a)).isEqualTo(hash(b));
    }

    @Test
    void aChangeInTheMainContentChangesTheHash() {
        String a = FakeTransport.page("Tuition is 100 EUR per year.", "menu");
        String b = FakeTransport.page("Tuition is 120 EUR per year.", "menu");
        assertThat(hash(a)).isNotEqualTo(hash(b));
    }

    @Test
    void timestampsAndCopyrightLinesAreNotContent() {
        String a = "<html><body><main><p>Fees apply.</p><p>Last updated: 3 March 2026</p><p>© 2026 University</p></main></body></html>";
        String b = "<html><body><main><p>Fees apply.</p><p>Last updated: 9 June 2027</p><p>© 2027 University</p></main></body></html>";
        assertThat(hash(a)).isEqualTo(hash(b));
    }

    @Test
    void scriptsStylesAndHiddenElementsAreIgnored() {
        String a = "<html><body><main><p>Text</p><script>var x=1;</script><style>p{}</style><div hidden>secret</div></main></body></html>";
        String b = "<html><body><main><p>Text</p><script>var x=2;</script></main></body></html>";
        assertThat(hash(a)).isEqualTo(hash(b));
    }

    @Test
    void whitespaceDifferencesDoNotMatter() {
        assertThat(hash("<main><p>A   B</p>\n\n<p>C</p></main>")).isEqualTo(hash("<main><p>A B</p><p>C</p></main>"));
    }

    @Test
    void structuredDataIsCollectedBeforeScriptsAreRemoved() {
        String html = FakeTransport.pageWithJsonLd("Body", "{\"@type\":\"Course\"}");
        var page = canonicalizer.canonicalize(html, "text/html");
        assertThat(page.jsonLd()).containsExactly("{\"@type\":\"Course\"}");
        assertThat(page.text()).isEqualTo("Body");
        assertThat(page.title()).isEqualTo("Programme");
    }

    @Test
    void jsonDocumentsAreHashedAsTheyAre() {
        var a = canonicalizer.canonicalize("{\"v\":1}\n", "application/json");
        var b = canonicalizer.canonicalize("{\"v\":1}", "application/json");
        var c = canonicalizer.canonicalize("{\"v\":2}", "application/json");
        assertThat(a.hash()).isEqualTo(b.hash()).isNotEqualTo(c.hash());
    }

    @Test
    void theHashIsAStableSha256() {
        assertThat(PageCanonicalizer.sha256("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
