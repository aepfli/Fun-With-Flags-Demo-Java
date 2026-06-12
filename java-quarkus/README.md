# OpenFeature Quarkus Demo

This is the Quarkus variant of [Fun With Flags](../README.md). I wrote it because a lot of Java shops run Quarkus rather than Spring Boot, and I did not want anyone to have to translate a Spring tutorial in their head while also learning OpenFeature for the first time. The step-by-step arc mirrors the [Spring Boot variant](../java-spring/README.md) one-to-one, so you can read them side by side.

Two things to flag up front as differences from the Spring version:

- Wiring happens via CDI (`@ApplicationScoped`, `@PostConstruct` on a startup bean) instead of a Spring `@Configuration`.
- The language interceptor is a `ContainerRequestFilter` from JAX-RS, not a Spring `HandlerInterceptor`. The transaction context propagator is the same `ThreadLocalTransactionContextPropagator` though, because OpenFeature itself does not change between the two frameworks.

Run the app with `./mvnw quarkus:dev`, then `curl http://localhost:8080/`. Requests for every step live in [`requests.http`](requests.http).

## Step 1.1 Add the OpenFeature SDK

The goal here is small: add the SDK, evaluate one flag, see that without a provider we get the fallback value.

```xml
<dependency>
    <groupId>dev.openfeature</groupId>
    <artifactId>sdk</artifactId>
    <version>1.14.2</version>
</dependency>
```

In the JAX-RS resource:

```java
@Path("/")
public class IndexResource {
    @GET
    public FlagEvaluationDetails<String> index() {
        Client client = OpenFeatureAPI.getInstance().getClient();
        return client.getStringDetails("greetings", "No World");
    }
}
```

Run the app, hit `/`, get `No World`. That is the fallback, and it is exactly what you should see before a provider is wired in.

## Step 1.2 Provider initialization (in-memory)

Next I give the SDK a provider so it has something to evaluate against. For the first pass I use `InMemoryProvider` so there is no file, no network, just a map.

```java
@Startup
@ApplicationScoped
public class OpenFeatureStartup {
    @PostConstruct
    public void initProvider() {
        HashMap<String, Flag<?>> flags = new HashMap<>();
        flags.put("greetings",
                Flag.builder()
                        .variant("goodbye", "Goodbye World!")
                        .variant("hello", "Hello World!")
                        .defaultVariant("hello")
                        .build());
        OpenFeatureAPI.getInstance().setProviderAndWait(new InMemoryProvider(flags));
    }
}
```

Yes, building flags in Java code is tedious — that is why in step 2 I move the definition to a file.

## Step 2.1 Flagd file provider

I want the flag definition out of code and into something I can edit live in front of an audience. Flagd's file resolver is the smallest change that gets me there.

```xml
<dependency>
    <groupId>dev.openfeature.contrib.providers</groupId>
    <artifactId>flagd</artifactId>
    <version>0.11.8</version>
</dependency>
```

Flags live in [`flags.json`](flags.json) at the project root. The startup bean now builds a `FlagdProvider` in FILE mode:

```java
FlagdOptions options = FlagdOptions.builder()
        .resolverType(Config.Resolver.FILE)
        .offlineFlagSourcePath("./flags.json")
        .build();
OpenFeatureAPI.getInstance().setProviderAndWait(new FlagdProvider(options));
```

Edit `flags.json`, change the `defaultVariant`, hit the endpoint again. The new value comes through without a restart.

## Step 3.1 Dynamic context

Flags that always return the same value are not that interesting. Targeting lets the flag value depend on something about the request. I start with the simplest case — read a `language` query parameter and put it into the evaluation context.

```java
@GET
public FlagEvaluationDetails<String> index(@QueryParam("language") String language) {
    Map<String, Value> attributes = new HashMap<>();
    if (language != null) attributes.put("language", new Value(language));
    return OpenFeatureAPI.getInstance().getClient()
            .getStringDetails("greetings", "Hello World", new ImmutableContext(attributes));
}
```

The targeting rule in `flags.json` maps `language == "de"` to the `hallo` variant.

## Step 3.1.1 Request filter

Wiring the context inside every resource is going to get old fast. JAX-RS gives me a `ContainerRequestFilter` for exactly this — set the transaction context on the way in, clear it on the way out, and the resource goes back to being a one-liner.

```java
@Provider
public class LanguageRequestFilter implements ContainerRequestFilter {
    @Override
    public void filter(ContainerRequestContext ctx) {
        String language = ctx.getUriInfo().getQueryParameters().getFirst("language");
        if (language != null) {
            Map<String, Value> attrs = Map.of("language", new Value(language));
            OpenFeatureAPI.getInstance().setTransactionContext(new ImmutableContext(attrs));
        }
    }
}
```

For this to work the `OpenFeatureStartup` registers a `ThreadLocalTransactionContextPropagator`.

## Step 3.2 Global evaluation context

Some targeting keys do not change between requests — runtime version, region, deployment stage. Those belong on the global context, set once at startup.

```java
String version = System.getProperty("quarkus.platform.version", "3.0.0");
api.setEvaluationContext(new ImmutableContext(Map.of("quarkusVersion", new Value(version))));
```

The targeting in `flags.json` checks `quarkusVersion >= 3.0.0` and returns the `springer` variant when it matches. Yes, the variant is named after Spring — I kept the name from the original demo so the side-by-side comparison stays clean.

> Note: Quarkus does not expose its own version via a clean runtime API the way Spring does with `SpringVersion.getVersion()`. The build injects the platform version as a system property; I fall back to `"3.0.0"` so the targeting keeps working in tests that do not set the property.

## Step 4 Hooks

Hooks run around every evaluation. They are the extension point I reach for when I want logging, metrics, or tracing without touching every call site.

```java
public class CustomHook implements Hook {
    private static final Logger LOG = Logger.getLogger(CustomHook.class);

    @Override public Optional<EvaluationContext> before(HookContext ctx, Map hints) { LOG.info("Before hook"); return Optional.empty(); }
    @Override public void after(HookContext ctx, FlagEvaluationDetails details, Map hints) { LOG.infof("After hook - %s", details.getReason()); }
    @Override public void error(HookContext ctx, Exception error, Map hints) { LOG.error("Error hook", error); }
    @Override public void finallyAfter(HookContext ctx, FlagEvaluationDetails details, Map hints) { LOG.infof("Finally - %s", details.getReason()); }
}
```

Registered once in `OpenFeatureStartup` via `api.addHooks(new CustomHook())`.

## Step 5.1 Remote flagd via docker compose

File mode is fine for demos on a laptop. In a real setup flagd runs as its own process and the app talks to it over gRPC or HTTP. I spin flagd up with [`docker-compose.yaml`](docker-compose.yaml), then switch the provider to RPC mode:

```java
FlagdOptions options = FlagdOptions.builder()
        .resolverType(Config.Resolver.RPC)
        .build();
```

In RPC mode every evaluation hits flagd. `IN_PROCESS` mode fetches the flag set and watches for updates — good for hot paths.

## Step 5.2 Testing against flagd without docker compose

I do not want an attendee to have to remember `docker compose up` in a second terminal, and I do not want CI to have to coordinate a sidecar. Testcontainers owns the container lifecycle inside the test itself.

```java
@Testcontainers
@QuarkusTest
class FlagdIntegrationTest {
    @Container
    static GenericContainer<?> flagd = new GenericContainer<>("ghcr.io/open-feature/flagd:latest")
            .withExposedPorts(8013)
            .withFileSystemBind("./flags.json", "/flags.json", BindMode.READ_ONLY)
            .withCommand("start", "--uri", "file:/flags.json");

    @Test void germanVariant() {
        given().queryParam("language", "de").get("/")
                .then().body(containsString("Hallo Welt"));
    }
}
```

Run `./mvnw verify` — the container starts, the test passes, the container stops. No second terminal.

## Step 6 OpenTelemetry observability

Every flag evaluation becomes a span in Tempo, nested under the HTTP request span that triggered it. Steps 6 and 7 run from `main` — they use the shared [`../observability/`](../observability/README.md) and [`../loadgen/`](../loadgen/README.md) stacks, which only stay current on `main` (the `step/java-quarkus/6` and `/7` branches predate them and don't carry them). In a per-language Codespace the full repo is still on disk, so reach those sibling folders from the terminal with `cd ../observability` even though the Explorer only lists this folder.

Run `cd ../observability && docker compose up -d`, then from this folder on `main` start the app with `./mvnw quarkus:dev`. Grafana UI at <http://localhost:3000>, open the **Fun With Flags — Feature Flag Metrics** dashboard or use Explore → Tempo to pick the `fun-with-flags-java-quarkus` service.

## Step 7 Progressive rollout

A new greeting algorithm is rolling out. It is slower (200ms) than the old code path and it errors 10% of the time. The job of step 7 is to roll it out gradually, watch the consequences in Grafana, and roll it back without redeploying.

The code is on `main`, in this folder — the handler reads a new `new_greeting_algo` flag, and the middleware passes `?userId=...` through as the OpenFeature `targetingKey` so the fractional rollout buckets stick per user.

Two moving parts work together:

- **[`../loadgen/`](../loadgen/README.md)** drives traffic. It is gated by an `loadgen_active` flag (already in this folder's `flags.json`, default `"off"`) — flip it to `"on"` to start the load, back to `"off"` to stop. The feature-flag demo, feature-flagged.
- **`flags.json`** in this folder (on `main`) defines `new_greeting_algo` with a flagd `fractional` rule, defaulting to 100% off. Bump the percentage and flagd hot-reloads — no app restart.

Run the demo: start observability + the app + loadgen, flip `loadgen_active` to `"on"`, then ramp `new_greeting_algo` from `[["off",100],["on",0]]` to 10/90, then 50/50. Watch the **HTTP request latency (p50, p99)** and **HTTP 5xx per second** panels in Grafana climb. Roll back to 100/0 the moment something looks bad.
