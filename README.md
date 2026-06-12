# Fun With Flags — An OpenFeature Demo

[![CI](https://github.com/aepfli/Fun-With-Flags-Demo/actions/workflows/ci.yml/badge.svg)](https://github.com/aepfli/Fun-With-Flags-Demo/actions/workflows/ci.yml)
[![Java Spring](https://img.shields.io/static/v1?label=Codespace&message=Java%20Spring&color=6db33f&logo=spring)](https://codespaces.new/aepfli/Fun-With-Flags-Demo?devcontainer_path=.devcontainer%2Fjava-spring%2Fdevcontainer.json)
[![Java Quarkus](https://img.shields.io/static/v1?label=Codespace&message=Java%20Quarkus&color=4695eb&logo=quarkus)](https://codespaces.new/aepfli/Fun-With-Flags-Demo?devcontainer_path=.devcontainer%2Fjava-quarkus%2Fdevcontainer.json)
[![Go](https://img.shields.io/static/v1?label=Codespace&message=Go&color=00add8&logo=go)](https://codespaces.new/aepfli/Fun-With-Flags-Demo?devcontainer_path=.devcontainer%2Fgo-chi%2Fdevcontainer.json)
[![Python](https://img.shields.io/static/v1?label=Codespace&message=Python&color=3776ab&logo=python)](https://codespaces.new/aepfli/Fun-With-Flags-Demo?devcontainer_path=.devcontainer%2Fpython-fastapi%2Fdevcontainer.json)
[![Node](https://img.shields.io/static/v1?label=Codespace&message=Node&color=339933&logo=nodedotjs)](https://codespaces.new/aepfli/Fun-With-Flags-Demo?devcontainer_path=.devcontainer%2Fnode-express%2Fdevcontainer.json)

I built this demo because OpenFeature talks tend to pick one language and leave the rest of the room guessing whether the same ideas apply to them. They do, and this repo proves it. I walk the exact same journey — add the SDK, swap providers, add context, add hooks, go remote — across Java Spring Boot, Java Quarkus, Go, Python (FastAPI), and Node.js (Express). Each folder is self-contained, so if you came for Go you never need to read the Python code, and the steps line up 1:1 so you can peek at another language when you want to compare.

I am a CNCF Ambassador, an OpenFeature maintainer, and I sit in the top three contributors to the project — so the opinions in these READMEs are mine, and I'll tell you when I think something is awkward.

## Pick your stack

| Folder | Stack |
| --- | --- |
| [`java-spring/README.md`](java-spring/README.md) | Java + Spring Boot |
| [`java-quarkus/README.md`](java-quarkus/README.md) | Java + Quarkus |
| [`go-chi/README.md`](go-chi/README.md) | Go + chi |
| [`python-fastapi/README.md`](python-fastapi/README.md) | Python + FastAPI |
| [`node-express/README.md`](node-express/README.md) | Node.js + Express |

The folder name always reads `<language>-<framework>`, so adding a new variant later (`java-micronaut`, `python-flask`, `go-gin`, …) drops in alongside the existing ones without breaking the pattern.

## Walk the steps

Pick a language and you're done choosing — there's one Codespace per stack, and the whole journey runs inside it. You don't swap environments as you progress; you just bring up more infrastructure as the steps ask for it. flagd lives in your language folder's `docker-compose.yaml`, and the observability stack and loadgen are shared (see [`observability/`](observability/README.md) and [`loadgen/`](loadgen/README.md)) — `docker compose up` them when you reach the step that needs them.

| Steps | What it teaches | What it needs |
| --- | --- | --- |
| **1.1, 1.2** | OpenFeature SDK basics, in-memory provider | just the language toolchain |
| **2.1 → 5.2** | flagd, targeting, interceptors, hooks, remote flagd, Testcontainers | flagd, via your folder's `docker compose up` |
| **6, 7** | OpenTelemetry traces & metrics, progressive rollout with consequences | + the shared LGTM stack and loadgen |

Click your stack's Codespaces badge at the top to launch — it boots a slim, language-specific image straight into that folder. Locally: clone the repo, then in VS Code run **Dev Containers: Reopen in Container** and pick your language from the prompt.

Port `8080` is the app. `8013` is flagd's gRPC eval (the gRPC-Gateway HTTP/JSON paths ride on the same port via cmux); `8014` is flagd management — Prometheus `/metrics`, `/healthz`, `/readyz`; `8015` is the sync gRPC stream that powers `IN_PROCESS`; `8016` is flagd's OFREP HTTP eval API. `3000` is Grafana. `4317` / `4318` are the OTLP receivers. Every language Codespace forwards all of these — you just won't touch the later ones until you reach those steps.

## Slides

The canonical, always-current deck lives at **<https://schrottner.at/openFeatureTalk>**. A snapshot is checked in as [`Fun with Flags.pdf`](Fun%20with%20Flags.pdf) at the repo root for workshops where the wifi does not cooperate.

## Shared infrastructure

Most of the journey lives per-language inside `<language>-<framework>/`, but two cross-cutting folders sit at the repo root because they're identical regardless of which stack you picked:

- **[`observability/`](observability/README.md)** — a Grafana LGTM container (Grafana, Prometheus, Tempo, Loki, OTLP receivers, all one image). One backend for every variant, one URL (<http://localhost:3000>) to open. Used by **step 6**, where each `step/<folder>/6` branch adds the OpenTelemetry hooks + exporter config so every flag evaluation lands in Tempo and Prometheus alongside the rest of the app's telemetry.
- **[`loadgen/`](loadgen/README.md)** — a k6 container that drives traffic against whichever language variant you're running, **gated by the `loadgen_active` OpenFeature flag** (the demo is itself feature-flagged at the lever you're learning about — flip to start, flip to stop). Used by **step 7**, where each `step/<folder>/7` branch adds the deliberately-bad `new_greeting_algo` (200 ms slower, 10% errors) and reads `?userId=…` as the OpenFeature `targetingKey` so the fractional rollout buckets are stable per user.

The per-language READMEs walk the step-by-step operational story for both — start there for the recipe (ramp the percentage in `flags.json`, watch the HTTP latency and 5xx panels respond, roll back the second something looks bad).

> The legacy `demo/with-tracking` branch is kept around for anyone who bookmarked it, but `step/<folder>/6` supersedes it.
