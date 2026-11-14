package com.pathwayai.backend.refresh.registry;

import java.time.Instant;

/** One row of the source registry joined with its source URL. */
public record SourceMonitor(
        long sourceId, String url, Long universityId, Long programId, String domain, String kind,
        String extractionMethod, String adapterName, String adapterVersion, String status, String unsupportedReason,
        String verificationStatus, int checkIntervalDays, Instant lastAttemptAt, Instant lastSuccessAt,
        Instant lastChangeAt, String etag, String lastModified, String contentHash, String hashParserVersion,
        String lastResult, int failureCount, Instant nextCheckAt) {

    public static final String ACTIVE = "ACTIVE";
    public static final String FAILING = "FAILING";
    public static final String UNSUPPORTED = "UNSUPPORTED";
    public static final String PAUSED = "PAUSED";


    /** Mutable copy of the columns a check may change; see {@link #with}. */
    public static final class Fields {
        public String status, unsupportedReason, etag, lastModified, contentHash, hashParserVersion, lastResult;
        public String extractionMethod, adapterName, adapterVersion;
        public Instant lastAttemptAt, lastSuccessAt, lastChangeAt, nextCheckAt;
        public int failureCount;
    }

    /** Returns a copy with the given changes applied (the record itself is immutable). */
    public SourceMonitor with(java.util.function.Consumer<Fields> change) {
        Fields f = new Fields();
        f.status = status; f.unsupportedReason = unsupportedReason; f.etag = etag; f.lastModified = lastModified;
        f.contentHash = contentHash; f.hashParserVersion = hashParserVersion; f.lastResult = lastResult;
        f.extractionMethod = extractionMethod; f.adapterName = adapterName; f.adapterVersion = adapterVersion;
        f.lastAttemptAt = lastAttemptAt; f.lastSuccessAt = lastSuccessAt; f.lastChangeAt = lastChangeAt;
        f.nextCheckAt = nextCheckAt; f.failureCount = failureCount;
        change.accept(f);
        return new SourceMonitor(sourceId, url, universityId, programId, domain, kind, f.extractionMethod, f.adapterName,
                f.adapterVersion, f.status, f.unsupportedReason, verificationStatus, checkIntervalDays, f.lastAttemptAt,
                f.lastSuccessAt, f.lastChangeAt, f.etag, f.lastModified, f.contentHash, f.hashParserVersion,
                f.lastResult, f.failureCount, f.nextCheckAt);
    }

    /** A registration request: which page, what it is about, and how to read it. */
    public record Spec(String url, Long universityId, Long programId, String kind, String supportedFields,
                       String extractionMethod, String adapterName, String adapterVersion, int intervalDays) {
        public static Spec hashOnly(String url, Long universityId, Long programId, String kind, int intervalDays) {
            return new Spec(url, universityId, programId, kind, "", "CONTENT_HASH", "content-hash", "1", intervalDays);
        }
    }
}
