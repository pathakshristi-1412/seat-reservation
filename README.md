# Seat Reservation Service
A concurrency-safe seat reservation service built with Java, Spring Boot, PostgreSQL, Docker, and Prometheus metrics.
The service is designed for high-contention event sales where many users may attempt to reserve the same seat concurrently. PostgreSQL is used as the source of truth and row-level locking is used to prevent double-selling.
## Live Deployment
The service is publicly deployed on Railway.
**Base URL**
```text
https://seat-reservation-production-7d7b.up.railway.app
```
**Health**
```text
GET https://seat-reservation-production-7d7b.up.railway.app/actuator/health
```
**Readiness**
```text
GET https://seat-reservation-production-7d7b.up.railway.app/actuator/health/readiness
```
The production deployment uses PostgreSQL on Railway and runs with the `prod` Spring profile.
### Production Authentication
Reservation and cancellation endpoints require an HMAC-signed bearer token. The signing secret is not stored in this repository.
The following pre-generated demo tokens can be used to test the deployed service:
```text
demo-user-a: ZGVtby11c2VyLWE.KVg7aMLw3FwXv9Capvf2VhTiMPERJzeoaU-3RhRUUT8
demo-user-b: ZGVtby11c2VyLWI.HG5WnRwdRLIcAUU1ojKQIMV2E6Lq6yqqTjLkROS2JCQ
demo-user-c: ZGVtby11c2VyLWM.y3aNp-UYm3mAJ2u1pEKNVapbiPGfNrjoZ_xKrvlnX6c
demo-user-d: ZGVtby11c2VyLWQ.DjZcC6rijV8fhB5NGZ7B-PESdXSud7-4KkZHl2ojbAg
```
Example reservation using `demo-user-a`:
```bash
curl -X POST "https://seat-reservation-production-7d7b.up.railway.app/shows/1/reserve" \
  -H "Authorization: Bearer ZGVtby11c2VyLWE.KVg7aMLw3FwXv9Capvf2VhTiMPERJzeoaU-3RhRUUT8" \
  -H "Idempotency-Key: evaluator-test-001" \
  -H "Content-Type: application/json" \
  -d '{"seats":["A2"]}'
```
User identity is derived from the signed token and is not accepted from the reservation request body.
---
## Tech Stack
- Java 17
- Spring Boot 4.1.1
- Spring Data JPA / Hibernate
- PostgreSQL 17
- Docker / Docker Compose
- Micrometer + Prometheus
- Maven
## Core Guarantees
The service provides the following guarantees:
- A seat cannot be sold to two users.
- Reservation decisions are made atomically inside database transactions.
- Multi-seat reservations are all-or-nothing.
- Maximum 4 confirmed seats per user per show.
- Retried requests are idempotent.
- Reusing an idempotency key with a different request returns HTTP 409.
- User identity is derived from a signed bearer token rather than request body data.
- Only the reservation owner can cancel a reservation.
- Cancelled seats can safely be booked again.
- Domain conflicts return 409 rather than 5xx errors.
- Show inventory maintains:
```text
available + held + confirmed = totalSeats
```
The current implementation performs immediate confirmation, so `held` is currently always `0`.
---
## Architecture
```text
Client
   |
   | HTTP
   v
Spring Boot API
   |
   | JPA / Hibernate
   v
PostgreSQL
   |
   +-- Shows
   +-- Seats
   +-- Reservations
   +-- Reservation Seats
   +-- User/Show Booking Counters
   +-- Idempotency Records
Spring Boot
   |
   +-- Actuator Health
   +-- Prometheus Metrics
   +-- Correlation / Request IDs
```
PostgreSQL is the authoritative source for reservation state.
Correctness does not depend on an in-memory lock, which means multiple application instances can coordinate through the database.
---
# Running Locally
## Prerequisites
Only Docker Desktop and Docker Compose are required for the containerized setup.
Start the application:
```bash
docker compose up --build
```
The application becomes available at:
```text
http://localhost:8080
```
Check health:
```text
GET /actuator/health
```
Readiness including database connectivity:
```text
GET /actuator/health/readiness
```
Prometheus metrics:
```text
GET /actuator/prometheus
```
Stop the application:
```bash
docker compose down
```
To also remove the local PostgreSQL volume:
```bash
docker compose down -v
```
---
# Authentication
Reservation and cancellation APIs derive user identity from an HMAC-SHA256 signed bearer token.
The user ID is not accepted from the reservation request body.
Token format:
```text
base64url(userId).HMAC_SHA256_SIGNATURE
```
The signing secret is configured using:
```text
AUTH_SECRET
```
The Docker Compose configuration contains a development-only secret.
## Development Token Endpoint
When the `dev` Spring profile is enabled, a token can be generated using:
```text
GET /dev/token?userId=user-a
```
Example:
```bash
curl "http://localhost:8080/dev/token?userId=user-a"
```
The returned token can then be supplied as:
```text
Authorization: Bearer <token>
```
The `/dev/token` endpoint is profile-restricted and must not be enabled in production. Production deployments should use a securely configured `AUTH_SECRET` and should not run with the `dev` profile.
---
# API
## Create Show
```text
POST /shows
```
Example request:
```json
{
  "name": "friday-night",
  "seats": ["A1", "A2", "A3", "A4"],
  "price_paise": 25000
}
```
Money is stored and returned as integer paise.
Example response:
```json
{
  "id": 1,
  "name": "friday-night",
  "totalSeats": 4,
  "available": 4,
  "held": 0,
  "confirmed": 0,
  "seats": [
    {
      "seat": "A1",
      "status": "available"
    }
  ]
}
```
---
## Get Show State
```text
GET /shows/{showId}
```
Returns seat-level state and aggregate counts.
The following invariant should always hold:
```text
available + held + confirmed = totalSeats
```
---
## Reserve Seats
```text
POST /shows/{showId}/reserve
```
Headers:
```text
Authorization: Bearer <signed-token>
Idempotency-Key: <unique-key>
Content-Type: application/json
```
Request:
```json
{
  "seats": ["A1"]
}
```
Successful response:
```json
{
  "reservation_id": 1,
  "show_id": 1,
  "user_id": "user-a",
  "seats": ["A1"],
  "amount_paise": 25000,
  "status": "confirmed"
}
```
A successful reservation returns HTTP `201`.
If another transaction has already confirmed the seat, the request returns HTTP `409`.
### Multi-seat semantics
Multi-seat reservations are all-or-nothing.
For example:
```json
{
  "seats": ["A1", "A2", "A3"]
}
```
either confirms all three seats or confirms none of them.
---
## Cancel Reservation
```text
POST /reservations/{reservationId}/cancel
```
Header:
```text
Authorization: Bearer <signed-token>
```
Only the reservation owner can cancel it.
Cancellation is idempotent. Repeating cancellation does not incorrectly release a seat that has subsequently been booked by another reservation.
---
# Concurrency Strategy
Reservation operations execute inside a database transaction.
The important lock order is:
```text
Idempotency record
       ↓
User/show booking counter
       ↓
Requested seat rows
```
Requested seats are selected using PostgreSQL pessimistic write locking.
Conceptually:
```sql
SELECT ...
FROM seats
WHERE ...
ORDER BY seat_code
FOR UPDATE;
```
Consider two users trying to reserve `A1` concurrently.
```text
User A                    User B
  |                         |
  | lock A1                 |
  |------------------------>|
  |                         | waits
  |
  | A1 = AVAILABLE
  | set CONFIRMED
  | COMMIT
  |
  | release lock            |
                            |
                            | acquire A1 lock
                            | A1 = CONFIRMED
                            | return 409
```
Therefore only one transaction can successfully transition the seat from `AVAILABLE` to `CONFIRMED`.
Seat rows are locked in deterministic seat-code order for multi-seat reservations to reduce deadlock risk.
---
# Per-User Limit
Each `(user, show)` has a database-backed booking counter.
The row is locked before checking the user's current confirmed-seat count.
The current maximum is:
```text
4 seats per user per show
```
This prevents concurrent requests from independently observing the same old count and exceeding the limit.
---
# Idempotency
Each reservation request requires an idempotency key.
The service stores:
```text
user
idempotency key
request fingerprint
reservation
```
The request fingerprint is deterministic and includes the show and requested seats.
Behavior:
```text
same user + same key + same request
    -> original reservation returned
same user + same key + different request
    -> HTTP 409
```
Concurrent requests for the same idempotency key coordinate through a database row lock.
---
# Cancellation Safety
Cancellation locks the reservation, the user's show counter, and relevant seat rows.
A seat is released only if its `currentReservation` still references the reservation being cancelled.
This prevents an old/stale cancellation from releasing a seat that now belongs to a newer reservation.
---
# Observability
## Health
```text
/actuator/health
/actuator/health/liveness
/actuator/health/readiness
```
Readiness includes database health.
## Prometheus
```text
/actuator/prometheus
```
Application metrics include:
```text
reservations.confirmed
reservations.declined
reservations.idempotent.replays
seats.available
```
Reservation declines are tagged with reasons such as:
```text
seat_taken
per_user_limit
```
The confirmed-reservation metric is incremented only after the database transaction commits successfully.
## Request Correlation
Requests support:
```text
X-Request-ID
```
If the client does not provide one, the service generates a UUID.
The request ID is included in application logs and returned in the response header.
---
# Correctness Test Suite
The repository contains:
```text
scripts/burst-test.py
```
The script uses only the Python standard library.
It automatically creates isolated shows and tests:
1. Hot-seat contention.
2. Idempotent retry.
3. Same idempotency key with a different request.
4. Concurrent per-user booking limit.
5. Inventory invariant.
6. Absence of server-side 5xx errors during domain contention.
The script generates signed authentication tokens directly using the configured development secret, so it does not depend on the development token endpoint.
## Run with Docker
No local Python installation is required.
With the application running through Docker Compose:
```powershell
docker run --rm `
  --network seat-reservation_default `
  -e BASE_URL=http://app:8080 `
  -e TOTAL_REQUESTS=5000 `
  -e CONCURRENCY=100 `
  -e REQUEST_TIMEOUT=120 `
  -e AUTH_SECRET=local-docker-development-secret `
  -v "${PWD}/scripts:/scripts" `
  python:3.12-slim `
  python /scripts/burst-test.py
```
A successful run ends with:
```text
Hot-seat concurrency : PASS
Idempotency          : PASS
Per-user limit       : PASS
OVERALL: PASS
```
## Load-Test Results
A local Docker run with 5,000 requests and concurrency 100 completed successfully with:
```text
201 Created       : 1
409 Conflict      : 4,999
5xx responses     : 0
Client errors     : 0
Inventory invariant: PASS
```
A separate 20,000-request run at concurrency 200 maintained the backend safety properties:
```text
201 Created       : 1
5xx responses     : 0
Inventory invariant: PASS
```
That run recorded 301 client-side load-generator errors, so it is not reported as a clean 20,000-request pass. The clean reproducible local result is the 5,000-request / concurrency-100 run above.
### Production Deployment Correctness Test
The same correctness suite was also executed against the publicly deployed Railway service:
https://seat-reservation-production-7d7b.up.railway.app
Production test configuration:
TOTAL_REQUESTS=100
CONCURRENCY=20
REQUEST_TIMEOUT=120
Result:
201 Created       : 1
409 Conflict      : 99
5xx responses     : 0
Client errors     : 0
Inventory invariant: PASS
HOT SEAT RESULT: PASS

First request              : 201
Same-key replay            : 201
Same reservation returned  : YES
Same key / different body  : 409
IDEMPOTENCY RESULT: PASS

Successful reservations : 4
Limit conflicts          : 4
Confirmed seats          : 4
5xx responses           : 0
Client errors           : 0
Inventory invariant     : PASS
PER-USER LIMIT RESULT: PASS

Hot-seat concurrency : PASS
Idempotency          : PASS
Per-user limit       : PASS

OVERALL: PASS
This verifies the correctness suite against the actual public deployment and Railway PostgreSQL database, rather than only the local Docker environment.
---
# Configuration
Important environment variables:
| Variable | Purpose |
|---|---|
| `DB_URL` | PostgreSQL JDBC URL |
| `DB_USERNAME` | Database username |
| `DB_PASSWORD` | Database password |
| `AUTH_SECRET` | HMAC authentication secret |
| `SPRING_PROFILES_ACTIVE` | Spring profile |
Do not use the development authentication secret in production.
---
# Build Without Docker
Java 17 is required.
Windows:
```powershell
.\mvnw.cmd clean package
```
Linux/macOS:
```bash
./mvnw clean package
```
---
# Design Notes
A detailed discussion of locking, idempotency, failure handling, consistency trade-offs, observability, load testing, AI usage, and future improvements is available in:
```text
WRITEUP.md
```