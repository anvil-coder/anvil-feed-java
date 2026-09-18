# The Java SDKs

Two modules, one Gradle build, one version number.

| Module | What it is | Depends on |
|---|---|---|
| `anvil-feed-core` | The framework-free SDK for Java 25. A `Throwable` in; a scrubbed, bounded, queued event out, posted to `POST <endpoint>/events`. | Jackson. That is the whole runtime list. |
| `anvil-feed-spring-boot-starter` | Auto-configuration for Spring Boot 4. Three properties and the dependency are the whole integration. | the core, `spring-boot-autoconfigure` |

No Spring in the core, no Quarkus anywhere, no git access, no ticket logic (X5, Z2). The HTTP client
is the JDK's own `java.net.http.HttpClient` (O11), so the JVM's proxy settings and truststore apply
without any configuration of ours (C11, C12). slf4j is `compileOnly`: the SDK logs through whatever
the host already has, and pulls in nothing to do it.

**The fifteen-minute guide is [`docs/integrate-spring-boot.md`](../docs/integrate-spring-boot.md).**
Start there. This page is about the build.

## Using the core on its own

```java
var config = AnvilFeedConfig.builder()
        .token(System.getenv("ANVIL_INGEST_TOKEN"))
        .service("shop-api")
        .environment("production")
        .build(FeedLogger.toSystemLogger());

var client = AnvilFeedClient.create(config);
client.start();
AnvilFeed.init(client);          // the static entry point, for code that cannot be injected into
```

Sending runs on one daemon thread every `flush_interval_ms`, plus a shutdown hook. Nothing happens
on a request thread, and nothing here ever throws into the host application.

Every parameter, every default and every environment variable name is in
[`conformance/config-defaults.json`](../conformance/config-defaults.json) — the same file the PHP
SDK is measured against, in both directions, by `ConfigParityTest` on each side.

## Running the build

```bash
cd java
./gradlew --no-daemon build
```

That is both modules: the starter is a subproject, not a second gate. It is also exactly what CI's
`java` field runs.

```bash
# one class
./gradlew --no-daemon :anvil-feed-core:test --tests '*ConfigParityTest*'

# the oracle against a real dark-factory checkout — see below
./gradlew --no-daemon :anvil-feed-core:test --tests '*FingerprintOracleFreshnessTest*' \
  -Danvilfeed.incidents.worktree=/path/to/dark-factory
```

Never run two Gradle builds against this tree at once.

### The fingerprint oracle

`src/test/java/com/darkfactory/domain/service/IncidentFingerprint.java` is a **copy** of the
server's own grouping rule, taken by hand on 2026-09-17. `FingerprintTest` measures this SDK against
that copy on ten thousand generated inputs — the strongest evidence in the repository, and all of it
evidence about a file that stopped being the server's the moment somebody changed the server's.

`FingerprintOracleFreshnessTest` is what notices. Without
`-Danvilfeed.incidents.worktree` it **skips**, naming the property, because the checkout is not on
every machine. With it, it is a hard check: a missing file fails and so does a moved digest.
CI's `oracle-freshness` job is what stops the skip branch from being the branch that always runs —
and it says loudly in the run summary when the read token is absent, rather than passing quietly.

## Versions

`gradle/libs.versions.toml` is the **only** place a version number is written (C18), including
`java = "25"` and `sdkVersion`. The SDK's `sdk.version` on the wire is generated from `sdkVersion` at
build time into `SdkVersion.java`, so the published Maven coordinate and the number a customer reads
off a support ticket cannot drift. CI fails the build if `25` or `4.0.0` is spelled a second time in
any `*.kts`, `*.gradle` or `Dockerfile` under `java/`.

**Java 25 is a hard floor.** On an older JVM the jar fails at class load with
`UnsupportedClassVersionError: … class file version 69.0`, which names no version an operator can
act on. The SDK cannot improve that message: a check inside our own classes would have to be loaded
to run, and loading is what failed. Class file 69 is Java 25.

## Publishing

Not configured, and deliberately. `tech.anvil-coder:anvil-feed-core` and
`tech.anvil-coder:anvil-feed-spring-boot-starter` are **proposals** with nothing reserved on Maven
Central; there is no `publishing {}` block and no signing key.
`.github/workflows/publish-maven-central.yml` records the step and refuses to run it. The open
decisions are in that file's header and in the root [`README.md`](../README.md).

Until then, a consuming Gradle project uses a composite build — `includeBuild("…/anvil-feed/java")`
and a versionless coordinate. The guide has the exact lines, and they were run against a scratch
project.

## Toolchain provisioning and egress

With no JDK 25 installed, `settings.gradle.kts` resolves the toolchain through **`api.foojay.io`** (the Foojay resolver convention) and downloads Temurin 25. On a host with no egress to that API, install a JDK 25 first — CI does exactly that with `actions/setup-java` before Gradle starts, so the call never happens there. Details in the root README.
