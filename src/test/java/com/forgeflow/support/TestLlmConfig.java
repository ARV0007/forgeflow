package com.forgeflow.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the real model with ScriptedLlm for every integration test. */
@TestConfiguration
public class TestLlmConfig {

    @Bean
    @Primary
    public ScriptedLlm scriptedLlm() {
        return new ScriptedLlm();
    }
}
