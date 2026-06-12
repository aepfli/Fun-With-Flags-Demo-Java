# OpenFeature Node.js Demo

This is the Node.js variant of [Fun With Flags](../README.md), built on Express. I added it because a lot of OpenFeature adopters ship on Node, and having an idiomatic Node example saves people the mental translation from a Java tutorial. The step-by-step arc mirrors the [Spring Boot variant](../java-spring/README.md) one-to-one.

Two things to flag up front as differences from the JVM versions:

- Per-request state lives in `AsyncLocalStorage`, not in a thread-local. This matters because Node is single-threaded but very async — `AsyncLocalStorage` is the right primitive for propagating per-request context through `await` boundaries, and OpenFeature's `AsyncLocalStorageTransactionContextPropagator` uses it directly.
- The whole thing is ES modules, Node 20+. Logs go through `pino` so the output looks like something you would actually keep in production.

Run the app with `node src/index.js`, then `curl http://localhost:8080/`. Requests for every step live in [`requests.http`](requests.http).

## Step 1.1 Add the OpenFeature SDK

```
npm install @openfeature/server-sdk
```

In `src/index.js`:

```js
import { OpenFeature } from '@openfeature/server-sdk';
const client = OpenFeature.getClient();

app.get('/', async (_req, res) => {
  const details = await client.getStringDetails('greetings', 'Hello World');
  res.json(details);
});
```

Run it, hit `/`, get `Hello World` — the fallback. No provider yet.

## Step 1.2 Provider initialization (in-memory)

```js
import { InMemoryProvider } from '@openfeature/server-sdk';

await OpenFeature.setProviderAndWait(new InMemoryProvider({
  greetings: {
    defaultVariant: 'hello',
    variants: { hello: 'Hello World!', goodbye: 'Goodbye World!' },
    disabled: false,
  },
}));
```

Enough for step 1, not enough for step 2.

## Step 2.1 Flagd file provider

```
npm install @openfeature/flagd-provider
```

I move the flag definition to [`flags.json`](flags.json) and swap the provider in `src/openfeature.js`:

```js
import { FlagdProvider } from '@openfeature/flagd-provider';

const provider = new FlagdProvider({
  resolverType: 'in-process',
  offlineFlagSourcePath: './flags.json',
});
await OpenFeature.setProviderAndWait(provider);
```

Edit `flags.json`, change the `defaultVariant`, curl again. The value changes without a restart.

## Step 3.1 Dynamic context

The simplest form of targeting pulls `language` from the query string and puts it in the evaluation context:

```js
app.get('/', async (req, res) => {
  const ctx = { language: req.query.language };
  const details = await client.getStringDetails('greetings', 'Hello World', ctx);
  res.json(details);
});
```

The targeting rule in `flags.json` maps `language == "de"` to the `hallo` variant.

## Step 3.1.1 Express middleware + AsyncLocalStorage

Wiring the context inside every handler is going to get old fast. I set up an `AsyncLocalStorageTransactionContextPropagator` once, then an Express middleware runs each request inside an `AsyncLocalStorage` scope:

```js
OpenFeature.setTransactionContextPropagator(
  new AsyncLocalStorageTransactionContextPropagator(),
);
```

```js
export function languageMiddleware(req, _res, next) {
  const language = req.query.language;
  if (language) {
    OpenFeature.setTransactionContext({ language }, next);
  } else {
    next();
  }
}
```

With that wired in, the handler goes back to a one-liner: `await client.getStringDetails('greetings', 'Hello World')` reads the context from the `AsyncLocalStorage` without the handler having to plumb it through.

## Step 3.2 Global evaluation context

Runtime version does not change per request, so it belongs on the global context set once at startup:

```js
OpenFeature.setContext({ nodeVersion: process.versions.node });
```

The targeting rule in `flags.json` matches `nodeVersion >= "20.0.0"` and returns the `noder` variant. `process.versions.node` already returns a clean semver string like `"22.4.1"`, so no prefix-stripping gymnastics like the Go variant needs.

## Step 4 Hooks

```js
import pino from 'pino';
const logger = pino();

export class CustomHook {
  before(ctx)  { logger.info({ flag: ctx.flagKey }, 'Before hook'); }
  after(ctx, details) { logger.info({ flag: ctx.flagKey, variant: details.variant, reason: details.reason }, 'After hook'); }
  error(ctx, err) { logger.error({ flag: ctx.flagKey, err }, 'Error hook'); }
  finally(ctx, details) { logger.info({ flag: ctx.flagKey, reason: details.reason }, 'Finally'); }
}
```

Registered once in `src/openfeature.js` via `OpenFeature.addHooks(new CustomHook())`.

## Step 5.1 Remote flagd via docker compose

File mode is fine for demos. In real deployments flagd runs as its own process. I spin it up with [`docker-compose.yaml`](docker-compose.yaml), then switch the resolver to RPC:

```js
const provider = new FlagdProvider({ resolverType: 'rpc', host: 'localhost', port: 8013 });
```

RPC calls flagd on every evaluation. `in-process` mode pointed at the same flagd container (`port: 8015`, the sync stream) opens a subscription instead — flagd pushes the flag set into the SDK and evaluations stay local. Cheaper for hot paths.

## Step 5.2 Testing against flagd without docker compose

`testcontainers` (the Node package) owns the container lifecycle inside the test, so `vitest` does not need a second terminal:

```js
import { GenericContainer } from 'testcontainers';

const container = await new GenericContainer('ghcr.io/open-feature/flagd:latest')
  .withBindMounts([{ source: resolve('./flags.json'), target: '/flags.json', mode: 'ro' }])
  .withExposedPorts(8013)
  .withCommand(['start', '--uri', 'file:/flags.json'])
  .start();
```

Run `npm test`. Vitest starts the container, runs the assertions against `?language=de` and the default greeting, and shuts the container down.

## Step 6 OpenTelemetry observability

Every flag evaluation becomes a span in Tempo, nested under the HTTP request span that triggered it. Steps 6 and 7 run from `main` — they use the shared [`../observability/`](../observability/README.md) and [`../loadgen/`](../loadgen/README.md) stacks, which only stay current on `main` (the `step/node-express/6` and `/7` branches predate them and don't carry them). In a per-language Codespace the full repo is still on disk, so reach those sibling folders from the terminal with `cd ../observability` even though the Explorer only lists this folder.

Run `cd ../observability && docker compose up -d`, then from this folder on `main` start the app with `node src/index.js`. Grafana UI at <http://localhost:3000>, open the **Fun With Flags — Feature Flag Metrics** dashboard or use Explore → Tempo to pick the `fun-with-flags-node-express` service.

## Step 7 Progressive rollout

A new greeting algorithm is rolling out. It is slower (200ms) than the old code path and it errors 10% of the time. The job of step 7 is to roll it out gradually, watch the consequences in Grafana, and roll it back without redeploying.

The code is on `main`, in this folder — the handler reads a new `new_greeting_algo` flag, and the middleware passes `?userId=...` through as the OpenFeature `targetingKey` so the fractional rollout buckets stick per user.

Two moving parts work together:

- **[`../loadgen/`](../loadgen/README.md)** drives traffic. It is gated by an `loadgen_active` flag (already in this folder's `flags.json`, default `"off"`) — flip it to `"on"` to start the load, back to `"off"` to stop. The feature-flag demo, feature-flagged.
- **`flags.json`** in this folder (on `main`) defines `new_greeting_algo` with a flagd `fractional` rule, defaulting to 100% off. Bump the percentage and flagd hot-reloads — no app restart.

Run the demo: start observability + the app + loadgen, flip `loadgen_active` to `"on"`, then ramp `new_greeting_algo` from `[["off",100],["on",0]]` to 10/90, then 50/50. Watch the **HTTP request latency (p50, p99)** and **HTTP 5xx per second** panels in Grafana climb. Roll back to 100/0 the moment something looks bad.
