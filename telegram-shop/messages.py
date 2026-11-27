"""Short Ukrainian and Russian copy for the Telegram shop."""

LABELS = {
    "uk": {
        "buy": "🛒 Купити",
        "promo": "🎟 Промокод",
        "pay": "⭐ Оплата",
        "download": "📥 Моя покупка",
        "language": "🌐 Мова",
    },
    "ru": {
        "buy": "🛒 Купить",
        "promo": "🎟 Промокод",
        "pay": "⭐ Оплата",
        "download": "📥 Моя покупка",
        "language": "🌐 Язык",
    },
}

LANGUAGE_PICKER = {
    "inline_keyboard": [[
        {"text": "Українська 🇺🇦", "callback_data": "language:uk"},
        {"text": "Русский 🇷🇺", "callback_data": "language:ru"},
    ]]
}

TEXT = {
    "uk": {
        "product": (
            "Dash Client • {price} XTR\n"
            "Лоадер та інструкція для одиночної гри.\n\n"
            "1. Введи промокод, якщо є.\n"
            "2. Натисни «Оплата».\n"
            "3. Отримай файли."
        ),
        "terms": (
            "1. Оплата в Telegram Stars. Ціна — {base_price} XTR, зі знижкою — {promo_price} XTR.\n"
            "2. Лоадер для Windows x64, Java 25 та Minecraft Java 1.21.11. Одиночний режим. "
            "Копія безстрокова, JAR і код публічні.\n"
            "3. Файл після оплати, повторна видача через /download. Повернення й підтримка — /paysupport. "
            "Бот зберігає ID та дані покупки."
        ),
        "agree": "Приймаю умови",
        "invoice_title": "Dash Client — безстрокова копія",
        "invoice_description": "Лоадер та інструкція. Одиночна гра. Замовлення {order}.",
        "already_owned": "Покупка доступна. Натисни «Моя покупка».",
        "redelivery": "Надсилаю комплект.",
        "admin": "Адміністратор • 0 XTR. Надсилаю комплект.",
        "not_owned": "Спочатку натисни «Оплата».",
        "promo_hint": 'Введи /promo "код" або надішли свій код.',
        "promo_invalid": "Промокод недійсний. Спробуй ще раз.",
        "promo_applied": "Знижку 15% застосовано. До оплати {price} XTR.",
        "promo_cancelled": "Введення промокоду скасовано.",
        "choose_language": "Обери мову / Выбери язык",
        "language_changed": "Мова — українська 🇺🇦",
        "menu_hint": "Обери дію в меню.",
        "support": "Питання щодо оплати й повернень — адміністратору.",
        "contact_admin": "Написати адміністратору",
        "admin_only": "Команда для адміністратора.",
        "refund_missing": "Вкажи номер оплаченого замовлення після /refund.",
        "refund_done": "Повернення за замовленням {order} підтверджено.",
        "refund_already": "Замовлення {order} вже повернено.",
        "delivery_caption": "Твій лоадер Dash Client.",
        "invoice_owner": "Рахунок недійсний. Створи новий через /pay.",
        "invoice_amount": "Сума рахунку не збігається. Створи новий через /pay.",
        "invoice_expired": "Рахунок застарів або вже обробляється.",
        "payment_pending": "Оплата ще обробляється. Спробуй згодом.",
    },
    "ru": {
        "product": (
            "Dash Client • {price} XTR\n"
            "Лоадер и инструкция для одиночной игры.\n\n"
            "1. Введи промокод, если есть.\n"
            "2. Нажми «Оплата».\n"
            "3. Получи файлы."
        ),
        "terms": (
            "1. Оплата в Telegram Stars. Цена — {base_price} XTR, со скидкой — {promo_price} XTR.\n"
            "2. Лоадер для Windows x64, Java 25 и Minecraft Java 1.21.11. Одиночный режим. "
            "Копия бессрочная, JAR и код публичные.\n"
            "3. Файл после оплаты, повторная выдача через /download. Возвраты и поддержка — /paysupport. "
            "Бот хранит ID и данные покупки."
        ),
        "agree": "Принимаю условия",
        "invoice_title": "Dash Client — бессрочная копия",
        "invoice_description": "Лоадер и инструкция. Одиночная игра. Заказ {order}.",
        "already_owned": "Покупка доступна. Нажми «Моя покупка».",
        "redelivery": "Отправляю комплект.",
        "admin": "Администратор • 0 XTR. Отправляю комплект.",
        "not_owned": "Сначала нажми «Оплата».",
        "promo_hint": 'Введи /promo "код" или пришли свой код.',
        "promo_invalid": "Промокод недействителен. Попробуй ещё раз.",
        "promo_applied": "Скидка 15% применена. К оплате {price} XTR.",
        "promo_cancelled": "Ввод промокода отменён.",
        "choose_language": "Обери мову / Выбери язык",
        "language_changed": "Язык — русский 🇷🇺",
        "menu_hint": "Выбери действие в меню.",
        "support": "Вопросы об оплате и возвратах — администратору.",
        "contact_admin": "Написать администратору",
        "admin_only": "Команда для администратора.",
        "refund_missing": "Укажи номер оплаченного заказа после /refund.",
        "refund_done": "Возврат по заказу {order} подтверждён.",
        "refund_already": "Заказ {order} уже возвращён.",
        "delivery_caption": "Твой лоадер Dash Client.",
        "invoice_owner": "Счёт недействителен. Создай новый через /pay.",
        "invoice_amount": "Сумма счёта не совпадает. Создай новый через /pay.",
        "invoice_expired": "Счёт устарел или уже обрабатывается.",
        "payment_pending": "Оплата ещё обрабатывается. Попробуй позже.",
    },
}


def translate(language, key, **values):
    return TEXT.get(language, TEXT["uk"])[key].format(**values)


def menu(language):
    labels = LABELS.get(language, LABELS["uk"])
    rows = (("buy", "promo"), ("pay", "download"), ("language",))
    return {
        "keyboard": [[{"text": labels[key]} for key in row] for row in rows],
        "resize_keyboard": True,
        "is_persistent": True,
    }


def button_action(text):
    for labels in LABELS.values():
        for action, label in labels.items():
            if text == label:
                return action
    return None
