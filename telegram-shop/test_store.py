import tempfile
import unittest
from pathlib import Path

from store import ADMIN_ID, Store, StoreError


class StoreTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.path = Path(self.temporary.name) / "state.sqlite3"
        self.store = Store(self.path)
        self.store.accept_terms(123)

    def tearDown(self):
        self.store.close()
        self.temporary.cleanup()

    def approved_order(self, user_id=123, at=100):
        self.store.accept_terms(user_id)
        order = self.store.order_for(user_id, user_id, now=at)
        error = self.store.validate_checkout(
            order.payload, user_id, "XTR", order.amount, query_id=f"query-{user_id}", now=at + 1
        )
        self.assertIsNone(error)
        return order

    def paid_order(self, user_id=123, at=100, charge="charge-1"):
        order = self.approved_order(user_id, at)
        result = self.store.accept_payment(
            order.payload, user_id, "XTR", order.amount, charge, now=at + 2
        )
        self.assertFalse(result.duplicate)
        return order

    def test_requires_terms_and_reuses_open_order(self):
        with self.assertRaises(StoreError):
            self.store.order_for(456, 456, now=100)
        order = self.store.order_for(123, 123, now=100)
        self.assertEqual(order.amount, 99)
        self.assertLessEqual(len(order.payload.encode()), 128)
        self.assertEqual(order.id, self.store.order_for(123, 123, now=110).id)

    def test_promo_is_case_insensitive_and_survives_restart(self):
        old = self.store.order_for(123, 123, now=100)
        self.assertEqual("Chrome", self.store.set_promo(123, " cHrOmE "))
        self.store.set_waiting_promo(123)
        self.store.set_offset(900)
        self.store.close()
        self.store = Store(self.path)
        self.assertEqual("Chrome", self.store.get_promo(123))
        self.assertTrue(self.store.get_waiting_promo(123))
        self.assertTrue(self.store.has_accepted_terms(123))
        self.assertEqual(900, self.store.get_offset())
        self.store.set_offset(899)
        self.assertEqual(900, self.store.get_offset())
        current = self.store.order_for(123, 123, now=105)
        self.assertEqual(84, current.amount)
        self.assertNotEqual(old.id, current.id)
        self.assertIsNotNone(self.store.validate_checkout(
            old.payload, 123, "XTR", 99, query_id="old", now=110
        ))

    def test_invalid_promo_changes_nothing(self):
        self.store.set_promo(123, "art")
        with self.assertRaises(StoreError):
            self.store.set_promo(123, "arbitrary")
        self.assertEqual("Art", self.store.get_promo(123))

    def test_checkout_rejects_user_currency_and_amount(self):
        order = self.store.order_for(123, 123, now=100)
        for user, currency, amount in ((456, "XTR", 99), (123, "UAH", 99), (123, "XTR", 98)):
            self.assertIsNotNone(self.store.validate_checkout(
                order.payload, user, currency, amount, query_id="bad", now=110
            ))
        self.assertIsNone(self.store.validate_checkout(
            order.payload, 123, "XTR", 99, query_id="good", now=110
        ))

    def test_checkout_is_idempotent_and_prevents_other_payments(self):
        order = self.approved_order()
        second = Store(self.path)
        try:
            self.assertIsNone(second.validate_checkout(
                order.payload, 123, "XTR", 99, query_id="query-123", now=105
            ))
            self.assertIsNotNone(second.validate_checkout(
                order.payload, 123, "XTR", 99, query_id="another", now=105
            ))
            with self.assertRaises(StoreError):
                second.order_for(123, 123, now=105)
        finally:
            second.close()

    def test_expired_invoice_is_not_approved(self):
        order = self.store.order_for(123, 123, now=100)
        self.assertIsNotNone(self.store.validate_checkout(
            order.payload, 123, "XTR", 99, query_id="late", now=1900
        ))
        self.assertNotEqual(order.id, self.store.order_for(123, 123, now=1900).id)

    def test_approved_payment_can_arrive_after_expiry_and_promo_change(self):
        order = self.approved_order()
        self.store.set_promo(123, "zeff")
        self.store.accept_payment(order.payload, 123, "XTR", 99, "late-charge", now=3000)
        self.assertTrue(self.store.entitlement(123))
        self.assertEqual("paid", self.store.get_order(order.id).status)

    def test_reject_payment_before_checkout_and_wrong_receipts(self):
        order = self.store.order_for(123, 123, now=100)
        with self.assertRaises(StoreError):
            self.store.accept_payment(order.payload, 123, "XTR", 99, "early")
        self.store.validate_checkout(order.payload, 123, "XTR", 99, query_id="good", now=110)
        for user, amount, charge in ((456, 99, "wrong"), (123, 84, "wrong"), (123, 99, "")):
            with self.assertRaises(StoreError):
                self.store.accept_payment(order.payload, user, "XTR", amount, charge)
        self.assertFalse(self.store.entitlement(123))

    def test_duplicate_successful_payment_does_not_duplicate_delivery(self):
        order = self.paid_order()
        again = self.store.accept_payment(order.payload, 123, "XTR", 99, "charge-1")
        self.assertTrue(again.duplicate)
        self.assertEqual(1, len(self.store.pending_deliveries()))
        self.store.mark_document_sent(123)
        self.assertTrue(self.store.pending_deliveries()[0].document_sent)
        self.store.mark_instructions_sent(123)
        self.assertEqual([], self.store.pending_deliveries())
        self.store.accept_payment(order.payload, 123, "XTR", 99, "charge-1")
        self.assertEqual([], self.store.pending_deliveries())

    def test_reused_charge_id_from_different_order_is_rejected(self):
        self.paid_order()
        other = self.approved_order(user_id=456)
        with self.assertRaises(StoreError):
            self.store.accept_payment(other.payload, 456, "XTR", 99, "charge-1")
        self.assertFalse(self.store.entitlement(456))

    def test_delivery_retry_survives_restart(self):
        self.paid_order()
        self.store.mark_delivery_failed(123, now=1000)
        self.assertEqual([], self.store.pending_deliveries(now=1001))
        self.store.close()
        self.store = Store(self.path)
        self.assertEqual(1, len(self.store.pending_deliveries(now=1002)))
        self.store.mark_document_sent(123)
        self.store.mark_instructions_sent(123)
        self.store.retry_delivery(123, 777)
        retry = self.store.pending_deliveries()[0]
        self.assertEqual(777, retry.chat_id)
        self.assertFalse(retry.document_sent)

    def test_admin_free_grant_requires_exact_id(self):
        with self.assertRaises(StoreError):
            self.store.grant_admin(123, 123)
        self.store.grant_admin(ADMIN_ID, ADMIN_ID)
        self.assertTrue(self.store.entitlement(ADMIN_ID))
        self.assertEqual(1, len(self.store.pending_deliveries()))

    def test_refund_revokes_entitlement_and_is_idempotent(self):
        order = self.paid_order()
        self.assertEqual("charge-1", self.store.get_order(order.id).charge_id)
        self.store.mark_refunded(order.id)
        self.store.mark_refunded(order.id)
        self.assertFalse(self.store.entitlement(123))
        self.assertEqual([], self.store.pending_deliveries())
        self.assertEqual("refunded", self.store.get_order(order.id).status)
        duplicate = self.store.accept_payment(order.payload, 123, "XTR", 99, "charge-1")
        self.assertTrue(duplicate.duplicate)
        self.assertFalse(self.store.entitlement(123))

    def test_extra_charge_is_recorded_and_one_refund_keeps_other_entitlement(self):
        order = self.paid_order()
        extra = self.store.accept_payment(order.payload, 123, "XTR", 99, "charge-2")
        self.assertTrue(extra.already_owned)
        self.store.mark_refunded(order.id, "charge-1")
        self.assertTrue(self.store.entitlement(123))
        self.assertEqual("charge-2", self.store.get_order(order.id).charge_id)
        self.store.mark_refunded(order.id, "charge-2")
        self.assertFalse(self.store.entitlement(123))

    def test_admin_grant_survives_a_purchase_refund(self):
        order = self.paid_order(user_id=ADMIN_ID)
        self.store.grant_admin(ADMIN_ID, ADMIN_ID)
        self.store.mark_refunded(order.id)
        self.assertTrue(self.store.entitlement(ADMIN_ID))

    def test_processed_updates_persist_and_old_ids_are_cleaned_with_offset(self):
        self.store.mark_update_handled(41)
        self.store.mark_update_handled(42)
        self.store.mark_update_handled(42)
        self.store.close()
        self.store = Store(self.path)
        self.assertTrue(self.store.is_update_handled(41))
        self.assertTrue(self.store.is_update_handled(42))
        self.store.set_offset(42)
        self.assertFalse(self.store.is_update_handled(41))
        self.assertTrue(self.store.is_update_handled(42))
        self.store.set_offset(43)
        self.assertFalse(self.store.is_update_handled(42))
        self.store.mark_update_handled(44)
        self.store.set_offset(40)
        self.assertEqual(43, self.store.get_offset())
        self.assertTrue(self.store.is_update_handled(44))

    def test_refunded_payment_notification_validates_owner_amount_and_receipt(self):
        order = self.paid_order()
        for user, currency, amount, charge in (
            (456, "XTR", 99, "charge-1"),
            (123, "UAH", 99, "charge-1"),
            (123, "XTR", 98, "charge-1"),
            (123, "XTR", 99, "wrong-charge"),
            (123, "XTR", 99, ""),
        ):
            with self.assertRaises(StoreError):
                self.store.accept_refund(order.payload, user, currency, amount, charge)
        self.assertTrue(self.store.entitlement(123))
        self.assertEqual(
            order.id,
            self.store.accept_refund(order.payload, 123, "XTR", 99, "charge-1"),
        )
        self.assertFalse(self.store.entitlement(123))
        self.assertEqual(
            order.id,
            self.store.accept_refund(order.payload, 123, "XTR", 99, "charge-1"),
        )

    def test_refund_cannot_revoke_another_orders_payment(self):
        first = self.paid_order()
        second = self.paid_order(user_id=456, charge="charge-2")
        with self.assertRaises(StoreError):
            self.store.accept_refund(first.payload, 123, "XTR", 99, "charge-2")
        self.assertTrue(self.store.entitlement(123))
        self.assertTrue(self.store.entitlement(456))
        self.assertEqual("paid", self.store.get_order(second.id).status)


if __name__ == "__main__":
    unittest.main()
