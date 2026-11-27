"""Durable orders, Telegram payment receipts, and delivery progress."""

import sqlite3
import threading
import time
import uuid
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path


ADMIN_ID = 6662507956
BASE_PRICE = 99
PROMO_PRICE = 84
ORDER_LIFETIME = 30 * 60
LANGUAGES = {"uk", "ru"}
CHECKOUT_ERRORS = {
    "Рахунок належить іншому покупцю або не знайдений.": "invoice_owner",
    "Валюта або сума рахунку не збігається.": "invoice_amount",
    "Ти вже маєш доступ до Dash Client.": "already_owned",
    "Рахунок уже обробляється або застарів. Створи новий через Оплата.": "invoice_expired",
}
_INITIALIZATION_LOCK = threading.Lock()


class StoreError(Exception):
    def __init__(self, message, key=None):
        super().__init__(message)
        self.key = key


@dataclass(frozen=True)
class Order:
    id: str
    payload: str
    user_id: int
    chat_id: int
    amount: int
    promo: str | None
    status: str
    created_at: int
    expires_at: int
    charge_id: str | None = None


@dataclass(frozen=True)
class PaymentResult:
    order_id: str
    duplicate: bool
    already_owned: bool
    charge_id: str


@dataclass(frozen=True)
class Delivery:
    user_id: int
    chat_id: int
    document_sent: bool
    instructions_sent: bool


class Store:
    def __init__(self, path, promo_codes=()):
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        self.promo_codes = {
            code.strip().casefold(): code.strip() for code in promo_codes if code.strip()
        }
        with _INITIALIZATION_LOCK:
            self.db = sqlite3.connect(path, timeout=2, isolation_level=None)
            try:
                self._initialize()
            except BaseException:
                self.db.close()
                raise

    def _initialize(self):
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA foreign_keys=ON")
        self.db.execute("PRAGMA busy_timeout=2000")
        self.db.executescript(
            """
            CREATE TABLE IF NOT EXISTS users (
                user_id INTEGER PRIMARY KEY,
                promo TEXT,
                waiting_promo INTEGER NOT NULL DEFAULT 0,
                accepted_terms INTEGER NOT NULL DEFAULT 0,
                language TEXT NOT NULL DEFAULT 'uk'
            );
            CREATE TABLE IF NOT EXISTS orders (
                id TEXT PRIMARY KEY,
                payload TEXT NOT NULL UNIQUE,
                user_id INTEGER NOT NULL,
                chat_id INTEGER NOT NULL,
                amount INTEGER NOT NULL,
                promo TEXT,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                checkout_id TEXT,
                checkout_at INTEGER
            );
            CREATE INDEX IF NOT EXISTS orders_user ON orders(user_id);
            CREATE TABLE IF NOT EXISTS payments (
                charge_id TEXT PRIMARY KEY,
                order_id TEXT NOT NULL REFERENCES orders(id),
                paid_at INTEGER NOT NULL,
                refunded INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS entitlements (
                user_id INTEGER PRIMARY KEY,
                chat_id INTEGER NOT NULL,
                admin_grant INTEGER NOT NULL DEFAULT 0,
                document_sent INTEGER NOT NULL DEFAULT 0,
                instructions_sent INTEGER NOT NULL DEFAULT 0,
                failures INTEGER NOT NULL DEFAULT 0,
                retry_at INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS settings (
                key TEXT PRIMARY KEY,
                value INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS handled_updates (
                update_id INTEGER PRIMARY KEY
            );
            """
        )
        with self._transaction():
            columns = {column["name"] for column in self.db.execute("PRAGMA table_info(users)")}
            if "language" not in columns:
                self.db.execute("ALTER TABLE users ADD COLUMN language TEXT NOT NULL DEFAULT 'uk'")

    def close(self):
        self.db.close()

    @contextmanager
    def _transaction(self):
        self.db.execute("BEGIN IMMEDIATE")
        try:
            yield
        except BaseException:
            self.db.rollback()
            raise
        else:
            self.db.commit()

    @staticmethod
    def _now(now):
        return int(time.time() if now is None else now)

    def _user(self, user_id):
        self.db.execute("INSERT OR IGNORE INTO users(user_id) VALUES (?)", (user_id,))

    def get_offset(self):
        row = self.db.execute("SELECT value FROM settings WHERE key='offset'").fetchone()
        return row["value"] if row else 0

    def set_offset(self, offset):
        with self._transaction():
            self.db.execute(
                "INSERT INTO settings(key, value) VALUES ('offset', ?) "
                "ON CONFLICT(key) DO UPDATE SET value=MAX(value, excluded.value)",
                (int(offset),),
            )
            self.db.execute(
                "DELETE FROM handled_updates WHERE update_id < "
                "(SELECT value FROM settings WHERE key='offset')"
            )

    def is_update_handled(self, update_id):
        return self.db.execute(
            "SELECT 1 FROM handled_updates WHERE update_id=?", (int(update_id),)
        ).fetchone() is not None

    def mark_update_handled(self, update_id):
        with self._transaction():
            self.db.execute(
                "INSERT OR IGNORE INTO handled_updates(update_id) VALUES (?)", (int(update_id),)
            )

    def get_promo(self, user_id):
        row = self.db.execute("SELECT promo FROM users WHERE user_id=?", (user_id,)).fetchone()
        return row["promo"] if row else None

    def set_promo(self, user_id, code):
        canonical = self.promo_codes.get(code.strip().casefold())
        if canonical is None:
            raise StoreError("Промокод не знайдено.", key="invalid_promo")
        with self._transaction():
            self._user(user_id)
            self.db.execute(
                "UPDATE users SET promo=?, waiting_promo=0 WHERE user_id=?",
                (canonical, user_id),
            )
            self.db.execute(
                "UPDATE orders SET status='superseded' WHERE user_id=? AND status='pending'",
                (user_id,),
            )
        return canonical

    def get_language(self, user_id):
        row = self.db.execute(
            "SELECT language FROM users WHERE user_id=?", (user_id,)
        ).fetchone()
        language = row["language"] if row else "uk"
        return language if language in LANGUAGES else "uk"

    def set_language(self, user_id, language):
        if language not in LANGUAGES:
            raise StoreError("Непідтримувана мова.", key="invalid_language")
        with self._transaction():
            self._user(user_id)
            self.db.execute(
                "UPDATE users SET language=? WHERE user_id=?", (language, user_id)
            )

    def clear_promo(self, user_id):
        with self._transaction():
            self._user(user_id)
            self.db.execute(
                "UPDATE users SET promo=NULL, waiting_promo=0 WHERE user_id=?", (user_id,)
            )
            self.db.execute(
                "UPDATE orders SET status='superseded' WHERE user_id=? AND status='pending'",
                (user_id,),
            )

    def get_waiting_promo(self, user_id):
        row = self.db.execute(
            "SELECT waiting_promo FROM users WHERE user_id=?", (user_id,)
        ).fetchone()
        return bool(row and row["waiting_promo"])

    def set_waiting_promo(self, user_id, waiting=True):
        with self._transaction():
            self._user(user_id)
            self.db.execute(
                "UPDATE users SET waiting_promo=? WHERE user_id=?", (int(waiting), user_id)
            )

    def has_accepted_terms(self, user_id):
        row = self.db.execute(
            "SELECT accepted_terms FROM users WHERE user_id=?", (user_id,)
        ).fetchone()
        return bool(row and row["accepted_terms"])

    def accept_terms(self, user_id):
        with self._transaction():
            self._user(user_id)
            self.db.execute(
                "UPDATE users SET accepted_terms=1 WHERE user_id=?", (user_id,)
            )

    def entitlement(self, user_id):
        return self.db.execute(
            "SELECT 1 FROM entitlements WHERE user_id=?", (user_id,)
        ).fetchone() is not None

    @staticmethod
    def _order(row):
        if row is None:
            return None
        return Order(**{name: row[name] for name in Order.__dataclass_fields__})

    def get_order(self, order_id):
        row = self.db.execute(
            "SELECT o.*, (SELECT charge_id FROM payments p WHERE p.order_id=o.id "
            "ORDER BY refunded, paid_at, charge_id LIMIT 1) AS charge_id "
            "FROM orders o WHERE o.id=?",
            (order_id,),
        ).fetchone()
        return self._order(row)

    def order_for(self, user_id, chat_id, now=None):
        now = self._now(now)
        with self._transaction():
            if self.entitlement(user_id):
                raise StoreError(
                    "Доступ уже придбано. Скористайся кнопкою отримання файлу.",
                    key="already_owned",
                )
            if not self.has_accepted_terms(user_id):
                raise StoreError("Спочатку прочитай і прийми умови покупки.", key="accept_terms")
            active = self.db.execute(
                "SELECT id FROM orders WHERE user_id=? AND status='checkout' "
                "AND expires_at>? LIMIT 1",
                (user_id, now),
            ).fetchone()
            if active:
                raise StoreError(
                    "Попередній платіж уже обробляється. Дочекайся результату.",
                    key="payment_pending",
                )
            promo = self.get_promo(user_id)
            amount = PROMO_PRICE if promo else BASE_PRICE
            self.db.execute(
                "UPDATE orders SET status='expired' WHERE user_id=? "
                "AND status='pending' AND expires_at<=?",
                (user_id, now),
            )
            previous = self.db.execute(
                "SELECT id FROM orders WHERE user_id=? AND chat_id=? AND status='pending' "
                "AND amount=? AND promo IS ? AND expires_at>? ORDER BY created_at DESC LIMIT 1",
                (user_id, chat_id, amount, promo, now),
            ).fetchone()
            if previous:
                order_id = previous["id"]
            else:
                order_id = uuid.uuid4().hex
                self.db.execute(
                    "INSERT INTO orders(id,payload,user_id,chat_id,amount,promo,status,created_at,"
                    "expires_at) VALUES (?,?,?,?,?,?,'pending',?,?)",
                    (order_id, f"dash:{order_id}", user_id, chat_id, amount, promo, now,
                     now + ORDER_LIFETIME),
                )
        return self.get_order(order_id)

    def validate_checkout(self, payload, user_id, currency, amount, query_id=None, now=None):
        now = self._now(now)
        with self._transaction():
            order = self.db.execute("SELECT * FROM orders WHERE payload=?", (payload,)).fetchone()
            if not order or order["user_id"] != user_id:
                return "Рахунок належить іншому покупцю або не знайдений."
            if currency != "XTR" or type(amount) is not int or amount != order["amount"]:
                return "Валюта або сума рахунку не збігається."
            if self.entitlement(user_id):
                return "Ти вже маєш доступ до Dash Client."
            if order["status"] == "checkout" and query_id and order["checkout_id"] == query_id:
                return None
            if order["status"] != "pending" or order["expires_at"] <= now:
                return "Рахунок уже обробляється або застарів. Створи новий через Оплата."
            self.db.execute(
                "UPDATE orders SET status='checkout',checkout_at=?,checkout_id=? WHERE id=?",
                (now, query_id, order["id"]),
            )
        return None

    def _grant(self, user_id, chat_id, admin=False):
        self.db.execute(
            "INSERT INTO entitlements(user_id,chat_id,admin_grant) VALUES (?,?,?) "
            "ON CONFLICT(user_id) DO UPDATE SET chat_id=excluded.chat_id,"
            "admin_grant=MAX(entitlements.admin_grant,excluded.admin_grant)",
            (user_id, chat_id, int(admin)),
        )

    def accept_payment(self, payload, user_id, currency, amount, charge_id, now=None):
        now = self._now(now)
        if not isinstance(charge_id, str) or not charge_id.strip():
            raise StoreError("У платежі немає ідентифікатора Telegram.", key="missing_charge")
        with self._transaction():
            order = self.db.execute("SELECT * FROM orders WHERE payload=?", (payload,)).fetchone()
            if not order or order["user_id"] != user_id:
                raise StoreError(
                    "Платіж не відповідає покупцю або рахунку.", key="payment_owner"
                )
            if currency != "XTR" or type(amount) is not int or amount != order["amount"]:
                raise StoreError(
                    "Сума або валюта отриманого платежу не відповідає рахунку.",
                    key="payment_amount",
                )
            receipt = self.db.execute(
                "SELECT * FROM payments WHERE charge_id=?", (charge_id,)
            ).fetchone()
            if receipt:
                if receipt["order_id"] != order["id"]:
                    raise StoreError(
                        "Ідентифікатор платежу вже пов'язаний з іншим рахунком.",
                        key="charge_reused",
                    )
                return PaymentResult(order["id"], True, self.entitlement(user_id), charge_id)
            if order["checkout_at"] is None:
                raise StoreError(
                    "Платіж надійшов без підтвердженого pre-checkout.",
                    key="checkout_missing",
                )
            already_owned = self.entitlement(user_id)
            self.db.execute(
                "INSERT INTO payments(charge_id,order_id,paid_at) VALUES (?,?,?)",
                (charge_id, order["id"], now),
            )
            self.db.execute("UPDATE orders SET status='paid' WHERE id=?", (order["id"],))
            self._grant(user_id, order["chat_id"])
        return PaymentResult(order["id"], False, already_owned, charge_id)

    def grant_admin(self, user_id, chat_id):
        if user_id != ADMIN_ID:
            raise StoreError(
                "Безкоштовний доступ доступний тільки адміністратору.", key="admin_only"
            )
        with self._transaction():
            self._grant(user_id, chat_id, admin=True)

    def _refund_receipt(self, order_id, charge_id):
        order = self.get_order(order_id)
        if order is None:
            raise StoreError("Рахунок не знайдено.", key="order_missing")
        charge_id = charge_id or order.charge_id
        receipt = self.db.execute(
            "SELECT * FROM payments WHERE order_id=? AND charge_id=?",
            (order_id, charge_id),
        ).fetchone()
        if receipt is None:
            raise StoreError("Платіж рахунку не знайдено.", key="charge_missing")
        self.db.execute("UPDATE payments SET refunded=1 WHERE charge_id=?", (charge_id,))
        unpaid = self.db.execute(
            "SELECT 1 FROM payments WHERE order_id=? AND refunded=0", (order_id,)
        ).fetchone()
        if unpaid is None:
            self.db.execute("UPDATE orders SET status='refunded' WHERE id=?", (order_id,))
        other = self.db.execute(
            "SELECT 1 FROM payments p JOIN orders o ON p.order_id=o.id "
            "WHERE o.user_id=? AND p.refunded=0 LIMIT 1",
            (order.user_id,),
        ).fetchone()
        if other is None:
            self.db.execute(
                "DELETE FROM entitlements WHERE user_id=? AND admin_grant=0",
                (order.user_id,),
            )

    def mark_refunded(self, order_id, charge_id=None):
        with self._transaction():
            self._refund_receipt(order_id, charge_id)

    def accept_refund(self, payload, user_id, currency, amount, charge_id):
        with self._transaction():
            order = self.db.execute("SELECT * FROM orders WHERE payload=?", (payload,)).fetchone()
            if not order or order["user_id"] != user_id:
                raise StoreError(
                    "Повернення не відповідає покупцю або рахунку.", key="refund_owner"
                )
            if currency != "XTR" or type(amount) is not int or amount != order["amount"]:
                raise StoreError(
                    "Сума або валюта повернення не відповідає рахунку.",
                    key="refund_amount",
                )
            if not isinstance(charge_id, str) or not charge_id.strip():
                raise StoreError(
                    "Повернення не містить ідентифікатора платежу.", key="missing_charge"
                )
            self._refund_receipt(order["id"], charge_id)
            return order["id"]

    def pending_deliveries(self, now=None):
        rows = self.db.execute(
            "SELECT user_id,chat_id,document_sent,instructions_sent FROM entitlements "
            "WHERE (document_sent=0 OR instructions_sent=0) AND retry_at<=? "
            "ORDER BY retry_at,user_id LIMIT 50",
            (self._now(now),),
        ).fetchall()
        return [
            Delivery(row["user_id"], row["chat_id"], bool(row["document_sent"]),
                     bool(row["instructions_sent"]))
            for row in rows
        ]

    def mark_document_sent(self, user_id):
        with self._transaction():
            self.db.execute(
                "UPDATE entitlements SET document_sent=1,failures=0,retry_at=0 WHERE user_id=?",
                (user_id,),
            )

    def mark_instructions_sent(self, user_id):
        with self._transaction():
            self.db.execute(
                "UPDATE entitlements SET instructions_sent=1,failures=0,retry_at=0 WHERE user_id=?",
                (user_id,),
            )

    def mark_delivery_failed(self, user_id, now=None):
        with self._transaction():
            row = self.db.execute(
                "SELECT failures FROM entitlements WHERE user_id=?", (user_id,)
            ).fetchone()
            if row:
                failures = row["failures"] + 1
                delay = min(300, 2 ** min(failures, 9))
                self.db.execute(
                    "UPDATE entitlements SET failures=?,retry_at=? WHERE user_id=?",
                    (failures, self._now(now) + delay, user_id),
                )

    def retry_delivery(self, user_id, chat_id):
        with self._transaction():
            result = self.db.execute(
                "UPDATE entitlements SET chat_id=?,document_sent=0,instructions_sent=0,"
                "failures=0,retry_at=0 WHERE user_id=?",
                (chat_id, user_id),
            )
            if result.rowcount != 1:
                raise StoreError("Спочатку придбай доступ до Dash Client.", key="purchase_required")
