# SolTradBot 2.0 (DRX) — Техническое задание (ТЗ)

> ## ✅ Статус: РЕАЛИЗОВАНО (2026-07-06)
> Все 10 этапов выполнены, каждый — отдельным коммитом. Полная инструкция по запуску — в **[DOCUMENTATION.md](DOCUMENTATION.md)**.
>
> **Быстрый старт:** `setup.bat` → `start.bat` (сервер) → Telegram `/start` → «🟢 Старт». Desktop UI — `desktop.bat`.
>
> Решения по разделу 11 (одобрены):
> 1. Сначала DEMO (paper-trading), REAL-исполнение через Jupiter — последним этапом. В настройках — **переключатель DEMO/REAL**, которым пользователь управляет режимом.
> 2. Старая файловая история — выбрасывается, старт с чистой SQLite-БД.
> 3. Название в UI: **DRX**.
>
> **Цель:** полностью перестроить SolTradBot по образцу проекта **MRX** — архитектура модулей, дизайн UI, сервер, база данных, кеширование, Telegram-управление, логика входа/выхода. **Единственное отличие от MRX:** SolTradBot торгует **только на Solana DEX мемкоинами** (спот, без плеча и шортов), а не фьючерсами Bybit.

---

## 1. Общая идея

| | MRX (образец) | SolTradBot 2.0 (цель) |
|---|---|---|
| Рынок | Bybit USDT-Perpetual (фьючерсы) | Solana DEX — мемкоины (спот) |
| Биржевой клиент | `BybitClient` (REST+WS, HMAC) | `DexClient`: DexScreener + Jupiter + GeckoTerminal + Solana RPC |
| Направление | LONG + SHORT, плечо 1–125x | Только LONG (покупка токена за SOL/USDC), без плеча |
| Модули | shared / server / desktopApp / androidApp / iosApp | shared / server / desktopApp / androidApp (iOS — не делаем) |
| БД | SQLDelight (SQLite): Strategy, Trade, AccountCache, Report, Settings, OptimizationResult | То же самое + TokenCache (кеш сканера токенов) |
| Сервер | Ktor: REST API v1 + WebSocket + Bearer-auth | Идентично |
| Telegram | TelegramBotController (long-polling, inline-меню) + TelegramNotifier | Идентично (переиспользуем существующий `bot/` код, меню приводим к MRX) |
| UI | Compose Multiplatform, тёмная тема, 5 экранов | Идентичный дизайн и палитра |
| Риск | RiskManager: дневной убыток, просадка, лимит позиций | Идентично |
| Выход из позиции | TradeMonitor: SL/TP/partial TP/trailing/break-even | Идентично, цена — с DexScreener |
| Демо-режим | нет | **Сохраняем** DemoAccountManager (paper-trading) — преимущество SolTradBot |

Сервер — главный процесс (торгует 24/7 на VPS), Desktop/Android — дашборды и пульт управления, Telegram — удалённое управление.

---

## 2. Структура модулей (как в MRX)

Текущий единственный модуль `composeApp` разбивается на:

```
SolTradBott/
├── shared/                    ← ВСЯ бизнес-логика + Compose UI (KMP: JVM + Android)
│   └── src/commonMain/kotlin/tj/khujand/solana/trading/bot/
│       ├── App.kt                        ← Корневой Compose UI (5 экранов, палитра MRX)
│       ├── SeedData.kt                   ← Дефолтные стратегии при первом запуске
│       ├── core/
│       │   ├── engine/BotEngine.kt       ← старт/стоп/пауза, параллельные стратегии (до 3)
│       │   ├── engine/TradeMonitor.kt    ← мониторинг открытых позиций: SL/TP/trailing → закрытие
│       │   ├── engine/ActivityLog.kt     ← лента событий (для Dashboard и /status)
│       │   ├── risk/RiskManager.kt       ← дневной убыток, просадка, лимит позиций, размер позиции
│       │   ├── strategy/                 ← Strategy-интерфейс, StrategyManager, StrategyFactory + стратегии
│       │   │   └── levels/LevelDetector.kt  ← 3 типа уровней (перенос из domain/dars)
│       │   └── Indicators.kt             ← EMA, RSI, MACD, Bollinger, ATR (порт из MRX)
│       ├── exchange/dex/
│       │   ├── DexClient.kt              ← единый фасад (аналог BybitClient)
│       │   ├── DexScreenerApi.kt         ← цены, пары, поиск новых токенов (перенос)
│       │   ├── JupiterApi.kt             ← котировки и свопы (перенос)
│       │   ├── GeckoTerminalApi.kt       ← OHLCV-свечи (перенос)
│       │   ├── SolanaRpcClient.kt        ← баланс, отправка транзакций (перенос)
│       │   ├── TokenScanner.kt           ← скан новых мемкоинов + фильтры (из TokenMonitor)
│       │   └── TokenCache.kt             ← кеш топ-токенов в БД (аналог TopSymbolCache)
│       ├── telegram/
│       │   ├── TelegramBotController.kt  ← long-polling, inline-меню (рефакторинг bot/)
│       │   └── TelegramNotifier.kt       ← алерты о сделках
│       ├── data/
│       │   ├── Models.kt                 ← общие модели
│       │   ├── SettingsStore.kt          ← рантайм-настройки в БД
│       │   └── DatabaseDriverFactory.kt  ← expect/actual SQLDelight-драйвер
│       ├── crypto/                       ← Ed25519-подпись, Base58, транзакции (перенос)
│       └── demo/DemoAccountManager.kt    ← paper-trading (перенос)
│   └── src/commonMain/sqldelight/.../db/
│       ├── Strategy.sq  Trade.sq  AccountCache.sq  TokenCache.sq  Report.sq  Settings.sq
├── server/                    ← Ktor-сервер (JVM, порт 8080) — ГЛАВНЫЙ торговый процесс
│   └── src/main/kotlin/.../Application.kt + Config.kt
├── desktopApp/                ← Compose Desktop (окно = shared App.kt)
├── androidApp/                ← Android-приложение (= shared App.kt)
├── setup.bat / start.bat / desktop.bat   ← как в MRX
├── Dockerfile / docker-compose.yml       ← как в MRX
├── .env.example                          ← шаблон конфига
└── gradle/libs.versions.toml
```

Пакет остаётся `tj.khujand.solana.trading.bot`. Версии стека — как в MRX: Kotlin 2.x, Compose Multiplatform, Ktor 3.x, SQLDelight 2.x, kotlinx-serialization/datetime/coroutines, Logback.

---

## 3. База данных (SQLDelight, вместо файловой персистенции)

Один SQLite-файл (`DB_PATH`, по умолчанию `soltradbot.db`), общий для сервера и Desktop.

| Таблица | Содержимое |
|---|---|
| `Strategy` | Конфиги стратегий: имя, тип, активность, размер позиции (% от баланса), SL/TP/partial TP/trailing/break-even, фильтры токенов (ликвидность, MC, возраст, объём), параметры Dars/индикаторов, режим (DEMO/REAL) |
| `Trade` | Журнал сделок: mint-адрес, символ, вход (цена/время/размер SOL и USD), выход, PnL, комиссии+slippage, причина выхода (TP/SL/TRAILING/MANUAL), стратегия, demo-флаг |
| `AccountCache` | Кеш баланса SOL + USDC (одна строка) — чтобы не дёргать RPC на каждый расчёт |
| `TokenCache` | Кеш результатов сканера DexScreener (топ-кандидаты с метриками, TTL) |
| `Report` | Агрегированные дневные отчёты |
| `Settings` | Рантайм-настройки: Telegram-токен/чат, ключи, флаги — переживают перезапуск, меняются из Telegram/UI без рестарта |

Миграция: текущие JSON/файловые истории (`TokenHistoryPersistence`) не переносятся — начинаем с чистой БД (как «сброс» в MRX: удалил файл — пересоздалось с дефолтами через `SeedData`).

---

## 4. Ядро: вход и выход (логика MRX, адаптированная под DEX)

### 4.1 BotEngine
- Статусы `RUNNING / PAUSED / STOPPED` в `StateFlow` (UI и WS подписаны).
- `start()` — запускает воркер на каждую активную стратегию (до 3 параллельно), каждая крутит цикл: скан кандидатов → анализ → сигнал.
- `pause()/resume()/stop()` — как в MRX; `stop()` позиции не закрывает, `closeAllPositions()` — закрывает всё немедленно.
- После старта сервер НЕ торгует сам — ждёт `/bot/start` (REST) или «🟢 Старт» в Telegram (как в MRX).

### 4.2 Вход в позицию (pipeline)
1. `TokenScanner` отдаёт кандидатов из `TokenCache` (свежие мемкоины с DexScreener, прошедшие фильтры: ликвидность, MC, возраст, объём, соотношение buy/sell).
2. Стратегия анализирует OHLCV (GeckoTerminal) → сигнал BUY + confidence.
3. `RiskManager.canTrade()` — дневной убыток, просадка, лимит открытых позиций.
4. Расчёт размера: `usdAmount = balance * positionSize / 100` (баланс из `AccountCache`).
5. Исполнение: **DEMO** → `DemoAccountManager`; **REAL** → котировка Jupiter → своп SOL→token (slippage и priority fee из настроек) → подтверждение транзакции через RPC.
6. Запись `Trade` (OPEN) + `TelegramNotifier` алерт + `ActivityLog`.

### 4.3 Выход из позиции — TradeMonitor (как в MRX)
Отдельный корутин-монитор, каждые N секунд (настройка) сверяет OPEN-сделки из БД с текущей ценой DexScreener и исполняет:
- **Stop Loss** — жёсткий % от входа;
- **Take Profit** — основной TP (100% позиции);
- **Partial TP** — TP1 закрывает X%, TP2 ещё Y%, остаток на основном TP;
- **Trailing Stop** — активируется после X% прибыли, тянется за ценой;
- **Break-even** — после X% прибыли SL переносится в безубыток + offset;
- **Time-stop** (DEX-специфика) — принудительный выход, если позиция висит дольше X минут без движения;
- **Ликвидность-стоп** (DEX-специфика) — экстренный выход при падении ликвидности пула ниже порога (защита от rug pull).

Закрытие: **REAL** → своп token→SOL через Jupiter; **DEMO** → демо-счёт. Затем `Trade` → CLOSED с реальным PnL (с учётом комиссий и slippage), уведомление в Telegram, дневная статистика для RiskManager.

### 4.4 Стратегии (Strategy-интерфейс + Factory, как в MRX)
1. **Dars / Smart Money** *(дефолт, активна)* — перенос существующего `domain/dars/` (импульс/коррекция, ложный пробой, треугольник, уровни `LevelDetector`) в формат `Strategy`-интерфейса MRX.
2. **Momentum Scalping** — быстрый вход по всплеску объёма и buy-pressure у свежих токенов, короткие TP/SL (аналог ScalpingStrategy).
3. **RSI + EMA** — порт из MRX на свечах GeckoTerminal (для «взрослых» мемкоинов с историей).

`SeedData.kt` создаёт все 3 при первом запуске, активна только Dars — как в MRX.

### 4.5 RiskManager (порт из MRX)
- Дневной лимит убытка (% от баланса) — блокирует входы до следующего дня.
- Максимальная просадка по стратегии — блокирует до ручного сброса.
- Лимит одновременных позиций (по стратегии и глобальный).
- Расчёт размера позиции — % от баланса.

---

## 5. Сервер (Ktor, как MRX Application.kt)

- `GET /health` — без авторизации: `{"status":"ok","bot":"SolTradBot"}`.
- Всё остальное — `Authorization: Bearer $SOLTRAD_API_KEY`, префикс `/api/v1`:

| Метод | Путь | Действие |
|---|---|---|
| POST | `/bot/start` `/bot/stop` `/bot/pause` `/bot/resume` | Управление движком |
| GET | `/bot/status` | RUNNING / PAUSED / STOPPED |
| GET | `/stats` | Статистика: сделки, winrate, PnL |
| GET | `/positions` | Открытые позиции с текущим PnL |
| POST | `/positions/closeall` | Закрыть все позиции |
| GET | `/balance` | Баланс SOL/USDC (из кеша, с форс-обновлением `?refresh=true`) |
| GET | `/strategies` | Список стратегий |
| GET | `/trades` | Журнал сделок |
| GET | `/tokens/scanner` | Текущие кандидаты сканера (из TokenCache) |
| WS | `/ws` | Пуш смены статуса `{"type":"status","value":"RUNNING"}` |

Конфиг через env vars (`Config.kt`, приоритет env > `.env` > дефолт):

```
SOLANA_PRIVATE_KEY=      # приватный ключ кошелька (Base58) — только для REAL-режима
SOLANA_RPC_URL=          # RPC-эндпоинт (дефолт mainnet-beta)
DEMO_MODE=true           # true = paper-trading, REAL только когда уверен
TG_BOT_TOKEN=            # Telegram-бот
TG_CHAT_ID=0             # единственный разрешённый chat_id
PORT=8080
SOLTRAD_API_KEY=soltrad-secret   # Bearer для REST — сменить на проде!
DB_PATH=soltradbot.db
```

---

## 6. Telegram (как MRX, переиспользуем существующий `bot/`)

Существующий Telegram-код SolTradBot (роутеры, DTO, HTTP-клиент) переносится в `shared/telegram/` и приводится к UX MRX:

- Доступ только с `TG_CHAT_ID` (уже есть `AdminAccessPolicy`).
- Команды: `/start /menu /status /stop /pause /resume /stats /positions /balance /closeall /strategies /report /scanner /mode /signalonly /help` (регистрируются в Telegram через `setMyCommands`).
- Inline-меню как в MRX: `🟢 Старт | 🔴 Стоп | ⏸ Пауза | ▶️ Продолжить | 📊 Статус | 💰 Баланс | 📈 Позиции | 📉 Статистика | ⚙️ Стратегии | 📋 Отчёт | 🚨 Закрыть всё` + DEX-специфика: `🔍 Сканер` (текущие кандидаты).
- Подменю «⚙️ Стратегии» — вкл/выкл каждой (✅/⭕), как в MRX.
- `TelegramNotifier`: алерты об открытии/закрытии (с PnL, причиной выхода), ошибки, дневной отчёт.

---

## 7. UI — дизайн 1-в-1 как MRX

### Палитра (точные цвета MRX)

```kotlin
BgDark        = Color(0xFF0A0E1A)   // фон
SurfaceCard   = Color(0xFF1A2235)   // карточки
Green         = Color(0xFF00D97E)   // прибыль / активно
Red           = Color(0xFFFF4757)   // убыток / стоп
Blue          = Color(0xFF4E9EFF)
Purple        = Color(0xFF9B7AFF)
Amber         = Color(0xFFF59E0B)
TextPrimary   = Color(0xFFEAF0FB)
TextSecondary = Color(0xFF6B7A99)
BorderColor   = Color(0xFF1E2D45)
```

### Экраны: `enum Screen { DASHBOARD, HISTORY, STATS, STRATEGIES, SETTINGS }`

Текущие 4 таба (Strategies/Portfolio/Statistics/Settings) заменяются на 5 экранов MRX с нижней навигацией:

1. **DASHBOARD** (структура MRX DashboardScreen):
   - Header: «SolTrad Bot» + PulseLine (точка коннекта, счётчик запросов, время последнего ответа DexScreener) + StatusChip;
   - BalanceCard — SOL/USDC баланс;
   - PnlChartCard — equity curve по сделкам;
   - StatsRow — сделки/winrate/PnL за день;
   - ControlButtons — Старт/Стоп/Пауза;
   - ActivityFeedCard — живая лента событий (скан, сигналы, входы, выходы);
   - Карточки стратегий (активность, символ/режим сканера, PnL, winrate);
   - Последние 3 сделки.
2. **HISTORY** — журнал сделок с фильтрами (как MRX HistoryScreen), карточка сделки: токен, вход→выход, PnL, причина выхода.
3. **STATS** — winrate, profit factor, средняя прибыль/убыток, распределение по стратегиям, лучшая/худшая сделка (как MRX StatsScreen).
4. **STRATEGIES** — список + **StrategyFormScreen** (полноэкранная форма как в MRX): основное, размер позиции, SL (+trailing, break-even), TP (+partial), фильтры токенов (ликвидность/MC/возраст/объём), параметры Dars/индикаторов, риск.
5. **SETTINGS** — как MRX SettingsScreen: кошелёк/RPC, URL сервера, Telegram, глобальный риск (дневной убыток, просадка, макс позиций, авто-стоп), управление ботом (интервалы, кулдаун), уведомления (по типам), DEMO/REAL переключатель, сброс бота.

Desktop и Android рендерят один и тот же `shared/App.kt` (как в MRX).

---

## 8. Кеширование (как MRX)

| Кеш | Аналог в MRX | Что хранит |
|---|---|---|
| `AccountCache` (БД) | AccountCache.sq | Баланс SOL/USDC, обновляется фоново, UI/RiskManager читают кеш |
| `TokenCache` (БД) | TopSymbolCache | Топ-кандидаты сканера с метриками, TTL ~1–5 мин |
| OHLCV in-memory | кеш свечей в BybitClient | Свечи GeckoTerminal на символ+таймфрейм, чтобы не упираться в rate-limit |
| `Settings` (БД) | Settings.sq | Рантайм-настройки, переживают перезапуск |

---

## 9. Что происходит с текущим кодом

| Сейчас | Станет |
|---|---|
| `composeApp` (один модуль) | 4 модуля: shared / server / desktopApp / androidApp |
| `TokenMonitor` (1957 строк — скан+решения+мониторинг в одном) | Разбивается: скан → `TokenScanner`, решения → стратегии, сопровождение позиций → `TradeMonitor` |
| `domain/dars/*` | Переезжает в `core/strategy/` как DarsStrategy (логика сохраняется) |
| `TradingRuntime`, `TradingEngineController` | Заменяются на `BotEngine` (модель MRX) |
| `DemoAccountManager`, `EquityManager` | Сохраняются (`demo/`), интегрируются с БД |
| `FilterSettingsManager`, `AppSettings` | Фильтры → в `StrategyConfig` (per-strategy), глобальное → `SettingsStore` (БД) |
| `TokenHistoryPersistence` (файлы) | Удаляется — всё в SQLDelight |
| `bot/` (Telegram) | Переезжает в `shared/telegram/`, меню приводится к MRX |
| UI-экраны (Strategies/Portfolio/Statistics/Settings + Theme/Components) | Полностью переписываются под 5 экранов и палитру MRX |
| `network/*Api` | Переезжают в `exchange/dex/` под фасад `DexClient` |
| `AiAnalyzer` | Сохраняется как опциональный фильтр confidence (выключен по умолчанию) |
| iOS-таргет | Не восстанавливаем (уже удалён) |

---

## 10. Этапы реализации (порядок работ)

1. **Каркас** — разбивка на модули shared/server/desktopApp/androidApp, gradle, libs.versions.toml, компиляция «пустого» каркаса.
2. **БД** — SQLDelight-схемы (6 таблиц), DatabaseDriverFactory (jvm/android), SeedData, SettingsStore.
3. **Exchange-слой** — перенос network/* в exchange/dex/, фасад DexClient, TokenScanner, TokenCache, AccountCache.
4. **Ядро** — Indicators (порт из MRX), RiskManager, Strategy-интерфейс + 3 стратегии (перенос dars), StrategyManager/Factory, BotEngine, TradeMonitor, ActivityLog, DemoAccountManager-интеграция.
5. **Сервер** — Ktor Application + Config, REST v1, WebSocket, Bearer-auth.
6. **Telegram** — рефакторинг bot/ → telegram/, меню и нотификации как MRX.
7. **UI** — палитра, 5 экранов, StrategyFormScreen, подключение к BotEngine/БД.
8. **Инфраструктура** — setup.bat/start.bat/desktop.bat, Dockerfile, docker-compose, .env.example, systemd-пример.
9. **Документация** — DOCUMENTATION.md на русском по образцу MRX (запуск, деплой VPS, FAQ).
10. **Тесты** — перенос DarsAnalysisTest, тесты RiskManager/TradeMonitor/индикаторов.

Каждый этап — отдельный коммит; проект компилируется после каждого этапа.

---

## 11. Вопросы перед стартом (можно ответить вместе с «добро»)

1. **Реальная торговля**: Jupiter-своп реальными деньгами делаем сразу или сначала всё через DEMO, а REAL-исполнение — последним этапом? *(рекомендую: DEMO сначала, REAL последним)*
2. **Старая история сделок/токенов** из файлов — выбросить (чистая БД) или написать разовый импорт в SQLite? *(рекомендую: выбросить)*
3. **Название в UI**: «SolTrad Bot» — ок?

---
---

# Приложение: текущие инструкции (до реструктуризации)

### Run Telegram Bot On Installed Windows App

If you already installed the app (for example in `C:\Program Files\tj.khujand.solana.trading.bot`), you can run the Telegram bot without Gradle.

**Simplest (double-click):**

1. Copy `scripts/windows/run-telegram-bot-installed.bat` into the installed app folder (next to `app\` and `runtime\`).
2. Double-click `run-telegram-bot-installed.bat`. On the first run it can open Notepad with `%USERPROFILE%\.soltradbot\telegram-bot.properties` — paste your bot token and admin IDs, save, close Notepad, then double-click the `.bat` again.
3. Later, starting the bot is always: double-click the same `.bat`.

Config is read in this order: environment variables `TELEGRAM_BOT_TOKEN` (and optional `TELEGRAM_ADMIN_*`), then `telegram-bot.properties` in the app folder, then `%USERPROFILE%\.soltradbot\telegram-bot.properties`, then token saved in the desktop app (Filters screen).

To stop the bot: close the console window or press `Ctrl + C`. To run again, double-click the `.bat` again.
