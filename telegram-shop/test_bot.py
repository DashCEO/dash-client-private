import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from bot import Shop, Telegram, TelegramError
from store import ADMIN_ID, Store


class FakeTelegram:
    def __init__(self):
        self.calls = []
        self.document_failures = 0
        self.instruction_failures = 0

    def call(self, method, parameters=None, file=None, timeout=7):
        self.calls.append((method, parameters, file))
        if method == "sendDocument" and self.document_failures:
            self.document_failures -= 1
            raise TelegramError("Temporary upload failure")
        if method == "sendMessage" and parameters.get("text") == "HOW TO PLAY" and self.instruction_failures:
            self.instruction_failures -= 1
            raise TelegramError("Temporary message failure")
        return {"message_id": len(self.calls)}


class BotTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name)
        self.loader = self.path / "PythonLoaderDC.py"
        self.loader.write_text("print('test')", encoding="utf-8")
        self.store = Store(self.path / "shop.sqlite3")
        self.api = FakeTelegram()
        self.shop = Shop(self.api, self.store, self.loader, "HOW TO PLAY")

    def tearDown(self):
        self.store.close()
        self.temp.cleanup()

    def message(self, text, user=123):
        return {"from": {"id": user}, "chat": {"id": user, "type": "private"}, "text": text}

    def paid_order(self, user=123):
        self.store.accept_terms(user)
        order = self.store.order_for(user, user)
        self.assertIsNone(
            self.store.validate_checkout(order.payload, user, "XTR", order.amount, query_id="query1")
        )
        self.shop.message({
            "from": {"id": user}, "chat": {"id": user, "type": "private"},
            "successful_payment": {
                "invoice_payload": order.payload, "currency": "XTR",
                "total_amount": order.amount, "telegram_payment_charge_id": "charge1",
            },
        })
        return order

    def test_terms_before_invoice_and_discount_amount(self):
        self.shop.message(self.message("/pay"))
        self.assertFalse(any(name == "sendInvoice" for name, *_ in self.api.calls))
        self.shop.message(self.message("/promo cHrOmE"))
        self.shop.callback({
            "id": "button1", "from": {"id": 123}, "data": "agree",
            "message": {"chat": {"id": 123, "type": "private"}},
        })
        invoice = next(data for name, data, _ in self.api.calls if name == "sendInvoice")
        self.assertEqual(invoice["currency"], "XTR")
        self.assertEqual(invoice["provider_token"], "")
        self.assertEqual(invoice["prices"], [{"label": "Dash Client", "amount": 84}])
        self.assertTrue(invoice["start_parameter"])
        self.assertLessEqual(len(invoice["payload"].encode()), 128)
        self.assertFalse(self.store.entitlement(123))

    def test_only_successful_payment_unlocks_delivery(self):
        order = self.paid_order()
        self.assertTrue(self.store.entitlement(123))
        self.assertEqual(len(self.store.pending_deliveries()), 1)
        self.assertFalse(any(name == "sendDocument" for name, *_ in self.api.calls))
        self.shop.deliver(self.store.pending_deliveries()[0])
        self.assertEqual(self.store.pending_deliveries(), [])
        self.assertEqual([name for name, *_ in self.api.calls], ["sendDocument", "sendMessage"])
        self.assertTrue(self.store.get_order(order.id).charge_id)

    def test_admin_is_free_without_invoice(self):
        self.shop.message(self.message("/pay", ADMIN_ID))
        self.assertTrue(self.store.entitlement(ADMIN_ID))
        self.assertFalse(any(name == "sendInvoice" for name, *_ in self.api.calls))
        self.shop.message(self.message("/pay", 6662507955))
        self.assertFalse(self.store.entitlement(6662507955))

    def test_failed_instruction_delivery_does_not_resend_document(self):
        self.paid_order()
        self.api.instruction_failures = 1
        with self.assertRaises(TelegramError):
            self.shop.deliver(self.store.pending_deliveries()[0])
        delivery = self.store.pending_deliveries()[0]
        self.assertTrue(delivery.document_sent)
        self.assertFalse(delivery.instructions_sent)
        self.shop.deliver(delivery)
        self.assertEqual(sum(name == "sendDocument" for name, *_ in self.api.calls), 1)

    def test_duplicate_payment_does_not_repeat_finished_delivery(self):
        order = self.paid_order()
        self.shop.deliver(self.store.pending_deliveries()[0])
        self.shop.message({
            "from": {"id": 123}, "chat": {"id": 123, "type": "private"},
            "successful_payment": {
                "invoice_payload": order.payload, "currency": "XTR",
                "total_amount": 99, "telegram_payment_charge_id": "charge1",
            },
        })
        self.assertEqual(self.store.pending_deliveries(), [])

    def test_download_before_payment_does_not_send_loader(self):
        self.shop.message(self.message("/download"))
        self.assertEqual(self.store.pending_deliveries(), [])
        self.assertFalse(any(name == "sendDocument" for name, *_ in self.api.calls))

    def test_group_and_foreign_callback_cannot_grant_admin(self):
        self.shop.message({"from": {"id": ADMIN_ID}, "chat": {"id": -5, "type": "group"}, "text": "/pay"})
        self.shop.callback({
            "id": "wrong", "from": {"id": 123}, "data": "agree",
            "message": {"chat": {"id": ADMIN_ID, "type": "private"}},
        })
        self.assertFalse(self.store.entitlement(ADMIN_ID))
        self.assertFalse(self.store.has_accepted_terms(123))

    def test_refund_event_revokes_delivery(self):
        order = self.paid_order()
        self.shop.message({
            "from": {"id": 123}, "chat": {"id": 123, "type": "private"},
            "refunded_payment": {
                "invoice_payload": order.payload, "currency": "XTR",
                "total_amount": 99, "telegram_payment_charge_id": "charge1",
            },
        })
        self.assertFalse(self.store.entitlement(123))
        self.assertEqual(self.store.pending_deliveries(), [])


class TelegramTransportTests(unittest.TestCase):
    def api(self):
        return Telegram("123456:ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghi")

    def test_upload_multipart_contains_document_and_chat_id(self):
        with tempfile.TemporaryDirectory() as location:
            loader = Path(location) / "PythonLoaderDC.py"
            loader.write_bytes(b"print('OK')\n")
            response = io.BytesIO(json.dumps({"ok": True, "result": {"message_id": 1}}).encode())
            with patch("bot.urlopen", return_value=response) as open_url:
                self.api().call("sendDocument", {"chat_id": 123}, file=("document", loader))
            request = open_url.call_args.args[0]
            self.assertIn(b'name="chat_id"\r\n\r\n123', request.data)
            self.assertIn(b'filename="PythonLoaderDC.py"', request.data)
            self.assertIn(b"print('OK')", request.data)

    def test_bad_json_and_list_response_are_recoverable(self):
        for payload in (b"<html>temporary error</html>", b"[]"):
            with self.subTest(payload=payload):
                with patch("bot.urlopen", return_value=io.BytesIO(payload)):
                    with self.assertRaises(TelegramError):
                        self.api().call("getMe")

    def test_api_error_redacts_token(self):
        api = self.api()
        data = json.dumps({
            "ok": False, "error_code": 400, "description": f"Invalid data at {api.token}",
        }).encode()
        with patch("bot.urlopen", return_value=io.BytesIO(data)):
            with self.assertRaises(TelegramError) as caught:
                api.call("sendMessage")
        self.assertNotIn(api.token, str(caught.exception))


if __name__ == "__main__":
    unittest.main()
