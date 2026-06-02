<p align="center">
  <img src="https://img.shields.io/badge/Spring_Boot-4.0-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot 4">
  <img src="https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white" alt="Java 25">
  <img src="https://img.shields.io/badge/RabbitMQ-4.1-FF6600?logo=rabbitmq&logoColor=white" alt="RabbitMQ 4.1">
  <img src="https://img.shields.io/badge/Keycloak-26-4D4D4D?logo=keycloak&logoColor=white" alt="Keycloak 26">
  <img src="https://img.shields.io/badge/license-MIT-blue" alt="MIT License">
</p>

<h1 align="center">OAuth2-secured AMQP messaging</h1>

<p align="center">
  A Spring Boot demo that secures <strong>both</strong> broker connections and per-message permissions with OAuth2.<br>
  No <code>guest/guest</code>. No application-level ACLs. Just tokens, scopes, and a broker that refuses to lie.
</p>

---

Most RabbitMQ tutorials authenticate services with `guest/guest` (or some other shared password) and enforce
who-can-do-what inside the application. Both habits are bad: shared credentials are a blast radius, and
application-level ACLs are trust-on-first-read.

This repo shows a cleaner model — every service authenticates to the broker with an OAuth2 access token, and RabbitMQ
authorizes each publish and consume based on the scopes inside that token. When the reporter service (which is only
supposed to read results) tries to publish, the broker rejects the attempt. Not the application. The broker.

## Architecture

```
                          Traefik (reverse proxy)
                                   │  /auth
                                   ▼
Keycloak (HTTP, behind Traefik) ── Postgres
  ├── client: dispatcher  (scopes: jobs_write)
  ├── client: worker      (scopes: jobs_read, results_write)
  └── client: reporter    (scopes: results_read)

RabbitMQ (OAuth2 plugin → JWKS via Traefik over TLS)
  ├── exchange: jobs      (dispatcher writes, worker consumes via jobs.in)
  └── exchange: results   (worker writes, reporter consumes via results.out)

Services (Spring Boot 4)
  ├── dispatcher (:8080)  REST trigger → publish to jobs
  ├── worker              consume jobs → publish to results
  └── reporter            consume results (publish attempts rejected by broker)
```

### How the pieces fit together

Each service uses the OAuth2 `client_credentials` grant to fetch an access token from Keycloak. The token becomes the
AMQP connection password — the RabbitMQ Java client treats the JWT as the credentials, the broker validates its
signature against Keycloak's JWKS, checks that its audience is `rabbitmq`, and the scopes inside determine which
exchanges and queues the client can touch.

The scope claim carries friendly aliases (`jobs_write`, `results_read`) that the broker's OAuth2 plugin maps to its
native permission format — **including the routing key**: `jobs_write` expands to `rabbitmq.write:*/jobs/job.*`, so the
dispatcher may publish to the `jobs` exchange but only with `job.*` routing keys. Keycloak stays readable; RabbitMQ
stays expressive. See [Routing keys](#routing-keys) for the full convention.

Two details make the validation real:

- **Audience** — every token carries `aud: rabbitmq` (added by a Keycloak audience mapper), because the broker's
  `verify_aud` is on by default.
- **TLS for JWKS** — the OAuth2 plugin *requires* an `https` JWKS URL. Rather than putting a certificate on Keycloak
  (which runs plain HTTP), the broker fetches the keys through **Traefik**, which terminates TLS with its built-in
  self-signed certificate. Nothing is committed; the broker uses `verify_none` for that internal hop.

Each service wires the token in through a Spring Boot `ConnectionFactoryCustomizer`, so Boot still auto-configures the
connection factory, `RabbitTemplate`, and listener containers from `spring.rabbitmq.*` — only the credentials are
swapped. Spring Security's `OAuth2AuthorizedClientManager` mints and caches the tokens, and a `CredentialsRefreshService`
renews each one **on the live connection** (via the AMQP `update-secret` method) at 80% of its lifetime — so a long-lived
connection never outlives its token.

## Quick start

You need [Docker](https://docs.docker.com/get-docker/) and [mise](https://mise.jdx.dev/). mise handles Java, Maven, and
Node versions automatically, so there's no manual setup.

```bash
git clone https://github.com/lukas-grigis/spring-oauth2-amqp.git
cd spring-oauth2-amqp
mise run demo
```

One command. It builds the Maven project, starts the infrastructure (Traefik, Postgres, Keycloak, RabbitMQ),
provisions the Keycloak realm, then boots all three Spring Boot services. First run takes a minute while containers
download.

When it's up, submit a job:

```bash
curl -X POST http://localhost:8080/jobs \
  -H 'Content-Type: application/json' \
  -d '{"payload":"hello amqp"}'
```

Then watch the logs:

```bash
tail -f .logs/{dispatcher,worker,reporter}.log
```

You'll see the job flow through: dispatcher publishes → worker consumes from `jobs.in` and publishes the result →
reporter consumes from `results.out` and logs it.

Press `Ctrl+C` in the `mise run demo` terminal to stop everything.

## The payoff

The point of the demo is to prove that broker-level authorization actually holds. The reporter service (scope:
`results_read`) can consume results but the moment it tries to *publish*, RabbitMQ refuses the channel with
`ACCESS_REFUSED`. The rejection comes from the broker, not from any `if (user.hasPermission(...))` in application code.

You don't have to take that on faith — the [integration tests](#tests) prove it against the live stack: every service
may publish exactly what its scopes allow, and the reporter (publish), the dispatcher (cross-exchange), and the worker
(out-of-scope routing key) are each refused.

## Routing keys

Both exchanges are `topic` exchanges, and the routing key is part of the contract — and part of the authorization.
The convention is `<domain>.<entity>.<event>`, lowercase and dot-delimited, most-stable segment first:

```
jobs (topic)      dispatcher publishes  job.submitted   →  queue jobs.in    binds job.#
results (topic)   worker publishes      result.ready    →  queue results.out binds result.#
```

Producers always publish a **fully-qualified** key (`job.submitted`); consumers bind with **wildcards** (`#` = zero or
more words, `*` = exactly one). Because each exchange owns its own key namespace (`job.*` vs `result.*`), the two stay
visibly distinct, and the OAuth2 write scopes carry the routing key (`rabbitmq.write:*/jobs/job.*`) — so the broker
enforces not just *which exchange* a service may publish to, but *which routing keys*.

When to reach for what:

| Change                | Add it when…                                                                     |
|-----------------------|----------------------------------------------------------------------------------|
| **A new routing key** | there's a new *event type* a subset of consumers cares about (`job.failed`)      |
| **A new binding**     | a new consumer wants a subset of existing events (a `jobs.audit` queue, `job.#`) |
| **A new exchange**    | you cross a *domain* boundary (jobs vs. results)                                 |

The integration test exercises this directly: the worker may publish `result.ready` but is refused when it tries
`internal.audit`, because that key falls outside its `result.*` scope.

## How the RabbitMQ OAuth2 plugin is configured

`support/rabbitmq/rabbitmq.conf` enables the OAuth2 plugin alongside the internal backend (kept so the management UI
stays usable with `admin/admin`):

```properties
auth_backends.1=rabbit_auth_backend_internal
auth_backends.2=rabbit_auth_backend_oauth2
auth_oauth2.resource_server_id=rabbitmq

# The plugin requires an https JWKS URL. Keycloak runs plain HTTP, so the broker fetches the keys through
# Traefik, which terminates TLS with its built-in self-signed cert (verify_none for this internal hop).
auth_oauth2.jwks_uri=https://traefik/auth/realms/amqp-demo/protocol/openid-connect/certs
auth_oauth2.https.peer_verification=verify_none
auth_oauth2.https.hostname_verification=none

# Aliases map clean Keycloak scope names to native permissions. The 3rd segment on write scopes restricts
# the routing key (glob match — "job.*" = routing keys starting with "job.").
auth_oauth2.scope_aliases.jobs_write=rabbitmq.write:*/jobs/job.*
auth_oauth2.scope_aliases.jobs_read=rabbitmq.read:*/jobs.in rabbitmq.configure:*/jobs.in
auth_oauth2.scope_aliases.results_write=rabbitmq.write:*/results/result.*
auth_oauth2.scope_aliases.results_read=rabbitmq.read:*/results.out rabbitmq.configure:*/results.out
```

The scope aliases keep Keycloak scope names clean (`jobs_write`) while the broker operates in its native permission
vocabulary (`rabbitmq.{permission}:{vhost}/{resource}/{routing-key}`).

## How Spring AMQP uses OAuth2 for the connection password

Each service registers a `ConnectionFactoryCustomizer` that installs a `CredentialsProvider` on the auto-configured
RabbitMQ connection factory, returning the current OAuth2 access token as the password — Boot keeps ownership of the
factory, so the rest of `spring.rabbitmq.*` still applies:

```java
public String getPassword() {
    var request = OAuth2AuthorizeRequest
            .withClientRegistrationId("rabbitmq-broker")
            .principal(PRINCIPAL)
            .build();
    return authorizedClientManager.authorize(request).getAccessToken().getTokenValue();
}
```

*(Simplified for readability — the real `OAuth2CredentialsProvider` takes the registration id as a
constructor argument and guards against a null token.)*

Spring's `OAuth2AuthorizedClientManager` mints and caches the tokens, and `getPassword()` returns the current one. On its
own that is *connect-time* only — over AMQP 0.9.1 a long-lived connection keeps the token it was born with, and once that
token expires the broker refuses every operation with `ACCESS_REFUSED` (it does not drop the connection, so the client
never re-authenticates). So the same customizer also installs a `CredentialsRefreshService`: because
`OAuth2CredentialsProvider` reports `getTimeBeforeExpiration()`, the client renews the token **in place** on the open
connection — via the AMQP `update-secret` method — at 80% of its lifetime. No reconnect, no dropped messages. The
[integration tests](#tests) prove both halves.

## Keycloak runs over plain HTTP

Keycloak runs `start-dev` over plain HTTP behind Traefik (at `/auth`) — no certificate on Keycloak itself. Two small
consequences, both handled automatically by `mise run demo` / `mise run keycloak:setup`:

- Keycloak is backed by **Postgres** (not the in-memory H2) so the next point survives a restart.
- The `master` realm refuses plain-HTTP admin calls by default. The setup flips `ssl_required` to `NONE` on `master`
  (`UPDATE realm SET ssl_required = 'NONE'` via `psql`, then a restart — **dev only**) so the Admin-API provisioner can
  run over HTTP. The `amqp-demo` realm is created with `sslRequired: none` directly.

## Tests

```bash
# 1. start everything (separate terminal, leave it running)
mise run demo

# 2. run the integration tests against the live stack
mise run test:integration
```

- **Per-service context tests** (`*ApplicationTest`, run by `mise run test` / `mvn clean verify`) boot each Spring context offline — they
  verify the beans and the validated `app.amqp` properties wire up, with no broker required.
- **Integration tests** (`test/`, Node's built-in test runner + `amqplib`) run against the live
  stack and are the real proof. They fetch real `client_credentials` tokens for each service and assert: the HTTP
  entry point accepts a job; every service may publish what its scopes allow; and the **reporter (publish), the
  dispatcher (cross-exchange), and the worker (out-of-scope routing key) are each refused** by the broker. A final pair
  shortens the realm's token lifespan to 15s and proves the refresh story: the broker refuses an **expired** token, and
  the AMQP **`update-secret`** method renews the credential on a live connection.

## Running individual services

```bash
mise run infra:up          # start Traefik + Postgres + Keycloak + RabbitMQ
mise run keycloak:setup    # relax master-realm SSL, then provision realm + clients + scopes
mise run dispatcher:start  # start just the dispatcher
mise run worker:start
mise run reporter:start
mise run infra:down        # tear everything down
```

## Project structure

```
.
├── pom.xml                     # parent Maven module (aggregates the three services)
├── mise.toml                   # tool versions + the `mise run …` task runner
├── dispatcher/                 # REST trigger → publishes jobs
├── worker/                     # consumes jobs, publishes results
├── reporter/                   # consumes results (read-only)
├── test/                       # Node + amqplib integration tests against the live stack
└── support/
    ├── .env                    # ports + image tags
    ├── docker-compose.yml
    ├── database/               # Postgres .env + init script (creates the keycloak DB)
    ├── keycloak/               # realm + client provisioning (TypeScript Admin API)
    └── rabbitmq/               # rabbitmq.conf, enabled_plugins, topology definitions
```

The `database` and `keycloak` services each own a small `.env` file that docker-compose loads via `env_file:`, so their
configuration lives next to them. (`support/.env` holds the shared host ports and image tags.)

## Tech stack

| Layer          | What's running                                            |
|----------------|-----------------------------------------------------------|
| Services       | Spring Boot 4, Spring AMQP, Spring Security OAuth2 Client |
| Broker         | RabbitMQ 4.1 with `rabbitmq_auth_backend_oauth2`          |
| Auth           | Keycloak 26 (client_credentials grant) + Postgres         |
| Edge           | Traefik (reverse proxy, TLS termination for JWKS)         |
| Infrastructure | Docker Compose                                            |
| Build          | Maven (multi-module), Java 25                             |

## Security notice

This is a demo. The setup is optimized for clarity, not for production:

- Client secrets are committed in plain text (use a secrets manager in real life; the apps already read them from
  `${*_CLIENT_SECRET}` env vars, defaulting to the demo values)
- Keycloak runs in `start-dev` over plain HTTP, with `ssl_required` disabled on the `master` realm; the broker reaches
  its JWKS through Traefik with `verify_none` (use real CA-signed certs and `verify_peer` end-to-end in production)
- The Postgres and RabbitMQ admin users are `admin/admin`
- The `dispatcher` REST endpoint is unauthenticated

Don't deploy this as-is. Use it as a reference for how broker-level OAuth2 wires together, then harden it to your own
standards.

## Contributing

If you find a bug or have an idea, open an issue or send a pull request.

1. Fork the repo
2. Create a branch (`git checkout -b my-change`)
3. Make your changes
4. Run `mise run test` (and `mise run test:integration` against a running stack) to make sure things work
5. Open a PR

## License

[MIT](LICENSE)
