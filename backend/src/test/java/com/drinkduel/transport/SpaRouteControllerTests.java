package com.drinkduel.transport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = SpaRouteController.class,
        properties = "drinkduel.spa-index=classpath:/spa-test-index.html")
final class SpaRouteControllerTests {
    @Autowired private MockMvc mvc;

    @Test void knownAngularRoutesForwardToTheApplicationShell() throws Exception {
        for (String route : new String[]{
                "/", "/create", "/join", "/room/ABC234", "/room/ABC234/games",
                "/room/ABC234/who-am-i/playing", "/room/ABC234/liars-dice/reveal"}) {
            mvc.perform(get(route)).andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("text/html"))
                    .andExpect(content().string(containsString("DrinkDuel 🎲 | Party Games with Friends")))
                    .andExpect(forwardedUrl(null));
        }
    }

    @Test void joinRouteReturnsResolvedMetadataInTheInitialHtml() throws Exception {
        mvc.perform(get("/join").queryParam("room", "ABC123").with(request -> {
                    request.setScheme("https");
                    request.setServerName("party.example");
                    request.setServerPort(443);
                    return request;
                }))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "content=\"https://party.example/images/drinkduel-og.png\"")))
                .andExpect(content().string(containsString(
                        "content=\"https://party.example/join?room=ABC123\"")))
                .andExpect(content().string(not(containsString("__DRINKDUEL_OG_"))));
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
