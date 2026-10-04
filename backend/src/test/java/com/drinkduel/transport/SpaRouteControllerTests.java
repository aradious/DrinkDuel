package com.drinkduel.transport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SpaRouteController.class)
final class SpaRouteControllerTests {
    @Autowired private MockMvc mvc;

    @Test void knownAngularRoutesForwardToTheApplicationShell() throws Exception {
        for (String route : new String[]{
                "/create", "/join", "/room/ABC234", "/room/ABC234/games",
                "/room/ABC234/who-am-i/playing", "/room/ABC234/liars-dice/reveal"}) {
            mvc.perform(get(route)).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        }
    }

    @Test void backendNamespacesAreNeverHandledByTheSpaFallback() throws Exception {
        mvc.perform(get("/api/not-a-real-endpoint")).andExpect(status().isNotFound())
                .andExpect(forwardedUrl(null));
        mvc.perform(get("/actuator/not-a-real-endpoint")).andExpect(status().isNotFound())
                .andExpect(forwardedUrl(null));
        mvc.perform(get("/ws/not-a-real-endpoint")).andExpect(status().isNotFound())
                .andExpect(forwardedUrl(null));
    }

    @Test void staticAssetPathsAreNotHandledByTheSpaFallback() throws Exception {
        mvc.perform(get("/assets/missing.webp")).andExpect(status().isNotFound())
                .andExpect(forwardedUrl(null));
        mvc.perform(get("/missing.js")).andExpect(status().isNotFound())
                .andExpect(forwardedUrl(null));
    }
}
