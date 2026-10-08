# Исследование торговых стратегий для DRX (SolTradBot)

> Дата: 2026-10-08. Цель — разобрать 10 стратегий (Momentum, Pullback, Trend Following, Volume+Liquidity,
> Early Momentum, Smart Money, Elliott Wave, Mean Reversion, Scaling Out, Market-cap ladder) по книгам,
> академическим работам и свежим исследованиям мемкоинов Solana, и привязать выводы к коду DRX.
> Связка, которую предложил пользователь: **Scanner → Momentum → Pullback → Volume confirmation → Staged TP → Trailing SL**.
>
> **Статус (2026-10-08):** связка из §3 реализована как стратегия `BREAKOUT_PULLBACK` «Пробой + откат»
> (`core/strategy/BreakoutPullbackStrategy.kt`). Выходы в R, раннер, ATR-трейлинг и размер от риска — общие тумблеры
> для любой стратегии (`core/engine/ExitRules.kt`, `TradeMonitor`, `RiskManager.positionSizeUsd`).
> Бэктестер (этап A) — `domain/backtest`, экран 🧪 на карточке стратегии; выходы считает та же `decideExit`,
> что и `TradeMonitor`. Ещё не сделаны: MAE в сделках (этап A) и сканер (этап D).

Уровни доказательности в тексте:
- 🟢 — академическая работа / большой on-chain датасет;
- 🟡 — классическая книга / практик с публичной статистикой;
- 🔴 — вендор, блог, маркетинг, единичная оценка (использовать только как гипотезу).

---

## 0. Коротко (TL;DR)

1. **Связка пользователя в основном уже реализована в Dars.** `ImpulseCorrectionAnalyzer` уже ищет импульс вверх, затем
   слабую коррекцию и подтверждающий бар разворота, а потом ставит структурный стоп, TP у сопротивления и проверяет R:R ≥ 1.5.
   Не хватает трёх вещей: **объёмного** подтверждения (объём «высыхает» в коррекции и растёт на баре-триггере),
   контекста **пробоя уровня** перед импульсом и **нормальных выходов**: R-лестница, раннер, трейлинг по ATR.
2. **Сейчас слабее всего выходы, а не входы.** Главный TP закрывает 100% позиции и проверяется *до* частичных TP.
   Безубыток не привязан к TP1. Трейлинг идёт фиксированным % от пика по опросу цены раз в 15 с.
   Размер позиции считается как % баланса, а не от риска. Подробности — в §4.
3. **`MomentumStrategy` в нынешнем виде покупает памп**: всплеск объёма плюс зелёные свечи, без уровня пробоя, без тренда,
   со стопом в механических %. Исследования (pump-and-dump, Bulkowski) говорят, что так входят у вершины.
   Её надо переделать в «пробой уровня → ожидание отката → вход» (§3).
4. **Elliott Wave, Mean Reversion, Smart Money и Early Momentum (5–30 мин)** как самостоятельные триггеры для этого бота
   либо не подтверждаются данными, либо технически невыполнимы при опросе раз в 30 с. Их лучше использовать как
   **фильтры или признаки**, а не как стратегии (§1, §2).
5. **Без измерения всё это гадание.** Сначала нужно хранить `initial_stop` (чтобы считать R-multiple) и `min_price` (MAE),
   а также написать бэктестер на OHLCV GeckoTerminal. Только после этого калибровать пороги (§5, этап A).

---

## 1. Контекст: что это за рынок (цифры)

| Факт | Значение | Источник |
|---|---|---|
| Токены Pump.fun с последней сделкой в день запуска | ~69% из 18.67 млн (янв 2024 – июн 2026) | 🟢 CoinGecko, [cryptopotato](https://cryptopotato.com/nearly-70-of-pump-fun-tokens-die-on-launch-day-coingecko/) |
| Доля токенов, торгующихся дольше 90 дней | 4.55% (это не значит «прибыльны») | 🟢 CoinGecko, [cryptobriefing](https://cryptobriefing.com/pump-fun-token-survival-rate-study/) |
| Доля токенов с ликвидностью > $1000 (Solidus, 2025) | ~97 тыс. из 7+ млн, итого ~98.6% «схлопнулись» | 🟢 [Solidus Labs](https://www.soliduslabs.com/reports/solana-rug-pulls-pump-dumps-crypto-compliance) |
| Доля graduation (выход с бондинг-кривой) | ~0.6–2% | 🟢 [arXiv 2602.14860](https://arxiv.org/html/2602.14860v1) (0.63% за сен 2025) |
| Солана-мемтрейдеры в плюсе за 90 дней | ~6.25% из 304 тыс. | 🟡 Dune-дашборд, [HTX](https://www.htx.com/news/what-are-the-odds-only-6-of-solana-meme-traders-made-a-profi-5gTkabXy/) |
| Доля wash-trading в объёме мемов/NFT на Solana | ~41% (оценка с допущениями) | 🔴 [M. Sigel](https://x.com/matthew_sigel/status/1853456012734591462) |
| Доля supply у бандл-кошельков (кластеризация) | 36.5% | 🟢 [MemeTrans, arXiv 2602.13480](https://arxiv.org/html/2602.13480v1) |
| Доля «высокорисковых» запусков среди мигрировавших | 84% (74% после отсева мусора) | 🟢 MemeTrans |
| Пампы: рост +25% за первые 70 с, затем разворот, через час эффект в основном исчез | 500 пампов | 🟢 [Li, Shin, Wang](https://abfer.org/media/abfer-events-2019/annual-conference/investment-finance/AC19P3078_Cryptocurrency_Pump-and-Dump_Schemes.pdf) |

**Выводы для дизайна:**
- Базовая ставка проигрышная, поэтому **фильтры отбора важнее сигналов входа**: большинство монет просто не доживают.
- Сырой объём ненадёжен из-за wash trading и бандлов. Важнее **уникальные трейдеры, органический объём и концентрация холдеров**.
- Правый хвост доходностей экстремальный: редкие монеты дают ×5–×50. Значит, **нельзя полностью закрывать позицию на жёстком TP**,
  нужен раннер.
- Издержки велики. В CPMM-пуле средний слиппедж ≈ `2 × размер / ликвидность`: вход на 1% ликвидности пула ≈ 2% слиппеджа в одну
  сторону, плюс комиссия пула ~0.25%. TP1 должен быть заметно больше издержек на вход и выход.

---

## 2. Десять стратегий: суть → доказательства → мемкоины → что есть в DRX → вердикт

### 2.1 Momentum / Breakout (пробой локального максимума + объём)

- **Суть.** Цена закрывается выше максимума за N баров на повышенном объёме (Donchian, Turtles; O'Neil; Minervini: пивот VCP).
- **Книги.** Curtis Faith, *Way of the Turtle*: пробой 20/55 дней, стоп 2N (N = ATR20), пирамидинг по 0.5N, лимиты юнитов.
  Mark Minervini, *Trade Like a Stock Market Wizard*: VCP с сужающимися откатами, высыхание объёма к концу базы,
  объём на пробое на 50%+ выше среднего, «не покупать дальше ~10% от пивота», стоп под последним сужением.
- **Доказательства.**
  - 🟢 Time-series momentum ([Moskowitz–Ooi–Pedersen](https://papers.ssrn.com/abstract=2089463)) устойчив на 58 фьючерсах.
  - 🟢 В крипте TSMOM сильнее кросс-секционного ([AUT](https://acfr.aut.ac.nz/__data/assets/pdf_file/0009/918729/Time_Series_and_Cross_Sectional_Momentum_in_the_Cryptocurrency_Market_with_IA.pdf)),
    а периоды моментума длиннее, чем на акциях ([Borgards 2021](https://econpapers.repec.org/RePEc:eee:ecofin:v:57:y:2021:i:c:s1062940821000590)).
  - 🟢 Han, Kang, Ryu (по вторичным сводкам): с учётом издержек многие моментум-портфели незначимы, прибыль сосредоточена
    в крупных победителях, а проигравшие часто отскакивают.
  - 🟡 Bulkowski ([volume study](https://thepatternsite.com/VolumeStudy.html)): после пробоя на объёме выше среднего
    **throwback (возврат к уровню) бывает в 57% случаев против 18%** на низком объёме. Доля провалов пробоев за 1991–2008
    выросла в 2–4 раза ([failure rates](https://thepatternsite.com/FailureRates.html)).
- **Мемкоины.** Моментум есть, но первые секунды и минуты принадлежат инсайдерам и снайперам. Бот с опросом раз в 30 с
  и свечами GeckoTerminal заходит *после* них. Покупка «на всплеске» = покупка у тех, кто фиксирует прибыль.
- **В DRX.** [MomentumStrategy.kt](shared/src/commonMain/kotlin/tj/khujand/solana/trading/bot/core/strategy/MomentumStrategy.kt):
  объём ≥ 1.5× среднего, `priceChangeM5 > 0`, 3 из 5 последних свечей зелёные. **Нет уровня пробоя, нет тренда старшего ТФ,
  нет анти-погони, стоп и тейк механические.**
- **Вердикт.** ✅ Использовать, но как **событие, которое взводит сетап**, а не как момент входа. Пробой фиксирует пивот и импульс,
  а вход берётся на откате (§3). Цифра Bulkowski «57% throwback на объёмных пробоях» прямо обосновывает схему
  «пробой → ждать ретест».

### 2.2 Pullback (покупать откат после импульса, а не памп)

- **Книги.**
  - Linda Raschke & Larry Connors, *Street Smarts*, сетап Holy Grail: ADX(14) > 30 и растёт, откат к EMA/SMA(20),
    покупка выше максимума бара, коснувшегося средней ([разбор](https://tradingsetupsreview.com/the-holy-grail-trading-setup),
    [EBC](https://www.ebc.com/forex/holy-grail-trading-setup)). Касание средней — ещё не вход: нужен возврат тренда.
  - Connors & Alvarez, *Short Term Trading Strategies That Work*: RSI(2) < 5–10 при цене выше SMA200, выход на возврате выше SMA5
    ([обзор](https://www.luxalgo.com/library/concept/rsi-2/)).
  - Brian Shannon, *Technical Analysis Using Multiple Timeframes* и *Maximum Trading Gains with Anchored VWAP*: смотреть на наклон
    средней важнее, чем на сам факт касания. Не покупать «просто откат», а ждать паттерн. AVWAP от начала движения — это
    средняя цена покупателей импульса ([alphatrends](https://alphatrends.net/anchored-vwap-book/)).
- **Доказательства.** 🟡 Практики сходятся, строгих академических тестов именно «Holy Grail» нет.
  Kaminski & Lo ([MIT](https://dspace.mit.edu/handle/1721.1/114876)) показали: стоп-лосс добавляет ценность только
  при наличии моментума, а при случайном блуждании лишь снижает доходность. Откат *внутри* моментума — та среда, где стопы работают.
- **В DRX.** Это ядро Dars: [ImpulseCorrectionAnalyzer.kt](shared/src/commonMain/kotlin/tj/khujand/solana/trading/bot/domain/dars/ImpulseCorrectionAnalyzer.kt)
  проверяет доминирование импульса, длину и глубину коррекции, «нет второй стороны», подтверждающий бар.
  [AccumulationGate](shared/src/commonMain/kotlin/tj/khujand/solana/trading/bot/domain/dars/AccumulationGate.kt) запрещает вход
  сразу после резкого бара.
- **Вердикт.** ✅ Главная стратегия. Нужно добавить объёмные проверки (§3, шаги 3–4) и опционально AVWAP от бара пробоя
  как динамическую поддержку.

### 2.3 Trend Following (по тренду, выход на сломе)

- **Книги.** Michael Covel, *Trend Following*; Andreas Clenow, *Following the Trend*; Chuck LeBeau & David Lucas,
  *Computer Analysis of the Futures Market* (Chandelier Exit: `HighestHigh(22) − 3×ATR(22)`, стоп только поднимается,
  [описание](https://nexusfi.com/a/indicators/chandelier-exit)).
- **Доказательства.** 🟢 TSMOM (см. 2.1). 🟡 Деньги трендовых систем — в «толстом правом хвосте»: прибыльные сделки
  должны жить в 2.5–3 раза дольше убыточных. Отсюда главный вывод: **выход важнее входа**.
- **В DRX.** Гейт тренда старшего ТФ есть в Dars (fail-closed). `RsiEmaStrategy` — по сути откат в тренде.
  **Выхода «на сломе тренда» нет**: трейлинг в фиксированных % (`trailingStopPercent=10`) от пика, пик берётся
  по опросу цены каждые 15 с, а не по закрытым свечам.
- **Вердикт.** ✅ Не отдельная стратегия, а **модуль выхода**: Chandelier/ATR-трейлинг на закрытых свечах плюс выход
  при сломе структуры (закрытие ниже последнего higher-low).

### 2.4 Volume + Liquidity (растут объём, ликвидность и капитализация)

- **Доказательства.**
  - 🔴 Wash trading — по одной оценке ~41% объёма мемов на Solana.
  - 🟡 [Bitquery](https://bitquery.io/blog/wash-trading-solana-crypto-market-manipulation-detection): 233 кошелька дали
    $532K объёма за 12 ч; 96% кошельков и покупали, и продавали с почти равными суммами.
  - 🟢 MemeTrans: самые сильные группы признаков — активность (число транзакций, трейдеров, холдеров, покупок),
    затем статистика бандлов, затем концентрация (`top10_hold_pct`, `dev_hold_pct`, `sniper_hold_pct`). У рискованных
    токенов первые 10–20 покупателей держат на 17–19 п.п. больше supply. Отбор моделью снизил средний убыток
    выборки «топ-100» с −60.7% до −26.6%. То есть даже лучший фильтр не делает выборку прибыльной, он лишь режет хвост.
- **В DRX.** [TokenScanner.kt](shared/src/commonMain/kotlin/tj/khujand/solana/trading/bot/exchange/dex/TokenScanner.kt)
  использует ликвидность, `volumeH1`, buys/sells и `changeH1` (штраф за резкость). Скан — **моментальный снимок**:
  динамики «ликвидность растёт» нет. Нет данных о холдерах и органичности.
- **Вердикт.** ✅ Как фильтр сканера:
  1. Хранить снимки метрик по mint и считать прирост ликвидности, MC и объёма за 15/60 мин.
  2. Проверка на здравость: `volumeH1 / liquidity` слишком большой при малом числе сделок → подозрение на wash.
  3. Jupiter Tokens API v2 (`organicScore`, `topHoldersPercentage`, mint/freeze authority) — уже запланировано
     в исследовании от июля 2026.
  4. Ликвидность в CPMM растёт только от добавления LP, поэтому «растёт ликвидность» для пулов Pump.fun/PumpSwap — это часто
     просто рост цены. Проверять **ликвидность к MC** (тонкий пул при большом MC = легко обрушить).

### 2.5 Early Momentum (первые 5–30 минут)

- **Доказательства.**
  - 🟢 [arXiv 2602.14860](https://arxiv.org/html/2602.14860v1) (655 тыс. токенов): лучший предиктор graduation —
    «скорость ликвидности» (сколько сделок нужно, чтобы набрать уровень vSOL). Но даже условные вероятности **почти везде ниже
    точки безубыточности** buy-and-hold: простые правила входа в среднем убыточны. Продавать *до* graduation выгоднее,
    чем сразу после (после миграции глубина пула меньше). У 92% токенов с ≥30 свапами был хотя бы один «дамп» ≤ −4σ.
  - 🟢 [Catching the Rug, arXiv 2608.20271](https://arxiv.org/html/2608.20271): по первым 5 минутам прогноз рага на часе
    дал MCC ≈ 0.36. Авторы прямо пишут, что для реального применения этого недостаточно.
  - 🔴 Модель latency-tax: при стандартной задержке вход на 11–24% хуже лидера.
- **В DRX.** По умолчанию `minTokenAgeMinutes=30`, поэтому окно 5–30 минут отрезано. Свечей у такой монеты мало
  (5–30 штук на 1m), структурные детекторы Dars на них не работают.
- **Вердикт.** ⚠️ Самый рискованный режим. Если делать, то отдельной стратегией с микро-размером и только при наличии
  on-chain данных (холдеры, бандлы, снайперы), которых сейчас нет. Не приоритет.

### 2.6 Smart Money / Wallet Tracking

- **Доказательства.**
  - 🔴/🟡 [Количественная модель](https://romankurnovskii.com/en/blog/pumpfun-copy-trading-feasibility/): винрейт копировщика падает
    с ~28% при задержке 1 с до ~4% при 5 с.
  - 🔴 «Кошельки-приманки», которые специально собирают копировщиков. Ошибка выжившего при отборе по PnL за 30 дней.
  - 🟢 В arXiv 2602.14860 присутствие «успешных» кошельков среди ранних покупателей дало лишь **скромный** прирост вероятности успеха.
- **В DRX.** Нет. Цикл раз в 30 с при задержке копирования в секундах означает гарантированный проигрыш на задержке.
- **Вердикт.** ❌ Как триггер — нет. Позже можно использовать как **признак в баллах**: «среди холдеров есть N кошельков
  из списка с подтверждённой историей». Для этого нужен индексер (Helius/Birdeye).

### 2.7 Elliott Wave (Impulse → Correction → Impulse)

- **Книга.** Frost & Prechter, *Elliott Wave Principle*.
- **Доказательства.**
  - 🟢 Тест на FX ([NU, 2023](https://nur.nu.edu.kz/handle/123456789/7226)): у паттернов Эллиотта нет статистически значимой
    предсказательной силы.
  - 🟢 Fibonacci: [ESWA 2021](https://www.datalearner.com/academic/journal-papers/0957-4174/volumes-and-issues/391/paper-detail/17703) —
    поведение цены у фибо-уровней и не-фибо-уровней статистически не различается.
  - 🟡 [forexop](https://forexop.com/strategy/fibonacci-fact-or-fiction/): 83% из 40 тыс. коррекций лежат в зоне 15–61.8%,
    где четыре фибо-уровня. Попадание объясняется случайностью.
- **В DRX.** Практичная часть уже есть: ноги ZigZag (`LegSegmenter`), пара импульс→коррекция, глубина коррекции корзинами
  35/66/100%.
- **Вердикт.** ❌ Полный счёт волн и фибо-уровни не делать. Оставить глубину коррекции как **непрерывный признак**,
  без «магических» 38.2/61.8.

### 2.8 Mean Reversion (после экстремального пампа ждать отката к средней)

- **Доказательства.**
  - 🟢 Разворот после пампа начинается примерно через 70 с, через час эффект в основном исчезает; сильнее всего в неликвидных монетах
    ([Li, Shin, Wang](https://abfer.org/media/abfer-events-2019/annual-conference/investment-finance/AC19P3078_Cryptocurrency_Pump-and-Dump_Schemes.pdf)).
  - 🟢 Общий эффект разворота в крипте сильнее у малых и неликвидных монет.
  - 🟡 Connors: RSI(2) работает **только с фильтром тренда** (цена выше SMA200).
- **Мемкоины, только спот и лонг.** Шортить памп нельзя. «Купить после обвала» на мемкоине часто означает купить умирающую
  монету или раг. Mean reversion с фильтром тренда — это и есть Pullback (2.2).
- **В DRX.** `AccumulationGate` (не входить сразу после резкого бара) и штраф за `|changeH1|` в скоринге уже реализуют
  «после пампа — ждать».
- **Вердикт.** ⚠️ Отдельной стратегией не делать. Использовать как **фильтр** (уже есть) и как опциональный **триггер внутри отката**
  (RSI(2) вышел из зоны < 10 при восходящем тренде старшего ТФ).

### 2.9 Scaling Out (+30% часть, +50–70% часть, остаток трейлингом)

- **Книги.** Van K. Tharp, *Trade Your Way to Financial Freedom*: R-multiple (прибыль в единицах начального риска),
  ожидание в R, размер позиции по модели percent-risk ([R-multiples](https://www.traderlion.com/risk-management/r-and-r-multiples/)).
  López de Prado, *Advances in Financial Machine Learning*: triple barrier, то есть TP, SL и **барьер времени**
  ([разбор](https://hudsonthames.org/does-meta-labeling-add-to-signal-efficacy/)).
- **Математика.** 🟡 ([LuxAlgo](https://www.luxalgo.com/library/concept/scaling-out/)) Частичная фиксация **не добавляет
  ожидание сама по себе**: она повышает долю зелёных сделок и режет крупнейших победителей. Пример: винрейт 40%, убыток −1R.
  - Полный выход на +2R: `0.4×2 − 0.6×1 = +0.20R`.
  - Половина на +1R и половина на +2R: `0.4×1.5 − 0.6 = 0.00R`. Чтобы сравняться, винрейт должен вырасти до ~48%.

  На мемкоинах с экстремальным правым хвостом важнее всего **раннер**: остаток без жёсткого TP, ведётся трейлингом.
- **В DRX.** [TradeMonitor.kt](shared/src/commonMain/kotlin/tj/khujand/solana/trading/bot/core/engine/TradeMonitor.kt)
  уже имеет TP1/TP2, безубыток, трейлинг и тайм-стоп, но (подробно в §4):
  - главный TP закрывает 100% и проверяется **до** частичных;
  - уровни задаются в % от входа, а не в R;
  - безубыток срабатывает по своему порогу, а не после TP1;
  - раннера нет.
- **Вердикт.** ✅ Внедрить R-лестницу и раннер, затем проверить бэктестом: полный выход vs лестница vs лестница + раннер.

### 2.10 Market-cap strategy (зоны $100k → $200k → $300k → $500k)

- **Доказательства.**
  - 🟢 Urquhart (2017, [Economics Letters](https://eprints.soton.ac.uk/412873)): цены BTC кластеризуются на круглых числах,
    но **после круглого числа нет значимого паттерна доходности**.
  - Порог graduation Pump.fun около $69K MC привязан к SOL, а не к долларам
    ([cryptomemecoin](https://cryptomemecoin.com/blog/pump-fun-graduation-explained)).
- **По сути.** MC = цена × supply, поэтому лестница по MC — это лестница по % от цены входа (100k → 200k = +100%).
  Её единственное реальное преимущество — круглые числа, у которых стоят чужие лимитные продажи.
- **Вердикт.** ⚠️ Опциональный режим TP: ставить цели **чуть ниже** круглых значений MC (например, 0.95× от $200k),
  чтобы исполниться раньше скопления продаж. Сочетать с уровнями сопротивления Dars, а не вместо них.

### Сводка вердиктов

| # | Стратегия | Доказательность | В DRX сейчас | Решение |
|---|---|---|---|---|
| 1 | Momentum/Breakout | 🟢 TSMOM, 🟡 Bulkowski | `MomentumStrategy` (покупает памп) | ✅ Переделать: пробой взводит сетап |
| 2 | Pullback | 🟡 Raschke, Connors, Shannon | Dars ImpulseCorrection | ✅ Ядро; добавить объём |
| 3 | Trend Following | 🟢 TSMOM, 🟡 LeBeau | HTF-гейт в Dars | ✅ Как модуль выхода (ATR-трейлинг) |
| 4 | Volume+Liquidity | 🟢 MemeTrans, 🔴 wash-оценки | снимок в сканере | ✅ Фильтр: динамика + органичность |
| 5 | Early Momentum | 🟢 (против простых правил) | отрезано `minAge=30` | ⚠️ Позже, микро-размер |
| 6 | Smart Money | 🔴/🟢 (слабый признак) | нет | ❌ Не триггер; позже — признак |
| 7 | Elliott Wave | 🟢 (против) | ноги ZigZag в Dars | ❌ Не делать счёт волн |
| 8 | Mean Reversion | 🟢 (только с трендом) | AccumulationGate | ⚠️ Фильтр, а не стратегия |
| 9 | Scaling Out | 🟡 математика R | TP1/TP2 в %, без раннера | ✅ R-лестница + раннер |
| 10 | Market-cap ladder | 🟢 Urquhart (слабо) | нет | ⚠️ Опциональный режим TP |

---

## 3. Рекомендуемая связка: спецификация

Работает без сохранения состояния: каждый цикл анализирует последние свечи. История пробоя уже лежит в свечах, поэтому
после перезапуска ничего не теряется. Это совпадает с моделью `Strategy.analyze(candidate, candles, htf)`.
Название рабочее — `BreakoutPullbackStrategy`, либо апгрейд `MomentumStrategy`.

```
Scanner ─► [0] фильтры отбора ─► [1] тренд старшего ТФ ─► [2] событие пробоя (momentum)
        ─► [3] откат (pullback) + высыхание объёма ─► [4] бар-триггер + объём
        ─► [5] стоп под структурой, гейт R:R, размер от риска
        ─► [6] TP1 (+1.5R) → BE ─► TP2 (+3R) ─► раннер: Chandelier-трейлинг ─► тайм-стоп / слом тренда
```

| Шаг | Правило (стартовые значения, калибровать бэктестом) | Откуда |
|---|---|---|
| 0. Отбор | Ликвидность ≥ X; ликвидность/MC ≥ 5–10%; `volumeH1/liquidity` в разумных пределах; buys/sells > 1; *(позже)* `organicScore`, top10 < 30–40%, mint/freeze authority отключены | MemeTrans, Bitquery, исследование API (июль 2026) |
| 1. Тренд | HTF (15m) вверх по `TrendAnalyzer` *или* close > EMA20 и EMA20 растёт; опционально цена выше AVWAP от первой свечи | Raschke (ADX > 30 — опционально), Shannon, TSMOM |
| 2. Пробой | В последних 3–20 барах есть бар `b`: `close_b > max(high[b−20..b−1])`, `vol_b ≥ 2×SMA20(vol)`, `closePosition ≥ 0.6`. Пивот `P` = тот максимум. Опционально VCP: `ATR(5)/ATR(20) < 0.8` перед `b` | Turtles (Donchian 20), Minervini/O'Neil (объём +50% → для мемов ×2), Bulkowski |
| 3. Откат | Пик `H` после `b`; минимум отката `L`: глубина `(H−L)/(H−низ импульса)` в 25–66%; `L ≥ P − 0.5×ATR` (ретест удержал уровень) **или** `L` выше AVWAP от бара `b`; ≥ 2 бара отката; **средний объём отката ≤ 0.7× среднего объёма импульса**; ни один бар отката по объёму не больше `vol_b` | Bulkowski (throwback), Minervini (dry-up), Shannon (AVWAP), Dars (корзины глубины, «нет второй стороны») |
| 4. Триггер | Последний **закрытый** бар: бычий, `close > high` предыдущего бара, `closePosition ≥ 0.6`, `vol ≥ 1.2×` среднего объёма отката. Анти-погоня: живая цена не дальше 3% от цены сигнала (уже есть `MAX_ENTRY_PRICE_DRIFT`) | Raschke (вход выше максимума бара касания), Dars (CONFIRM_*) |
| 5. Стоп и размер | Стоп = `L − 0.5×ATR` (под минимумом отката). Если `stopFrac > SL%` из конфига → **отказ** (сетап не тугой). Запас хода до пика `(H − entry) ≥ 1R`. Размер = `equity × risk% / stopFrac`, не больше `positionSize%` и не больше 0.5–1% ликвидности пула | Minervini (стоп под последним сужением), Tharp (percent-risk), формула слиппеджа CPMM |
| 6. Выходы | TP1: 40% на +1.5R → SL в безубыток + издержки. TP2: 30% на +3R (или чуть ниже круглого MC). Раннер 30%: **Chandelier** `HH(22) − 3×ATR(14)` по закрытым свечам рабочего ТФ, стоп только вверх. Тайм-стоп: не было +0.5R за 20 баров → выход. Ликвидность-стоп — уже есть | LeBeau, Tharp, López de Prado (vertical barrier), Kaminski–Lo |

**Почему так, а не «купить пробой».** На объёмных пробоях ретест бывает примерно в 57% случаев (Bulkowski), а бот всё равно
опаздывает к пробою из-за цикла 30 с и кеша 5 мин. Вход на откате даёт близкий структурный стоп (меньше R) и отсекает
пампы без продолжения. Цена такого подхода — пропуск самых сильных пробоев без ретеста. Это осознанный выбор.

**Быстрый путь (минимальные изменения).** Вместо новой стратегии добавить в `ImpulseCorrectionAnalyzer` два опциональных правила:
`curCorr.avgVolume ≤ 0.7 × curImp.avgVolume` (высыхание) и объём бара-подтверждения ≥ 1.2× среднего объёма коррекции.
Поля `avgVolume` и `maxVolume` у `Leg` уже есть ([LegSegmenter.kt](shared/src/commonMain/kotlin/tj/khujand/solana/trading/bot/domain/dars/LegSegmenter.kt)).
Сейчас проверяется только `curCorr.maxVolume > curImp.maxVolume × 1.2`.

---

## 4. Пробелы в текущем коде, найденные при исследовании

1. **Главный TP перебивает лестницу.** В [TradeMonitor.kt](shared/src/commonMain/kotlin/tj/khujand/solana/trading/bot/core/engine/TradeMonitor.kt)
   `price >= t.take_profit → closeFull` проверяется до partial TP. Dars ставит TP у ближайшего сопротивления
   (иногда +5–10%), а `tp1Percent=15`, поэтому при включённых частичных TP **TP1 и TP2 могут не сработать никогда**.
   Раннера нет в принципе: всё закрывается на `take_profit`.
2. **Безубыток не связан с TP1.** BE срабатывает по `breakEvenTriggerPercent`, а не после частичной фиксации.
   Также не учитываются издержки: offset 1% меньше слиппеджа на выходе на тонком пуле.
3. **Трейлинг в фиксированных %, по тиковой цене.** `peak × (1 − 10%)` при волатильности бара мемкоина от 3% до 30% либо выбивает
   шумом, либо отдаёт половину движения. Пик обновляется по опросу каждые 15 с, поэтому фитиль выбивает стоп.
   Нужен ATR-трейлинг по **закрытым** свечам. Ограничение: GeckoTerminal 30 запросов в минуту, поэтому ATR лучше
   фиксировать при входе, а свечи для открытых позиций обновлять не чаще раза в бар.
4. **Размер позиции не от риска.** `RiskManager.calculatePositionSizeUsd` = `balance × positionSize%`.
   При стопах от 5% до 15% риск на сделку плавает втрое. Нужна модель percent-risk (Tharp)
   и лимит по ликвидности пула.
5. **R-multiple не посчитать.** В таблице `trade` поле `stop_loss` перезаписывается безубытком и трейлингом,
   поэтому начальный риск теряется. Нужны `initial_stop` (R) и `min_price` (MAE; `peak_price` уже даёт MFE).
   Это поздние колонки по паттерну `DbMigrations`.
6. **`MomentumStrategy` покупает памп.** Нет уровня, тренда и структурного стопа (§2.1).
7. **Нет бэктестера.** Ни один порог (1.5×, 70%, 1.3, 15%/30%) не откалиброван на данных. В GeckoTerminal есть OHLCV
   примерно до 6 месяцев.

---

## 5. План внедрения (по приоритету)

**Этап A — измерение (без него нельзя честно сравнивать).**
- Колонки `initial_stop`, `min_price` в `trade`. В статистике: R-multiple по сделке, ожидание в R, MFE/MAE в R по стратегиям.
- Бэктестер (commonMain + jvmTest): прогон того же `Strategy.analyze` по окну свечей, исполнение выходов
  по логике `TradeMonitor` на свечах, издержки = комиссия + слиппедж `2×size/liquidity`.

**Этап B — выходы (наибольший эффект).**
- Режим «раннер»: `take_profit` не закрывает 100% при включённой лестнице, либо уровни задаются в R.
- BE после TP1 с учётом издержек.
- Chandelier/ATR-трейлинг по закрытым свечам.
- Тайм-стоп «не пошла» (+0.5R за N баров) вдобавок к существующему тайм-стопу по минутам.

**Этап C — вход.**
- Объёмные правила в `ImpulseCorrectionAnalyzer` (быстрый путь из §3).
- `BreakoutPullbackStrategy` по спецификации §3 или замена `MomentumStrategy` на неё.
- Размер позиции от риска с лимитом по ликвидности.

**Этап D — сканер.**
- Снимки метрик по mint → динамика ликвидности, MC и объёма.
- Jupiter `organicScore` и топ-холдеры, ликвидность/MC, wash-эвристики.

**Этап E — опционально и позже.** Early-режим с микро-размером, MC-лестница для TP, smart-money как признак.

---

## 6. Книги: что брать из каждой для DRX

| Книга | Автор | Что взять |
|---|---|---|
| *Trade Your Way to Financial Freedom* | Van K. Tharp | R-multiple, ожидание, percent-risk / percent-volatility sizing |
| *Way of the Turtle* | Curtis Faith | Donchian-пробой, N=ATR, стоп 2N, пирамидинг, лимиты юнитов |
| *Trade Like a Stock Market Wizard*; *Think & Trade Like a Champion* | Mark Minervini | VCP, пивот, высыхание объёма, «не гнаться», стоп под сужением, продажа на силе |
| *Street Smarts* | L. Raschke, L. Connors | Holy Grail (откат к EMA20 при ADX > 30), Turtle Soup (= FalseBreakout в Dars) |
| *Short Term Trading Strategies That Work* | L. Connors, C. Alvarez | RSI(2) + SMA200: покупать откат в тренде, а не пробой |
| *Technical Analysis Using Multiple Timeframes*; *Maximum Trading Gains with Anchored VWAP* | Brian Shannon | Мульти-ТФ, AVWAP от запуска или пробоя как средняя цена покупателей |
| *Encyclopedia of Chart Patterns* | Thomas Bulkowski | Статистика провалов пробоев, throwback/pullback, роль объёма |
| *Following the Trend* | Andreas Clenow | Трендовые выходы, ATR-размер, «выход важнее входа» |
| *Trend Following* | Michael Covel | Философия толстых хвостов, «пусть прибыль течёт» |
| *Computer Analysis of the Futures Market* | C. LeBeau, D. Lucas | Chandelier Exit, ADX |
| *The New Trading for a Living* | Alexander Elder | Triple Screen (мульти-ТФ), SafeZone-стоп, правила 2% и 6% |
| *Trading Systems and Methods* | Perry Kaufman | Тестирование систем, Efficiency Ratio, адаптивные средние |
| *Advances in Financial Machine Learning* | Marcos López de Prado | Triple barrier, meta-labeling, переобучение бэктестов |
| *Algorithmic Trading* | Ernest Chan | Когда работает моментум, а когда возврат к среднему; проверки стационарности |
| *Elliott Wave Principle* | A. J. Frost, R. Prechter | Для понимания терминов; торговую ценность данные не подтверждают |

Свежие исследования мемкоинов (обязательное чтение):
- [arXiv 2602.14860](https://arxiv.org/html/2602.14860v1) — успех токенов Pump.fun;
- [arXiv 2602.13480](https://arxiv.org/html/2602.13480v1) — MemeTrans, риск запусков;
- [arXiv 2608.20271](https://arxiv.org/html/2608.20271) — ранний прогноз рагов;
- [arXiv 2507.01963](https://arxiv.org/pdf/2507.01963) — манипуляции в мемкоинах;
- [arXiv 2603.24625](https://arxiv.org/pdf/2603.24625) — раг-пуллы на Solana.

---

## 7. Источники

- Moskowitz, Ooi, Pedersen — [Time Series Momentum (SSRN)](https://papers.ssrn.com/abstract=2089463)
- [TSMOM vs CSMOM в крипте (AUT)](https://acfr.aut.ac.nz/__data/assets/pdf_file/0009/918729/Time_Series_and_Cross_Sectional_Momentum_in_the_Cryptocurrency_Market_with_IA.pdf); [Borgards 2021](https://econpapers.repec.org/RePEc:eee:ecofin:v:57:y:2021:i:c:s1062940821000590)
- Kaminski & Lo — [When do stop-loss rules stop losses? (MIT)](https://dspace.mit.edu/handle/1721.1/114876)
- Bulkowski — [Volume study](https://thepatternsite.com/VolumeStudy.html), [Failure rates](https://thepatternsite.com/FailureRates.html)
- [VCP (LuxAlgo)](https://www.luxalgo.com/library/concept/volatility-contraction-pattern/)
- Holy Grail — [tradingsetupsreview](https://tradingsetupsreview.com/the-holy-grail-trading-setup), [EBC](https://www.ebc.com/forex/holy-grail-trading-setup)
- [Turtle rules (MQL5)](https://www.mql5.com/en/articles/23448)
- [Chandelier Exit (NexusFi)](https://nexusfi.com/a/indicators/chandelier-exit)
- [R-multiples (TraderLion)](https://www.traderlion.com/risk-management/r-and-r-multiples/)
- [Scaling out (LuxAlgo)](https://www.luxalgo.com/library/concept/scaling-out/)
- [Triple barrier (Hudson & Thames)](https://hudsonthames.org/does-meta-labeling-add-to-signal-efficacy/)
- [RSI(2) (LuxAlgo)](https://www.luxalgo.com/library/concept/rsi-2/)
- [Anchored VWAP (Alphatrends)](https://alphatrends.net/anchored-vwap-book/)
- Elliott — [NU 2023](https://nur.nu.edu.kz/handle/123456789/7226); Fibonacci — [ESWA 2021](https://www.datalearner.com/academic/journal-papers/0957-4174/volumes-and-issues/391/paper-detail/17703), [forexop](https://forexop.com/strategy/fibonacci-fact-or-fiction/)
- [Cryptocurrency Pump-and-Dump Schemes (Li, Shin, Wang)](https://abfer.org/media/abfer-events-2019/annual-conference/investment-finance/AC19P3078_Cryptocurrency_Pump-and-Dump_Schemes.pdf)
- CoinGecko — [cryptopotato](https://cryptopotato.com/nearly-70-of-pump-fun-tokens-die-on-launch-day-coingecko/), [cryptobriefing](https://cryptobriefing.com/pump-fun-token-survival-rate-study/); [Solidus Labs](https://www.soliduslabs.com/reports/solana-rug-pulls-pump-dumps-crypto-compliance)
- [Bitquery: wash trading на Solana](https://bitquery.io/blog/wash-trading-solana-crypto-market-manipulation-detection); [M. Sigel](https://x.com/matthew_sigel/status/1853456012734591462)
- [Copy-trading feasibility на Pump.fun](https://romankurnovskii.com/en/blog/pumpfun-copy-trading-feasibility/); [HTX: 6% в плюсе](https://www.htx.com/news/what-are-the-odds-only-6-of-solana-meme-traders-made-a-profi-5gTkabXy/)
- Urquhart — [Price clustering in Bitcoin](https://eprints.soton.ac.uk/412873); [Pump.fun graduation](https://cryptomemecoin.com/blog/pump-fun-graduation-explained)

> Оговорка: многие цифры взяты из аннотаций и вторичных обзоров, а не из полных текстов. Пороги в §3 — стартовые значения.
> Их нужно калибровать бэктестом на данных DRX, а не принимать на веру. Это не инвестиционная рекомендация.
