package com.pathwayai.backend.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pathwayai.backend.TestcontainersConfiguration;
import com.pathwayai.backend.account.GoogleIdentityVerifier.GoogleAuthException;
import com.pathwayai.backend.account.GoogleIdentityVerifier.Identity;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Google sign-in with a stubbed Google (the real token check is covered by GoogleIdentityValidationTest). Synthetic identities only. */
@SpringBootTest(properties = {"app.access.catalogue-public=false", "app.google.client-id=test-client-id",
        "app.google.client-secret=test-client-secret", "app.account.site-url=http://site.test"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, GoogleAuthIT.StubGoogle.class})
class GoogleAuthIT {

    static final AtomicReference<Identity> NEXT = new AtomicReference<>();
    static final AtomicReference<RuntimeException> FAIL = new AtomicReference<>();

    @TestConfiguration
    static class StubGoogle {
        @Bean
        @Primary
        GoogleIdentityVerifier stubGoogle() {
            return (code, verifier, redirect, nonce) -> {
                if (FAIL.get() != null) {
                    throw FAIL.get();
                }
                return NEXT.get();
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean ExternalIdentityRepository identitiesSpy;

    @AfterEach
    void clean() {
        NEXT.set(null);
        FAIL.set(null);
        jdbc.update("delete from user_account where email_normalized like '%@g.example'");
        jdbc.update("delete from auth_throttle");
        jdbc.update("delete from spring_session");
    }

    static String mail() {
        return "u" + System.nanoTime() + "@g.example";
    }

    static Identity google(String sub, String email, boolean verified) {
        return new Identity(sub, email, verified, "Gina Google");
    }

    /** Runs the start step and returns the session cookie plus the state Google would echo back. */
    record Started(Cookie session, String state, String location) {
    }

    Started start(String query, Cookie existing) throws Exception {
        var req = get("/api/v1/account/oauth/google/start" + query);
        if (existing != null) {
            req = req.cookie(existing);
        }
        MvcResult r = mvc.perform(req).andExpect(status().isFound()).andReturn();
        String location = r.getResponse().getHeader("Location");
        Matcher m = Pattern.compile("[?&]state=([^&]+)").matcher(location);
        assertThat(m.find()).isTrue();
        Cookie cookie = r.getResponse().getCookie("PATHWAY_SESSION");
        return new Started(cookie != null ? cookie : existing, m.group(1), location);
    }

    MvcResult callback(Started s, String state) throws Exception {
        return mvc.perform(get("/api/v1/account/oauth/google/callback?code=abc&state=" + state).cookie(s.session())).andReturn();
    }

    static String location(MvcResult r) {
        return r.getResponse().getHeader("Location");
    }

    long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void googleLoginIsOfferedOnlyWhenConfiguredAndTheStartRedirectIsSound() throws Exception {
        mvc.perform(get("/api/v1/account/config")).andExpect(jsonPath("$.googleLoginAvailable").value(true));
        Started s = start("", null);
        URI uri = URI.create(s.location());
        assertThat(uri.getHost()).isEqualTo("accounts.google.com");
        String q = s.location();
        assertThat(q).contains("response_type=code", "client_id=test-client-id", "code_challenge_method=S256", "scope=openid+email+profile",
                "redirect_uri=http%3A%2F%2Fsite.test%2Fapi%2Fv1%2Faccount%2Foauth%2Fgoogle%2Fcallback", "nonce=", "code_challenge=");
        assertThat(q).doesNotContain("test-client-secret").doesNotContain("code_verifier");
        assertThat(s.session()).isNotNull();
    }

    @Test
    void aFirstTimeGoogleUserGetsOneAccountWithoutAPasswordAndASession() throws Exception {
        String email = mail();
        NEXT.set(google("sub-new-1", email, true));
        Started s = start("", null);
        MvcResult r = callback(s, s.state());
        assertThat(location(r)).isEqualTo("http://site.test/dashboard?welcome=1");
        Cookie session = r.getResponse().getCookie("PATHWAY_SESSION");
        assertThat(session).isNotNull();
        mvc.perform(get("/api/v1/account/me").cookie(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email)).andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.hasPassword").value(false)).andExpect(jsonPath("$.providers[0]").value("GOOGLE"));
        assertThat(jdbc.queryForObject("select password_hash from user_account where email_normalized = ?", String.class, email)).isNull();
        assertThat(count("select count(*) from external_identity where subject = 'sub-new-1'")).isEqualTo(1);
    }

    @Test
    void aReturningGoogleUserIsTheSameAccountWithItsDataAndNoDuplicate() throws Exception {
        String email = mail();
        NEXT.set(google("sub-ret-1", email, true));
        Started s1 = start("", null);
        MvcResult first = callback(s1, s1.state());
        Cookie one = first.getResponse().getCookie("PATHWAY_SESSION");
        String id = mvc.perform(get("/api/v1/account/me").cookie(one)).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/v1/account/logout").cookie(one).header("Origin", "http://localhost:3000")).andReturn();
        // a later sign-in: same subject, even if Google now reports another display name
        NEXT.set(new Identity("sub-ret-1", email, true, "Renamed"));
        Started s2 = start("?next=/saved", null);
        MvcResult second = callback(s2, s2.state());
        assertThat(location(second)).isEqualTo("http://site.test/saved");
        Cookie two = second.getResponse().getCookie("PATHWAY_SESSION");
        String again = mvc.perform(get("/api/v1/account/me").cookie(two)).andReturn().getResponse().getContentAsString();
        assertThat(again).contains(email);
        assertThat(count("select count(*) from user_account where email_normalized = ?", email)).isEqualTo(1);
        assertThat(id).contains(email);
    }

    @Test
    void aWrongOrMissingStateAndAReplayedCallbackCreateNothing() throws Exception {
        String email = mail();
        NEXT.set(google("sub-state-1", email, true));
        Started s = start("", null);
        assertThat(location(callback(s, "forged"))).isEqualTo("http://site.test/login?error=google_state");
        // the failed attempt consumed the pending login: even the right state no longer works
        assertThat(location(callback(s, s.state()))).isEqualTo("http://site.test/login?error=google_state");
        Started t = start("", null);
        assertThat(location(callback(t, t.state()))).startsWith("http://site.test/dashboard");
        assertThat(location(callback(t, t.state()))).isEqualTo("http://site.test/login?error=google_state"); // replay
        assertThat(count("select count(*) from user_account where email_normalized = ?", email)).isEqualTo(1);
        mvc.perform(get("/api/v1/account/oauth/google/callback?code=abc&state=x")).andExpect(status().isFound()); // no session at all
    }

    @Test
    void anExistingPasswordAccountIsNeverMergedByEmail() throws Exception {
        String email = mail();
        mvc.perform(post("/api/v1/account/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + AccountApiIT.PASSWORD + "\"}")).andExpect(status().isCreated());
        NEXT.set(google("sub-conflict-1", email, true));
        Started s = start("", null);
        MvcResult r = callback(s, s.state());
        // never merged by email, and no session: the visitor is sent to the one-time "prove it is your account" screen instead
        assertThat(location(r)).isEqualTo("http://site.test/login/link-google");
        assertThat(count("select count(*) from external_identity where subject = 'sub-conflict-1'")).isZero();
        mvc.perform(get("/api/v1/account/me").cookie(s.session())).andExpect(status().isUnauthorized()); // parked, not signed in
        // the password account still works and is untouched
        mvc.perform(post("/api/v1/account/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + AccountApiIT.PASSWORD + "\"}")).andExpect(status().isOk());
    }

    @Test
    void anUnverifiedGoogleAddressAndAFailedExchangeCreateNoAccount() throws Exception {
        String email = mail();
        NEXT.set(google("sub-unv-1", email, false));
        Started s = start("", null);
        assertThat(location(callback(s, s.state()))).isEqualTo("http://site.test/login?error=google_unverified_email");
        FAIL.set(new GoogleAuthException("identity token rejected: wrong audience"));
        Started t = start("", null);
        assertThat(location(callback(t, t.state()))).isEqualTo("http://site.test/login?error=google_failed");
        assertThat(count("select count(*) from user_account where email_normalized = ?", email)).isZero();
        // the person cancelled at Google
        Started u = start("", null);
        MvcResult cancelled = mvc.perform(get("/api/v1/account/oauth/google/callback?error=access_denied&state=" + u.state()).cookie(u.session())).andReturn();
        assertThat(location(cancelled)).isEqualTo("http://site.test/login?error=google_cancelled");
    }

    @Test
    void theReturnAddressMustBeAPathOnThisSite() throws Exception {
        NEXT.set(google("sub-next-1", mail(), true));
        Started s = start("?next=//evil.example", null);
        // a new account goes to the welcome page; a returning one is covered by safeNext
        assertThat(GoogleAuthController.safeNext("//evil.example")).isEqualTo("/dashboard");
        assertThat(GoogleAuthController.safeNext("https://evil.example")).isEqualTo("/dashboard");
        assertThat(GoogleAuthController.safeNext("/\\evil")).isEqualTo("/dashboard");
        assertThat(GoogleAuthController.safeNext("/saved?x=1")).isEqualTo("/saved?x=1");
        assertThat(location(callback(s, s.state()))).startsWith("http://site.test/");
    }

    @Test
    void linkingNeedsTheSignedInOwnerAndTheirPasswordAndNeverStealsAnIdentity() throws Exception {
        String email = mail();
        Cookie owner = mvc.perform(post("/api/v1/account/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + AccountApiIT.PASSWORD + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie("PATHWAY_SESSION");
        // anonymous visitors cannot start linking; a wrong password is refused
        mvc.perform(post("/api/v1/account/oauth/google/link").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/account/oauth/google/link").cookie(owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"wrong password here\"}")).andExpect(status().isForbidden());
        // link mode without the intent is refused
        MvcResult noIntent = mvc.perform(get("/api/v1/account/oauth/google/start?mode=link").cookie(owner)).andReturn();
        assertThat(location(noIntent)).contains("google=expired");
        // the right password opens a short window; the Google step then attaches the identity to THIS account
        mvc.perform(post("/api/v1/account/oauth/google/link").cookie(owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + AccountApiIT.PASSWORD + "\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/api/v1/account/oauth/google/start?mode=link"));
        NEXT.set(google("sub-link-1", mail(), true)); // the Google address may differ from the account address
        Started s = start("?mode=link", owner);
        assertThat(location(callback(s, s.state()))).isEqualTo("http://site.test/settings?section=security&google=linked");
        assertThat(count("select count(*) from external_identity where subject = 'sub-link-1'")).isEqualTo(1);
        // from now on that Google identity opens the password account
        Started later = start("", null);
        Cookie viaGoogle = callback(later, later.state()).getResponse().getCookie("PATHWAY_SESSION");
        mvc.perform(get("/api/v1/account/me").cookie(viaGoogle)).andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.hasPassword").value(true)).andExpect(jsonPath("$.providers[0]").value("GOOGLE"));

        // a second account cannot take the same Google identity
        String other = mail();
        Cookie thief = mvc.perform(post("/api/v1/account/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + other + "\",\"password\":\"" + AccountApiIT.PASSWORD + "\"}"))
                .andReturn().getResponse().getCookie("PATHWAY_SESSION");
        mvc.perform(post("/api/v1/account/oauth/google/link").cookie(thief).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + AccountApiIT.PASSWORD + "\"}")).andExpect(status().isOk());
        Started t = start("?mode=link", thief);
        assertThat(location(callback(t, t.state()))).isEqualTo("http://site.test/settings?section=security&google=taken");
        assertThat(count("select count(*) from external_identity where subject = 'sub-link-1'")).isEqualTo(1);
    }

    /** Registers a password account and returns its address; the Google step for the same address is then parked as a pending link. */
    record Parked(String email, Cookie session, java.util.UUID accountId) {
    }

    Parked parkedLink(String sub) throws Exception {
        String email = mail();
        Cookie owner = mvc.perform(post("/api/v1/account/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + AccountApiIT.PASSWORD + "\",\"displayName\":\"Pat Student\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie("PATHWAY_SESSION");
        java.util.UUID id = jdbc.queryForObject("select id from user_account where email_normalized = ?", java.util.UUID.class, email);
        jdbc.update("insert into saved_university (account_id, university_slug) values (?, 'synthetic-university')", id);
        mvc.perform(post("/api/v1/account/logout").cookie(owner)).andReturn();
        NEXT.set(google(sub, email, true));
        Started s = start("", null);
        MvcResult r = callback(s, s.state());
        assertThat(location(r)).isEqualTo("http://site.test/login/link-google");
        return new Parked(email, s.session(), id);
    }

    String confirm(Cookie session, String password) {
        return "{\"password\":\"" + password + "\"}";
    }

    @Test
    void theOneTimePasswordProofLinksGoogleToTheSameAccountAndBothMethodsKeepWorking() throws Exception {
        Parked p = parkedLink("sub-onetime-1");
        long savedBefore = count("select count(*) from saved_university where account_id = ?", p.accountId());
        mvc.perform(get("/api/v1/account/oauth/google/pending-link").cookie(p.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(p.email()));
        MvcResult done = mvc.perform(post("/api/v1/account/oauth/google/confirm-link").cookie(p.session()).contentType(MediaType.APPLICATION_JSON)
                .content(confirm(p.session(), AccountApiIT.PASSWORD))).andExpect(status().isOk())
                .andExpect(jsonPath("$.next").value("/dashboard")).andReturn();
        Cookie session = done.getResponse().getCookie("PATHWAY_SESSION");
        assertThat(session.getValue()).isNotEqualTo(p.session().getValue()); // a new session id: no fixation
        // THE SAME account: same id, same data, still exactly one row for the address, now with Google as a provider next to the password
        mvc.perform(get("/api/v1/account/me").cookie(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(p.email())).andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.providers[0]").value("GOOGLE"));
        assertThat(count("select count(*) from user_account where email_normalized = ?", p.email())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select account_id from external_identity where subject = 'sub-onetime-1'", java.util.UUID.class))
                .isEqualTo(p.accountId());
        assertThat(count("select count(*) from saved_university where account_id = ?", p.accountId())).isEqualTo(savedBefore);
        // replaying the proof does nothing: the pending link was consumed
        mvc.perform(post("/api/v1/account/oauth/google/confirm-link").cookie(p.session()).contentType(MediaType.APPLICATION_JSON)
                .content(confirm(p.session(), AccountApiIT.PASSWORD))).andExpect(status().isGone());
        // from now on Google opens the same account directly, without the password
        NEXT.set(google("sub-onetime-1", p.email(), true));
        Started again = start("", null);
        MvcResult direct = callback(again, again.state());
        assertThat(location(direct)).isEqualTo("http://site.test/dashboard");
        mvc.perform(get("/api/v1/account/me").cookie(direct.getResponse().getCookie("PATHWAY_SESSION")))
                .andExpect(jsonPath("$.email").value(p.email()));
        // and the password still works
        mvc.perform(post("/api/v1/account/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + p.email() + "\",\"password\":\"" + AccountApiIT.PASSWORD + "\"}")).andExpect(status().isOk());
        assertThat(count("select count(*) from user_account where email_normalized = ?", p.email())).isEqualTo(1);
    }

    @Test
    void aWrongPasswordLinksNothingAndFiveMissesEndThePendingLink() throws Exception {
        Parked p = parkedLink("sub-wrong-1");
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/api/v1/account/oauth/google/confirm-link").cookie(p.session()).contentType(MediaType.APPLICATION_JSON)
                    .content(confirm(p.session(), "not the password"))).andExpect(status().isForbidden());
        }
        // still pending after four misses, and the right password would still work; a fifth miss ends it
        mvc.perform(get("/api/v1/account/oauth/google/pending-link").cookie(p.session())).andExpect(status().isOk());
        mvc.perform(post("/api/v1/account/oauth/google/confirm-link").cookie(p.session()).contentType(MediaType.APPLICATION_JSON)
                .content(confirm(p.session(), "not the password"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/account/oauth/google/confirm-link").cookie(p.session()).contentType(MediaType.APPLICATION_JSON)
                .content(confirm(p.session(), AccountApiIT.PASSWORD))).andExpect(status().isGone());
        assertThat(count("select count(*) from external_identity where subject = 'sub-wrong-1'")).isZero();
        assertThat(count("select count(*) from user_account where email_normalized = ?", p.email())).isEqualTo(1);
        // the original account is unchanged and still logs in
        mvc.perform(post("/api/v1/account/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + p.email() + "\",\"password\":\"" + AccountApiIT.PASSWORD + "\"}")).andExpect(status().isOk());
    }

    @Test
    void withoutAPendingLinkTheProofEndpointsAnswerGoneAndNotFoundNeverAnAccount() throws Exception {
        mvc.perform(get("/api/v1/account/oauth/google/pending-link")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/account/oauth/google/confirm-link").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + AccountApiIT.PASSWORD + "\"}")).andExpect(status().isGone());
    }

    @Test
    void aGoogleSubjectAlreadyLinkedElsewhereCannotBeAttachedByTheProof() throws Exception {
        Parked p = parkedLink("sub-taken-1");
        // meanwhile somebody else's account took that Google subject
        String other = mail();
        NEXT.set(google("sub-taken-1", other, true));
        Started s = start("", null);
        callback(s, s.state());
        mvc.perform(post("/api/v1/account/oauth/google/confirm-link").cookie(p.session()).contentType(MediaType.APPLICATION_JSON)
                .content(confirm(p.session(), AccountApiIT.PASSWORD))).andExpect(status().isConflict());
        assertThat(count("select count(*) from external_identity where subject = 'sub-taken-1'")).isEqualTo(1);
        assertThat(count("select count(*) from external_identity where account_id = ?", p.accountId())).isZero();
    }

    @Test
    void aGoogleAddressThatIsNotVerifiedNeverStartsLinking() throws Exception {
        String email = mail();
        mvc.perform(post("/api/v1/account/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + AccountApiIT.PASSWORD + "\"}")).andExpect(status().isCreated());
        NEXT.set(google("sub-unverified-link", email, false));
        Started s = start("", null);
        assertThat(location(callback(s, s.state()))).isEqualTo("http://site.test/login?error=google_unverified_email");
        mvc.perform(get("/api/v1/account/oauth/google/pending-link").cookie(s.session())).andExpect(status().isNotFound());
    }

    @Test
    void anUnexpectedFailureInTheCallbackIsASafeRedirectNeverAServerError() throws Exception {
        // the real production failure: the external_identity table was missing, so the lookup threw a database error
        org.mockito.Mockito.doThrow(new org.springframework.jdbc.BadSqlGrammarException("lookup", "select ...",
                new java.sql.SQLException("relation \"external_identity\" does not exist"))).when(identitiesSpy).accountOf(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        NEXT.set(google("sub-boom-1", mail(), true));
        Started s = start("", null);
        MvcResult r = callback(s, s.state());
        assertThat(r.getResponse().getStatus()).isEqualTo(302);
        assertThat(location(r)).isEqualTo("http://site.test/login?error=google_failed");
        assertThat(r.getResponse().getContentAsString()).doesNotContain("external_identity").doesNotContain("Exception");
    }

    @Test
    void aGoogleOnlyAccountHasNoPasswordToChangeOrGuessAndSurvivesNothingLikeAMergeByReset() throws Exception {
        String email = mail();
        NEXT.set(google("sub-only-1", email, true));
        Started s = start("", null);
        Cookie session = callback(s, s.state()).getResponse().getCookie("PATHWAY_SESSION");
        mvc.perform(post("/api/v1/account/change-password").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"whatever whatever 1\",\"newPassword\":\"another long phrase 9\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_PASSWORD"));
        for (String guess : List.of("", "password", AccountApiIT.PASSWORD)) {
            mvc.perform(post("/api/v1/account/login").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\",\"password\":\"" + guess + "\"}")).andExpect(status().is4xxClientError());
        }
    }

    @Test
    void theDatabaseRefusesASecondIdentityForTheSameGoogleSubjectOrTwoForOneAccount() {
        String email = mail();
        jdbc.update("insert into user_account (email, email_normalized, password_hash) values (?, ?, null)", email, email);
        jdbc.update("insert into user_account (email, email_normalized, password_hash) values (?, ?, null)", "b" + email, "b" + email);
        Object a = jdbc.queryForObject("select id from user_account where email_normalized = ?", Object.class, email);
        Object b = jdbc.queryForObject("select id from user_account where email_normalized = ?", Object.class, "b" + email);
        jdbc.update("insert into external_identity (account_id, provider, subject) values (?, 'GOOGLE', 'sub-db-1')", a);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> jdbc.update("insert into external_identity (account_id, provider, subject) values (?, 'GOOGLE', 'sub-db-1')", b))
                .hasMessageContaining("uq_external_identity_subject");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> jdbc.update("insert into external_identity (account_id, provider, subject) values (?, 'GOOGLE', 'sub-db-2')", a))
                .hasMessageContaining("uq_external_identity_account_provider");
    }

    @Test
    void twoSimultaneousFirstLoginsOfTheSamePersonCreateExactlyOneAccount() throws Exception {
        String email = mail();
        NEXT.set(google("sub-race-1", email, true));
        Started a = start("", null);
        Started b = start("", null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<MvcResult>> jobs = List.of(() -> callback(a, a.state()), () -> callback(b, b.state()));
            List<Future<MvcResult>> done = pool.invokeAll(jobs);
            for (Future<MvcResult> f : done) {
                assertThat(location(f.get())).startsWith("http://site.test/dashboard");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("select count(*) from user_account where email_normalized = ?", email)).isEqualTo(1);
        assertThat(count("select count(*) from external_identity where subject = 'sub-race-1'")).isEqualTo(1);
    }
}
