"""Telegram Stars shop for the existing Dash Client loader. Python 3.11+."""

import argparse
import json
import logging
import os
import re
import secrets
import sys
import threading
import time
from contextlib import contextmanager
from http.client import HTTPException
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from messages import LANGUAGE_PICKER, button_action, menu, translate
from store import ADMIN_ID, BASE_PRICE, CHECKOUT_ERRORS, PROMO_PRICE, Store, StoreError


ROOT = Path(__file__).resolve().parent
LOGGER = logging.getLogger("dash_shop")
BASE_STARS = BASE_PRICE
PROMO_STARS = PROMO_PRICE


class TelegramError(Exception):
    def __init__(self, description, code=0, retry_after=0):
        super().__init__(description)
        self.code = code
        self.retry_after = retry_after


def load_env(path):
    if not path.is_file():
        return
    for raw in path.read_text(encoding="utf-8-sig").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or not re.fullmatch(r"[A-Z][A-Z0-9_]*", key.strip()):
            raise ValueError("Некоректний рядок у .env.")
        os.environ.setdefault(key.strip(), value.strip().strip("\"'"))


class Telegram:
    def __init__(self, token, test_mode=False):
        if re.fullmatch(r"[0-9]{5,}:[A-Za-z0-9_-]{20,}", token or "") is None:
            raise ValueError("Задайте BOT_TOKEN від BotFather у .env або змінних середовища.")
        environment = "test/" if test_mode else ""
        self.endpoint = f"https://api.telegram.org/bot{token}/{environment}"
        self.token = token

    def call(self, method, parameters=None, file=None, timeout=3):
        parameters = parameters or {}
        headers = {}
        if file is None:
            body = json.dumps(parameters, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        else:
            field, path = file
            boundary = "DashShop" + secrets.token_hex(16)
            parts = []
            for name, value in parameters.items():
                if not isinstance(value, str):
                    value = json.dumps(value, ensure_ascii=False)
                parts.append(
                    f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n'
                    f"\r\n{value}\r\n".encode("utf-8")
                )
            parts.append(
                f'--{boundary}\r\nContent-Disposition: form-data; name="{field}"; '
                f'filename="{path.name}"\r\nContent-Type: application/octet-stream\r\n'
                "\r\n".encode("utf-8")
            )
            parts.extend((path.read_bytes(), f"\r\n--{boundary}--\r\n".encode("ascii")))
            body = b"".join(parts)
            headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
        request = Request(self.endpoint + method, body, headers, method="POST")
        try:
            with urlopen(request, timeout=timeout) as response:
                result = json.loads(response.read())
        except HTTPError as error:
            try:
                result = json.loads(error.read())
            except (ValueError, OSError):
                raise TelegramError(f"Telegram HTTP {error.code}", error.code) from None
        except (URLError, OSError, TimeoutError, ValueError, HTTPException):
            raise TelegramError("Telegram недоступний або перевищено час очікування.") from None
        if not isinstance(result, dict):
            raise TelegramError("Некоректна відповідь Telegram.")
        if not result.get("ok"):
            description = str(result.get("description", "Некоректна відповідь Telegram."))
            description = description.replace(self.token, "[TOKEN]")
            raise TelegramError(
                description,
                result.get("error_code", 0),
                result.get("parameters", {}).get("retry_after", 0),
            )
        return result["result"]


@contextmanager
def single_instance(database):
    database.parent.mkdir(parents=True, exist_ok=True)
    with database.with_suffix(".lock").open("a+b") as handle:
        handle.seek(0, os.SEEK_END)
        if handle.tell() == 0:
            handle.write(b"0")
            handle.flush()
        handle.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            raise RuntimeError("Бот уже працює з цією базою даних.") from None
        yield


class Shop:
    def __init__(self, api, store, loader, instructions):
        self.api = api
        self.store = store
        self.loader = loader
        self.instructions = instructions

    def say(self, chat_id, text, reply_markup=None):
        payload = {"chat_id": chat_id, "text": text}
        if reply_markup is not None:
            payload["reply_markup"] = reply_markup
        return self.api.call("sendMessage", payload)

    def price(self, user_id):
        if user_id == ADMIN_ID:
            return 0
        return PROMO_STARS if self.store.get_promo(user_id) else BASE_STARS

    def show_product(self, user_id, chat_id, language):
        if self.store.entitlement(user_id):
            self.say(chat_id, translate(language, "already_owned"), menu(language))
            return
        self.say(
            chat_id,
            translate(language, "product", price=self.price(user_id)),
            menu(language),
        )

    def show_terms(self, chat_id, language):
        terms = translate(language, "terms", base_price=BASE_STARS, promo_price=PROMO_STARS)
        self.say(chat_id, terms, {"inline_keyboard": [
            [{"text": translate(language, "agree"), "callback_data": "agree"}],
        ]})

    def show_languages(self, chat_id, language):
        self.say(chat_id, translate(language, "choose_language"), LANGUAGE_PICKER)

    def invoice(self, user_id, chat_id, language):
        if user_id == ADMIN_ID:
            self.store.grant_admin(user_id, chat_id)
            self.say(chat_id, translate(language, "admin"))
            return
        if self.store.entitlement(user_id):
            self.store.retry_delivery(user_id, chat_id)
            self.say(chat_id, translate(language, "redelivery"))
            return
        if not self.store.has_accepted_terms(user_id):
            self.show_terms(chat_id, language)
            return
        try:
            order = self.store.order_for(user_id, chat_id)
        except StoreError as error:
            if error.key == "accept_terms":
                self.show_terms(chat_id, language)
            else:
                self.say(chat_id, translate(language, error.key or "payment_pending"))
            return
        self.api.call("sendInvoice", {
            "chat_id": chat_id,
            "title": translate(language, "invoice_title"),
            "description": translate(language, "invoice_description", order=order.id),
            "payload": order.payload,
            "provider_token": "",
            "currency": "XTR",
            "prices": [{"label": "Dash Client", "amount": order.amount}],
            "start_parameter": "dash_purchase",
        })

    def checkout(self, query):
        user_id = query["from"]["id"]
        error = self.store.validate_checkout(
            query.get("invoice_payload", ""),
            user_id,
            query.get("currency"),
            query.get("total_amount"),
            query_id=query["id"],
        )
        payload = {"pre_checkout_query_id": query["id"], "ok": error is None}
        if error:
            key = CHECKOUT_ERRORS.get(error, "invoice_owner")
            payload["error_message"] = translate(self.store.get_language(user_id), key)
        self.api.call("answerPreCheckoutQuery", payload, timeout=3)

    def paid(self, message):
        payment = message["successful_payment"]
        result = self.store.accept_payment(
            payment.get("invoice_payload", ""),
            message["from"]["id"],
            payment.get("currency"),
            payment.get("total_amount"),
            payment.get("telegram_payment_charge_id", ""),
        )
        LOGGER.info("Confirmed order %s; duplicate event=%s", result.order_id, result.duplicate)

    def promo(self, user_id, chat_id, code, language):
        code = code.strip()
        if len(code) >= 2 and code[0] in ('"', "'") and code[-1] == code[0]:
            code = code[1:-1].strip()
        if not code:
            self.say(chat_id, translate(language, "promo_hint"))
            return
        try:
            self.store.set_promo(user_id, code)
        except StoreError:
            self.say(chat_id, translate(language, "promo_invalid"))
            return
        self.say(
            chat_id,
            translate(language, "promo_applied", price=self.price(user_id)),
            menu(language),
        )

    def refund(self, user_id, chat_id, identifier, language):
        if user_id != ADMIN_ID:
            self.say(chat_id, translate(language, "admin_only"))
            return
        order = self.store.get_order(identifier.strip())
        if order is None or not order.charge_id:
            self.say(chat_id, translate(language, "refund_missing"))
            return
        if order.status == "refunded":
            self.say(chat_id, translate(language, "refund_already", order=order.id))
            return
        try:
            self.api.call("refundStarPayment", {
                "user_id": order.user_id,
                "telegram_payment_charge_id": order.charge_id,
            })
        except TelegramError as error:
            if error.code != 400 or "CHARGE_ALREADY_REFUNDED" not in str(error):
                raise
        self.store.mark_refunded(order.id, order.charge_id)
        self.say(chat_id, translate(language, "refund_done", order=order.id))

    def message(self, message):
        sender = message.get("from", {})
        chat = message.get("chat", {})
        if chat.get("type") != "private":
            return
        if message.get("refunded_payment"):
            refund = message["refunded_payment"]
            self.store.accept_refund(
                refund.get("invoice_payload", ""),
                chat["id"],
                refund.get("currency"),
                refund.get("total_amount"),
                refund.get("telegram_payment_charge_id", ""),
            )
            return
        if not sender.get("id") or sender.get("is_bot"):
            return
        user_id, chat_id = sender["id"], chat["id"]
        if message.get("successful_payment"):
            self.paid(message)
            return
        text = message.get("text", "").strip()
        if not text:
            return
        language = self.store.get_language(user_id)
        parts = text.split(maxsplit=1)
        command = parts[0].partition("@")[0].casefold()
        argument = parts[1] if len(parts) == 2 else ""
        action = button_action(text)
        if command in ("/start", "/buy") or action == "buy":
            self.show_product(user_id, chat_id, language)
        elif command == "/terms":
            self.show_terms(chat_id, language)
        elif command in ("/language", "/launguage", "language", "launguage") or action == "language":
            self.show_languages(chat_id, language)
        elif command == "/promo" or action == "promo":
            self.promo(user_id, chat_id, argument if command == "/promo" else "", language)
        elif command == "/pay" or action == "pay":
            self.invoice(user_id, chat_id, language)
        elif command == "/download" or action == "download":
            if user_id == ADMIN_ID:
                self.store.grant_admin(user_id, chat_id)
            if self.store.entitlement(user_id):
                self.store.retry_delivery(user_id, chat_id)
                self.say(chat_id, translate(language, "redelivery"))
            else:
                self.say(chat_id, translate(language, "not_owned"), menu(language))
        elif command in ("/paysupport", "/support"):
            self.say(chat_id, translate(language, "support"), {
                "inline_keyboard": [[{
                    "text": translate(language, "contact_admin"),
                    "url": f"tg://user?id={ADMIN_ID}",
                }]],
            })
        elif command == "/refund":
            self.refund(user_id, chat_id, argument, language)
        else:
            self.say(chat_id, translate(language, "menu_hint"), menu(language))

    def callback(self, callback):
        message = callback.get("message", {})
        user_id = callback.get("from", {}).get("id")
        chat = message.get("chat", {})
        self.api.call("answerCallbackQuery", {"callback_query_id": callback["id"]})
        if chat.get("type") != "private" or user_id != chat.get("id"):
            return
        action = callback.get("data")
        language = self.store.get_language(user_id)
        if action == "terms":
            self.show_terms(chat["id"], language)
        elif action in ("agree", "pay"):
            if action == "agree":
                self.store.accept_terms(user_id)
            self.invoice(user_id, chat["id"], language)
        elif action in ("language:uk", "language:ru"):
            language = action.split(":", 1)[1]
            self.store.set_language(user_id, language)
            self.say(chat["id"], translate(language, "language_changed"), menu(language))

    def update(self, update):
        if update.get("pre_checkout_query"):
            self.checkout(update["pre_checkout_query"])
        elif update.get("message"):
            self.message(update["message"])
        elif update.get("callback_query"):
            self.callback(update["callback_query"])

    def deliver(self, delivery):
        if not self.store.entitlement(delivery.user_id):
            return
        language = self.store.get_language(delivery.user_id)
        if not delivery.document_sent:
            self.api.call("sendDocument", {
                "chat_id": delivery.chat_id,
                "caption": translate(language, "delivery_caption"),
            }, file=("document", self.loader), timeout=30)
            self.store.mark_document_sent(delivery.user_id)
        if not delivery.instructions_sent:
            self.say(delivery.chat_id, self.instructions[language])
            self.store.mark_instructions_sent(delivery.user_id)


def delivery_worker(api, database, loader, instructions, stop):
    store = Store(database)
    shop = Shop(api, store, loader, instructions)
    try:
        while not stop.is_set():
            for delivery in store.pending_deliveries():
                try:
                    shop.deliver(delivery)
                except (TelegramError, OSError, StoreError) as error:
                    store.mark_delivery_failed(delivery.user_id)
                    LOGGER.warning("Delivery to %s failed: %s", delivery.user_id, error)
                if stop.is_set():
                    break
            stop.wait(1)
    finally:
        store.close()


def run(api, database, loader, instructions, promo_codes):
    store = Store(database, promo_codes=promo_codes)
    stop = threading.Event()
    worker = None
    try:
        identity = api.call("getMe")
        webhook = api.call("getWebhookInfo")
        if webhook.get("url"):
            raise RuntimeError("У бота налаштовано webhook. Вимкніть його перед polling-запуском.")
        LOGGER.info("Starting @%s", identity["username"])
        worker = threading.Thread(
            target=delivery_worker, args=(api, database, loader, instructions, stop), daemon=True
        )
        worker.start()
        shop = Shop(api, store, loader, instructions)
        while True:
            try:
                updates = api.call("getUpdates", {
                    "offset": store.get_offset(), "timeout": 20, "limit": 1,
                    "allowed_updates": ["message", "callback_query", "pre_checkout_query"],
                }, timeout=27)
            except TelegramError as error:
                if error.code in (401, 409):
                    raise
                LOGGER.warning("Polling failed: %s", error)
                time.sleep(max(2, min(error.retry_after, 60)))
                continue
            # Answer time-sensitive checkout requests before slower menu messages.
            for update in updates:
                if "pre_checkout_query" not in update or store.is_update_handled(update["update_id"]):
                    continue
                try:
                    shop.checkout(update["pre_checkout_query"])
                    store.mark_update_handled(update["update_id"])
                except TelegramError as error:
                    if error.code == 400 and any(
                        phrase in str(error).lower() for phrase in ("query is too old", "query_id_invalid")
                    ):
                        store.mark_update_handled(update["update_id"])
                    else:
                        LOGGER.warning("Checkout response will be retried: %s", error)
            for update in sorted(updates, key=lambda item: item["update_id"]):
                try:
                    if not store.is_update_handled(update["update_id"]):
                        if "pre_checkout_query" in update:
                            break
                        shop.update(update)
                        store.mark_update_handled(update["update_id"])
                except StoreError as error:
                    LOGGER.error("Rejected update %s: %s", update["update_id"], error)
                    store.mark_update_handled(update["update_id"])
                except TelegramError as error:
                    LOGGER.warning("Update %s will be retried: %s", update["update_id"], error)
                    time.sleep(max(1, min(error.retry_after, 15)))
                    break
                store.set_offset(update["update_id"] + 1)
    finally:
        stop.set()
        if worker:
            worker.join(timeout=35)
        store.close()


def main():
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure") and not stream.isatty():
            stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    arguments = argparse.ArgumentParser(description=__doc__)
    arguments.add_argument("--check", action="store_true", help="Перевірити локальні файли без Telegram.")
    options = arguments.parse_args()
    load_env(ROOT / ".env")
    loader = ROOT / "delivery" / "PythonLoaderDC.py"
    if not loader.is_file():
        raise RuntimeError("Бракує delivery/PythonLoaderDC.py.")
    instructions = {}
    for language, filename in (("uk", "START.txt"), ("ru", "START.ru.txt")):
        instructions[language] = (ROOT / "delivery" / filename).read_text(encoding="utf-8-sig").strip()
        if not 1 <= len(instructions[language]) <= 4096:
            raise RuntimeError(f"{filename} повинна містити від 1 до 4096 символів.")
    promo_codes = os.environ.get("PROMO_CODES", "").split(",")
    if options.check:
        compile(loader.read_text(encoding="utf-8-sig"), str(loader), "exec")
        print(f"Файли готові. Ціна {BASE_STARS} XTR, зі знижкою {PROMO_STARS} XTR.")
        return
    api = Telegram(os.environ.get("BOT_TOKEN"), os.environ.get("BOT_TEST_MODE") == "1")
    configured_db = os.environ.get("SHOP_DB")
    database = Path(configured_db) if configured_db else ROOT / "data" / "shop.sqlite3"
    if not database.is_absolute():
        database = ROOT / database
    database = database.expanduser().resolve()
    with single_instance(database):
        run(api, database, loader, instructions, promo_codes)


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    try:
        main()
    except KeyboardInterrupt:
        LOGGER.info("Stopped.")
    except (ValueError, RuntimeError, TelegramError, OSError) as failure:
        LOGGER.error("%s", failure)
        raise SystemExit(1) from None
