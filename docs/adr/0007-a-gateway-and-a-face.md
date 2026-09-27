# ADR 0007: one gateway, one web app, and the event stream as the picture

Status: accepted

## Context

Eight services, each on its own port, each wanting a bearer token from Keycloak, and no
way to see what they do except curl, `rpk topic consume` and Grafana. That proves the
system to someone reading the README; it shows nothing to someone watching a demo. A
payment is the interesting thing here and it is invisible: a 202, then three services
trading ten messages over Kafka, then a row that says `Captured`.

What a browser needs that the services do not give it:

- one origin to call, not eight ports (and no CORS config in eight services);
- a login that ends in a token the services already accept;
- the messages themselves, as they happen - the saga is the product, not its end state;
- a list of payments (payment-service only answers `GET /{id}`).

## Options considered

1. **Browser calls every service directly.** Eight origins, CORS in the shared starter,
   and still no way to see the events. Rejected.
2. **Gateway as a backend-for-frontend holding the session** (OAuth2 client, cookie in the
   browser, TokenRelay downstream). The safest token handling, and a stateful gateway with
   a session store to size and replicate. More than a demo needs.
3. **Gateway as a stateless edge, browser does OIDC with PKCE.** The SPA logs in against
   Keycloak (a new public client, `ledgerflow-web`), sends the access token to the gateway,
   the gateway validates it with the same shared resource-server chain every service
   already has, routes by path and passes the header on. The services check it again:
   the gateway is a convenience, not the only door.

## Decision

Option 3.

- **`gateway-service`** (8088): Spring Cloud Gateway Server WebMVC on the same Boot and
  starter as everything else, so its spans join the payment's trace and its requests carry
  the same `X-Request-Id`. One route per path prefix:
  `/api/v1/accounts|transfers` → account, `/holds` → ledger, `/balances` → balance,
  `/payments` → payment, `/cases` → risk, `/fx` → issuer, `/batch` → settlement.
- **`GET /api/v1/stream`** on the gateway: Server-Sent Events of every
  `ledgerflow.*.events.v1` and `ledgerflow.*.commands.v1` record, as they cross the broker.
  The gateway reads with its own throwaway consumer group from `latest`, commits nothing,
  and keeps the last 200 in memory so a page that connects mid-payment still sees its start.
  It observes; it consumes nothing any service depends on.
- **`web`** (8089 in containers, 5173 under Vite): React 19 + TypeScript + Vite, Tailwind,
  TanStack Query, React Router, `react-oidc-context` for PKCE, React Flow for the live map.
  Served by nginx, which proxies `/api` to the gateway, so the browser sees one origin.
  The browser mints the `X-Request-Id` for each write, so the `correlationId` on every event
  that write causes is a value the page already holds.
- **`GET /api/v1/payments?limit=`** on payment-service: the newest payments, same state shape
  as `GET /{id}`.

One web app, not two: the customer view (wallets, pay, transfer) and the operator view
(live map, risk cases, settlement runs) share a login, a gateway and a component set; the
role in the token (`ledger-write` or only `account-read`) decides which buttons do anything.
Splitting it into micro-frontends buys independent deploys for a team that does not exist.

## Consequences

- One more JVM (gateway) and one nginx: two more pods on the kind cluster, NodePorts
  30088/30089 → host 8088/8089. A kind port mapping only applies at cluster creation;
  an existing cluster needs `kubectl port-forward` until it is recreated.
- The gateway adds a hop to every UI request. The load tests keep calling the services
  directly, so the perf table does not move; the gateway is not on the measured path.
- The event stream is only as complete as the moment the gateway started: no replay, no
  history beyond 200 records. The source of truth stays in each service's tables.
- Tokens live in the browser's memory (sessionStorage via oidc-client-ts). Fine for a
  local demo realm with 300 s tokens; option 2 is the upgrade if this ever faced the internet.
