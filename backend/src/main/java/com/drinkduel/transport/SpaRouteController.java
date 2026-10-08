package com.drinkduel.transport;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves only known Angular client routes from the packaged application shell. */
@Controller
public final class SpaRouteController {
    private final Resource index;
    private volatile String template;

    public SpaRouteController(@Value("${drinkduel.spa-index:classpath:/static/index.html}") Resource index) {
        this.index = index;
    }

    @GetMapping({
            "/",
            "/index.html",
            "/create",
            "/join",
            "/room/{roomCode}",
            "/room/{roomCode}/games",
            "/room/{roomCode}/who-am-i/{*route}",
            "/room/{roomCode}/liars-dice/{*route}"
    })
    public void angularRoute(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!index.exists()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String html = OpenGraphMetadata.render(template(), request);
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        response.setContentType(MediaType.TEXT_HTML_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    private String template() throws IOException {
        String current = template;
        if (current != null) return current;
        synchronized (this) {
            if (template == null) template = index.getContentAsString(StandardCharsets.UTF_8);
            return template;
        }
    }
}
