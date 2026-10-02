# Seat Reservation Service — Engineering Write-Up

## 1. Goal

The goal of this service is not only to expose reservation APIs, but to preserve correctness when many clients compete for limited inventory.

The most important invariants are:

1. A seat must never be sold twice.
2. A user must not exceed the per-show booking limit under concurrency.
3. Retrying the same request must not create a second reservation.
4. Multi-seat reservations must not partially succeed.
5. Cancellation must not release a seat owned by another reservation.
6. Expected business conflicts must not become HTTP 5xx failures.

PostgreSQL is the authoritative source of truth for these decisions.

---

# 2. Atomic Seat Reservation

The core reservation operation runs inside a Spring `@Transactional` method.

For requested seats, the service performs a pessimistic write-locking query equivalent to:

```sql
SELECT *
FROM seats
WHERE show_id = ?
  AND seat_code IN (...)
ORDER BY seat_code
FOR UPDATE;
```

The application does not perform an unlocked read followed later by an update.

That would allow:

```text
Transaction A reads AVAILABLE
Transaction B reads AVAILABLE

Transaction A confirms
Transaction B confirms

=> double sale
```

Instead, the row lock serializes competing transactions.

```text
Transaction A
    |
    | lock seat A1
    |
    | verify AVAILABLE
    | mark CONFIRMED
    | commit
    v

Transaction B
    |
    | waits for A1
    |
    | acquires lock after A commits
    | observes CONFIRMED
    | returns conflict
```

Therefore the first transaction can confirm the seat and later contenders return HTTP 409.

Correctness is enforced by the database rather than by a JVM-local lock.

That is important because JVM locks would not coordinate multiple application instances.

---

# 3. Multi-Seat Atomicity

A reservation may contain multiple seats.

The semantics are intentionally:

```text
ALL seats confirmed
OR
NO seats confirmed
```

All requested rows are locked first.

The service then validates that every requested seat exists and every seat is `AVAILABLE`.

Only after all checks pass are seats changed to `CONFIRMED`.

Because the operation runs in one database transaction, any exception rolls back the complete reservation.

This avoids states such as:

```text
requested: A1, A2, A3

A1 = confirmed
A2 = confirmed
A3 = unavailable

=> partial booking
```

---

# 4. Deadlock Reduction

Multi-row locking introduces potential deadlock risk if transactions acquire the same rows in different orders.

For example:

```text
Transaction A: lock A1 -> wait for A2
Transaction B: lock A2 -> wait for A1
```

To reduce this risk, requested seat rows are acquired in deterministic `seat_code` order.

Reservation and cancellation also follow the same broader lock order where applicable:

```text
user/show booking counter
        ↓
seat rows
```

Consistent lock ordering significantly reduces cyclic lock dependencies.

---

# 5. Per-User Booking Limit

The service allows a maximum of four confirmed seats per user per show.

A simple query such as:

```text
count current reservations
if count < 4
    allow reservation
```

would not be concurrency safe.

Two simultaneous requests could both observe the old count and both proceed.

Instead, the application maintains a database-backed `(user, show)` booking counter.

Before checking the limit, the counter row is locked using a pessimistic write lock.

Therefore concurrent reservation attempts for the same user and show serialize around the counter.

Conceptually:

```text
Request A
    |
    | lock user/show counter
    | current = 3
    | request = 1
    | update to 4
    | commit

Request B
    |
    | waits
    | then reads current = 4
    | rejects request
```

The correctness suite also sends concurrent requests for different seats using the same user and verifies that only four seats become confirmed.

---

# 6. Idempotency

Network clients may retry requests because they do not know whether a previous attempt completed.

Without idempotency:

```text
client sends request
server succeeds
response is lost
client retries
server creates another reservation
```

Each reservation therefore requires an idempotency key.

The idempotency record contains:

```text
user ID
idempotency key
request hash
reservation
```

A unique constraint on the user/key pair prevents duplicate idempotency records.

The service creates the row if necessary and obtains a write lock on it.

A deterministic request hash is generated from the show and requested seats.

### Same key + same request

If the idempotency record already references a reservation, that original reservation is returned.

No second reservation is created.

### Same key + different request

If the stored request hash differs from the current request hash, the request returns HTTP 409.

This prevents accidental reuse of an idempotency key for another logical operation.

---

# 7. Cancellation and Rebooking

Cancellation must be safe even when a seat is later rebooked.

Each seat tracks its current reservation.

During cancellation, a seat is released only when:

```text
seat.currentReservation == reservation being cancelled
```

Therefore an old cancellation cannot blindly mark a seat available after ownership has moved to another reservation.

Cancellation itself is idempotent.

A repeated cancellation of an already-cancelled reservation returns the existing cancelled state rather than releasing inventory again.

The user's booking counter is reduced only by seats actually released by that cancellation.

---

# 8. Authentication and Spoof Resistance

The reservation body does not contain a trusted `user_id`.

Instead, user identity is derived from the bearer token.

The current implementation uses an HMAC-SHA256 signed token:

```text
base64url(userId).signature
```

The signature is verified using a server-side secret.

Changing the encoded user without producing a valid signature causes authentication to fail.

This prevents a client from simply submitting another user's identifier in the request body.

A `/dev/token` endpoint exists only under the Spring `dev` profile for local development.

A production deployment should run without the `dev` profile and should receive `AUTH_SECRET` through secure environment configuration.

---

# 9. Consistency vs Availability

For reservation decisions, this service intentionally prioritizes consistency.

During a network partition or database outage, the service should not independently confirm a seat without reaching the authoritative PostgreSQL state.

Doing so could allow two partitions to sell the same seat.

For this domain:

```text
temporarily unable to sell a seat
```

is preferable to:

```text
selling the same seat twice
```

Therefore reservation writes require database availability.

This is a deliberate consistency-over-write-availability trade-off for scarce inventory.

Read scaling and caching could be added separately, but cached state must never independently authorize a reservation.

---

# 10. Immediate Confirmation vs Holds

The current implementation uses immediate confirmation.

Seat states exposed by the API are:

```text
available
held
confirmed
```

but the current reservation flow does not create temporary holds, so:

```text
held = 0
```

This was chosen to keep the core correctness path small and explicit within the assignment scope.

A production extension could introduce expiring holds with:

```text
hold owner
expires_at
payment state
```

An expiry worker would then release expired holds using conditional ownership checks.

The same principle used for cancellation would apply: an expiry operation must never release a seat that no longer belongs to that hold.

---

# 11. Observability

The application exposes Spring Boot Actuator endpoints.

## Liveness

```text
/actuator/health/liveness
```

Used to determine whether the application process is alive.

## Readiness

```text
/actuator/health/readiness
```

Readiness includes database connectivity.

An application without access to its authoritative database should not be considered ready to accept reservation traffic.

## Prometheus

Metrics are available at:

```text
/actuator/prometheus
```

Important application metrics include:

```text
reservations.confirmed
reservations.declined
reservations.idempotent.replays
seats.available
```

Declines are tagged by reason, including seat contention and per-user limit violations.

The confirmed counter is scheduled only after the database transaction successfully commits.

This prevents a transaction that later rolls back from incorrectly increasing the successful-reservation metric.

---

# 12. Correlation IDs and Logs

Every request has an `X-Request-ID`.

If one is supplied by the caller, it is propagated.

Otherwise the service generates a UUID.

The request ID is:

1. placed in the logging MDC,
2. included in application log lines,
3. returned in the response header.

Reservation logs contain contextual fields such as:

```text
reservation_id
show_id
user_id
seat
reason
request_id
```

This makes it possible to trace an individual request through contention and failure scenarios.

---

# 13. Correctness / Burst Testing

The repository includes:

```text
scripts/burst-test.py
```

The test uses the Python standard library and can be executed inside a temporary Docker Python container.

It creates fresh shows automatically, preventing previous test data from affecting results.

The suite verifies:

### Hot-seat contention

Many independent users attempt to reserve the exact same seat.

Expected:

```text
exactly one 201
all other completed requests 409
zero 5xx
inventory invariant preserved
```

### Idempotency

The suite verifies:

```text
same key + same request
=> same reservation

same key + different request
=> 409
```

### Per-user concurrency

Multiple concurrent requests from the same user target different seats.

Expected:

```text
4 successful reservations
remaining requests rejected
confirmed seats = 4
```

### Inventory invariant

After contention:

```text
available + held + confirmed == totalSeats
```

---

# 14. Observed Load-Test Results

A local Docker run using:

```text
5,000 total requests
100 concurrent load-generator workers
one hot seat
```

produced:

```text
201 Created       : 1
409 Conflict      : 4,999
5xx responses     : 0
Client errors     : 0
Inventory invariant: PASS
```

This demonstrates the expected safety behavior under sustained contention in the local test environment.

An additional run used:

```text
20,000 total requests
200 load-generator workers
```

The backend still maintained:

```text
exactly one successful reservation
zero 5xx responses
correct final inventory invariant
```

but the temporary client-side load generator recorded 301 client errors.

Because of those client-side failures, that run is not represented as a clean 20,000-request pass.

The lower-concurrency run completed without client errors.

The worker count is a property of the load generator and is not an application-side concurrency limit.

---

# 15. Failure Handling

Expected domain failures are mapped to appropriate HTTP responses instead of becoming internal server errors.

Examples:

```text
seat already taken       -> 409
booking limit exceeded   -> 409
idempotency conflict     -> 409
invalid request          -> 4xx
invalid authentication   -> 401
wrong reservation owner  -> 403
```

Unexpected failures remain observable as server errors rather than being silently converted into business conflicts.

---

# 16. Metrics and Transaction Boundaries

One subtle observability issue is that application code runs before the surrounding transaction has necessarily committed.

Incrementing the successful-reservation metric immediately before returning from the transactional method could produce:

```text
metric increment
database commit fails
```

That would make metrics disagree with authoritative database state.

The implementation therefore registers an `afterCommit` transaction synchronization and increments `reservations.confirmed` only after successful commit.

---

# 17. Deployment Model

The service is containerized using a multi-stage Docker build.

The local Docker Compose environment contains:

```text
Spring Boot application
PostgreSQL 17
```

The application receives database configuration and the authentication secret through environment variables.

For public deployment:

- use a managed or persistent PostgreSQL instance,
- configure a strong `AUTH_SECRET`,
- run without the `dev` Spring profile,
- expose the application port through HTTPS,
- retain Actuator readiness and Prometheus endpoints according to deployment requirements.

---

# 18. AI Usage

AI assistance was used during development as permitted by the assignment.

It was used for:

- discussing architecture and concurrency approaches,
- explaining PostgreSQL row-level locking,
- reviewing transaction and lock ordering,
- generating and reviewing boilerplate,
- helping construct Docker configuration,
- creating the correctness/load-test script,
- debugging development environment issues,
- reviewing documentation and edge cases.

The implementation was built incrementally and tested locally throughout development.

The important correctness decisions remain explicit in the code and this document so that they can be explained, reviewed, and modified without relying on generated output.

---

# 19. Current Limitations

The current implementation intentionally keeps the design focused on assignment correctness.

Known limitations / simplifications include:

1. No temporary seat-hold/payment workflow; reservations confirm immediately.
2. The per-user maximum is currently configured in application code as four seats.
3. Schema evolution currently relies on Hibernate rather than versioned production migrations.
4. The development token generator is intentionally only suitable for local development.
5. The test load generator is lightweight Python tooling rather than a dedicated distributed load-testing platform.
6. PostgreSQL is a required dependency for reservation writes, by design.
7. The service does not currently introduce Redis or a cache in the reservation decision path.

---

# 20. Production Improvements

With additional time, the next improvements would be:

### Database migrations

Introduce Flyway or Liquibase and replace automatic schema updates with versioned migrations.

### Holds and payment lifecycle

Add:

```text
AVAILABLE -> HELD -> CONFIRMED
```

with expiration and safe conditional release.

### Authentication

Integrate a standard identity provider using OAuth2/OIDC or JWT validation rather than the assignment-focused HMAC token mechanism.

### Distributed load testing

Use a dedicated tool such as k6, Gatling, or Locust from separate load-generator hosts.

### Scalability

Run multiple stateless application instances behind a load balancer.

The database locking mechanism would continue to provide cross-instance reservation correctness.

### Observability

Add dashboards and alerts for:

```text
reservation success rate
seat-taken conflict rate
booking-limit rejection rate
database latency
connection-pool saturation
HTTP 5xx rate
p95/p99 reservation latency
```

### Schema constraints

Add/version additional database constraints and migration-managed indexes as the production data model evolves.

---

# 21. Summary

The central design principle is:

> The database makes the reservation decision.

The application does not trust an earlier unlocked read or an in-memory lock.

Reservation correctness is achieved through:

```text
database transactions
+ pessimistic row locking
+ deterministic lock ordering
+ user/show counter locking
+ idempotency coordination
+ ownership-aware cancellation
```

This keeps the key safety properties valid even when many requests compete concurrently.