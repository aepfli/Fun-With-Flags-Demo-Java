# OpenFeature Spring Boot Demo

This is a little Spring Boot Demo application for OpenFeature.

Follow each Step and see how OpenFeature can be used within a Spring Boot Application

Within [requests.http](requests.http) you will find requests for each section to play with.

> Each numbered step lives on its own branch — `step/java-spring/1.1`, `1.2`, `2.1`, `3.1`, `3.1.1`, `3.2`, `4`, `5.1`, `5.2`, `6`, `7`. `main` carries the full end state. Check out a step branch to see the codebase at that point.

## Step 1 Basic OpenFeature Setup

Checkout the Repository and start the application.

### Step 1.1 Add OpenFeature SDK

1. Add OpenFeature SDK to the pom.xml by adding following dependencies

    ```xml
    <dependency>
        <groupId>dev.openfeature</groupId>
        <artifactId>sdk</artifactId>
        <version>1.14.2</version>
    </dependency>
    ```

2. Add Evaluation a Feature Flag Evaluation to the IndexController

    ```java
    @GetMapping("/")
    public FlagEvaluationDetails<String>  helloWorld() {
        Client client = OpenFeatureAPI.getInstance().getClient();
        return client.getStringDetails("greetings", "No World");
    }
    ```

If you run the code we will get `No World`, and this is expected.
We need to define a provider which our client is using.
Within the next step we will add this.

### Step 1.2 Provider Initialization

1. We will setup a provider within a PostConstruct configuration like
   ```java
   @Configuration
   public class OpenFeatureConfig {
   
       @PostConstruct
       public void initProvider() {
           OpenFeatureAPI api = OpenFeatureAPI.getInstance();
           api.setProviderAndWait(new InMemoryProvider(new HashMap<>()));
       }
   }
   ```
   
   > Note: Nothing will change during the execution at this stage, but with the next step, we add feature flags

2. Fill the HashMap within the InMemoryProvider with data like:
   ```java
    @PostConstruct
    public void initProvider() {
        OpenFeatureAPI api = OpenFeatureAPI.getInstance();
        HashMap<String, Flag<?>> flags = new HashMap<>();
        flags.put("greetings",
                Flag.builder()
                        .variant("goodbye", "Goodbye World!")
                        .variant("hello", "Hello World!")
                        .defaultVariant("hello")
                        .build());

        api.setProviderAndWait(new InMemoryProvider(flags));
    }
   ```
   
   > Note: Yes it is tedious to do this via code, that is just the simplest example :)

   Now we can change the default variant and see OpenFeatures Basic Magic.
   Depending on the default variant we should see either `Hello World` or `Goodbye World`.

### Summary

We have now added OpenFeature to our codebase and using it to evaluate feature flags.
However, the feature flag definition is in code and does not offer us the flexibility we want.
Let's jump into the next chapter and retrieve feature flags from a file.

## Step 2 Providers

Flagd is our cloud native reference implementation and it comes with a lot of interesting features.
First lets focus on the file provider, to show you how easy it is to change the provider.

### Step 2.1 Adding Flagd File Provider

1. To utilize flagd we need to add an additional dependency -> the flagd provider
   ```xml
     <dependency>
         <groupId>dev.openfeature.contrib.providers</groupId>
         <artifactId>flagd</artifactId>
         <version>0.11.8</version>
     </dependency>
   ```
2. We need to migrate our flag configuration to a json file for the flagd file provider.
   Therefore, we create a `flags.json` within the project root with the following content:
   
   ```json
   {
    "flags": {
      "greetings": {
        "state": "ENABLED",
          "variants": {
            "hello": "Hello World!",
            "goodbye": "Goodbye World!"
          },
          "defaultVariant": "hello"
        }
      }
   }
   ```
   
3. We need to instrument the flagD provider instead of our InMemory Provider
   ```java
   @PostConstruct
   public void initProvider() {
     OpenFeatureAPI api = OpenFeatureAPI.getInstance();
     FlagdOptions flagdOptions = FlagdOptions.builder()
             .resolverType(Config.Resolver.FILE)
             .offlineFlagSourcePath("./flags.json")
             .build();

     api.setProviderAndWait(new FlagdProvider(flagdOptions));
   }
   ```
 Now we can change the file and see that based on the file we will get different values.


## Step 3 Targeting

Targeting allows us to change the evaluation outcome based on contextual data.

### Step 3.1 Dynamic Context

Targeting allows us to modify our result based on arbitrary data.

1. Lets adapt our controller endpoint to utilize a query parameter as contextual data,
   ```java
   @GetMapping("/")
   public FlagEvaluationDetails<String> helloWorld(@RequestParam(required = false) String language) {
        Client client = OpenFeatureAPI.getInstance().getClient();
        HashMap<String, Value> attributes = new HashMap<>();
        attributes.put("language", new Value(language));
        return client.getStringDetails("greetings", "Hello World",
                new ImmutableContext(attributes));
    }
   ```

2. Lets adopt our flag and add some targeting
   ```json
   {
    "flags": {
      "greetings": {
        "state": "ENABLED",
          "variants": {
            "hallo": "Hallo Welt!",
            "hello": "Hello World!",
            "goodbye": "Goodbye World!"
          },
          "defaultVariant": "hello",
          "targeting": {
            "if": [
              {
                "===": [
                  {
                    "var": "language"
                  },
                  "de"
                ]
              },
              "hallo"
              ]
          }    
        }
      }
   }
   ``` 

### Step 3.1.1 interceptor?

Adding this context population for each endpoint is a lot of effort, why not use an interceptor for this.

1. create an interceptor called `LanguageInterceptor.java`
   ```java
   public class LanguageInterceptor implements HandlerInterceptor {
       public LanguageInterceptor() {
       }
   
       @Override
       public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
           String language = request.getParameter("language");
           if (language != null) {
               HashMap<String, Value> attributes = new HashMap<>();
               attributes.put("language", new Value(language));
               ImmutableContext evaluationContext = new ImmutableContext(attributes);
               OpenFeatureAPI.getInstance().setTransactionContext(evaluationContext);
           }
           return HandlerInterceptor.super.preHandle(request, response, handler);
       }
       
       public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
           OpenFeatureAPI.getInstance().setTransactionContext(new ImmutableContext());
           HandlerInterceptor.super.afterCompletion(request, response, handler, ex);
       }
   
       static {
           OpenFeatureAPI.getInstance().setTransactionContextPropagator(new ThreadLocalTransactionContextPropagator());
       }
   }
   ```
   
2. adapt our `OpenFeatureConfig` to add this interceptor
   ```java
   @Configuration
   public class OpenFeatureConfig implements WebMvcConfigurer {
   
       @PostConstruct
       public void initProvider() {
           OpenFeatureAPI api = OpenFeatureAPI.getInstance();
           FlagdOptions flagdOptions = FlagdOptions.builder()
                   .resolverType(Config.Resolver.FILE)
                   .offlineFlagSourcePath("./flags.json")
                   .build();
   
           api.setProviderAndWait(new FlagdProvider(flagdOptions));
       }
   
       @Override
       public void addInterceptors(InterceptorRegistry registry) {
           registry.addInterceptor(new LanguageInterceptor());
       }
   }
   ```

3. remove the context propagation from the controller. Before we started with targeting
   ```java
       @GetMapping("/")
       public FlagEvaluationDetails<String> helloWorld() {
           Client client = OpenFeatureAPI.getInstance().getClient();
           return client.getStringDetails("greetings", "No World");
       }
   ```

### Step 3.2 Global Context

As mentioned we can also set some context globally. eg. springVersion

1. We adapt our flags configuration to also match for a certain spring version like:
   ```json
   {
     "flags": {
       "greetings": {
         "state": "ENABLED",
         "variants": {
           "springer": "Hi springer",
           "hallo": "Hallo Welt!",
           "hello": "Hello World!",
           "goodbye": "Goodbye World!"
         },
         "defaultVariant": "hello",
         "targeting": {
           "if": [
             {
               "sem_ver": [
                 {
                   "var": "springVersion"
                 },
                 ">=",
                 "3.0.0"
               ]
             },
             "springer",
             {
               "===": [
                 {
                   "var": "language"
                 },
                 "de"
               ]
             },
             "hallo"
           ]
         }
       }
     }
   }
   ```

2. Adding a Context within our initialization code:
   ```java
       @PostConstruct
       public void initProvider() {
           OpenFeatureAPI api = OpenFeatureAPI.getInstance();
           FlagdOptions flagdOptions = FlagdOptions.builder()
                   .resolverType(Config.Resolver.FILE)
                   .offlineFlagSourcePath("./flags.json")
                   .build();
   
           api.setProviderAndWait(new FlagdProvider(flagdOptions));
           
           HashMap<String, Value> attributes = new HashMap<>();
           attributes.put("springVersion", new Value(SpringVersion.getVersion()));
           ImmutableContext evaluationContext = new ImmutableContext(attributes);
           api.setEvaluationContext(evaluationContext);
       }
   ```
   
   If you change now the targeting, you will see that his version is actively affecting our evaluation.

Voila, we now see a different output as our version is one of our first arguments.

## Step 4 Hooks

Hooks allow us to enhance our code during feature flag evaluations, without writing our own provider.

### Step 4.1 creating and adding a hook

1. Creating a `CustomHook.java`
   ```java
    public class CustomHook implements Hook {
       private static final Logger LOG = LoggerFactory.getLogger(CustomHook.class);
   
   
       @Override
       public Optional<EvaluationContext> before(HookContext ctx, Map hints) {
           LOG.info("Before hook");
           return Optional.empty();
       }
   
       @Override
       public void after(HookContext ctx, FlagEvaluationDetails details, Map hints) {
           LOG.info("After hook - {}", details.getReason());
       }
   
       @Override
       public void error(HookContext ctx, Exception error, Map hints) {
           LOG.error("Error hook", error);
       }
   
       @Override
       public void finallyAfter(HookContext ctx, FlagEvaluationDetails details, Map hints) {
           LOG.info("Finally After hook - {}", details.getReason());
       }
   }
   ```

2. Adding the hook during instrumentation
   ```java
    @PostConstruct
    public void initProvider() {
        // ...
        api.addHooks(new CustomHook());
    }
   ```
   
   Take a look at the console, and see what kind of information you are getting.

## Step 5 Remote flagd

We already showed the file mode, which is good for getting a glimpse of the functionality, but flagd is more powerful.
So let's use flagd as a standalone process to fetch feature flag configurations.

### Step 5.1 Setup Flagd Standalone

1. We need a docker compose file (./docker-compose.yaml) , exposing the ports, and utilizing the same file
   ```yaml
   services:
     flagd:
       stdin_open: true
       tty: true
       container_name: flagd
       image: ghcr.io/open-feature/flagd:latest
       ports:
         - "8013:8013"
         - "8014:8014"
         - "8015:8015"
         - "8016:8016"
       env_file:
         - .env.local
       volumes:
         - "./flags.json:/flags.json"
       command: start --uri file:./flags.json
   ```

2. We can start the docker container with `docker compose up` within a terminal.
3. Let's change the flagd provider mode to either `RPC` or `IN_PROCESS`. Both talk to the running flagd container — no `offlineFlagSourcePath` needed (that's a `FILE` mode option, where the SDK reads `flags.json` directly without flagd):
   ```java
   FlagdOptions flagdOptions = FlagdOptions.builder()
          .resolverType(Config.Resolver.RPC)   // or Config.Resolver.IN_PROCESS
          .host("localhost")
          .port(8013)                          // 8015 for IN_PROCESS
          .build();
   ```

There are two different behaviours we can observe depending on the mode:
- **RPC**: every flag evaluation makes one gRPC round-trip to flagd on `:8013`.
- **IN_PROCESS**: the SDK opens a sync stream to flagd on `:8015`; flagd pushes the full flag set into the JVM, and evaluations happen locally with no per-call hop. When flagd notices `flags.json` change on disk, it streams the update to the SDK, which fires a change event.

## Step 6 OpenTelemetry observability

Every flag evaluation becomes a span in Tempo, nested under the HTTP request span that triggered it. Steps 6 and 7 run from `main` — they use the shared [`../observability/`](../observability/README.md) and [`../loadgen/`](../loadgen/README.md) stacks, which only stay current on `main` (the `step/java-spring/6` and `/7` branches predate them and don't carry them). In a per-language Codespace the full repo is still on disk, so reach those sibling folders from the terminal with `cd ../observability` even though the Explorer only lists this folder.

Run `cd ../observability && docker compose up -d`, then from this folder on `main` start the app. Grafana UI at <http://localhost:3000>, open the **Fun With Flags — Feature Flag Metrics** dashboard or use Explore → Tempo to pick the `fun-with-flags-java-spring` service — one span per evaluation with the flag key, variant, and reason attached as attributes.

## Step 7 Progressive rollout

A new greeting algorithm is rolling out. It is slower (200ms) than the old code path and it errors 10% of the time. The job of step 7 is to roll it out gradually, watch the consequences in Grafana, and roll it back without redeploying.

The code is on `main`, in this folder — the handler reads a new `new_greeting_algo` flag, and the middleware passes `?userId=...` through as the OpenFeature `targetingKey` so the fractional rollout buckets stick per user.

Two moving parts work together:

- **[`../loadgen/`](../loadgen/README.md)** drives traffic. It is gated by an `loadgen_active` flag (already in this folder's `flags.json`, default `"off"`) — flip it to `"on"` to start the load, back to `"off"` to stop. The feature-flag demo, feature-flagged.
- **`flags.json`** in this folder (on `main`) defines `new_greeting_algo` with a flagd `fractional` rule, defaulting to 100% off. Bump the percentage and flagd hot-reloads — no app restart.

Run the demo: start observability + the app + loadgen, flip `loadgen_active` to `"on"`, then ramp `new_greeting_algo` from `[["off",100],["on",0]]` to 10/90, then 50/50. Watch the **HTTP request latency (p50, p99)** and **HTTP 5xx per second** panels in Grafana climb. Roll back to 100/0 the moment something looks bad.
