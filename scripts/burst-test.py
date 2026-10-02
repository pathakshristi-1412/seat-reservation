import base64
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed

BASE_URL = os.getenv("BASE_URL", "http://localhost:8080")
AUTH_SECRET = os.getenv(
    "AUTH_SECRET",
    "local-docker-development-secret"
)

TOTAL_REQUESTS = int(os.getenv("TOTAL_REQUESTS", "5000"))
CONCURRENCY = int(os.getenv("CONCURRENCY", "100"))
REQUEST_TIMEOUT = int(os.getenv("REQUEST_TIMEOUT", "120"))


# ============================================================
# HTTP / AUTH HELPERS
# ============================================================

def base64url(data):
    return (
        base64.urlsafe_b64encode(data)
        .rstrip(b"=")
        .decode("utf-8")
    )


def generate_token(user_id):
    encoded_user = base64url(user_id.encode("utf-8"))

    signature = hmac.new(
        AUTH_SECRET.encode("utf-8"),
        encoded_user.encode("utf-8"),
        hashlib.sha256
    ).digest()

    return (
        encoded_user
        + "."
        + base64url(signature)
    )


def http_request(
        method,
        path,
        body=None,
        user_id=None,
        idempotency_key=None,
        request_id=None):

    headers = {
        "Content-Type": "application/json"
    }

    if user_id:
        headers["Authorization"] = (
            f"Bearer {generate_token(user_id)}"
        )

    if idempotency_key:
        headers["Idempotency-Key"] = (
            idempotency_key
        )

    if request_id:
        headers["X-Request-ID"] = request_id

    data = None

    if body is not None:
        data = json.dumps(body).encode("utf-8")

    request = urllib.request.Request(
        f"{BASE_URL}{path}",
        data=data,
        method=method,
        headers=headers
    )

    try:
        with urllib.request.urlopen(
                request,
                timeout=REQUEST_TIMEOUT
        ) as response:

            raw = response.read().decode("utf-8")

            parsed = (
                json.loads(raw)
                if raw
                else None
            )

            return (
                response.status,
                parsed
            )

    except urllib.error.HTTPError as error:

        raw = error.read().decode(
            "utf-8",
            errors="replace"
        )

        try:
            parsed = json.loads(raw)
        except Exception:
            parsed = raw

        return (
            error.code,
            parsed
        )

    except Exception as error:

        return (
            "CLIENT_ERROR",
            f"{type(error).__name__}: {error}"
        )


# ============================================================
# SHOW HELPERS
# ============================================================

def create_show(name, seats):

    status, body = http_request(
        "POST",
        "/shows",
        {
            "name": name,
            "seats": seats,
            "price_paise": 25000
        }
    )

    if status != 201:
        raise RuntimeError(
            f"Unable to create show. "
            f"Status={status}, body={body}"
        )

    return body["id"]


def get_show(show_id):

    status, body = http_request(
        "GET",
        f"/shows/{show_id}"
    )

    if status != 200:
        raise RuntimeError(
            f"Unable to fetch show {show_id}. "
            f"Status={status}, body={body}"
        )

    return body


# ============================================================
# HOT SEAT TEST
# ============================================================

def run_hot_seat_test():

    print()
    print("=" * 60)
    print("1. HOT SEAT CONCURRENCY TEST")
    print("=" * 60)

    unique_name = (
        f"burst-{int(time.time() * 1000)}"
    )

    show_id = create_show(
        unique_name,
        [
            "HOT1",
            "A1",
            "A2",
            "A3",
            "A4"
        ]
    )

    print(f"Created show     : {show_id}")
    print(f"Total requests   : {TOTAL_REQUESTS}")
    print(f"Concurrency      : {CONCURRENCY}")
    print(f"Request timeout  : {REQUEST_TIMEOUT}s")
    print()

    started = time.time()

    results = []

    with ThreadPoolExecutor(
            max_workers=CONCURRENCY
    ) as executor:

        futures = []

        for i in range(TOTAL_REQUESTS):

            futures.append(
                executor.submit(
                    http_request,
                    "POST",
                    f"/shows/{show_id}/reserve",
                    {"seats": ["HOT1"]},
                    f"burst-user-{i}",
                    f"burst-key-{show_id}-{i}",
                    f"burst-{show_id}-{i}"
                )
            )

        completed = 0

        for future in as_completed(futures):

            results.append(
                future.result()
            )

            completed += 1

            if (
                completed % 1000 == 0
                or completed == TOTAL_REQUESTS
            ):

                elapsed = (
                    time.time() - started
                )

                print(
                    f"Completed "
                    f"{completed}/"
                    f"{TOTAL_REQUESTS} "
                    f"in {elapsed:.2f}s"
                )

    elapsed = time.time() - started

    statuses = Counter(
        status
        for status, _ in results
    )

    successes = statuses.get(201, 0)
    conflicts = statuses.get(409, 0)

    server_errors = sum(
        count
        for status, count in statuses.items()
        if isinstance(status, int)
        and 500 <= status <= 599
    )

    client_errors = [
        body
        for status, body in results
        if status == "CLIENT_ERROR"
    ]

    print()
    print("Results:")
    print(
        f"  201 Created       : {successes}"
    )
    print(
        f"  409 Conflict      : {conflicts}"
    )
    print(
        f"  5xx responses     : {server_errors}"
    )
    print(
        f"  Client errors     : {len(client_errors)}"
    )
    print(
        f"  Time              : {elapsed:.2f}s"
    )

    if client_errors:

        print()
        print("Client error breakdown:")

        error_counts = Counter(client_errors)

        for error, count in (
            error_counts.most_common(10)
        ):
            print(
                f"  {count} x {error}"
            )

    show = get_show(show_id)

    invariant = (
        show["available"]
        + show["held"]
        + show["confirmed"]
        == show["totalSeats"]
    )

    print()
    print(
        "Inventory invariant:",
        "PASS" if invariant else "FAIL"
    )

    passed = (
        successes == 1
        and conflicts == TOTAL_REQUESTS - 1
        and server_errors == 0
        and len(client_errors) == 0
        and invariant
    )

    print(
        "HOT SEAT RESULT:",
        "PASS" if passed else "FAIL"
    )

    return passed


# ============================================================
# IDEMPOTENCY TEST
# ============================================================

def run_idempotency_test():

    print()
    print("=" * 60)
    print("2. IDEMPOTENCY TEST")
    print("=" * 60)

    show_id = create_show(
        f"idempotency-{int(time.time() * 1000)}",
        ["I1", "I2"]
    )

    user = "idempotency-test-user"

    key = (
        f"idempotency-key-{show_id}"
    )

    status1, body1 = http_request(
        "POST",
        f"/shows/{show_id}/reserve",
        {"seats": ["I1"]},
        user,
        key,
        "idempotency-first"
    )

    status2, body2 = http_request(
        "POST",
        f"/shows/{show_id}/reserve",
        {"seats": ["I1"]},
        user,
        key,
        "idempotency-replay"
    )

    status3, _ = http_request(
        "POST",
        f"/shows/{show_id}/reserve",
        {"seats": ["I2"]},
        user,
        key,
        "idempotency-conflict"
    )

    same_reservation = (
        isinstance(body1, dict)
        and isinstance(body2, dict)
        and body1.get("reservation_id")
        == body2.get("reservation_id")
    )

    passed = (
        status1 == 201
        and status2 == 201
        and same_reservation
        and status3 == 409
    )

    print(
        f"First request              : {status1}"
    )

    print(
        f"Same-key replay            : {status2}"
    )

    print(
        "Same reservation returned :",
        "YES" if same_reservation else "NO"
    )

    print(
        f"Same key / different body  : {status3}"
    )

    print(
        "IDEMPOTENCY RESULT:",
        "PASS" if passed else "FAIL"
    )

    return passed


# ============================================================
# PER-USER LIMIT TEST
# ============================================================

def run_user_limit_test():

    print()
    print("=" * 60)
    print("3. PER-USER LIMIT CONCURRENCY TEST")
    print("=" * 60)

    seats = [
        "L1",
        "L2",
        "L3",
        "L4",
        "L5",
        "L6",
        "L7",
        "L8"
    ]

    show_id = create_show(
        f"user-limit-{int(time.time() * 1000)}",
        seats
    )

    user = "limit-test-user"

    results = []

    with ThreadPoolExecutor(
            max_workers=len(seats)
    ) as executor:

        futures = []

        for i, seat in enumerate(seats):

            futures.append(
                executor.submit(
                    http_request,
                    "POST",
                    f"/shows/{show_id}/reserve",
                    {"seats": [seat]},
                    user,
                    f"limit-key-{show_id}-{i}",
                    f"limit-request-{i}"
                )
            )

        for future in as_completed(futures):
            results.append(
                future.result()
            )

    statuses = Counter(
        status
        for status, _ in results
    )

    successes = statuses.get(201, 0)
    conflicts = statuses.get(409, 0)

    server_errors = sum(
        count
        for status, count in statuses.items()
        if isinstance(status, int)
        and 500 <= status <= 599
    )

    client_errors = statuses.get(
        "CLIENT_ERROR",
        0
    )

    show = get_show(show_id)

    confirmed = show["confirmed"]

    invariant = (
        show["available"]
        + show["held"]
        + show["confirmed"]
        == show["totalSeats"]
    )

    passed = (
        successes == 4
        and conflicts == 4
        and confirmed == 4
        and server_errors == 0
        and client_errors == 0
        and invariant
    )

    print(
        f"Successful reservations : {successes}"
    )

    print(
        f"Limit conflicts         : {conflicts}"
    )

    print(
        f"Confirmed seats         : {confirmed}"
    )

    print(
        f"5xx responses           : {server_errors}"
    )

    print(
        f"Client errors           : {client_errors}"
    )

    print(
        "Inventory invariant     :",
        "PASS" if invariant else "FAIL"
    )

    print(
        "PER-USER LIMIT RESULT:",
        "PASS" if passed else "FAIL"
    )

    return passed


# ============================================================
# MAIN
# ============================================================

def main():

    print()
    print("=" * 60)
    print("SEAT RESERVATION CORRECTNESS SUITE")
    print("=" * 60)

    print(f"Target      : {BASE_URL}")
    print(f"Burst size  : {TOTAL_REQUESTS}")
    print(f"Concurrency : {CONCURRENCY}")

    results = {}

    try:
        results["hot_seat"] = (
            run_hot_seat_test()
        )

        results["idempotency"] = (
            run_idempotency_test()
        )

        results["user_limit"] = (
            run_user_limit_test()
        )

    except Exception as error:

        print()
        print(
            "TEST SUITE ERROR:",
            f"{type(error).__name__}: {error}"
        )

        sys.exit(1)

    print()
    print("=" * 60)
    print("FINAL RESULTS")
    print("=" * 60)

    print(
        "Hot-seat concurrency :",
        "PASS"
        if results["hot_seat"]
        else "FAIL"
    )

    print(
        "Idempotency          :",
        "PASS"
        if results["idempotency"]
        else "FAIL"
    )

    print(
        "Per-user limit       :",
        "PASS"
        if results["user_limit"]
        else "FAIL"
    )

    all_passed = all(
        results.values()
    )

    print()
    print(
        "OVERALL:",
        "PASS" if all_passed else "FAIL"
    )

    sys.exit(
        0 if all_passed else 1
    )


if __name__ == "__main__":
    main()