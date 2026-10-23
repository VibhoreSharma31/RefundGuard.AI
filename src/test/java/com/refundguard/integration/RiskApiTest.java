package com.refundguard.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RiskApiTest {

    @Autowired MockMvc mvc;

    @Test
    void methodologyStatesRuleBasedAndNotMachineLearning() throws Exception {
        mvc.perform(get("/api/risk/methodology").with(httpBasic("operator", "Rg-Test-Pass-O-1234")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("RULE_BASED"))
                .andExpect(jsonPath("$.machineLearning").value(false));
    }

    @Test
    void riskEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/risk/latest")).andExpect(status().isUnauthorized());
    }
}
