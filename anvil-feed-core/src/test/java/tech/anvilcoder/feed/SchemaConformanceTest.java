package tech.anvilcoder.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;
import shop.CheckoutService;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The serialiser against the normative contract itself — {@code schema/v1.0/event.schema.json} — rather
 * than against a hand-written reading of it, and against the fixture sets both SDKs share: the SDK must be
 * able to produce every shape under {@code conformance/valid/} and must never produce one under
 * {@code conformance/sdk-forbidden/}.
 */
class SchemaConformanceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path CONFORMANCE = Path.of(System.getProperty("anvilfeed.conformance"));
    private static final Path SCHEMA = Path.of(System.getProperty("anvilfeed.schema"));

    private static JsonSchema schema() throws Exception {
        try (InputStream in = Files.newInputStream(SCHEMA.resolve("v1.0/event.schema.json"))) {
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
        }
    }

    private static Set<ValidationMessage> errors(JsonSchema schema, FeedEvent event) throws Exception {
        return schema.validate(JSON.readTree(event.canonicalJson()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static EventFactory factory() {
        AnvilFeedConfig config = AnvilFeedConfig.builder()
                .token("anv_srv_0123456789abcdef0123456789abcdef")
                .service("shop-api")
                .environment("production")
                .release("2026.9.3")
                .build(FeedLogger.toSystemLogger());
        return new EventFactory(config, new Scrubber(AnvilFeedConfig.DEFAULT_DENYLIST), GitContext.none());
    }

    /** A rich context: every optional block the factory knows how to fill. */
    private static Map<String, Object> fullContext() {
        Map<String, Object> context = new LinkedHashMap<>();
        // "500" as a STRING on purpose (review finding 2): a filter reading the status off a header, or a
        // value that took a properties or JSON hop, hands over exactly this — and an event carrying a
        // string here is answered schema_violation and lost.
        context.put("request", new LinkedHashMap<>(Map.of(
                "method", "POST", "url", "https://shop.example/api/checkout?token=abc123",
                "status_code", "500")));
        context.put("user_id", "u-4711");
        context.put("breadcrumbs", new ArrayList<>(List.of("cart.add sku=A1", "checkout.start")));
        context.put("tags", new LinkedHashMap<>(Map.of("tier", "gold")));
        return context;
    }

    @Test
    void theFixturesTheSdkMustBeAbleToProduceAgreeWithTheSchema() throws Exception {
        JsonSchema schema = schema();
        try (Stream<Path> files = Files.list(CONFORMANCE.resolve("valid"))) {
            for (Path file : files.sorted().toList()) {
                JsonNode node = JSON.readTree(Files.readString(file));
                assertThat(schema.validate(node)).as("%s", file.getFileName()).isEmpty();
            }
        }
    }

    @Test
    void theShapesNoSdkMayProduceAreRefusedByTheSchema() throws Exception {
        JsonSchema schema = schema();
        try (Stream<Path> files = Files.list(CONFORMANCE.resolve("sdk-forbidden"))) {
            for (Path file : files.sorted().toList()) {
                JsonNode node = JSON.readTree(Files.readString(file));
                assertThat(schema.validate(node)).as("%s", file.getFileName()).isNotEmpty();
            }
        }
    }

    @Test
    void everyEventThisFactoryProducesValidates() throws Exception {
        JsonSchema schema = schema();
        EventFactory factory = factory();

        for (FeedEvent event : List.of(
                factory.fromThrowable(CheckoutService.charge(), "error", fullContext()),
                factory.fromThrowable(new IllegalStateException(), "fatal", Map.of()),
                CheckoutService.report(factory, "Payment reconciliation skipped for 3 accounts", fullContext()),
                CheckoutService.verify(factory))) {
            assertThat(errors(schema, event)).as("%s", event.canonicalJson()).isEmpty();
        }
    }

    /**
     * The six forbidden shapes, each produced the way a host application would actually produce it. The
     * schema is the judge: none of them may come out of the factory.
     */
    @Test
    void theFactoryCannotBeMadeToProduceAForbiddenShape() throws Exception {
        JsonSchema schema = schema();
        AnvilFeedConfig config = AnvilFeedConfig.builder()
                .token("anv_srv_x").service("s".repeat(400)).environment("Prod_EU")
                .build(FeedLogger.toSystemLogger());
        EventFactory factory =
                new EventFactory(config, new Scrubber(AnvilFeedConfig.DEFAULT_DENYLIST), GitContext.none());

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("request", new LinkedHashMap<>(Map.of("method", "GET", "url", "file:///etc/passwd")));
        context.put("user_id", "u-4711");
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("user.ip_hash", "deadbeef");
        IntStream.range(0, 40).forEach(i -> tags.put("t" + i, "v" + i));
        context.put("tags", tags);

        FeedEvent event = factory.fromThrowable(CheckoutService.charge(), "info", context);
        Map<String, Object> payload = event.payload();

        assertThat(errors(schema, event)).as("%s", event.canonicalJson()).isEmpty();
        assertThat(payload.get("level")).isEqualTo("error");                       // level-info
        assertThat(payload.get("language")).isEqualTo("java");                     // missing-language
        assertThat((String) payload.get("service")).hasSize(128);                  // service-too-long
        assertThat(map(payload.get("user"))).containsOnlyKeys("id");        // user-ip
        assertThat(map(payload.get("tags"))).doesNotContainKey("user.ip_hash");  // tag-user-ip-hash
        assertFalse(((Map<?, ?>) payload.get("request")).containsKey("url"));      // request-url-file-scheme
    }

    /** The bytes on the socket are the bytes the factory produced, in the order it produced them. */
    @Test
    void theCanonicalJsonIsTheCanonicalOrderAndNothingEscapesTheSlashes() {
        FeedEvent event = factory().fromThrowable(CheckoutService.charge(), "error", fullContext());

        String json = event.canonicalJson();
        assertThat(json).startsWith("{\"schema_version\":\"1.0\",\"event_id\":\"" + event.id() + "\",\"timestamp\":");
        assertThat(json).contains("\"url\":\"https://shop.example/api/checkout?token=[Filtered]\"");
        assertThat(json).doesNotContain("\\/");
    }
    /**
     * The hostile shape a {@code beforeSend} hook produces: one more tag than the schema allows, one more
     * breadcrumb, one more frame. Judged by the schema itself, because that is what the ingest applies
     * (review finding 3).
     */
    @Test
    void aHookThatOverfillsEveryBlockStillProducesAValidEvent() throws Exception {
        JsonSchema schema = schema();
        EventFactory factory = factory();
        Map<String, Object> payload = new LinkedHashMap<>(
                factory.fromThrowable(CheckoutService.charge(), "error", fullContext()).payload());

        Map<String, Object> tags = new LinkedHashMap<>();
        IntStream.rangeClosed(1, 30).forEach(i -> tags.put("tag-" + i, "v" + i));
        payload.put("tags", tags);
        payload.put("breadcrumbs", IntStream.rangeClosed(1, 31).mapToObj(i -> "step " + i).toList());

        FeedEvent event = new FeedEvent(payload.get("event_id").toString(),
                FeedEvent.inCanonicalOrder(factory.finalise(payload)));

        assertThat(errors(schema, event)).as("%s", event.canonicalJson()).isEmpty();
        assertThat(map(event.payload().get("tags"))).hasSize(29);
        assertThat((List<?>) event.payload().get("breadcrumbs")).hasSize(30);
    }
}
