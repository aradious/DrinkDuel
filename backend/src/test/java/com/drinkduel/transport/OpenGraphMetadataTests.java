package com.drinkduel.transport;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.filter.ForwardedHeaderFilter;

final class OpenGraphMetadataTests {
    private static final String ROOT_SHELL = """
            <!doctype html><html><head><base href="/">
            <meta property="og:image" content="__DRINKDUEL_OG_IMAGE__">
            <meta property="og:url" content="__DRINKDUEL_OG_URL__">
            </head><body></body></html>
            """;

    @Test void rootJoinMetadataUsesTheInitialHttpsRequestAndRoomCode() {
        String html = OpenGraphMetadata.render(ROOT_SHELL, request("/join", "ABC123"));
        assertTrue(html.contains("content=\"https://party.example/images/drinkduel-og.png\""));
        assertTrue(html.contains("content=\"https://party.example/join?room=ABC123\""));
        assertFalse(html.contains("__DRINKDUEL_OG_"));
    }

    @Test void subpathJoinMetadataUsesThePackagedAngularBaseHref() {
        String shell = ROOT_SHELL.replace("<base href=\"/\">", "<base href=\"/drinkduel/\">");
        String html = OpenGraphMetadata.render(shell, request("/join", "ABC123"));
        assertTrue(html.contains("content=\"https://party.example/drinkduel/images/drinkduel-og.png\""));
        assertTrue(html.contains("content=\"https://party.example/drinkduel/join?room=ABC123\""));
    }

    @Test void trustedForwardedProtocolAndHostAreAppliedBeforeMetadataRendering() throws Exception {
        var request = new MockHttpServletRequest("GET", "/join");
        request.setScheme("http");
        request.setServerName("backend");
        request.setServerPort(8080);
        request.setQueryString("room=ABC123");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "play.example");
        request.addHeader("X-Forwarded-Port", "443");
        var rendered = new String[1];

        new ForwardedHeaderFilter().doFilter(request, new org.springframework.mock.web.MockHttpServletResponse(),
                (forwardedRequest, ignored) -> rendered[0] = OpenGraphMetadata.render(
                        ROOT_SHELL, (jakarta.servlet.http.HttpServletRequest) forwardedRequest));

        assertTrue(rendered[0].contains("https://play.example/join?room=ABC123"));
        assertFalse(rendered[0].contains("http://backend:8080"));
    }

    @Test void malformedHostCannotInjectMarkupIntoMetadata() {
        var request = request("/join", "ABC123");
        request.setServerName("party.example\"><script>alert(1)</script>");
        String html = OpenGraphMetadata.render(ROOT_SHELL, request);
        assertFalse(html.contains("party.example"));
        assertFalse(html.contains("<script>"));
        assertFalse(html.contains("__DRINKDUEL_OG_"));
    }

    private static MockHttpServletRequest request(String path, String roomCode) {
        var request = new MockHttpServletRequest("GET", path);
        request.setScheme("https");
        request.setServerName("party.example");
        request.setServerPort(443);
        request.setQueryString("room=" + roomCode);
        return request;
    }
}
