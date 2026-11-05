package com.filesync.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityBehaviorTest {

    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;

    @Test
    void unauthenticatedRequestGets401NotLoginRedirectOr403() throws Exception {
        mvc.perform(get("/api/files")).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidTokenGets401() throws Exception {
        mvc.perform(get("/api/files").header("Authorization", "Bearer not.a.jwt")).andExpect(status().isUnauthorized());
    }

    @Test
    void validBearerTokenIsAccepted() throws Exception {
        String token = tokens.generateToken(987654L, "nobody@example.com");
        mvc.perform(get("/api/devices").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    @Test
    void tokenInQueryStringIsNotAcceptedForSse() throws Exception {
        String token = tokens.generateToken(987654L, "nobody@example.com");
        mvc.perform(get("/api/sync/events").param("token", token)).andExpect(status().isUnauthorized());
    }

    @Test
    void swaggerIsNotExposedByDefault() throws Exception {
        mvc.perform(get("/swagger-ui.html")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api-docs")).andExpect(status().isUnauthorized());
    }
}
