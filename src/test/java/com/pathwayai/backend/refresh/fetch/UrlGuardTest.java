package com.pathwayai.backend.refresh.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SSRF protection. No network: the resolver is a lambda. */
class UrlGuardTest {

    static final InetAddress PUBLIC = addr(93, 184, 216, 34);

    static InetAddress addr(int... b) {
        try {
            byte[] bytes = new byte[b.length];
            for (int i = 0; i < b.length; i++) {
                bytes[i] = (byte) b[i];
            }
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    static UrlGuard guard(InetAddress... resolved) {
        return new UrlGuard(host -> List.of(resolved), host -> host.endsWith("uni.example"));
    }

    @Test
    void acceptsAnApprovedHttpsHostThatResolvesToAPublicAddress() throws Exception {
        assertThat(guard(PUBLIC).validate("https://www.uni.example/admissions").getHost()).isEqualTo("www.uni.example");
    }

    @Test
    void rejectsPlainHttpCredentialsAndOddPorts() {
        assertThatThrownBy(() -> guard(PUBLIC).validate("http://www.uni.example/")).hasMessageContaining("https");
        assertThatThrownBy(() -> guard(PUBLIC).validate("https://user:pw@www.uni.example/")).hasMessageContaining("credentials");
        assertThatThrownBy(() -> guard(PUBLIC).validate("https://www.uni.example:8443/")).hasMessageContaining("port");
        assertThatThrownBy(() -> guard(PUBLIC).validate("file:///etc/passwd")).hasMessageContaining("https");
        assertThatThrownBy(() -> guard(PUBLIC).validate("not a url")).isInstanceOf(UrlGuard.UnsafeUrlException.class);
    }

    @Test
    void rejectsAHostThatIsNotApproved() {
        assertThatThrownBy(() -> guard(PUBLIC).validate("https://evil.example/")).hasMessageContaining("not an approved");
    }

    @Test
    void rejectsEveryPrivateAndInternalAddressRange() {
        List<InetAddress> internal = List.of(
                addr(127, 0, 0, 1), addr(10, 1, 2, 3), addr(172, 16, 0, 1), addr(192, 168, 1, 1),
                addr(169, 254, 169, 254),   // cloud metadata
                addr(100, 64, 0, 1),        // carrier-grade NAT
                addr(0, 0, 0, 0), addr(198, 18, 0, 1), addr(240, 0, 0, 1), addr(224, 0, 0, 1),
                addr(0xfc, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),   // unique local IPv6
                addr(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1));    // IPv6 loopback
        for (InetAddress a : internal) {
            assertThatThrownBy(() -> guard(a).validate("https://www.uni.example/"))
                    .as(a.getHostAddress()).hasMessageContaining("private or internal");
        }
    }

    @Test
    void oneInternalAddressAmongPublicOnesIsEnoughToReject() {
        assertThatThrownBy(() -> guard(PUBLIC, addr(10, 0, 0, 1)).validate("https://www.uni.example/"))
                .hasMessageContaining("private or internal");
    }

    @Test
    void rejectsAHostThatDoesNotResolve() {
        var g = new UrlGuard(host -> {
            throw new UnknownHostException(host);
        }, h -> true);
        assertThatThrownBy(() -> g.validate("https://gone.uni.example/")).hasMessageContaining("does not resolve");
    }
}
