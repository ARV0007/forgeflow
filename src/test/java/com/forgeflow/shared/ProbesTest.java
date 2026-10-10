package com.forgeflow.shared;

import com.forgeflow.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;

/** The endpoints deploy/k8s points its probes at exist, without a token. */
class ProbesTest extends ApiTestSupport {

    @Test
    void livenessAndReadinessAreServed() throws Exception {
        for (String probe : new String[]{"/actuator/health/liveness", "/actuator/health/readiness"}) {
            var r = mvc.perform(MockMvcRequestBuilders.get(probe)).andReturn().getResponse();
            assertThat(r.getStatus()).as(probe).isEqualTo(200);
            assertThat(r.getContentAsString()).contains("UP");
        }
    }
}
