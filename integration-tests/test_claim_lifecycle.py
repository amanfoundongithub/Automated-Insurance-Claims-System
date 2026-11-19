import json
import os
import subprocess
import sys
import time
import unittest
import uuid
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
JAVA_SERVICES = (
    ("claims-ingestion-service", "ingestion", 8081),
    ("policy-service", "policy", 8083),
    ("claims-orchestrator", "orchestrator", 8082),
    ("settlement-payout-service", "settlement-payout-service", 8086),
)
PYTHON_SERVICES = (
    ("risk-scoring-service", 8084),
    ("valuation-reserve-service", 8085),
)
HEALTH_URLS = (
    "http://127.0.0.1:8081/actuator/health",
    "http://127.0.0.1:8082/actuator/health",
    "http://127.0.0.1:8083/actuator/health",
    "http://127.0.0.1:8086/actuator/health",
    "http://127.0.0.1:8084/health",
    "http://127.0.0.1:8085/health",
)


def request_json(method: str, url: str, payload=None, headers=None):
    body = json.dumps(payload).encode() if payload is not None else None
    request = Request(
        url,
        data=body,
        headers={"Content-Type": "application/json", **(headers or {})},
        method=method,
    )
    try:
        with urlopen(request, timeout=10) as response:
            return response.status, json.loads(response.read())
    except HTTPError as error:
        raise AssertionError(f"{method} {url} returned HTTP {error.code}") from error


class ClaimLifecycleIntegrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        mongo_uri = os.environ.get("MONGODB_URI", "")
        if urlsplit(mongo_uri).hostname not in {"localhost", "127.0.0.1"}:
            raise RuntimeError("Integration tests require a local test MongoDB URI")

        cls.processes = []
        cls.log_handles = []
        log_dir = ROOT / "integration-tests" / "logs"
        log_dir.mkdir(parents=True, exist_ok=True)

        try:
            for service, application_name, port in JAVA_SERVICES:
                cls._start_java_service(service, application_name, port, log_dir)
            for service, port in PYTHON_SERVICES:
                cls._start_python_service(service, port, log_dir)
            for url in HEALTH_URLS:
                cls._wait_for_health(url)
        except Exception:
            cls._stop_services()
            raise

    @classmethod
    def tearDownClass(cls):
        cls._stop_services()

    @classmethod
    def _start_java_service(cls, service, application_name, port, log_dir):
        module = ROOT / service
        jars = [
            path
            for path in (module / "build" / "libs").glob("*.jar")
            if not path.name.endswith("-plain.jar")
        ]
        if len(jars) != 1:
            raise RuntimeError(f"Expected one executable JAR for {service}, found {len(jars)}")

        env = os.environ.copy()
        env.update(
            SERVER_PORT=str(port),
            SPRING_APPLICATION_NAME=application_name,
        )
        cls._start_process(
            ["java", "-jar", str(jars[0])],
            module,
            env,
            log_dir / f"{service}.log",
        )

    @classmethod
    def _start_python_service(cls, service, port, log_dir):
        module = ROOT / service
        cls._start_process(
            [
                sys.executable,
                "-m",
                "uvicorn",
                "app.main:app",
                "--host",
                "127.0.0.1",
                "--port",
                str(port),
            ],
            module,
            os.environ.copy(),
            log_dir / f"{service}.log",
        )

    @classmethod
    def _start_process(cls, command, cwd, env, log_path):
        log_handle = log_path.open("w", encoding="utf-8")
        cls.log_handles.append(log_handle)
        cls.processes.append(
            subprocess.Popen(
                command,
                cwd=cwd,
                env=env,
                stdout=log_handle,
                stderr=subprocess.STDOUT,
            )
        )

    @classmethod
    def _wait_for_health(cls, url):
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            for process in cls.processes:
                if process.poll() is not None:
                    raise RuntimeError(
                        f"Service exited during startup with code {process.returncode}"
                    )
            try:
                status, result = request_json("GET", url)
                if status == 200 and result.get("status") == "UP":
                    return
            except (AssertionError, URLError, TimeoutError, json.JSONDecodeError):
                pass
            time.sleep(2)
        raise TimeoutError(f"Service did not become healthy: {url}")

    @classmethod
    def _stop_services(cls):
        for process in reversed(getattr(cls, "processes", [])):
            if process.poll() is None:
                process.terminate()
        for process in reversed(getattr(cls, "processes", [])):
            if process.poll() is None:
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
        for log_handle in getattr(cls, "log_handles", []):
            log_handle.close()

    def test_claim_is_validated_scored_valued_and_settled(self):
        policy_id = f"policy-ci-{uuid.uuid4().hex}"
        now = datetime.now(timezone.utc)
        policy_request = {
            "policyId": policy_id,
            "policyholderId": "holder-ci",
            "status": "ACTIVE",
            "effectiveFrom": (now - timedelta(days=1)).isoformat(),
            "effectiveTo": (now + timedelta(days=1)).isoformat(),
            "eligibleClaimTypes": ["AUTO"],
            "maxCoverageAmount": {"amount": 10000.00, "currency": "USD"},
            "deductibleAmount": {"amount": 10.00, "currency": "USD"},
            "coPayRatio": 0.10,
        }
        status, created_policy = request_json(
            "POST", "http://127.0.0.1:8083/api/v1/policies", policy_request
        )
        self.assertEqual(201, status)
        self.assertEqual(policy_id, created_policy["policyId"])

        correlation_id = f"corr-ci-{uuid.uuid4().hex}"
        claim_request = {
            "policyId": policy_id,
            "policyholderId": "holder-ci",
            "claimType": "AUTO",
            "incidentOccurredAt": (now - timedelta(minutes=1)).isoformat(),
            "claimedAmount": {"amount": 100.00, "currency": "USD"},
            "incidentDetails": {"source": "integration-test"},
            "attachments": [],
        }
        status, submitted_claim = request_json(
            "POST",
            "http://127.0.0.1:8081/api/v1/claims",
            claim_request,
            {
                "Idempotency-Key": f"idem-ci-{uuid.uuid4().hex}",
                "X-Correlation-Id": correlation_id,
            },
        )
        self.assertEqual(202, status)
        claim_id = submitted_claim["claimId"]
        self.assertEqual(correlation_id, submitted_claim["correlationId"])

        final_state = self._wait_for_result(
            f"http://127.0.0.1:8082/api/v1/claims/{claim_id}",
            lambda result: result.get("status") in {"APPROVED", "REJECTED"},
            "claim lifecycle to reach a terminal state",
        )
        self.assertEqual("APPROVED", final_state["status"])

        risk = self._wait_for_result(
            f"http://127.0.0.1:8084/api/v1/risk-scores/{claim_id}",
            lambda result: result.get("claimId") == claim_id,
            "risk score",
        )
        self.assertEqual("LOW", risk["riskTier"])
        self.assertFalse(risk["requiresManualReview"])

        valuation = self._wait_for_result(
            f"http://127.0.0.1:8085/api/v1/valuations/{claim_id}",
            lambda result: result.get("claimId") == claim_id,
            "valuation",
        )
        self.assertEqual(Decimal("81.00"), Decimal(valuation["estimatedNetPayout"]))

        payout = self._wait_for_result(
            f"http://127.0.0.1:8086/api/v1/payouts/claims/{claim_id}",
            lambda result: result.get("status") in {"SETTLED", "FAILED"},
            "settlement payout",
        )
        self.assertEqual("SETTLED", payout["status"])
        self.assertTrue(payout["providerReference"].startswith("UTR-"))

    def _wait_for_result(self, url, predicate, description):
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            try:
                _, result = request_json("GET", url)
                if predicate(result):
                    return result
            except (AssertionError, URLError, TimeoutError, json.JSONDecodeError):
                pass
            time.sleep(1)
        self.fail(f"Timed out waiting for {description}")


if __name__ == "__main__":
    unittest.main()
