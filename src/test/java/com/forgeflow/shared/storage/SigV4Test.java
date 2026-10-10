package com.forgeflow.shared.storage;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Hand-rolled crypto is only as good as its test vectors. */
class SigV4Test {

    /**
     * The "GET Object" worked example from AWS's S3 docs (Signature Version 4,
     * "Examples: Signature Calculations"). Same keys, same request, same
     * expected signature as the documentation.
     */
    @Test
    void matchesTheWorkedExampleInTheS3Documentation() {
        String auth = SigV4.authorization("GET", "/test.txt", "",
                Map.of("Host", "examplebucket.s3.amazonaws.com",
                        "Range", "bytes=0-9",
                        "x-amz-content-sha256", SigV4.EMPTY_SHA256,
                        "x-amz-date", "20130524T000000Z"),
                SigV4.EMPTY_SHA256,
                "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                "us-east-1", "s3", "20130524T000000Z");

        assertThat(auth).isEqualTo("AWS4-HMAC-SHA256 "
                + "Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, "
                + "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, "
                + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41");
    }

    @Test
    void pathsAreEncodedTheS3Way() {
        assertThat(SigV4.encodePath("/bucket/projects/7/blobs/ab c$d.js")).isEqualTo("/bucket/projects/7/blobs/ab%20c%24d.js");
        assertThat(SigV4.encodePath("/b/café")).isEqualTo("/b/caf%C3%A9");
        assertThat(SigV4.sha256Hex("")).isEqualTo(SigV4.EMPTY_SHA256);
    }
}
