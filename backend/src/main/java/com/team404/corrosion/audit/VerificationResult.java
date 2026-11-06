package com.team404.corrosion.audit;

/** Outcome of walking the chain. firstBrokenId is null when the chain is intact. */
public record VerificationResult(boolean valid, long entriesChecked, Long firstBrokenId, String message) {

    public static VerificationResult ok(long entriesChecked) {
        return new VerificationResult(true, entriesChecked, null, "Chain intact");
    }

    public static VerificationResult broken(long entriesChecked, long firstBrokenId, String message) {
        return new VerificationResult(false, entriesChecked, firstBrokenId, message);
    }
}
