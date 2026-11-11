package com.pathwayai.backend.refresh.fetch;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * One HTTP GET, without redirect following (the fetcher validates every hop itself). The seam that lets every
 * crawler test run against recorded fixtures instead of live websites.
 */
public interface HttpTransport {

    /** Header names are lower-case. {@code tooLarge} means the body exceeded {@code maxBytes} and was discarded. */
    record Response(int status, Map<String, String> headers, byte[] body, boolean tooLarge) {
        public String header(String name) {
            return headers.get(name.toLowerCase(java.util.Locale.ROOT));
        }
    }

    Response get(URI uri, Map<String, String> requestHeaders, Duration timeout, int maxBytes) throws IOException;
}
