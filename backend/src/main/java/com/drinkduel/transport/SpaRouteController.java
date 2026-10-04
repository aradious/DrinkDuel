package com.drinkduel.transport;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Forwards only known Angular client routes to the packaged application shell. */
@Controller
public final class SpaRouteController {
    @GetMapping({
            "/create",
            "/join",
            "/room/{roomCode}",
            "/room/{roomCode}/games",
            "/room/{roomCode}/who-am-i/{*route}",
            "/room/{roomCode}/liars-dice/{*route}"
    })
    public String angularRoute() {
        return "forward:/index.html";
    }
}
