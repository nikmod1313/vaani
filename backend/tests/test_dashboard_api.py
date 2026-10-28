"""Pairing, the dashboard's khata, and the shared price list."""

import unittest

from fastapi.testclient import TestClient

from app import main
from app.store import LedgerStore


class DashboardApiTest(unittest.TestCase):
    def setUp(self) -> None:
        main.STORE = LedgerStore(":memory:")
        main.PAIRS.clear()
        self.client = TestClient(main.app)

    def test_the_phone_typing_the_code_pairs_the_browser(self):
        code = self.client.post("/pair/new").json()["code"]
        self.assertEqual(len(code), 6)
        self.assertEqual(self.client.get(f"/pair/{code}").json()["status"], "waiting")
        ok = self.client.post("/pair/claim", json={"code": code, "shop_id": "s1", "shop_name": "Gupta Store"}).json()
        self.assertTrue(ok["ok"])
        status = self.client.get(f"/pair/{code}").json()
        self.assertEqual((status["status"], status["shop_id"], status["shop_name"]), ("paired", "s1", "Gupta Store"))

    def test_a_wrong_code_pairs_nothing(self):
        self.client.post("/pair/new")
        self.assertFalse(self.client.post("/pair/claim", json={"code": "000000x", "shop_id": "s1"}).json()["ok"])
        self.assertEqual(self.client.get("/pair/123456").json()["status"], "expired")

    def test_a_dashboard_entry_is_searchable_and_reaches_the_phone(self):
        main.STORE.create_shop("s1", "Gupta Store")
        tx = self.client.post("/shops/s1/transactions", json={
            "customer_name": "Rahul", "amount": 450, "items": ["muslin kurti 1"],
        }).json()["id"]
        found = self.client.get("/shops/s1/transactions", params={"q": "muslin"}).json()["transactions"]
        self.assertEqual([t["id"] for t in found], [tx])
        # The phone's sync reply carries it, with the customer's name.
        pulled = self.client.post("/sync", json={"shop_id": "s1", "transactions": []}).json()["transactions"]
        self.assertEqual((pulled[0]["source"], pulled[0]["customer_name"]), ("dashboard", "Rahul"))

        self.client.delete(f"/transactions/{tx}")
        self.assertEqual(self.client.get("/shops/s1/transactions").json()["transactions"], [])

    def test_bad_entries_are_refused(self):
        main.STORE.create_shop("s1", "Gupta Store")
        self.assertEqual(self.client.post("/shops/s1/transactions", json={"customer_name": "R", "amount": 0}).status_code, 422)
        self.assertEqual(self.client.post("/shops/s1/transactions",
                                          json={"customer_name": "R", "amount": 5, "mode": "barter"}).status_code, 422)

    def test_the_newer_price_list_wins(self):
        put = lambda items, at: self.client.put("/shops/s1/catalogue", json={"items": items, "updated_at": at}).json()
        put([{"name": "muslin kurti", "price": 450}], 200)
        # An older copy from a phone that was offline does not overwrite it...
        self.assertEqual(put([{"name": "old"}], 100)["items"][0]["name"], "muslin kurti")
        # ...and a newer one does.
        self.assertEqual(put([{"name": "silk saree", "price": 1200}], 300)["items"][0]["name"], "silk saree")

    def test_top_items_strip_the_quantity(self):
        main.STORE.create_shop("s1", "Gupta Store")
        for items in (["kurti 2", "saree 1"], ["kurti 3"]):
            self.client.post("/shops/s1/transactions", json={"customer_name": "R", "amount": 100, "items": items})
        top = self.client.get("/shops/s1/items").json()["items"]
        self.assertEqual((top[0]["name"], top[0]["bills"], top[0]["quantity"]), ("kurti", 2, 5.0))


if __name__ == "__main__":
    unittest.main()
