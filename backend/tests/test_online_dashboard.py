"""The hosted dashboard: a shop is reachable only with its phone's key or its dashboard code."""

import unittest

from fastapi.testclient import TestClient

from app import main
from app.store import LedgerStore

KEY = {"X-Vaani-Key": "k" * 40}
OTHER = {"X-Vaani-Key": "x" * 40}
TX = {"id": "t1", "amount": 400, "mode": "udhaar", "direction": "sale", "customer_name": "Isha",
      "items": ["spring onion 2kg"], "occurred_at": "2026-10-30T23:34:00"}


class OnlineDashboardTest(unittest.TestCase):
    def setUp(self) -> None:
        self.public, main.PUBLIC = main.PUBLIC, True
        main.STORE = LedgerStore(":memory:")
        main._FAILS.clear()
        self.c = TestClient(main.app)

    def tearDown(self) -> None:
        main.PUBLIC = self.public

    def _phone_sync(self, headers=KEY, **extra):
        return self.c.post("/sync", headers=headers, json={"shop_id": "s1", "shop_name": "Gupta Store", "transactions": [TX], **extra})

    def test_a_shop_is_closed_without_proof(self):
        self.assertEqual(self._phone_sync().status_code, 200)
        for path in ("/shops/s1/summary", "/shops/s1/transactions", "/shops/s1/items", "/shops/s1/catalogue"):
            self.assertEqual(self.c.get(path).status_code, 401, path)
        self.assertEqual(self.c.post("/shops/s1/transactions", json={"customer_name": "X", "amount": 5}).status_code, 401)
        self.assertEqual(self.c.delete("/transactions/t1").status_code, 401)
        self.assertEqual(self.c.get("/summary/narrate", params={"shop_id": "s1"}).status_code, 401)

    def test_the_first_phone_key_is_kept(self):
        self.assertEqual(self._phone_sync().status_code, 200)
        self.assertEqual(self._phone_sync(headers=OTHER).status_code, 401)   # someone else's phone
        self.assertEqual(self._phone_sync(headers={}).status_code, 401)      # no key at all
        self.assertEqual(self.c.get("/shops/s1/summary", headers=KEY).status_code, 200)

    def test_the_dashboard_code_opens_the_shop(self):
        self._phone_sync()
        self.assertEqual(self.c.post("/shops/s1/dashboard-code", headers=KEY, json={"code": "VK7Q-M2PD"}).status_code, 200)
        r = self.c.post("/dashboard/redeem", json={"code": "vk7q m2pd"}).json()
        self.assertEqual((r["shop_id"], r["shop_name"]), ("s1", "Gupta Store"))
        auth = {"Authorization": "Bearer " + r["token"]}
        self.assertEqual(self.c.get("/shops/s1/summary", headers=auth).status_code, 200)
        self.assertEqual(self.c.get("/shops/s1/transactions", headers=auth).json()["transactions"][0]["amount"], 400)
        # A session opens its own shop only.
        self.c.post("/sync", headers=OTHER, json={"shop_id": "s2", "transactions": []})
        self.assertEqual(self.c.get("/shops/s2/summary", headers=auth).status_code, 401)

    def test_only_the_phone_can_set_the_code(self):
        self._phone_sync()
        self.assertEqual(self.c.post("/shops/s1/dashboard-code", json={"code": "AAAA-BBBB"}).status_code, 401)
        self.assertEqual(self.c.post("/shops/s1/dashboard-code", headers=OTHER, json={"code": "AAAA-BBBB"}).status_code, 401)
        r = self.c.post("/dashboard/redeem", json={"code": "VK7Q-M2PD"}).json()
        self.c.post("/shops/s1/dashboard-code", headers=KEY, json={"code": "VK7Q-M2PD"})
        session = self.c.post("/dashboard/redeem", json={"code": "VK7Q-M2PD"}).json()["token"]
        # A browser session cannot change the code.
        self.assertEqual(self.c.post("/shops/s1/dashboard-code", headers={"Authorization": "Bearer " + session},
                                     json={"code": "AAAA-BBBB"}).status_code, 401)

    def test_a_new_code_signs_old_browsers_out(self):
        self._phone_sync()
        self.c.post("/shops/s1/dashboard-code", headers=KEY, json={"code": "VK7Q-M2PD"})
        old = {"Authorization": "Bearer " + self.c.post("/dashboard/redeem", json={"code": "VK7Q-M2PD"}).json()["token"]}
        self.c.post("/shops/s1/dashboard-code", headers=KEY, json={"code": "ZZ34-NN56"})
        self.assertEqual(self.c.get("/shops/s1/summary", headers=old).status_code, 401)
        self.assertEqual(self.c.post("/dashboard/redeem", json={"code": "VK7Q-M2PD"}).status_code, 404)
        self.assertEqual(self.c.post("/dashboard/redeem", json={"code": "ZZ34-NN56"}).status_code, 200)
        # Switching it off closes the shop to every browser.
        self.c.post("/shops/s1/dashboard-code", headers=KEY, json={"code": ""})
        self.assertEqual(self.c.post("/dashboard/redeem", json={"code": "ZZ34-NN56"}).status_code, 404)

    def test_a_forged_session_is_refused(self):
        self._phone_sync()
        self.c.post("/shops/s1/dashboard-code", headers=KEY, json={"code": "VK7Q-M2PD"})
        token = self.c.post("/dashboard/redeem", json={"code": "VK7Q-M2PD"}).json()["token"]
        raw, sig = token.split(".")
        forged = bytes.fromhex(raw).decode().replace("s1", "s2").encode().hex() + "." + sig
        self.assertEqual(self.c.get("/shops/s2/summary", headers={"Authorization": "Bearer " + forged}).status_code, 401)
        self.assertEqual(self.c.get("/shops/s1/summary", headers={"Authorization": "Bearer " + token[:-2] + "00"}).status_code, 401)

    def test_guessing_is_rate_limited(self):
        self._phone_sync()
        self.c.post("/shops/s1/dashboard-code", headers=KEY, json={"code": "VK7Q-M2PD"})
        for _ in range(10):
            self.assertEqual(self.c.post("/dashboard/redeem", json={"code": "AAAA-AAAA"}).status_code, 404)
        self.assertEqual(self.c.post("/dashboard/redeem", json={"code": "VK7Q-M2PD"}).status_code, 429)

    def test_a_wiped_server_learns_the_code_again_from_the_next_sync(self):
        self._phone_sync(dashboard_code_hash=main.code_hash("VK7Q-M2PD"))
        token = self.c.post("/dashboard/redeem", json={"code": "VK7Q-M2PD"}).json()["token"]
        # Ordinary syncs with the same code keep the browser signed in.
        reply = self._phone_sync(dashboard_code_hash=main.code_hash("VK7Q-M2PD")).json()
        self.assertEqual(self.c.get("/shops/s1/summary", headers={"Authorization": "Bearer " + token}).status_code, 200)
        self.assertEqual(reply["server_count"], 1)
        # The server loses its disk...
        main.STORE = LedgerStore(":memory:")
        self.assertEqual(self.c.post("/dashboard/redeem", json={"code": "VK7Q-M2PD"}).status_code, 404)
        reply = self._phone_sync(dashboard_code_hash=main.code_hash("VK7Q-M2PD"), transactions=[]).json()
        self.assertEqual(reply["server_count"], 0)   # the phone resends its khata on seeing this
        self.assertEqual(self.c.post("/dashboard/redeem", json={"code": "VK7Q-M2PD"}).status_code, 200)

    def test_wifi_pairing_and_demo_data_are_off_online(self):
        self.assertEqual(self.c.post("/pair/new").status_code, 404)
        self.assertEqual(self.c.post("/shops/s1/seed").status_code, 404)
        self.assertTrue(self.c.get("/health").json()["public"])


class LocalModeUnchangedTest(unittest.TestCase):
    """On the shop's own Wi-Fi nothing asks for a key or a code."""

    def setUp(self) -> None:
        main.STORE = LedgerStore(":memory:")
        self.c = TestClient(main.app)

    def test_everything_works_without_proof(self):
        self.assertFalse(main.PUBLIC)
        self.assertEqual(self.c.post("/sync", json={"shop_id": "s1", "transactions": [TX]}).status_code, 200)
        self.assertEqual(self.c.get("/shops/s1/summary").status_code, 200)
        self.assertEqual(self.c.post("/pair/new").status_code, 200)
        self.assertFalse(self.c.get("/health").json()["public"])


if __name__ == "__main__":
    unittest.main()
