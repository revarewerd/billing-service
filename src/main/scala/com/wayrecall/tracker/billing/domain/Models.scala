package com.wayrecall.tracker.billing.domain

import zio.json.*
import java.time.{Instant, LocalDate}
import java.util.UUID

// ============================================================
// Opaque Types — типобезопасные идентификаторы
// ============================================================

opaque type AccountId = UUID
object AccountId:
  def apply(value: UUID): AccountId = value
  def generate: AccountId = UUID.randomUUID()
  def fromString(s: String): Either[String, AccountId] =
    try Right(UUID.fromString(s)) catch case _: Exception => Left(s"Некорректный AccountId: $s")
  
  extension (id: AccountId)
    def value: UUID = id
    def asString: String = id.toString

  given JsonEncoder[AccountId] = JsonEncoder[UUID]
  given JsonDecoder[AccountId] = JsonDecoder[UUID]

opaque type TariffId = UUID
object TariffId:
  def apply(value: UUID): TariffId = value
  def generate: TariffId = UUID.randomUUID()
  def fromString(s: String): Either[String, TariffId] =
    try Right(UUID.fromString(s)) catch case _: Exception => Left(s"Некорректный TariffId: $s")
  
  extension (id: TariffId)
    def value: UUID = id
    def asString: String = id.toString

  given JsonEncoder[TariffId] = JsonEncoder[UUID]
  given JsonDecoder[TariffId] = JsonDecoder[UUID]

opaque type SubscriptionId = UUID
object SubscriptionId:
  def apply(value: UUID): SubscriptionId = value
  def generate: SubscriptionId = UUID.randomUUID()
  def fromString(s: String): Either[String, SubscriptionId] =
    try Right(UUID.fromString(s)) catch case _: Exception => Left(s"Некорректный SubscriptionId: $s")
  
  extension (id: SubscriptionId)
    def value: UUID = id
    def asString: String = id.toString

  given JsonEncoder[SubscriptionId] = JsonEncoder[UUID]
  given JsonDecoder[SubscriptionId] = JsonDecoder[UUID]

opaque type PaymentId = UUID
object PaymentId:
  def apply(value: UUID): PaymentId = value
  def generate: PaymentId = UUID.randomUUID()
  def fromString(s: String): Either[String, PaymentId] =
    try Right(UUID.fromString(s)) catch case _: Exception => Left(s"Некорректный PaymentId: $s")
  
  extension (id: PaymentId)
    def value: UUID = id
    def asString: String = id.toString

  given JsonEncoder[PaymentId] = JsonEncoder[UUID]
  given JsonDecoder[PaymentId] = JsonDecoder[UUID]

opaque type InvoiceId = UUID
object InvoiceId:
  def apply(value: UUID): InvoiceId = value
  def generate: InvoiceId = UUID.randomUUID()
  def fromString(s: String): Either[String, InvoiceId] =
    try Right(UUID.fromString(s)) catch case _: Exception => Left(s"Некорректный InvoiceId: $s")
  
  extension (id: InvoiceId)
    def value: UUID = id
    def asString: String = id.toString

  given JsonEncoder[InvoiceId] = JsonEncoder[UUID]
  given JsonDecoder[InvoiceId] = JsonDecoder[UUID]

opaque type OrganizationId = UUID
object OrganizationId:
  def apply(value: UUID): OrganizationId = value
  def fromString(s: String): Either[String, OrganizationId] =
    try Right(UUID.fromString(s)) catch case _: Exception => Left(s"Некорректный OrganizationId: $s")
  
  extension (id: OrganizationId)
    def value: UUID = id
    def asString: String = id.toString

  given JsonEncoder[OrganizationId] = JsonEncoder[UUID]
  given JsonDecoder[OrganizationId] = JsonDecoder[UUID]

// ============================================================
// Денежные суммы — в копейках (чтобы избежать проблем с float)
// ============================================================

opaque type Money = Long
object Money:
  def kopecks(value: Long): Money = value
  def rubles(value: Double): Money = (value * 100).toLong
  val zero: Money = 0L

  extension (m: Money)
    def toKopecks: Long = m
    def toRubles: Double = m.toDouble / 100.0
    def +(other: Money): Money = m + other
    def -(other: Money): Money = m - other
    def *(factor: Int): Money = m * factor
    def negate: Money = -m
    def isPositive: Boolean = m > 0
    def isNegative: Boolean = m < 0
    def isZero: Boolean = m == 0

  given JsonEncoder[Money] = JsonEncoder[Long]
  given JsonDecoder[Money] = JsonDecoder[Long]

// ============================================================
// Аккаунт (привязан к организации)
// ============================================================

/**
 * Биллинговый аккаунт организации.
 * Баланс хранится в копейках.
 * Блокировка происходит при отрицательном балансе.
 */
final case class Account(
  id:             AccountId,
  organizationId: OrganizationId,
  name:           String,
  balance:        Money,
  tariffId:       Option[TariffId],
  status:         AccountStatus,
  autoPayment:    Boolean,          // Включена ли автооплата
  createdAt:      Instant,
  updatedAt:      Instant,
  blockedAt:      Option[Instant],  // Дата блокировки при неоплате
  deletedAt:      Option[Instant]   // Soft delete
) derives JsonEncoder, JsonDecoder

/**
 * Статус аккаунта
 */
enum AccountStatus derives JsonEncoder, JsonDecoder:
  case Active      // Активный, все сервисы работают
  case Suspended   // Приостановлен (задолженность, нет блокировки устройств)
  case Blocked     // Заблокирован (устройства не мониторятся)
  case Closed      // Закрыт (аккаунт удалён)

// ============================================================
// Тарифный план
// ============================================================

/**
 * Тарифный план определяет стоимость услуг.
 * abonentPrices — стоимость за единицу оборудования по типу (kopecks/day)
 * additionalServices — дополнительные опции с ценой
 */
final case class TariffPlan(
  id:                  TariffId,
  name:                String,
  description:         String,
  abonentPrices:       List[EquipmentPrice], // Цена по типам оборудования (в день)
  additionalServices:  List[ServicePrice],   // Доп. сервисы
  historyRetention:    HistoryRetention,     // Срок хранения GPS истории
  maxDevices:          Option[Int],          // Лимит устройств (None = безлимит)
  isDefault:           Boolean,              // Тариф по умолчанию для новых аккаунтов
  isPublic:            Boolean,              // Доступен для самостоятельного подключения
  createdAt:           Instant,
  updatedAt:           Instant,
  deletedAt:           Option[Instant]
) derives JsonEncoder, JsonDecoder

/**
 * Стоимость за единицу оборудования определённого типа (в день, в копейках)
 */
final case class EquipmentPrice(
  equipmentType: String,    // "tracker", "beacon", "sensor", "camera"
  dailyRate:     Money,     // Стоимость в день за 1 единицу (копейки)
  coefficient:   Double     // Коэффициент (основной=1.0, доп=0.25, спящий=0.1)
) derives JsonEncoder, JsonDecoder

/**
 * Дополнительный сервис с ценой
 */
final case class ServicePrice(
  serviceCode: String,      // "fuel_monitoring", "driver_id", "video", "retranslation"
  name:        String,
  dailyRate:   Money,
  perDevice:   Boolean      // true = за каждое устройство, false = фиксированная
) derives JsonEncoder, JsonDecoder

/**
 * Глубина хранения GPS истории
 */
enum HistoryRetention derives JsonEncoder, JsonDecoder:
  case Days(count: Int)
  case Months(count: Int)
  case Unlimited

// ============================================================
// Подписка (привязка устройств к аккаунту)
// ============================================================

/**
 * Подписка — связь аккаунта с устройством.
 * Определяет, какое устройство тарифицируется на каком аккаунте.
 */
final case class Subscription(
  id:             SubscriptionId,
  accountId:      AccountId,
  deviceId:       UUID,               // ID устройства из Device Manager
  equipmentType:  String,             // "tracker", "beacon", "sensor"
  status:         SubscriptionStatus,
  activatedAt:    Instant,
  deactivatedAt:  Option[Instant],
  additionalServices: List[String]    // Подключённые доп. сервисы (коды из ServicePrice)
) derives JsonEncoder, JsonDecoder

enum SubscriptionStatus derives JsonEncoder, JsonDecoder:
  case Active
  case Paused       // Временная пауза (не тарифицируется)
  case Cancelled    // Отменена
  case Expired      // Истёк срок

// ============================================================
// Платежи (пополнение баланса)
// ============================================================

/**
 * Платёж — операция пополнения баланса через эквайринг.
 * Провайдер-агностичная модель: работает с любым шлюзом.
 */
final case class Payment(
  id:                PaymentId,
  accountId:         AccountId,
  amount:            Money,
  currency:          Currency,
  status:            PaymentStatus,
  provider:          PaymentProvider,
  externalId:        Option[String],    // ID в платёжной системе
  paymentUrl:        Option[String],    // URL для оплаты (если redirect)
  description:       String,
  createdAt:         Instant,
  paidAt:            Option[Instant],
  failedAt:          Option[Instant],
  failureReason:     Option[String],
  metadata:          Map[String, String] // Доп. данные от шлюза
) derives JsonEncoder, JsonDecoder

enum PaymentStatus derives JsonEncoder, JsonDecoder:
  case Created       // Создан, ожидает оплаты
  case Pending       // Отправлен в шлюз, ожидание
  case Authorized    // Авторизован (деньги заморожены)
  case Confirmed     // Успешно оплачен
  case Cancelled     // Отменён пользователем
  case Refunded      // Возврат средств
  case Failed        // Ошибка

enum PaymentProvider derives JsonEncoder, JsonDecoder:
  case Tinkoff
  case Sber
  case YooKassa
  case Manual        // Ручное пополнение администратором
  case Mock          // Для тестирования

enum Currency derives JsonEncoder, JsonDecoder:
  case RUB
  case USD
  case EUR

// ============================================================
// Счёт (инвойс)
// ============================================================

/**
 * Счёт — документ на оплату. Формируется ежемесячно или по запросу.
 */
final case class Invoice(
  id:             InvoiceId,
  accountId:      AccountId,
  number:         String,              // Номер счёта: "INV-2026-03-0001"
  period:         InvoicePeriod,
  items:          List[InvoiceItem],
  totalAmount:    Money,
  status:         InvoiceStatus,
  createdAt:      Instant,
  paidAt:         Option[Instant],
  dueDate:        LocalDate            // Срок оплаты
) derives JsonEncoder, JsonDecoder

final case class InvoicePeriod(
  startDate: LocalDate,
  endDate:   LocalDate
) derives JsonEncoder, JsonDecoder

final case class InvoiceItem(
  description: String,
  quantity:    Int,
  unitPrice:   Money,
  totalPrice:  Money
) derives JsonEncoder, JsonDecoder

enum InvoiceStatus derives JsonEncoder, JsonDecoder:
  case Draft
  case Issued       // Выставлен
  case Paid         // Оплачен
  case Overdue      // Просрочен
  case Cancelled

// ============================================================
// История баланса
// ============================================================

/**
 * Запись в истории баланса — каждое изменение баланса.
 */
final case class BalanceTransaction(
  id:          UUID,
  accountId:   AccountId,
  amount:      Money,           // Положительный = пополнение, отрицательный = списание
  balanceAfter: Money,          // Баланс после операции
  txType:      TransactionType,
  description: String,
  referenceId: Option[String],  // ID связанной сущности (paymentId, invoiceId)
  createdAt:   Instant
) derives JsonEncoder, JsonDecoder

enum TransactionType derives JsonEncoder, JsonDecoder:
  case TopUp           // Пополнение через эквайринг
  case ManualTopUp     // Ручное пополнение
  case DailyFee        // Ежедневное абонентское списание
  case ServiceFee      // Списание за доп. сервис
  case Refund          // Возврат средств
  case Adjustment      // Корректировка администратором
  case Penalty         // Штраф/пеня

// ============================================================
// DTO — запросы и ответы API
// ============================================================

final case class CreateAccountRequest(
  organizationId: OrganizationId,
  name:           String,
  tariffId:       Option[TariffId]
) derives JsonDecoder

final case class UpdateAccountRequest(
  name:        Option[String],
  tariffId:    Option[TariffId],
  autoPayment: Option[Boolean]
) derives JsonDecoder

final case class CreateTariffRequest(
  name:               String,
  description:        String,
  abonentPrices:      List[EquipmentPrice],
  additionalServices: List[ServicePrice],
  historyRetention:   HistoryRetention,
  maxDevices:         Option[Int],
  isPublic:           Boolean
) derives JsonDecoder

final case class CreateSubscriptionRequest(
  accountId:          AccountId,
  deviceId:           UUID,
  equipmentType:      String,
  additionalServices: List[String]
) derives JsonDecoder

final case class CreatePaymentRequest(
  accountId:   AccountId,
  amount:      Money,
  currency:    Currency,
  provider:    PaymentProvider,
  description: String
) derives JsonDecoder

final case class TopUpRequest(
  amount:   Money,
  provider: PaymentProvider,
  description: Option[String]
) derives JsonDecoder

final case class ManualTopUpRequest(
  amount:      Money,
  description: String
) derives JsonDecoder

// Ответы API

final case class AccountResponse(
  account:       Account,
  tariff:        Option[TariffPlan],
  subscriptions: Int,
  dailyCost:     Money
) derives JsonEncoder

final case class PaymentResponse(
  payment:    Payment,
  paymentUrl: Option[String]  // URL для редиректа клиента
) derives JsonEncoder

final case class BalanceResponse(
  accountId:   AccountId,
  balance:     Money,
  dailyCost:   Money,
  daysLeft:    Int,            // Сколько дней хватит баланса
  status:      AccountStatus
) derives JsonEncoder

final case class FeeCalculation(
  accountId:   AccountId,
  dailyTotal:  Money,
  items:       List[FeeItem]
) derives JsonEncoder

final case class FeeItem(
  deviceId:      UUID,
  equipmentType: String,
  baseCost:      Money,
  serviceCosts:  List[ServiceCostItem]
) derives JsonEncoder

final case class ServiceCostItem(
  serviceCode: String,
  name:        String,
  cost:        Money
) derives JsonEncoder

// ============================================================
// Kafka сообщения
// ============================================================

/**
 * Событие биллинга — публикуется в топик billing-events
 */
enum BillingEvent derives JsonEncoder, JsonDecoder:
  case AccountCreated(accountId: AccountId, organizationId: OrganizationId, tariffId: Option[TariffId], timestamp: Instant)
  case AccountBlocked(accountId: AccountId, reason: String, timestamp: Instant)
  case AccountUnblocked(accountId: AccountId, timestamp: Instant)
  case PaymentReceived(accountId: AccountId, paymentId: PaymentId, amount: Money, provider: PaymentProvider, timestamp: Instant)
  case DailyFeeCharged(accountId: AccountId, amount: Money, devicesCount: Int, timestamp: Instant)
  case BalanceLow(accountId: AccountId, balance: Money, daysLeft: Int, timestamp: Instant)
  case SubscriptionActivated(accountId: AccountId, subscriptionId: SubscriptionId, deviceId: UUID, timestamp: Instant)
  case SubscriptionCancelled(accountId: AccountId, subscriptionId: SubscriptionId, deviceId: UUID, timestamp: Instant)
