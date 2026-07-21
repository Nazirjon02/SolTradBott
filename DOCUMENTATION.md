# DRX (SolTradBot) — Полная документация на русском

> Крипто-торговый бот для **Solana DEX** (мемкоины, спот, только LONG), написанный на **Kotlin Multiplatform** по архитектуре проекта MRX. Один общий код работает как backend-сервер (JVM), desktop-приложение (Compose Desktop) и Android-приложение. Управление — через Telegram-бот, REST API и UI.

---

## Оглавление

1. [Что это за проект](#1-что-это-за-проект)
2. [Архитектура проекта](#2-архитектура-проекта)
3. [Торговые стратегии](#3-торговые-стратегии)
4. [Режимы DEMO / REAL / «только сигнал»](#4-режимы-demo--real--только-сигнал)
5. [Вход и выход из позиции](#5-вход-и-выход-из-позиции)
6. [Переменные окружения (конфигурация)](#6-переменные-окружения-конфигурация)
7. [Установка и запуск](#7-установка-и-запуск)
8. [Telegram — команды и кнопки](#8-telegram--команды-и-кнопки)
9. [REST API](#9-rest-api)
10. [База данных](#10-база-данных)
11. [Риск-менеджмент](#11-риск-менеджмент)
12. [Деплой на сервер (VPS/Linux/Docker)](#12-деплой-на-сервер-vpslinuxdocker)
13. [Безопасность](#13-безопасность)
14. [Решение проблем (FAQ)](#14-решение-проблем-faq)

---

## 1. Что это за проект

**DRX** — автоматический торговый робот для **мемкоинов на Solana DEX**. Он сам сканирует новые токены (DexScreener), фильтрует скам (RugCheck), анализирует график (GeckoTerminal OHLCV), принимает решения по стратегиям, покупает через **Jupiter** (или виртуально в DEMO), сопровождает позицию (SL/TP/trailing) и считает статистику.

### Что бот умеет

- **Сканировать новые мемкоины** — DexScreener boosts/profiles + фильтры (ликвидность, MC, возраст, объём, давление покупок) + RugCheck.
- **Торговать на DEX** — свопы SOL→token→SOL через Jupiter Aggregator (REAL) или paper-trading (DEMO, счёт $10 000).
- **Запускать до 3 стратегий параллельно** — каждая со своими фильтрами и параметрами выхода.
- **Сопровождать позиции** — Stop Loss, Take Profit, частичные фиксации, trailing, break-even, time-stop и **ликвидность-стоп** (защита от rug pull).
- **Управлять рисками** — лимит дневного убытка, максимальная просадка, лимит позиций.
- **Управление через Telegram** — старт/стоп/пауза, баланс, позиции, статистика, стратегии, сканер, режим DEMO/REAL.
- **REST API + WebSocket** — для интеграции с UI и внешними системами.
- **Desktop/Android-дашборд (Compose Multiplatform)** — equity-кривая, история, аналитика, редактор стратегий.
- **SQLite-хранилище** — стратегии, сделки, кеши, настройки.

### Где бот живёт

Бот построен как **сервер на JVM**. Его можно запускать:
- локально на ПК (Windows / macOS / Linux) — `start.bat` / `./gradlew :server:run`;
- на VPS (рекомендуется) — чтобы работал 24/7;
- внутри Desktop-приложения (движок встроен в UI — для тестов).

---

## 2. Архитектура проекта

Gradle-мультимодульный Kotlin Multiplatform (структура MRX):

```
SolTradBott/
├── shared/         ← Общая бизнес-логика + Compose UI (KMP: JVM + Android)
├── server/         ← Ktor HTTP-сервер на JVM (порт 8080) — главный торговый процесс
├── desktopApp/     ← Compose Desktop UI (Windows/macOS/Linux)
├── androidApp/     ← Android-приложение
├── gradle/         ← Версии зависимостей (libs.versions.toml)
└── setup.bat / start.bat / desktop.bat / Dockerfile / docker-compose.yml
```

### Что внутри `shared/`

| Пакет | За что отвечает |
|-------|-----------------|
| `core/engine/BotEngine` | Главный движок: старт/стоп/пауза, до 3 стратегий параллельно, статистика, closeAll. |
| `core/engine/TradeMonitor` | Сопровождение позиций: SL/TP/partial/trailing/break-even/time-stop/liquidity-stop. |
| `core/engine/TradeExecutor` | Исполнение: DEMO (виртуальный счёт) / REAL (Jupiter quote→swap→sign→confirm). |
| `core/engine/ActivityLog` | Живая лента событий для Dashboard и пульс запросов. |
| `core/strategy/` | `Strategy`-интерфейс, `StrategyManager`, фабрика + 3 стратегии. |
| `core/risk/RiskManager` | Дневной убыток, просадка, лимит позиций, размер позиции. |
| `core/Indicators` | EMA, RSI, MACD, Bollinger, ATR (порт из MRX). |
| `exchange/dex/DexClient` | Фасад: DexScreener + Jupiter + GeckoTerminal + RugCheck + Solana RPC. |
| `exchange/dex/TokenScanner` | Скан новых мемкоинов + фильтры + скоринг → TokenCache. |
| `exchange/dex/TokenCache`, `AccountCache` | Кеши в БД (кандидаты сканера, балансы). |
| `domain/dars/` | Движок методики «Dars»: импульс/коррекция, ложный пробой, треугольник, уровни. |
| `telegram/` | `TelegramBotController` (long-polling, inline-меню) + `TelegramNotifier` (алерты). |
| `data/` | Модели, `SettingsStore` (key/value в БД), `DatabaseDriverFactory` (SQLDelight). |
| `crypto/` | Ed25519-подпись транзакций, Base58, seed-фраза → Signer. |
| `DrxRuntime` | Сборка всего стека в один объект (UI и Android-сервис делят один движок). |

---

## 3. Торговые стратегии

При первом запуске бот создаёт в БД **3 стратегии** (`SeedData.kt`). Включена по умолчанию только первая.

### 3.1 Dars / Smart Money — сканер мемкоинов *(включена по умолчанию)*

Основная стратегия по торговому курсу «Dars», адаптированная под мемкоины:
- тренд старшего ТФ (15m) + уровни (крупный бар, разворотная точка, зеркальный);
- сетапы: импульс/коррекция (Урок 1), ложный пробой (Урок 3), треугольник (Урок 5);
- «не покупаем у сопротивления» (Урок 4);
- вход только в LONG (бот спотовый).

| Параметр | Значение |
|----------|----------|
| Таймфрейм | 1m (тренд — 15m) |
| Размер позиции | 5% от баланса |
| Stop Loss | 15% |
| Take Profit | 30% (RR ≈ 2) |
| Фильтры | LIQ ≥ $10K, MC $50K–$10M, возраст 30м–30д, объём 1ч ≥ $5K, RugCheck ≤ 5000 |

### 3.2 Momentum Scalping — свежие мемкоины

Быстрый вход по всплеску объёма (≥2× среднего), давлению покупок (buys/sells ≥ 1.3) и импульсу за 5 минут. Trailing + break-even включены, time-stop 60 минут.

### 3.3 RSI + EMA — мемкоины с историей

Классика (порт из MRX): выход RSI из перепроданности + подтверждение EMA9>EMA21. Фильтры настроены на «взрослые» токены (старше недели, LIQ ≥ $50K).

> Все параметры каждой стратегии редактируются в UI (вкладка «Стратегии» → ✏️) или прямо в БД.

---

## 4. Режимы DEMO / REAL / «только сигнал»

| Режим | Что происходит | Как включить |
|-------|----------------|--------------|
| **DEMO** (по умолчанию) | Сделки виртуальные, счёт $10 000, PnL считается по реальным ценам DexScreener. | `DEMO_MODE=true`, Настройки → «Режим DEMO», Telegram `/mode` |
| **REAL** | Реальные свопы через Jupiter с вашего кошелька (нужна seed-фраза). | Настройки → выключить DEMO, Telegram `/mode` |
| **Только сигнал** | Сделки не открываются вообще — бот шлёт алерт с параметрами входа (цена, SL, TP, mint). | Настройки → «Только сигнал», Telegram `/signalonly` |

**Рекомендация: минимум 1–2 недели на DEMO**, потом REAL с маленьким размером позиции.

Переключатель DEMO/REAL хранится в БД и применяется мгновенно — перезапуск не нужен.

---

## 5. Вход и выход из позиции

### Вход (pipeline)

1. `TokenScanner` раз в ~30с отдаёт кандидатов (DexScreener → фильтры стратегии → RugCheck → скоринг). Кеш TTL 5 минут.
2. Стратегия анализирует свечи GeckoTerminal → сигнал BUY + уверенность (нужно ≥ 60%).
3. `RiskManager.canTrade()` — дневной убыток, просадка, лимит позиций.
4. Размер: `balance × positionSize%`.
5. Исполнение: DEMO → виртуально; REAL → Jupiter quote → swap → подпись → отправка → подтверждение.
6. Запись в БД + алерт в Telegram + запись в ленту активности. Дальше кулдаун (по умолчанию 300с).

### Выход — TradeMonitor (каждые 15 секунд, работает даже на паузе)

Проверки в порядке приоритета:
1. **Ликвидность-стоп** — пул осушён на X% от входа (дефолт 50%) → экстренный выход (rug pull).
2. **Stop Loss** — жёсткий % от входа (или подтянутый trailing'ом/break-even'ом).
3. **Take Profit** — основной, закрывает всё.
4. **Partial TP1/TP2** — частичные фиксации долей позиции.
5. **Break-even** — после X% прибыли SL переносится в безубыток+offset.
6. **Trailing** — после активации SL тянется за пиком цены.
7. **Time-stop** — принудительный выход по времени (если включён).

Закрытие: REAL → своп token→SOL (slippage 3% — важнее выйти); DEMO → виртуально. PnL — в БД, алерт — в Telegram.

---

## 6. Переменные окружения (конфигурация)

Приоритет: **переменная окружения > `.env` файл > дефолт**. Часть настроек дублируется в БД (`SettingsStore`) и меняется в рантайме из UI/Telegram.

| Переменная | По умолчанию | Описание |
|------------|--------------|----------|
| `DEMO_MODE` | `true` | `true` = paper-trading. **Оставь true, пока не уверен!** |
| `SOLANA_WALLET_SEED` | `""` | Seed-фраза кошелька (только для REAL). Можно задать в UI. |
| `SOLANA_RPC_URL` | mainnet-beta | RPC-эндпоинт (для прода лучше Helius/QuickNode). |
| `TG_BOT_TOKEN` | `""` | Токен Telegram-бота. Пусто — Telegram не запускается. |
| `TG_CHAT_ID` | `0` | Твой Telegram chat_id (бот отвечает только ему). |
| `PORT` | `8080` | Порт HTTP-сервера. |
| `SOLTRAD_API_KEY` | `soltrad-secret` | Bearer-токен REST API. **Поменяй на проде!** |
| `DB_PATH` | `soltradbot.db` | Путь к SQLite. Сервер и Desktop должны указывать одинаковый, чтобы делить базу. |

---

## 7. Установка и запуск

### Требования

- **JDK 17+** (https://adoptium.net). Проверить: `java -version`.
- Исходящий доступ к `api.dexscreener.com`, `api.geckoterminal.com`, `*.jup.ag`, `api.rugcheck.xyz`, Solana RPC и `api.telegram.org`.

### Windows (самый простой путь)

1. Дабл-клик **`setup.bat`** → заполни `.env` в Блокноте, сохрани.
2. Дабл-клик **`start.bat`** → сервер запущен. Управляй из Telegram.
3. Опц.: **`desktop.bat`** → десктоп-дашборд.

### Терминал (Linux/macOS)

```bash
cp .env.example .env && nano .env   # заполнить
./gradlew :server:run               # сервер (бот)
./gradlew :desktopApp:run           # десктоп UI
./gradlew :androidApp:assembleDebug # APK → androidApp/build/outputs/apk/debug/
```

### Что происходит при старте сервера

1. Открывается SQLite по `DB_PATH`, сеются 3 дефолтные стратегии.
2. Собирается стек: DexClient, кеши, RiskManager, TradeExecutor, StrategyManager, BotEngine.
3. Запускается TradeMonitor (сопровождение позиций).
4. Если задан `TG_BOT_TOKEN` — стартует Telegram long-polling.
5. Поднимается Ktor-сервер на `PORT`.

> ⚠️ **Сразу после старта бот НЕ торгует.** Нажми «🟢 Старт» в Telegram/UI или `POST /api/v1/bot/start`.

---

## 8. Telegram — команды и кнопки

Бот отвечает **только** на сообщения от `TG_CHAT_ID`. Чужие чаты игнорируются молча (не выдаём существование бота и не даём спамить). Список команд регистрируется автоматически (`setMyCommands`), поэтому в Telegram доступна кнопка-меню и автодополнение.

| Команда | Что делает |
|---------|------------|
| `/start`, `/menu` | Главное меню с inline-кнопками. |
| `/status` | Статус, режим, аптайм, P&L дня, открытые позиции. |
| `/stop` / `/pause` / `/resume` | Управление движком (позиции при стопе НЕ закрываются). |
| `/stats` | Общая статистика: сделки, winrate, P&L. |
| `/positions` | Открытые позиции с PnL. |
| `/balance` | Баланс: DEMO-счёт или SOL кошелька. |
| `/closeall` | 🚨 Закрыть все позиции немедленно. |
| `/strategies` | Меню стратегий — вкл/выкл кнопкой (✅/⭕). |
| `/report` | Ежедневный отчёт: winrate, profit factor, лучшая/худшая сделка. |
| `/scanner` | 🔍 Текущие кандидаты сканера (DEX-специфика). |
| `/mode` | 🎮 Меню режима DEMO/REAL (переключение кнопкой). |
| `/signalonly` | 📣 Меню режима «только сигнал» (вкл/выкл кнопкой). |
| `/help` | ℹ️ Список всех команд. |

Inline-меню после `/menu`:

```
🟢 Старт          🔴 Стоп
⏸ Пауза          ▶️ Продолжить
📊 Статус         💰 Баланс
📈 Позиции        📉 Статистика
⚙️ Стратегии      📋 Отчёт
🔍 Сканер         🎮 Режим
📣 Только сигнал  🚨 Закрыть всё
```

---

## 9. REST API

База: `http://<host>:8080`. Аутентификация — **Bearer-токен**: `Authorization: Bearer $SOLTRAD_API_KEY`. Без авторизации — только `/health`.

| Метод | Путь | Описание |
|-------|------|----------|
| `GET` | `/health` | `{"status":"ok","bot":"DRX"}` |
| `POST` | `/api/v1/bot/start` `/stop` `/pause` `/resume` | Управление движком. |
| `GET` | `/api/v1/bot/status` | `{"status":"RUNNING","mode":"DEMO"}` |
| `GET` | `/api/v1/stats` | Статистика. |
| `GET` | `/api/v1/positions` | Открытые позиции с PnL. |
| `POST` | `/api/v1/positions/closeall` | Закрыть все позиции. |
| `GET` | `/api/v1/balance` | Баланс (DEMO/SOL). |
| `GET` | `/api/v1/strategies` | Список стратегий. |
| `GET` | `/api/v1/trades` | Количество сделок. |
| `GET` | `/api/v1/tokens/scanner` | Кандидаты сканера из кеша. |
| `POST` | `/api/v1/mode/demo` `/mode/real` | Переключение DEMO/REAL. |
| `WS` | `/ws` | Пуш смены статуса: `{"type":"status","value":"RUNNING"}`. Требует тот же Bearer-токен. |

```bash
curl -X POST http://localhost:8080/api/v1/bot/start -H "Authorization: Bearer soltrad-secret"
curl http://localhost:8080/api/v1/bot/status -H "Authorization: Bearer soltrad-secret"
```

---

## 10. База данных

Файл: `soltradbot.db` (по `DB_PATH`), формат — SQLite (SQLDelight, схемы в `shared/src/commonMain/sqldelight/`).

| Таблица | Содержимое |
|---------|------------|
| `strategy` | Конфиги стратегий (фильтры, SL/TP, dars-параметры). |
| `trade` | Журнал сделок: mint, вход/выход, qty, PnL, причина выхода, is_demo, peak_price. |
| `account_cache` | Кеш балансов: SOL, DEMO_USD. |
| `token_cache` | Кандидаты сканера с метриками (TTL). |
| `settings` | Key/value рантайм-настройки: telegram, demo_mode, seed, RPC. |

```sql
-- Все стратегии
SELECT id, name, is_active, timeframe FROM strategy;
-- Включить стратегию
UPDATE strategy SET is_active = 1 WHERE id = 'momentum-scalp';
-- Последние 10 сделок
SELECT symbol, pnl, close_reason FROM trade ORDER BY opened_at DESC LIMIT 10;
```

**Сброс**: удали файл БД — при следующем запуске пересоздастся с дефолтами. Или «🚨 Сброс бота» в настройках UI (стирает сделки/кеши, стратегии оставляет).

---

## 11. Риск-менеджмент

Перед каждым входом `RiskManager.canTrade()` проверяет:

1. **Дневной убыток** — если P&L за сегодня хуже `-(balance × maxDailyLoss%)` — вход блокируется до следующего дня.
2. **Максимальная просадка** — худшая закрытая сделка **за последние 24 часа** хуже `-maxDrawdown%` — блокировка. Окно скользящее: через сутки лимит сам отпускает.
   > ⚠️ `maxDrawdown` обязан быть заметно **больше** `stopLossPercent`, иначе штатный выход по стоп-лоссу сам упирается в лимит и глушит стратегию. Дефолт — 30% при SL 15%.
3. **Лимит позиций** — не больше `maxPositions` открытых по стратегии.

Размер позиции: `usdAmount = balance × positionSize / 100` (спот, плеча нет). Баланс из кеша (DEMO-счёт или SOL×цена).

Плюс DEX-специфика: RugCheck-фильтр на входе, ликвидность-стоп и time-stop на выходе, slippage-лимит в свопах.

---

## 12. Деплой на сервер (VPS/Linux/Docker)

### Вариант A: Docker (проще всего)

```bash
git clone <репозиторий> drx && cd drx
nano docker-compose.yml   # заполнить TG_BOT_TOKEN, TG_CHAT_ID, SOLTRAD_API_KEY
docker compose up -d --build
docker compose logs -f    # логи
```

БД хранится в `./data/soltradbot.db` (volume).

### Вариант B: systemd

```bash
sudo apt update && sudo apt install -y openjdk-17-jdk git
cd /opt && sudo git clone <репозиторий> drx && cd drx
./gradlew :server:installDist
```

`/etc/systemd/system/drx.service`:

```ini
[Unit]
Description=DRX Trading Bot
After=network.target

[Service]
Type=simple
User=drx
WorkingDirectory=/var/lib/drx
Environment="DEMO_MODE=true"
Environment="TG_BOT_TOKEN=..."
Environment="TG_CHAT_ID=..."
Environment="SOLTRAD_API_KEY=..."
Environment="DB_PATH=/var/lib/drx/soltradbot.db"
ExecStart=/opt/drx/server/build/install/server/bin/server
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

```bash
sudo useradd -r -d /var/lib/drx -s /usr/sbin/nologin drx
sudo mkdir -p /var/lib/drx && sudo chown drx:drx /var/lib/drx
sudo systemctl daemon-reload && sudo systemctl enable --now drx
sudo journalctl -u drx -f
```

Не выставляй порт 8080 голым в интернет — прокси через Nginx+HTTPS, если нужен внешний доступ.

---

## 13. Безопасность

1. **Никогда не коммить `.env` и seed-фразу в Git** — уже в `.gitignore`.
2. **Seed-фраза** хранится локально (env или SQLite). Заведи **отдельный кошелёк для бота** с небольшой суммой — не основной!
3. **Поменяй `SOLTRAD_API_KEY`** — дефолтный `soltrad-secret` в проде нельзя.
4. **Один `TG_CHAT_ID`** — чужой аккаунт не сможет управлять ботом.
5. **Сначала DEMO, потом REAL.** Минимум 1–2 недели наблюдений.
6. **Мемкоины — экстремально рискованный рынок.** Rug pull, honeypot и осушение ликвидности случаются постоянно; RugCheck и ликвидность-стоп снижают риск, но не убирают его.

---

## 14. Решение проблем (FAQ)

**Q: `./gradlew` падает с `Could not find Java X`**
A: Установи JDK 17 и пропиши `JAVA_HOME`.

**Q: Telegram-бот молчит**
A: Проверь `TG_BOT_TOKEN`/`TG_CHAT_ID`. Напиши боту `/start` из правильного аккаунта. Убедись, что long-polling не запущен дважды (сервер + desktop с одним токеном конфликтуют — Telegram отдаёт update только одному).

**Q: Бот не отвечает на команды (хотя нотификации приходят)**
A: `TG_CHAT_ID` не совпадает — бот принимает команды только с этого чата, чужие сообщения игнорирует молча. Узнай свой ID через @userinfobot.

**Q: Бот не торгует, хотя нажал Старт**
A: 1) Нет активных стратегий (`/strategies`). 2) Сканер не находит кандидатов — ослабь фильтры (ликвидность/возраст/объём). 3) Риск-лимит достигнут (см. ленту активности). 4) Кулдаун после сделки.

**Q: REAL-режим не включается**
A: Не задана seed-фраза. Настройки → Кошелёк Solana, или `SOLANA_WALLET_SEED` в `.env`.

**Q: Jupiter buy/sell падает**
A: 1) Недостаточно SOL (нужен запас на комиссии ~0.0003+). 2) Публичный RPC перегружен — поставь Helius/QuickNode. 3) Слишком маленькая сумма свопа. 4) Токен без маршрута — Jupiter не может обменять.

**Q: Как добавить свою стратегию?**
A: 1) Класс `MyStrategy : Strategy` в `shared/.../core/strategy/`. 2) Значение в `StrategyType`. 3) Ветка в `StrategyFactory`. 4) Запись в `SeedData.kt` или через UI.

**Q: Как использовать одну БД для сервера и десктопа?**
A: Одинаковый `DB_PATH`. Но **не запускай два движка одновременно** — будут дубли сделок.

**Q: Где история сделок?**
A: Таблица `trade`; в UI — вкладки История/Аналитика; в Telegram — `/stats`, `/report`.

---

**Удачной торговли! Автоматический бот не убирает риск — только эмоции. Параметры стратегий и режим REAL — твоя ответственность.**
