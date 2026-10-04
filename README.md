# Seat Reservation Service

A small API that sells numbered seats for a show. When thousands of buyers hit "book" at the same moment, each seat
goes to exactly one buyer and everyone else gets a clean `409`. Spring Boot 4, Java 21, PostgreSQL.

How and why it works is in [WRITEUP.md](WRITEUP.md). Live service: https://seat-reservation-service-x22v.onrender.com
(free plan, so the first request after a quiet spell can take a minute).

**Contents:** [Run it with Docker](#run-it-with-docker) · [Metrics and logs access](#metrics-and-logs-access) ·
[Test it end to end](#test-it-end-to-end) · [API reference](#api-reference) · [The burst](#the-burst) ·
[Automated tests](#automated-tests) · [Deploy to Render](#deploy-to-render) · [Layout](#layout)

## Run it with Docker

You need Docker (Docker Desktop on Windows or Mac, with Linux containers) and a Bash shell (**Git Bash** or WSL on
Windows). Ports 8080 and 9090 must be free.

```bash
docker compose up --build --wait
docker compose ps
```

The first build takes a few minutes. This starts the service on `localhost:8080`, its Postgres, and Prometheus on
`localhost:9090`. Expect `db` and `app` listed as `healthy` and `prometheus` running.

| What | Where |
|---|---|
| Health | `GET /actuator/health/liveness` and `GET /actuator/health/readiness` (both check the database) |
| Metrics | `GET /actuator/prometheus`, or the Prometheus UI at http://localhost:9090 |
| Logs | `docker compose logs app`: one JSON line per event, each with a `requestId` |

### Windows

- Use Docker Desktop with the WSL 2 backend (the default).
- Run every shell command in this README from **Git Bash** or **WSL**.
- `.gitattributes` keeps line endings correct, whether you clone or download the ZIP.
- The full burst peaks at about 1.8 GB across the containers, so give Docker Desktop at least 3 GB. On a smaller
  machine use `USERS=500` (see [The burst](#the-burst)).

## Metrics and logs access

### Metrics

| Where | How |
|---|---|
| Local (Docker) | `curl localhost:8080/actuator/prometheus`, or the Prometheus UI at http://localhost:9090 (it scrapes the service every 5 seconds) |
| Live | `curl https://seat-reservation-service-x22v.onrender.com/actuator/prometheus` (open to anyone, no token) |
| Render dashboard | The service's **Metrics** tab, for CPU and memory (account owner only) |

```bash
curl -s localhost:8080/actuator/prometheus | grep -E '^(reservations_total|seats_available)'
```

What is exported:

- `reservations_total{outcome,reason}`: reserve requests that were created, or declined and why. A series appears on
  its first event.
- `seats_available`: seats free across all shows.
- `http_server_requests_seconds_*`: request count and duration by status and URI.
- `hikaricp_connections_*`: database pool usage (`active`, `pending`, `max`).
- JVM and process metrics.

Queries to paste into the Prometheus UI (http://localhost:9090/graph). Run them **while `bash scripts/burst.sh` is
running**: a rate needs the counter to change between two scrapes, so a very short run shows 0. A query with no
matching series returns nothing, which for the server-error query means there were none.

| Question | Query |
|---|---|
| Created vs declined, and why | `sum by (outcome, reason) (reservations_total)` |
| Declines per second | `sum(rate(reservations_total{outcome="declined"}[1m]))` |
| Reserve requests per second, by status | `sum by (status) (rate(http_server_requests_seconds_count{uri="/shows/{showId}/reserve"}[1m]))` |
| Average reserve time (server side) | `sum(rate(http_server_requests_seconds_sum{uri="/shows/{showId}/reserve"}[1m])) / sum(rate(http_server_requests_seconds_count{uri="/shows/{showId}/reserve"}[1m]))` |
| Server errors per second | `sum(rate(http_server_requests_seconds_count{status=~"5.."}[1m]))` |
| Requests waiting for a database connection | `hikaricp_connections_pending` |
| Seats left | `seats_available` |

During a burst, `hikaricp_connections_pending` rises into the hundreds: requests are queueing for one of the 10
database connections, which is normal and is what the 30-second connection wait absorbs.

### Logs

| Where | How |
|---|---|
| Local (Docker) | `docker compose logs -f app` to stream, or `docker compose logs app \| grep <requestId>` to follow one request |
| Live | Render dashboard, then the service, then the **Logs** tab; its search box accepts a `requestId`. Only people with access to the Render account can open it. Anyone else can use the local Docker logs, which have the same format. |

```bash
docker compose logs --tail 5 app
```

What is logged: one JSON line per business event (`seats reserved`, `reservation cancelled`, `hold expired`,
`show created`), plus warnings and errors such as `database unavailable`. Declined requests are counted in the
metrics, not logged. No request bodies, tokens or passwords are logged.

Every line carries a `requestId`. The service returns it in the `X-Request-Id` response header, and you can send your
own. [Step 11](#step-11-observability) of the walkthrough follows one request from the response to its log line. An
example line, trimmed:

```json
{"@timestamp":"2026-10-04T12:24:52.932Z","log":{"level":"INFO","logger":"com.seatreservation.reservation.ReservationService"},"message":"seats reserved. reservationId=2b0c2c5b-..., showId=f655b248-..., seatCount=2","requestId":"my-trace-1"}
```

## Test it end to end

Every step here is a command you can paste, with what you should see. It covers health, security, reserving, the
rules (all or nothing, idempotency, the 4-seat limit), cancel, expiry, observability, a database outage, and the
stampede. Start the stack first (see above).

Use **one terminal for the whole walkthrough**, because later steps reuse variables set in earlier ones. Ids and
timestamps in your output will differ from mine. Only the status codes and the `reason` values matter.

### Step 1: Set up helpers

Paste once. `api` prints the response and then its HTTP status; `idof` pulls the first `id` out of a JSON response.
Identity comes from a mock token, never from the request body: `Authorization: Bearer <role>:<userId>`.

```bash
api() { curl -s -w '\nHTTP %{http_code}\n' "$@"; }
idof() { grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4; }
JSON='Content-Type: application/json'
ADMIN='Authorization: Bearer admin:ops'
ALICE='Authorization: Bearer user:alice'
BOB='Authorization: Bearer user:bob'
```

### Step 2: Health

```bash
api localhost:8080/actuator/health/liveness
api localhost:8080/actuator/health/readiness
```

Expect `{"status":"UP"}` and `HTTP 200` for both. They need no token.

### Step 3: Security, who may do what

```bash
# No token.
api -X POST localhost:8080/shows
# A user trying to create a show.
api -X POST localhost:8080/shows -H "$ALICE" -H "$JSON" -d '{"name":"x","seats":["A1"],"price_paise":100}'
```

Expect `HTTP 401` with reason `UNAUTHENTICATED`, then `HTTP 403` with reason `FORBIDDEN`.

### Step 4: Create a show

```bash
SHOW=$(curl -s -X POST localhost:8080/shows -H "$ADMIN" -H "$JSON" \
  -d '{"name":"friday-night","seats":["A1","A2","A3","A4","A5","A6"],"price_paise":25000}' | idof)
echo "SHOW=$SHOW"
api localhost:8080/shows/$SHOW -H "$ALICE"
```

Expect a show id, then `HTTP 200` with `"total_seats":6,"available":6,"held":0,"confirmed":0` and six seats, all
`available`.

### Step 5: Reserve, and the all-or-nothing rule

```bash
# Alice reserves two seats.
RES1=$(curl -s -X POST localhost:8080/shows/$SHOW/reserve -H "$ALICE" -H "$JSON" \
  -d '{"seats":["A1","A2"],"idempotency_key":"alice-1"}' | idof)
echo "RES1=$RES1"

# Bob wants A2 (taken) and A3 (free).
api -X POST localhost:8080/shows/$SHOW/reserve -H "$BOB" -H "$JSON" \
  -d '{"seats":["A2","A3"],"idempotency_key":"bob-1"}'

# Was A3 left alone?
api localhost:8080/shows/$SHOW -H "$ALICE" | grep -o '"seat_label":"A3","status":"[a-z]*"'
```

Expect: Alice's reservation with `"status":"held"`, `"total_paise":50000` and an `expires_at` five minutes ahead.
Bob gets `HTTP 409`, reason `SEATS_UNAVAILABLE`, naming `A2`. `A3` is still `available`: a declined request changes
nothing.

### Step 6: Idempotency

```bash
# Alice's identical request again: the same reservation, not a second booking.
curl -s -X POST localhost:8080/shows/$SHOW/reserve -H "$ALICE" -H "$JSON" \
  -d '{"seats":["A1","A2"],"idempotency_key":"alice-1"}' | idof
echo "should equal RES1: $RES1"

# The same key with different seats.
api -X POST localhost:8080/shows/$SHOW/reserve -H "$ALICE" -H "$JSON" \
  -d '{"seats":["A5"],"idempotency_key":"alice-1"}'
```

Expect the first command to print the same id as `RES1`, and the second to give `HTTP 409`, reason
`IDEMPOTENCY_KEY_REUSED`.

### Step 7: The 4-seat limit

Alice already holds 2 seats (`A1`, `A2`).

```bash
RES2=$(curl -s -X POST localhost:8080/shows/$SHOW/reserve -H "$ALICE" -H "$JSON" \
  -d '{"seats":["A3"],"idempotency_key":"alice-2"}' | idof)
api -X POST localhost:8080/shows/$SHOW/reserve -H "$ALICE" -H "$JSON" \
  -d '{"seats":["A4"],"idempotency_key":"alice-3"}'
api -X POST localhost:8080/shows/$SHOW/reserve -H "$ALICE" -H "$JSON" \
  -d '{"seats":["A5"],"idempotency_key":"alice-4"}'
```

Expect `A3` and `A4` to succeed (`HTTP 201`, bringing Alice to 4 seats), then `HTTP 409` with reason
`SEAT_LIMIT_EXCEEDED` and the message `4 already held`.

### Step 8: Input validation

```bash
# A seat that does not exist.
api -X POST localhost:8080/shows/$SHOW/reserve -H "$BOB" -H "$JSON" \
  -d '{"seats":["Z9"],"idempotency_key":"bob-2"}'
# The same seat twice.
api -X POST localhost:8080/shows/$SHOW/reserve -H "$BOB" -H "$JSON" \
  -d '{"seats":["A5","A5"],"idempotency_key":"bob-3"}'
# A blank idempotency key.
api -X POST localhost:8080/shows/$SHOW/reserve -H "$BOB" -H "$JSON" \
  -d '{"seats":["A5"],"idempotency_key":""}'
```

Expect three `HTTP 400` responses with reasons `UNKNOWN_SEATS`, `DUPLICATE_SEATS` and `VALIDATION_FAILED`. The last
one lists `idempotency_key` in its `errors`.

### Step 9: Cancel

```bash
# Bob cannot cancel Alice's reservation.
api -X POST localhost:8080/reservations/$RES1/cancel -H "$BOB"
# Alice can, and doing it twice is safe.
api -X POST localhost:8080/reservations/$RES1/cancel -H "$ALICE"
api -X POST localhost:8080/reservations/$RES1/cancel -H "$ALICE"
# The freed seat can now be taken by someone else.
api -X POST localhost:8080/shows/$SHOW/reserve -H "$BOB" -H "$JSON" \
  -d '{"seats":["A1"],"idempotency_key":"bob-4"}'
# Totals add up.
api localhost:8080/shows/$SHOW -H "$ALICE" | grep -o '"total_seats":[0-9]*,"available":[0-9]*,"held":[0-9]*,"confirmed":[0-9]*'
```

Expect: `HTTP 403` (`NOT_RESERVATION_OWNER`), then `HTTP 200` twice with `"status":"cancelled"`, then `HTTP 201` for
Bob. The totals are `"total_seats":6,"available":3,"held":3,"confirmed":0`: Bob's `A1` plus Alice's `A3` and `A4`
are held, and `available + held + confirmed` equals the total.

### Step 10: Holds expire

A hold lasts 5 minutes. To avoid waiting, move every hold's expiry into the past; the background job notices within
about a second.

```bash
docker compose exec -T db psql -U seats -d seats \
  -c "UPDATE reservations SET expires_at = now() - interval '1 minute' WHERE status = 'HELD'"
sleep 3
api localhost:8080/shows/$SHOW -H "$ALICE" | grep -o '"total_seats":[0-9]*,"available":[0-9]*,"held":[0-9]*,"confirmed":[0-9]*'
# Replaying an expired reservation's key, and cancelling it.
api -X POST localhost:8080/shows/$SHOW/reserve -H "$ALICE" -H "$JSON" \
  -d '{"seats":["A3"],"idempotency_key":"alice-2"}'
api -X POST localhost:8080/reservations/$RES2/cancel -H "$ALICE"
```

Expect `UPDATE 3`, then totals of `"available":6,"held":0`: every seat is free again. Replaying the old key gives
`HTTP 409`, reason `RESERVATION_NOT_ACTIVE` (use a new key), and cancelling gives `HTTP 409`, reason
`RESERVATION_EXPIRED`.

### Step 11: Observability

Every response carries a request id, and you can supply your own. It also appears in the logs.

```bash
curl -s -i -X POST localhost:8080/shows/$SHOW/reserve -H "$ALICE" -H "$JSON" -H 'X-Request-Id: my-trace-1' \
  -d '{"seats":["A6"],"idempotency_key":"alice-5"}' | grep -iE '^(HTTP|x-request-id)'
docker compose logs app | grep my-trace-1
curl -s localhost:8080/actuator/prometheus | grep -E '^(reservations_total|seats_available)'
```

Expect `HTTP/1.1 201` and `X-Request-Id: my-trace-1`. The log line is JSON, with `"requestId":"my-trace-1"` next to
the reservation and show ids. The metrics list how many reservations were created and how many were declined for
each reason (the counts from the steps above), and `seats_available`.

Open http://localhost:9090/targets in a browser: the `seat-reservation-service` target should be **UP**. On the Graph
tab, try the query `reservations_total`.

### Step 12: A database outage

The service picks consistency: if it cannot reach the database it refuses requests rather than guess.

```bash
docker compose stop db
sleep 2
api localhost:8080/actuator/health/readiness
api localhost:8080/actuator/health/liveness
curl -s -i -X POST localhost:8080/shows/$SHOW/reserve -H "$ALICE" -H "$JSON" \
  -d '{"seats":["A1"],"idempotency_key":"outage-1"}' | grep -iE '^(HTTP|retry-after)|reason'
```

Expect both health checks `{"status":"DOWN"}` with `HTTP 503`, and the reserve to be `HTTP/1.1 503` with
`Retry-After: 1` and reason `DATABASE_UNAVAILABLE`. Now bring the database back:

```bash
docker compose start db
# Wait (up to a minute) until the service reports ready again; Postgres needs a few seconds to restart.
for i in $(seq 1 60); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' localhost:8080/actuator/health/readiness)" = "200" ] && break
  sleep 1
done
api localhost:8080/actuator/health/readiness
api -X POST localhost:8080/shows/$SHOW/reserve -H "$ALICE" -H "$JSON" \
  -d '{"seats":["A1"],"idempotency_key":"outage-1"}'
```

Expect the service to recover on its own, usually within a few seconds: readiness `UP`, and the same reserve request
now succeeds with `HTTP 201` (the failed attempt during the outage left nothing behind).

### Step 13: The stampede

```bash
bash scripts/burst.sh
```

This creates a fresh 100-seat show and sends 20,000 reserve requests from 2,000 users, mostly for the same few
hot seats. It takes about 20 to 30 seconds and ends with `RESULT: PASS`. The key lines to look for:

- `5xx 0` and `network errors 0`;
- about 100 seats claimed by the `201`s and **the same number held** in the final state (no seat sold twice);
- `available + held + confirmed == total` and `no user holds more than 4 seats`, both `PASS`.

Then recheck the metrics from Step 11: the counters now include the burst's thousands of declines. More about the
burst is in [The burst](#the-burst).

### Step 14: Clean up

```bash
docker compose down -v
```

## API reference

JSON field names are `snake_case`. Errors use the standard problem shape (`status`, `title`, `detail`) plus a
`reason` code; validation errors add an `errors` list. Admins create shows; users reserve and cancel.

| Endpoint | Who | Success | Failures |
|---|---|---|---|
| `POST /shows` `{name, seats[], price_paise}` | admin | `201` show with every seat `available` | `400` bad input or duplicate seats |
| `POST /shows/{id}/reserve` `{seats[], idempotency_key}` | user | `201` reservation, held for 5 minutes | `409` `SEATS_UNAVAILABLE`, `SEAT_LIMIT_EXCEEDED`, `IDEMPOTENCY_KEY_REUSED`, `RESERVATION_NOT_ACTIVE`; `400` `UNKNOWN_SEATS`, `DUPLICATE_SEATS`; `404` `SHOW_NOT_FOUND` |
| `POST /reservations/{id}/cancel` | user, owner only | `200` reservation, seats free again | `403` `NOT_RESERVATION_OWNER`; `404` `RESERVATION_NOT_FOUND`; `409` `RESERVATION_EXPIRED` |
| `GET /shows/{id}` | any role | `200` per-seat status and totals (`available + held + confirmed == total_seats`) | `404` |

Rules worth knowing:

- **All or nothing.** A request for several seats either gets all of them or none; a declined request changes nothing.
- **At most 4 seats per user per show**, however many requests arrive at once.
- **A hold lasts 5 minutes.** After that the seats become available again.
- **Idempotency.** The same key with the same seats returns the original reservation. The same key with different
  seats is a `409`. Prices are integer paise.
- Any request without a valid token gets `401`; the wrong role gets `403`.
- If the database is unreachable, requests get `503` with `Retry-After: 1` and the health checks report `DOWN`.

There is no confirm or payment step, so seats only ever move between `available` and `held`.

## The burst

`scripts/burst.sh` recreates the on-sale stampede: it creates a fresh 100-seat show, then 2,000 users send 10
requests each (20,000 in all), mostly for the same few hot seats. It prints the count of `201`s, `409`s and `5xx`s,
the latency, and the final reconciliation, and exits non-zero if any check fails.

```bash
bash scripts/burst.sh                          # against http://localhost:8080 (the compose stack)
bash scripts/burst.sh https://your-service.onrender.com   # against another URL
USERS=500 bash scripts/burst.sh                # a lighter run
```

It runs [k6](https://k6.io) from Docker, so nothing else needs installing. Settings: `SEATS` (default 100),
`USERS` (2000), `REQUESTS_PER_USER` (10). A run passes only if all of these hold:

- no `5xx` responses and no network errors;
- every request was answered;
- `available + held + confirmed == total`;
- no double-sell: the seats claimed by all the `201`s equal the seats the show shows as held;
- no user ever holds more than 4 seats.

2,000 concurrent users is what one machine can generate, so it is not 20,000 simultaneous connections.

## Automated tests

```bash
./gradlew test        # on Windows, run it from Git Bash too
```

About 150 tests, run against a real PostgreSQL started by Testcontainers, so Docker must be running. Gradle downloads
JDK 21 itself if it is missing, and the first run downloads dependencies, so allow a few minutes. Expect
`BUILD SUCCESSFUL`. They cover the concurrency rules (hundreds of users on one seat, opposite-order requests, one
user sending ten requests at once, the same key sent fifty times at once), cancel and expiry, the health checks with
the database actually stopped, and the metrics.

## Deploy to Render

The live service above runs on [Render](https://render.com)'s free plan. It is driven by `render.yaml` at the root of
this repo (a Render "Blueprint"): you click through a few screens and Render builds the `Dockerfile` and connects the
service to its database. Button names may differ slightly from what you see.

**You need:** a Render account (https://dashboard.render.com; signing up with GitHub is easiest), this repo on GitHub
and public, and possibly a payment method, which Render can ask for even on the free plan (the plan itself costs
nothing).

1. In the Render dashboard click **New +**, then **Blueprint**.
2. Connect your GitHub account if asked, allow access to `seat-reservation-service`, and select the repo and the
   `main` branch.
3. Render reads `render.yaml` and lists what it will create: a web service `seat-reservation-service` and a database
   `seats-db`, both on the **Free** plan. Check that you see exactly those two.
4. Click **Apply**. Render creates the database first, then builds the service. The first build compiles the app
   inside Docker, so expect about 5 to 10 minutes.
5. Watch the service's **Logs**. When you see `Started SeatReservationApplication`, the status turns **Live**. The
   first start is slow on the free plan (often a minute or two).
6. Copy the URL from the top of the service page, like `https://seat-reservation-service-xxxx.onrender.com`.

**Check it.** Replace the URL with yours:

```bash
URL=https://seat-reservation-service-xxxx.onrender.com
curl -s -w '\nHTTP %{http_code}\n' $URL/actuator/health/readiness
```

Expect `{"status":"UP"}`. The first request after a quiet spell can take up to a minute while the instance wakes up,
so retry if it is slow. Then run [Steps 2 to 9](#test-it-end-to-end) against your URL, using it instead of
`localhost:8080`. Skip the steps that use `docker compose`: Step 10 (expiry), the log line in Step 11, and Step 12
(outage), because they only apply to the local stack.

**A small burst.** The free instance has a small slice of one CPU, so do not run the full 2,000-user burst against it.
Use this one:

```bash
USERS=50 REQUESTS_PER_USER=5 SEATS=20 bash scripts/burst.sh $URL
```

What I measured on the free instance: it handles roughly 10 requests per second. That run (250 requests) passed every
check with no `5xx`; the slowest request took about 18 seconds. A bigger run (`USERS=150`, 1,500 requests) passed all
the correctness checks every time but sometimes ended with a few `503` responses (34, 2 and 0 in three runs). Those
`503`s come from the service itself, not from Render: when requests queue for a database connection for longer than
30 seconds, the service answers `503 DATABASE_UNAVAILABLE` with `Retry-After: 1` instead of waiting forever. On a
faster machine the same burst at full size gives no `5xx`. If a run on the free instance fails only the `5xx` check,
the instance ran out of capacity; seats were not mishandled. The checks that prove the seat logic are no double-sell,
no user over 4 seats, and `available + held + confirmed == total`.

**Where to look.** Logs: the service page, **Logs** tab (every line is JSON with a `requestId`). Metrics:
`$URL/actuator/prometheus` (open without a token) and Render's **Metrics** tab for CPU and memory. Database: the
`seats-db` page; external connections are switched off in `render.yaml`, so to use `psql` from your machine add your IP
under the database's access settings.

**On the free plan:** the web service goes to sleep after about 15 minutes without traffic and the next request wakes
it slowly; the free database expires 30 days after it is created; each push to `main` redeploys automatically.

**Troubleshooting**

| What you see | Likely cause and fix |
|---|---|
| The Blueprint screen reports an error in `render.yaml` | Copy the exact message; it is usually a single key to rename. |
| The first deploy fails with a database connection error in the logs | The service started before the database was ready. In the service page choose **Manual Deploy**, then **Deploy latest commit**. |
| Build fails during the Docker step | Open the build logs; the failing Gradle line is shown. |
| Deploy never becomes Live, logs end around startup | The free instance is slow to start. Wait a few minutes; the health check path is `/actuator/health/readiness`. |
| The service restarts again and again | Look in the logs for `OutOfMemoryError`. The JVM flags in `render.yaml` are sized for 512 MB. |
| `503` for a minute after a quiet spell | The instance is waking up. Retry. |

**Clean up.** In the Render dashboard delete the web service and the `seats-db` database; that stops all usage.

## Layout

```
src/main/java/com/seatreservation/
  show/          create show, show state
  reservation/   reserve, cancel, expiry sweeper, seat locking
  security/      mock-token authentication
  error/         one exception handler, problem responses
  health/        health checks and the seats_available gauge
  logging/       request id
scripts/         burst.sh, burst.js
prometheus/      scrape config for docker compose
render.yaml      Render deployment
WRITEUP.md       architecture write-up
```
