package com.drinkduel.transport;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class LocalIdentityScopeTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(LocalGmIdentityFilter.class);
    @Test void defaultAndFlagAloneNeverEnableDevIdentity() {
        context.run(c -> assertFalse(c.containsBean("localGmIdentityFilter")));
        context.withPropertyValues("drinkduel.dev-identity.enabled=true").run(c -> assertEquals(0, c.getBeansOfType(LocalGmIdentityFilter.class).size()));
    }
    @Test void localProfileAloneNeverEnablesDevIdentity() {
        context.withPropertyValues("spring.profiles.active=local-preview").run(c -> assertEquals(0, c.getBeansOfType(LocalGmIdentityFilter.class).size()));
    }
    @Test void explicitLocalOptInRequiredAndProductionProfilesDisableIt() {
        context.withPropertyValues("spring.profiles.active=local-preview", "drinkduel.dev-identity.enabled=true")
                .run(c -> assertEquals(1, c.getBeansOfType(LocalGmIdentityFilter.class).size()));
        for (String profile : new String[]{"prod", "production"})
            context.withPropertyValues("spring.profiles.active=local-preview," + profile, "drinkduel.dev-identity.enabled=true")
                    .run(c -> assertEquals(0, c.getBeansOfType(LocalGmIdentityFilter.class).size()));
    }
    @Test void originAndLoopbackChecksRejectRemoteAndForwardedClaims() {
        var request = new MockHttpServletRequest();
        request.setServerName("localhost"); request.setServerPort(4200); request.setRemoteAddr("127.0.0.1");
        request.addHeader("Origin", "http://localhost:4200");
        assertTrue(BrowserOrigin.loopback(request)); assertTrue(BrowserOrigin.sameOrigin(request));
        request.setRemoteAddr("192.168.1.20"); request.addHeader("X-Forwarded-For", "127.0.0.1");
        assertFalse(BrowserOrigin.loopback(request));
        request.setRemoteAddr("127.0.0.1"); request.setServerName("evil.example");
        assertFalse(BrowserOrigin.loopback(request)); assertFalse(BrowserOrigin.sameOrigin(request));
        request.setServerName("localhost"); request.removeHeader("Origin");
        assertFalse(BrowserOrigin.sameOrigin(request));
    }
}
