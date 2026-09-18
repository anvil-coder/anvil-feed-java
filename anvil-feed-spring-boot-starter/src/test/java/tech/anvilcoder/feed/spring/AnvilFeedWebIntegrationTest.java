package tech.anvilcoder.feed.spring;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import shop.ShopApplication;
import tech.anvilcoder.feed.AnvilFeedClient;
import tech.anvilcoder.feed.AnvilFeedConfig;
import tech.anvilcoder.feed.Transport;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static tech.anvilcoder.feed.spring.AnvilFeedTestSupport.map;

/**
 * The whole starter inside a real Spring Boot application context, driven through the dispatcher servlet.
 *
 * <p>The application lives in {@code shop} on purpose — see {@link ShopApplication}. {@code flush-interval-ms}
 * is a quarter of an hour so that the daemon pump does not empty the queue between the request and the
 * assertion; nothing here waits for a clock.
 */
@SpringBootTest(classes = {ShopApplication.class, AnvilFeedWebIntegrationTest.Ingest.class},
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "anvil.feed.token=anv_srv_0123456789abcdef0123456789abcdef",
                "anvil.feed.service=shop-api",
                "anvil.feed.environment=production",
                "anvil.feed.flush-interval-ms=900000"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AnvilFeedWebIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Ingest {
        @Bean
        Transport transport() {
            return new StubIngest();
        }
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private AnvilFeedClient client;

    @Autowired
    private AnvilFeedRequestFilter filter;

    private MockMvc mockMvc;

    @BeforeEach
    void mockMvc() {
        // The filter is added explicitly rather than through @AutoConfigureMockMvc: that annotation lives
        // in a Spring Boot test module this starter does not depend on, and adding the real bean is a
        // truer test than trusting an auto-configuration to have found it.
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filter).build();
        client.queue().clear();
    }

    @AfterEach
    void clearTheDeduplicationRegistry() {
        AnvilFeedReported.forget();
    }

    @Test
    void anUnhandledExceptionBecomesExactlyOneEventWithItsRequestAndItsRoute() {
        // The exception travels on unchanged, which is the proof that the resolver returned null: an SDK
        // that "handled" it would have turned this into a response and the application's own error
        // handling would never have run (FR-2 a).
        assertThatThrownBy(() -> mockMvc.perform(get("/bookings/4711")))
                .hasRootCauseInstanceOf(IllegalStateException.class);

        assertEquals(1, client.queue().size(), "one fault is one event, whatever sees it");
        Map<String, Object> payload = client.queue().take(1).get(0).payload();

        Map<String, Object> request = map(payload.get("request"));
        assertEquals("GET", request.get("method"));
        assertThat(String.valueOf(request.get("url"))).endsWith("/bookings/4711");
        assertEquals("/bookings/{id}", map(payload.get("tags")).get("route"));
        assertEquals("java.lang.IllegalStateException", map(payload.get("exception")).get("type"));
    }

    /** dark-factory runs this starter AND hosts the ingest; reporting the ingest's own failure is the loop. */
    @Test
    void anExceptionServingTheIngestsOwnPathIsNotReported() {
        assertThatThrownBy(() -> mockMvc.perform(get("/api/feed/events")))
                .hasRootCauseInstanceOf(IllegalStateException.class);

        assertEquals(0, client.queue().size());
    }

    @Test
    void aRequestThatWorksIsUntouched() throws Exception {
        mockMvc.perform(get("/orders")).andExpect(result ->
                assertEquals(200, result.getResponse().getStatus()));

        assertEquals(0, client.queue().size());
    }

    /**
     * {@code in_app_packages}, seeded from the {@code @SpringBootApplication} class's package. Without it
     * every Spring and Tomcat frame in a fat jar is the application's own, the fingerprint's in-app
     * partition promotes them over the controller's frame, and two unrelated faults that both end in the
     * dispatcher servlet group into one incident.
     */
    @Test
    void onlyTheApplicationsOwnFramesAreInApp() {
        assertEquals(List.of("shop"), context.getBean(AnvilFeedConfig.class).inAppPackages());

        assertThatThrownBy(() -> mockMvc.perform(get("/bookings/4711")))
                .hasRootCauseInstanceOf(IllegalStateException.class);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> frames = (List<Map<String, Object>>)
                map(client.queue().take(1).get(0).payload().get("exception")).get("stacktrace");
        assertThat(frames).isNotEmpty();
        assertThat(frames).filteredOn(frame -> Boolean.TRUE.equals(frame.get("in_app")))
                .isNotEmpty()
                .allSatisfy(frame -> assertThat(String.valueOf(frame.get("file")))
                        .isEqualTo("BookingController.java"));
    }
}
