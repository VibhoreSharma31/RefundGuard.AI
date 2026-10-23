package com.refundguard.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Endpoint authorisation matrix, exercised with real users bootstrapped from the test properties. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RbacEndpointSecurityTest {

    private static final String ADMIN_PW = "Rg-Test-Pass-A-1234";
    private static final String OPERATOR_PW = "Rg-Test-Pass-O-1234";
    private static final String REVIEWER_PW = "Rg-Test-Pass-R-1234";

    @Autowired MockMvc mvc;

    @Test
    void unauthenticatedRequestsGet401WithoutBasicChallenge() throws Exception {
        mvc.perform(get("/api/refunds")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/reviews")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/simulator/reset")).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordIs401() throws Exception {
        mvc.perform(get("/api/refunds").with(httpBasic("operator", "not-the-password"))).andExpect(status().isUnauthorized());
    }

    @Test
    void webhookEndpointStaysPublicAndFailsClosedWithoutSecret() throws Exception {
        mvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable()); // reached the handler (not 401/403): secret not configured
    }

    @Test
    void operatorCanViewButNotReviewResetOrAdminister() throws Exception {
        mvc.perform(get("/api/refunds").with(httpBasic("operator", OPERATOR_PW))).andExpect(status().isOk());
        mvc.perform(get("/api/reviews").with(httpBasic("operator", OPERATOR_PW))).andExpect(status().isOk());
        mvc.perform(post("/api/refunds/1/review/approve").with(httpBasic("operator", OPERATOR_PW))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"looks fine to me\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/simulator/reset").with(httpBasic("operator", OPERATOR_PW))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").with(httpBasic("operator", OPERATOR_PW))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/config").with(httpBasic("operator", OPERATOR_PW))).andExpect(status().isForbidden());
    }

    @Test
    void reviewerCanDecideButNotUploadSettlementsResolveCasesOrReset() throws Exception {
        mvc.perform(get("/api/reviews").with(httpBasic("reviewer", REVIEWER_PW))).andExpect(status().isOk());
        mvc.perform(post("/api/reconciliation/run").with(httpBasic("reviewer", REVIEWER_PW))).andExpect(status().isForbidden());
        mvc.perform(post("/api/cases/1/resolve").with(httpBasic("reviewer", REVIEWER_PW))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/simulator/reset").with(httpBasic("reviewer", REVIEWER_PW))).andExpect(status().isForbidden());
        // authorised for the endpoint: unknown refund -> 404 from the service, NOT 403
        mvc.perform(post("/api/refunds/999999/review/approve").with(httpBasic("reviewer", REVIEWER_PW))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"checked with customer\"}")).andExpect(status().isNotFound());
    }

    @Test
    void adminHasAccessToAdministrationAndReset() throws Exception {
        mvc.perform(get("/api/admin/users").with(httpBasic("admin", ADMIN_PW))).andExpect(status().isOk());
        mvc.perform(get("/api/admin/config").with(httpBasic("admin", ADMIN_PW))).andExpect(status().isOk());
        mvc.perform(post("/api/simulator/reset").with(httpBasic("admin", ADMIN_PW))).andExpect(status().isOk());
    }

    @Test
    void identityEndpointReportsServerSideRoleAndIgnoresClientHeaders() throws Exception {
        mvc.perform(get("/api/auth/me").with(httpBasic("operator", OPERATOR_PW)).header("X-Role", "ADMIN").param("role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.role").value("OPERATOR"));
    }

    @Test
    void adminCannotRemoveOrDemoteTheLastAdmin() throws Exception {
        // the only enabled admin tries to change their own role -> 409
        mvc.perform(patch("/api/admin/users/1").with(httpBasic("admin", ADMIN_PW))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"OPERATOR\"}")).andExpect(status().isConflict());
    }

    @Test
    void weakPasswordIsRejectedWhenCreatingUsers() throws Exception {
        mvc.perform(post("/api/admin/users").with(httpBasic("admin", ADMIN_PW))
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"newuser\",\"password\":\"short\",\"role\":\"OPERATOR\"}"))
                .andExpect(status().isBadRequest());
    }
}
