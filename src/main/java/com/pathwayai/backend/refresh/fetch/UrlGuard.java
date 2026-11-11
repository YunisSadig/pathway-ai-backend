package com.pathwayai.backend.refresh.fetch;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * SSRF protection. A URL may be fetched only if it is https, has no credentials, uses the default port, belongs to
 * an approved host, and every address its host resolves to is public. Applied to the first URL and to every redirect
 * hop. (DNS can change between this check and the connection; the approved-host rule and the https-only rule bound
 * that risk, and nothing here follows an address the check did not see.)
 */
public final class UrlGuard {

    /** Resolves a host to its addresses. A seam for tests; production uses the system resolver. */
    @FunctionalInterface
    public interface HostResolver {
        List<InetAddress> resolve(String host) throws UnknownHostException;
    }

    public static final HostResolver SYSTEM = host -> List.of(InetAddress.getAllByName(host));

    public static final class UnsafeUrlException extends Exception {
        public UnsafeUrlException(String message) {
            super(message);
        }
    }

    private final HostResolver resolver;
    private final Predicate<String> approvedHost;

    public UrlGuard(HostResolver resolver, Predicate<String> approvedHost) {
        this.resolver = resolver;
        this.approvedHost = approvedHost;
    }

    public URI validate(String url) throws UnsafeUrlException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new UnsafeUrlException("malformed URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new UnsafeUrlException("only https is allowed");
        }
        if (uri.getUserInfo() != null) {
            throw new UnsafeUrlException("credentials in the URL are not allowed");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw new UnsafeUrlException("non-standard port");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new UnsafeUrlException("no host");
        }
        host = host.toLowerCase(Locale.ROOT);
        if (!approvedHost.test(host)) {
            throw new UnsafeUrlException("host is not an approved source host: " + host);
        }
        List<InetAddress> addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new UnsafeUrlException("host does not resolve: " + host);
        }
        if (addresses.isEmpty()) {
            throw new UnsafeUrlException("host does not resolve: " + host);
        }
        for (InetAddress a : addresses) {
            if (isInternal(a)) {
                throw new UnsafeUrlException("host resolves to a private or internal address");
            }
        }
        return uri;
    }

    static boolean isInternal(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xff;
            int b1 = b[1] & 0xff;
            return b0 == 0                                   // "this" network
                    || (b0 == 100 && b1 >= 64 && b1 <= 127)  // carrier-grade NAT
                    || (b0 == 192 && b1 == 0 && (b[2] & 0xff) == 0) // IETF protocol assignments
                    || (b0 == 198 && (b1 == 18 || b1 == 19)) // benchmarking
                    || b0 >= 240;                             // reserved
        }
        if (a instanceof Inet6Address) {
            return (b[0] & 0xfe) == 0xfc;                     // unique local fc00::/7
        }
        return false;
    }
}
