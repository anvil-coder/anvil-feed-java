package shop;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A stand-in for the customer's application. It lives OUTSIDE {@code tech.anvilcoder.feed} on purpose:
 * {@code EventFactory}'s in-app rule treats every {@code tech.anvilcoder.feed.*} frame as the SDK's own —
 * the starter's package included, because an adapter's frames ARE SDK frames — so a test that raised its
 * exception inside the starter's own package would measure an empty stack and prove nothing about
 * {@code in_app} (task 9's handover, point 5).
 *
 * <p>It is also what seeds {@code in_app_packages}: {@code @SpringBootApplication} is the annotation
 * {@code AutoConfigurationPackages} records, so {@code "shop"} is the package the starter hands the core.
 */
@SpringBootApplication
public class ShopApplication {

    public static void main(String[] args) {
        // Never run. A @SpringBootTest needs a @SpringBootConfiguration class, not a main method.
    }
}
