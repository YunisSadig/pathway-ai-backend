package com.pathwayai.backend.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pathwayai.backend.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

/** The flows that need email, with a mailbox stub: verification, password reset, and identical answers for known and unknown addresses. */
@SpringBootTest(properties = "app.access.catalogue-public=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AccountEmailFlowIT.Mailbox.class})
class AccountEmailFlowIT {

    record Sent(String to, String subject, String body) {
    }

    static final List<Sent> OUTBOX = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class Mailbox {
        @Bean
        @Primary
        MailGateway mailbox() {
            return new MailGateway() {
                @Override
                public boolean available() {
                    return true;
                }

                @Override
                public void send(String to, String subject, String text) {
                    OUTBOX.add(new Sent(to, subject, text));
                }
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void emptyOutbox() {
        OUTBOX.clear();
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from user_account where email_normalized like '%@it.example'");
        jdbc.update("delete from auth_throttle");
        jdbc.update("delete from spring_session");
    }

    static String tokenIn(Sent mail) {
        Matcher m = Pattern.compile("token=([A-Za-z0-9_-]+)").matcher(mail.body());
        assertThat(m.find()).as("a link with a token in: " + mail.subject()).isTrue();
        return m.group(1);
    }

    String registerPending(String email) throws Exception {
        mvc.perform(post("/api/v1/account/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + AccountApiIT.PASSWORD + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CREATED"));
        return tokenIn(OUTBOX.get(OUTBOX.size() - 1));
    }

    void login(String email, String password, int expected) throws Exception {
        mvc.perform(post("/api/v1/account/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")).andExpect(status().is(expected));
    }

    @Test
    void registrationSendsALinkButLoginNeverWaitsForIt() throws Exception {
        String email = AccountApiIT.email();
        String token = registerPending(email);
        assertThat(OUTBOX.get(0).to()).isEqualTo(email);
        login(email, AccountApiIT.PASSWORD, 200); // an unverified address can still log in
        mvc.perform(post("/api/v1/account/verify-email").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk());
        login(email, AccountApiIT.PASSWORD, 200);
        // a link works once
        mvc.perform(post("/api/v1/account/verify-email").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        // only the hash is stored
        assertThat(jdbc.queryForObject("select count(*) from account_token where token_hash = ?", Integer.class, token)).isZero();
    }

    @Test
    void anExpiredVerificationLinkIsRefused() throws Exception {
        String email = AccountApiIT.email();
        String token = registerPending(email);
        jdbc.update("update account_token set expires_at = now() - interval '1 minute'");
        mvc.perform(post("/api/v1/account/verify-email").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isBadRequest());
        login(email, AccountApiIT.PASSWORD, 200);
    }

    @Test
    void registeringAnExistingAddressIsRefusedPlainly() throws Exception {
        String email = AccountApiIT.email();
        registerPending(email);
        OUTBOX.clear();
        mvc.perform(post("/api/v1/account/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + AccountApiIT.PASSWORD + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
        assertThat(OUTBOX).isEmpty();
    }

    @Test
    void passwordResetWorksOnceEndsSessionsAndDoesNotRevealWhoIsRegistered() throws Exception {
        String email = AccountApiIT.email();
        String verify = registerPending(email);
        mvc.perform(post("/api/v1/account/verify-email").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + verify + "\"}"))
                .andExpect(status().isOk());
        Cookie session = mvc.perform(post("/api/v1/account/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + AccountApiIT.PASSWORD + "\"}")).andExpect(status().isOk()).andReturn()
                .getResponse().getCookie("PATHWAY_SESSION");
        OUTBOX.clear();

        String known = mvc.perform(post("/api/v1/account/forgot-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}")).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String unknown = mvc.perform(post("/api/v1/account/forgot-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nobody-" + UUID.randomUUID() + "@it.example\"}")).andExpect(status().isAccepted()).andReturn()
                .getResponse().getContentAsString();
        assertThat(known).isEqualTo(unknown);
        assertThat(OUTBOX).hasSize(1);
        String token = tokenIn(OUTBOX.get(0));

        mvc.perform(post("/api/v1/account/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"short\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/account/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"a brand new long passphrase 5\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/account/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"yet another long passphrase 6\"}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/account/me").cookie(session)).andExpect(status().isUnauthorized()); // reset ends every session
        login(email, AccountApiIT.PASSWORD, 401);
        login(email, "a brand new long passphrase 5", 200);
    }

    @Test
    void resendingAVerificationLinkAnswersTheSameForAnyAddress() throws Exception {
        String email = AccountApiIT.email();
        registerPending(email);
        OUTBOX.clear();
        String known = mvc.perform(post("/api/v1/account/resend-verification").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}")).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String unknown = mvc.perform(post("/api/v1/account/resend-verification").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nobody-" + UUID.randomUUID() + "@it.example\"}")).andExpect(status().isAccepted()).andReturn()
                .getResponse().getContentAsString();
        assertThat(known).isEqualTo(unknown);
        assertThat(OUTBOX).hasSize(1);
    }
}
