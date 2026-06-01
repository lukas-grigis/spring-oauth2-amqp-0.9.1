/**
 * Integration tests for the OAuth2-secured AMQP demo.
 *
 * Requires the full stack to be running (Keycloak, RabbitMQ, and the three services).
 * Start it with `mise run demo`, then run these tests with `mise run test:integration`.
 *
 * The tests fetch real client_credentials tokens for each service and prove the broker-level
 * authorization model: the HTTP entry point accepts a job, every client may publish what its
 * scopes allow, and the broker refuses every publish a token is not authorized for.
 */

import {before, describe, it} from "node:test";
import assert from "node:assert/strict";
import amqp from "amqplib";

// ── Configuration (matches support/keycloak + the services' application.yml) ──

const KEYCLOAK = process.env.KEYCLOAK_URL ?? "http://localhost/auth";
const DISPATCHER = process.env.DISPATCHER_URL ?? "http://localhost:8080";
const RABBIT_HOST = process.env.RABBITMQ_HOST ?? "localhost";
const RABBIT_PORT = Number(process.env.RABBITMQ_PORT ?? "5672");
const REALM = "amqp-demo";

const SECRETS: Record<string, string> = {
  dispatcher: "dispatcher-secret",
  worker: "worker-secret",
  reporter: "reporter-secret",
};

// ── Helpers ───────────────────────────────────────────────────────────────

async function token(clientId: string): Promise<string> {
  const res = await fetch(`${KEYCLOAK}/realms/${REALM}/protocol/openid-connect/token`, {
    method: "POST",
    headers: {"Content-Type": "application/x-www-form-urlencoded"},
    body: new URLSearchParams({
      grant_type: "client_credentials",
      client_id: clientId,
      client_secret: SECRETS[clientId],
    }),
  });
  const body = await res.json();
  assert.equal(res.status, 200, `token request for ${clientId} failed: ${JSON.stringify(body)}`);
  return body.access_token as string;
}

/** The token IS the AMQP password (empty username), exactly as the services connect. */
async function connect(clientId: string) {
  return amqp.connect({
    protocol: "amqp",
    hostname: RABBIT_HOST,
    port: RABBIT_PORT,
    username: "",
    password: await token(clientId),
    vhost: "/",
  });
}

/** Attempts one publish with publisher confirms; returns {ok} or {ok:false, error} on broker refusal. */
async function tryPublish(
  clientId: string,
  exchange: string,
  routingKey: string,
): Promise<{ ok: boolean; error?: string }> {
  const connection = await connect(clientId);
  // The broker reports an authorization failure by closing the channel with a 403 ACCESS_REFUSED frame.
  // amqplib delivers that detail via the channel "error" event, while waitForConfirms() rejects with a
  // generic "channel closed" — so capture the detailed reason and prefer it when reporting the failure.
  let channelError: unknown;
  try {
    const channel = await connection.createConfirmChannel();
    channel.on("error", (err) => { channelError = err; });
    channel.publish(exchange, routingKey, Buffer.from("{}"));
    await channel.waitForConfirms();
    return {ok: true};
  } catch (error) {
    // The "error" event may land a tick after waitForConfirms() rejects; give it a moment.
    if (channelError === undefined) {
      await new Promise((resolve) => setTimeout(resolve, 50));
    }
    const reason = channelError ?? error;
    return {ok: false, error: reason instanceof Error ? reason.message : String(reason)};
  } finally {
    await connection.close().catch(() => { /* server may have closed it already */
    });
  }
}

// ── Tests ─────────────────────────────────────────────────────────────────

describe("OAuth2-secured AMQP", () => {
  before(async () => {
    const health = await fetch(`${DISPATCHER}/actuator/health`).catch(() => null);
    assert.ok(health?.ok, "Dispatcher is not reachable — is the stack running? (mise run demo)");
  });

  describe("HTTP entry point", () => {
    it("accepts a job and returns 202 with an id", async () => {
      const res = await fetch(`${DISPATCHER}/jobs`, {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({payload: "hello amqp"}),
      });
      assert.equal(res.status, 202);
      const body = await res.json();
      assert.ok(body.id, "expected a job id in the response");
      assert.equal(body.status, "accepted");
    });
  });

  describe("broker authorization — allowed", () => {
    it("dispatcher may publish job.* to the jobs exchange", async () => {
      const r = await tryPublish("dispatcher", "jobs", "job.submitted");
      assert.ok(r.ok, `expected success, got: ${r.error}`);
    });

    it("worker may publish result.* to the results exchange", async () => {
      const r = await tryPublish("worker", "results", "result.ready");
      assert.ok(r.ok, `expected success, got: ${r.error}`);
    });
  });

  describe("broker authorization — refused (the payoff)", () => {
    it("reporter cannot publish (results_read only)", async () => {
      const r = await tryPublish("reporter", "results", "result.ready");
      assert.equal(r.ok, false, "reporter must not be allowed to publish");
      assert.match(r.error ?? "", /ACCESS[_-]REFUSED|403/i);
    });

    it("dispatcher cannot cross over to the results exchange", async () => {
      const r = await tryPublish("dispatcher", "results", "result.ready");
      assert.equal(r.ok, false, "dispatcher must not be allowed to publish to results");
      assert.match(r.error ?? "", /ACCESS[_-]REFUSED|403/i);
    });

    it("worker cannot publish a routing key outside its result.* scope", async () => {
      const r = await tryPublish("worker", "results", "internal.audit");
      assert.equal(r.ok, false, "worker must not publish a disallowed routing key");
      assert.match(r.error ?? "", /ACCESS[_-]REFUSED|403/i);
    });
  });
});
