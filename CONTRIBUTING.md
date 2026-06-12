# Contributing

A short guide so the repo stays useful as a teaching aid and doesn't drift into a generic demo pile.

## Branch convention

Step branches follow `step/<folder>/<version>` — e.g. `step/java-spring/3.1`, `step/go-chi/5.2`. Each branch only changes files inside its own language folder. A learner on the Go track should never see a Python diff in their branch, and vice versa.

Step branches are frozen snapshots. Do **not** rebase them onto `main` later — they represent a specific moment in the tutorial, and rewriting them breaks the link between the slides and the code.

New work targeting all languages (devcontainer, root README, CI) lands on `main` directly via its own PR — never bundled with a language-specific change.

## Per-language folders are self-contained

Every language folder assumes you `cd` into it and everything runs from there: build, test, run, docker compose, the lot. If a change in `go-chi/` needs changes in `python-fastapi/`, split the PR. Cross-cutting PRs (devcontainer, umbrella README, CONTRIBUTING, renovate config) stay separate from single-language PRs. It keeps review small and keeps step branches clean.

Folder names read `<language>-<framework>` (`java-spring`, `java-quarkus`, `go-chi`, `python-fastapi`, `node-express`) so a second framework per language — `java-micronaut`, `python-flask`, `go-gin`, `node-fastify` — can slot in without renaming anything.

## Devcontainer configs

There's one Codespaces config per language under `.devcontainer/`: `java-spring/`, `java-quarkus/`, `go-chi/`, `python-fastapi/`, `node-express/`. Each uses a slim, language-specific base image (so the boot is quick), adds Docker-in-Docker so that folder's `docker-compose.yaml` runs, and sets `workspaceFolder` so the Codespace opens straight into its language folder. Changes to *any* of them are cross-cutting and should land in their own PR (don't bundle a devcontainer tweak with a language change).

## Tone for READMEs

The voice across this repo is deliberately mine — first-person singular, lead with *why*, name specifics (SDK versions, ports, framework names), and stay honest about trade-offs. Concretely:

- Write "I chose Express because…", not "We chose" and not "This project uses".
- One sentence on motivation before any code block.
- Short, direct sentences. No heavy subordination. No marketing words — `powerful`, `seamless`, `robust`, `cutting-edge`, `elegant` do not appear in this repo.
- When something is tedious or awkward, say so. "Yes, wiring this by hand is verbose, and that is the point of the next step."

## Proposing a new language or framework

Copy one of the existing folders (pick the closest paradigm — `node-express/` for a dynamic language, `go-chi/` for a compiled one), swap the OpenFeature SDK and the HTTP framework, and walk the same 1.1 → 5.2 arc: add SDK, in-memory provider, flagd file provider, dynamic context, interceptor/middleware, global context, hooks, remote flagd via compose, remote flagd via Testcontainers. Keep step numbering identical so the cross-language comparison still works. Open one PR for the scaffold on `main`, then one step-branch per tutorial step.
