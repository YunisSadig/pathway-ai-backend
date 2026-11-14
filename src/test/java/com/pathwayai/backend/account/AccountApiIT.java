package com.pathwayai.backend.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pathwayai.backend.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Accounts, sessions and profiles against a real PostgreSQL. Session cookies are carried between requests exactly as a browser would, so
 * the server-side JDBC session store is part of every test. Synthetic data only (addresses end in @it.example).
 */
@SpringBootTest(properties = "app.access.catalogue-public=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccountApiIT {

    static final String PASSWORD = "correct horse battery 7";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        jdbc.update("delete from user_account where email_normalized like '%@it.example'");
        jdbc.update("delete from auth_throttle");
        jdbc.update("delete from spring_session");
    }

    static String email() {
        return "user-" + UUID.randomUUID().toString().substring(0, 8) + "@it.example";
    }

    Cookie register(String email) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/account/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"displayName\":\"Test Student\"}"))
                .andExpect(status().isCreated()).andReturn();
        return r.getResponse().getCookie("PATHWAY_SESSION");
    }

    @Test
    void configIsPublicAndTruthful() throws Exception {
        mvc.perform(get("/api/v1/account/config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationOpen").value(true))
                .andExpect(jsonPath("$.emailDeliveryAvailable").value(false)) // no SMTP server in tests
                .andExpect(jsonPath("$.cataloguePublic").value(false));
    }

    @Test
    void registrationCreatesAnAccountAndAnHttpOnlySessionAndNeverReturnsTheHash() throws Exception {
        String email = email();
        MvcResult r = mvc.perform(post("/api/v1/account/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email.toUpperCase() + "\",\"password\":\"" + PASSWORD + "\",\"displayName\":\"Ada\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.account.email").value(email.toUpperCase()))
                .andExpect(jsonPath("$.account.role").value("USER"))
                .andExpect(jsonPath("$.account.catalogueAccess").value(false))
                .andExpect(jsonPath("$.account.emailVerified").value(false))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content -> assertThat(content.getResponse().getContentAsString()).doesNotContain("hash").doesNotContain(PASSWORD))
                .andReturn();
        String setCookie = r.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains("PATHWAY_SESSION=").containsIgnoringCase("HttpOnly").containsIgnoringCase("SameSite=Lax");
        // stored hashed, never plain, and the email is normalised for uniqueness
        String stored = jdbc.queryForObject("select password_hash from user_account where email_normalized = ?", String.class, email);
        assertThat(stored).startsWith("$2").doesNotContain(PASSWORD);
    }

    @Test
    void weakPasswordsAndBadEmailsAreRefusedWithFieldMessages() throws Exception {
        mvc.perform(post("/api/v1/account/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email() + "\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WEAK_PASSWORD"))
                .andExpect(jsonPath("$.violations[0].field").value("password"));
        mvc.perform(post("/api/v1/account/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.violations[0].field").value("email"));
        mvc.perform(post("/api/v1/account/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email() + "\",\"password\":\"password123\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));
    }

    @Test
    void aSecondRegistrationWithTheSameEmailIsRefusedCaseInsensitively() throws Exception {
        String email = email();
        register(email);
        mvc.perform(post("/api/v1/account/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email.toUpperCase() + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    @Test
    void sessionSurvivesAcrossRequestsAndLogoutEndsItOnTheServer() throws Exception {
        String email = email();
        Cookie session = register(email);
        mvc.perform(get("/api/v1/account/me").cookie(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email)).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post("/api/v1/account/logout").cookie(session)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/account/me").cookie(session)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from spring_session", Integer.class)).isZero();
    }

    @Test
    void anonymousAndForgedSessionsAreRejected() throws Exception {
        mvc.perform(get("/api/v1/account/me")).andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/account/me").cookie(new Cookie("PATHWAY_SESSION", "ZGVhZGJlZWYtZGVhZC1iZWVmLWRlYWQtYmVlZjAwMDAw")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/account/profile")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/account/saved")).andExpect(status().isUnauthorized());
    }

    @Test
    void loginAnswersIdenticallyForAWrongPasswordAndAnUnknownEmail() throws Exception {
        String email = email();
        register(email);
        String wrongPassword = mvc.perform(post("/api/v1/account/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"definitely not it 11\"}"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String unknown = mvc.perform(post("/api/v1/account/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody-" + UUID.randomUUID() + "@it.example\",\"password\":\"definitely not it 11\"}"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertThat(wrongPassword).isEqualTo(unknown).contains("INVALID_CREDENTIALS");
        mvc.perform(post("/api/v1/account/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void repeatedFailuresLockTheEmailForRegisteredAndUnregisteredAddressesAlike() throws Exception {
        String known = email();
        String unknown = "ghost-" + UUID.randomUUID().toString().substring(0, 6) + "@it.example";
        register(known);
        for (String target : new String[] {known, unknown}) {
            for (int i = 0; i < 5; i++) {
                mvc.perform(post("/api/v1/account/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + target + "\",\"password\":\"wrong password " + i + "xx\"}")).andExpect(status().isUnauthorized());
            }
            mvc.perform(post("/api/v1/account/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + target + "\",\"password\":\"" + PASSWORD + "\"}"))
                    .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
        }
    }

    @Test
    void aCrossSiteWriteIsRefusedByItsOrigin() throws Exception {
        mvc.perform(post("/api/v1/account/login").header("Origin", "https://evil.example").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@it.example\",\"password\":\"whatever whatever\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BAD_ORIGIN"));
        mvc.perform(post("/api/v1/account/login").contentType(MediaType.APPLICATION_JSON) // no Origin: the website's proxy sends none
                        .content("{\"email\":\"a@it.example\",\"password\":\"whatever whatever\"}"))
                .andExpect(status().isUnauthorized()); // passes the origin check and fails on credentials
    }

    @Test
    void profileRoundTripsAndEachStudentSeesOnlyTheirOwn() throws Exception {
        Cookie ada = register(email());
        Cookie bob = register(email());
        mvc.perform(put("/api/v1/account/profile").cookie(ada).contentType(MediaType.APPLICATION_JSON).content("""
                {"currentCountry":"PL","currentUniversity":"Synthetic Uni","currentDegreeLevel":"BACHELOR","fieldOfStudy":"Biology",
                 "gpa":3.6,"gpaScaleMax":4.0,"targetDegreeLevel":"MASTER","intakeSeason":"AUTUMN","intakeYear":2027,
                 "maxAnnualTuition":12000,"maxAnnualTuitionCurrency":"EUR",
                 "languageQualifications":[{"test":"IELTS","overall":7.5}],"preferredDestinations":["FI","DE"]}"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currentCountry").value("PL"))
                .andExpect(jsonPath("$.languageQualifications[0].overall").value(7.5))
                .andExpect(jsonPath("$.preferredDestinations[0]").value("DE")).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/v1/account/profile").cookie(ada)).andExpect(status().isOk())
                .andExpect(jsonPath("$.fieldOfStudy").value("Biology")).andExpect(jsonPath("$.maxAnnualTuition").value(12000.0));
        // Bob's profile is empty and Ada's data never appears in it
        mvc.perform(get("/api/v1/account/profile").cookie(bob)).andExpect(status().isOk())
                .andExpect(jsonPath("$.currentCountry").doesNotExist()).andExpect(jsonPath("$.languageQualifications").isEmpty())
                .andExpect(content -> assertThat(content.getResponse().getContentAsString()).doesNotContain("Biology").doesNotContain("Synthetic Uni"));
        // there is no URL that names another account
        mvc.perform(get("/api/v1/account/profile/" + UUID.randomUUID()).cookie(bob)).andExpect(status().is4xxClientError());
    }

    @Test
    void profileValidationExplainsEachProblem() throws Exception {
        Cookie ada = register(email());
        String[][] bad = {
                {"{\"gpa\":5,\"gpaScaleMax\":4}", "gpa"},
                {"{\"gpa\":3}", "gpa"},
                {"{\"currentCountry\":\"ZZ\"}", "currentCountry"},
                {"{\"intakeYear\":2027}", "intakeYear"},
                {"{\"maxAnnualTuition\":100}", "maxAnnualTuitionCurrency"},
                {"{\"maxAnnualTuition\":100,\"maxAnnualTuitionCurrency\":\"QQQ\"}", "maxAnnualTuitionCurrency"},
                {"{\"languageQualifications\":[{\"test\":\"IELTS\",\"overall\":12}]}", "languageQualifications[0]"},
                {"{\"languageQualifications\":[{\"test\":\"IELTS\",\"overall\":7},{\"test\":\"IELTS\",\"overall\":6}]}", "languageQualifications[1]"},
                {"{\"preferredDestinations\":[\"FI\",\"XX\"]}", "preferredDestinations"}};
        for (String[] c : bad) {
            mvc.perform(put("/api/v1/account/profile").cookie(ada).contentType(MediaType.APPLICATION_JSON).content(c[0]))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.violations[0].field").value(c[1]));
        }
        mvc.perform(put("/api/v1/account/profile").cookie(ada).contentType(MediaType.APPLICATION_JSON).content("{\"targetDegreeLevel\":\"WIZARD\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void profileCanBeClearedAndTheAccountExported() throws Exception {
        Cookie ada = register(email());
        mvc.perform(put("/api/v1/account/profile").cookie(ada).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fieldOfStudy\":\"Physics\",\"preferredDestinations\":[\"FI\"]}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/account/export").cookie(ada)).andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.fieldOfStudy").value("Physics")).andExpect(jsonPath("$.exportedFor.email").exists())
                .andExpect(content -> assertThat(content.getResponse().getContentAsString()).doesNotContain("passwordHash").doesNotContain("$2"));
        mvc.perform(delete("/api/v1/account/profile").cookie(ada)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/account/profile").cookie(ada)).andExpect(jsonPath("$.fieldOfStudy").doesNotExist())
                .andExpect(jsonPath("$.preferredDestinations").isEmpty());
    }

    @Test
    void changingThePasswordEndsOtherSessionsButKeepsTheCurrentOne() throws Exception {
        String email = email();
        Cookie first = register(email);
        Cookie second = mvc.perform(post("/api/v1/account/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")).andExpect(status().isOk()).andReturn()
                .getResponse().getCookie("PATHWAY_SESSION");
        mvc.perform(post("/api/v1/account/change-password").cookie(first).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"nope nope nope 1\",\"newPassword\":\"another long passphrase 9\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/account/change-password").cookie(first).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"another long passphrase 9\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/account/me").cookie(first)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/account/me").cookie(second)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/account/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/account/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"another long passphrase 9\"}")).andExpect(status().isOk());
    }

    @Test
    void deletingTheAccountNeedsThePasswordAndRemovesEverything() throws Exception {
        String email = email();
        Cookie session = register(email);
        mvc.perform(put("/api/v1/account/profile").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fieldOfStudy\":\"Chemistry\",\"preferredDestinations\":[\"FI\"],\"languageQualifications\":[{\"test\":\"IELTS\",\"overall\":7}]}"))
                .andExpect(status().isOk());
        UUID id = jdbc.queryForObject("select id from user_account where email_normalized = ?", UUID.class, email);
        mvc.perform(delete("/api/v1/account").cookie(session).contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"wrong password 123\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/account/me").cookie(session)).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/account").cookie(session).contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/account/me").cookie(session)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/account/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")).andExpect(status().isUnauthorized());
        for (String table : new String[] {"user_account where id", "student_profile where account_id", "student_language_qualification where account_id",
                "student_destination_preference where account_id", "saved_program where account_id", "account_token where account_id"}) {
            assertThat(jdbc.queryForObject("select count(*) from " + table + " = ?", Integer.class, id)).as(table).isZero();
        }
        assertThat(jdbc.queryForObject("select count(*) from spring_session where principal_name = ?", Integer.class, id.toString())).isZero();
    }

    @Test
    void emailAndPasswordFeaturesAreHonestlyUnavailableWithoutAnSmtpServer() throws Exception {
        mvc.perform(post("/api/v1/account/forgot-password").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@it.example\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("EMAIL_UNAVAILABLE"));
        mvc.perform(post("/api/v1/account/verify-email").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"bogus\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void accountResponsesAreNeverCacheable() throws Exception {
        mvc.perform(get("/api/v1/account/config")).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/v1/account/me")).andExpect(header().string("Cache-Control", not(containsString("public"))));
    }
}
