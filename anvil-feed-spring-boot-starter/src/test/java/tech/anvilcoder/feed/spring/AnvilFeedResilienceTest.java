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
import tech.anvilcoder.feed.Transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * FR-2 b, in the place it is easiest to get wrong: the SDK is broken, and the application must not notice.
 *
 * <p>The transport here throws on every call, which is what a host's own HTTP decorator, a broken proxy
 * configuration or an exhausted file-descriptor table looks like from inside the sender. A request that
 * works must still answer 200, a request that fails must still fail with the APPLICATION's exception and
 * not with ours, and an explicit flush must not throw either.
 */
@SpringBootTest(classes = {ShopApplication.class, AnvilFeedResilienceTest.BrokenIngest.class},
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "anvil.feed.token=anv_srv_0123456789abcdef0123456789abcdef",
                "anvil.feed.service=shop-api",
                "anvil.feed.flush-interval-ms=900000"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AnvilFeedResilienceTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class BrokenIngest {
        @Bean
        Transport transport() {
            return StubIngest.throwing(new IllegalStateException("the transport itself is broken"));
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
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filter).build();
        client.queue().clear();
    }

    @AfterEach
    void clearTheDeduplicationRegistry() {
        AnvilFeedReported.forget();
    }

    @Test
    void aRequestThatWorksIsStillServed() throws Exception {
        client.captureMessage("something to send");
        client.flush();   // straight into the throwing transport

        mockMvc.perform(get("/orders")).andExpect(result ->
                assertEquals(200, result.getResponse().getStatus()));
    }

    @Test
    void aRequestThatFailsStillFailsWithTheApplicationsOwnException() {
        assertThatThrownBy(() -> mockMvc.perform(get("/bookings/4711")))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Cannot invoke \"Booking.total()\" because \"booking\" is null");

        assertThat(client.queue().size()).describedAs("the event was still recorded").isEqualTo(1);
    }

    @Test
    void anExplicitFlushIntoABrokenTransportIsTotal() {
        client.captureMessage("something to send");

        assertThatCode(() -> client.flush()).doesNotThrowAnyException();
    }
}
