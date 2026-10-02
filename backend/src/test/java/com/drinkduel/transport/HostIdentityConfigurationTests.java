package com.drinkduel.transport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class HostIdentityConfigurationTests {
    @Value("${server.servlet.session.cookie.http-only}") boolean httpOnly;
    @Value("${server.servlet.session.cookie.same-site}") String sameSite;
    @Value("${server.servlet.session.cookie.secure}") boolean secure;

    @Test void productionDefaultsProtectTheHostSessionCookie() {
        assertTrue(httpOnly);
        assertEquals("strict", sameSite);
        assertTrue(secure);
    }
}
