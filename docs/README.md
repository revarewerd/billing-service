> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-05` | Версия: `1.0`

# Billing Service

## Описание

Сервис биллинга и оплаты для платформы Wayrecall Tracker. Управляет аккаунтами, тарифами, подписками, платежами и ежедневным списанием абонентской платы. Provider-agnostic: поддерживает mock, Тинькофф, Сбер, ЮKassa.

## Порт: 8099

## Запуск

```bash
cd services/billing-service
sbt run
```

## API Endpoints

| Метод | Путь | Описание |
|-------|------|----------|
| GET | `/health` | Health check |
| POST | `/api/v1/accounts` | Создать аккаунт |
| GET | `/api/v1/accounts/:id` | Получить аккаунт |
| GET | `/api/v1/accounts/:id/balance` | Баланс аккаунта |
| POST | `/api/v1/accounts/:id/topup` | Пополнить баланс (ручное) |
| POST | `/api/v1/tariffs` | Создать тариф |
| GET | `/api/v1/tariffs` | Список публичных тарифов |
| GET | `/api/v1/tariffs/:id` | Получить тариф |
| POST | `/api/v1/subscriptions` | Подписать устройство |
| DELETE | `/api/v1/subscriptions/:id` | Отписать устройство |
| POST | `/api/v1/subscriptions/:id/pause` | Приостановить подписку |
| POST | `/api/v1/subscriptions/:id/resume` | Возобновить подписку |
| POST | `/api/v1/payments` | Инициировать платёж |
| POST | `/api/v1/payments/callback` | Callback от платёжного провайдера |

## Тесты

80 тестов, 100% проходят:
- DomainSpec (18): opaque types, Money, enums, HistoryRetention
- ErrorsSpec (14): httpStatus, toResponse, getMessage
- AccountServiceSpec (11): CRUD, balance, soft delete
- TariffServiceSpec (10): CRUD, listPublic, delete protection
- PaymentServiceSpec (10): initiatePayment, manualTopUp, history
- FeeProcessorSpec (9): calculateDailyFee, chargeDailyFee, chargeAllAccounts
- SubscriptionServiceSpec (8): subscribe, lifecycle, duplicate/limit checks

## Kafka

**Produce:** `billing-events` — BillingEvent (AccountCreated, PaymentCompleted, etc.)
**Consume:** `device-events` — события от устройств для расчёта подписок

## Redis

Не используется в текущей версии.

## БД

PostgreSQL (схема `billing`): accounts, tariffs, subscriptions, payments, invoices, balance_transactions.

## Документы

- [README.md](README.md) — этот файл
- [ARCHITECTURE.md](ARCHITECTURE.md) — TODO
- [DECISIONS.md](DECISIONS.md) — TODO
