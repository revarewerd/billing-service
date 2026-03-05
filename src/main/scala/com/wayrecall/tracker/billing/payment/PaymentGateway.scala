package com.wayrecall.tracker.billing.payment

import com.wayrecall.tracker.billing.domain.*
import zio.*

// ============================================================
// Абстракция платёжного шлюза (провайдер-агностичная)
// Поддерживает Тинькофф, Сбер, ЮKassa и Mock для тестов
// ============================================================

/**
 * Универсальный интерфейс платёжного шлюза.
 * Каждый провайдер реализует этот trait.
 */
trait PaymentGateway:
  /** Название провайдера */
  def providerName: PaymentProvider

  /** Инициировать платёж — возвращает Payment с URL для оплаты */
  def initPayment(request: InitPaymentRequest): Task[InitPaymentResponse]

  /** Проверить статус платежа по external ID */
  def checkStatus(externalId: String): Task[GatewayPaymentStatus]

  /** Подтвердить платёж (для двухстадийных) */
  def confirmPayment(externalId: String): Task[Unit]

  /** Отменить платёж */
  def cancelPayment(externalId: String): Task[Unit]

  /** Возврат средств */
  def refundPayment(externalId: String, amount: Money): Task[String] // refund ID

/**
 * Запрос на инициализацию платежа
 */
final case class InitPaymentRequest(
  orderId:     String,     // Наш внутренний ID (PaymentId)
  amount:      Money,      // Сумма в копейках
  currency:    Currency,
  description: String,
  customerEmail: Option[String],
  returnUrl:   Option[String],  // URL после оплаты (для редиректа)
  metadata:    Map[String, String]
)

/**
 * Ответ инициализации платежа
 */
final case class InitPaymentResponse(
  externalId:  String,      // ID в платёжной системе
  paymentUrl:  String,      // URL для клиента (redirect / iframe)
  status:      GatewayPaymentStatus
)

/**
 * Статус платежа в шлюзе
 */
enum GatewayPaymentStatus:
  case New
  case Pending
  case Authorized
  case Confirmed
  case Cancelled
  case Refunded
  case Failed(reason: String)

// ============================================================
// Mock Gateway — для тестирования и разработки
// ============================================================

/**
 * Мок-реализация платёжного шлюза.
 * Все платежи сразу подтверждаются.
 */
final case class MockPaymentGateway(payments: Ref[Map[String, GatewayPaymentStatus]]) extends PaymentGateway:
  val providerName: PaymentProvider = PaymentProvider.Mock

  def initPayment(request: InitPaymentRequest): Task[InitPaymentResponse] =
    for {
      externalId <- ZIO.succeed(s"mock-${request.orderId}")
      _          <- payments.update(_ + (externalId -> GatewayPaymentStatus.Confirmed))
      _          <- ZIO.logInfo(s"Биллинг Mock: платёж инициирован orderId=${request.orderId}, сумма=${request.amount.toRubles} руб")
    } yield InitPaymentResponse(
      externalId = externalId,
      paymentUrl = s"https://mock-payment.wayrecall.com/pay/$externalId",
      status = GatewayPaymentStatus.Confirmed
    )

  def checkStatus(externalId: String): Task[GatewayPaymentStatus] =
    payments.get.map(_.getOrElse(externalId, GatewayPaymentStatus.New))

  def confirmPayment(externalId: String): Task[Unit] =
    payments.update(_.updated(externalId, GatewayPaymentStatus.Confirmed))

  def cancelPayment(externalId: String): Task[Unit] =
    payments.update(_.updated(externalId, GatewayPaymentStatus.Cancelled))

  def refundPayment(externalId: String, amount: Money): Task[String] =
    for {
      refundId <- ZIO.succeed(s"refund-$externalId")
      _        <- payments.update(_.updated(externalId, GatewayPaymentStatus.Refunded))
      _        <- ZIO.logInfo(s"Биллинг Mock: возврат $refundId, сумма=${amount.toRubles} руб")
    } yield refundId

object MockPaymentGateway:
  val live: ZLayer[Any, Nothing, PaymentGateway] =
    ZLayer {
      Ref.make(Map.empty[String, GatewayPaymentStatus]).map(MockPaymentGateway(_))
    }

// ============================================================
// Tinkoff Gateway — заглушка (конфигурация читается из config)
// ============================================================

/**
 * Тинькофф Эквайринг — реализация при подключении.
 * Сейчас — заглушка для компиляции, готовая к интеграции.
 */
final case class TinkoffPaymentGateway(
  terminalKey: String,
  secretKey: String,
  apiUrl: String
) extends PaymentGateway:
  val providerName: PaymentProvider = PaymentProvider.Tinkoff

  def initPayment(request: InitPaymentRequest): Task[InitPaymentResponse] =
    // TODO: HTTP POST на Tinkoff API /Init
    ZIO.fail(new RuntimeException("Тинькофф: интеграция ещё не реализована"))

  def checkStatus(externalId: String): Task[GatewayPaymentStatus] =
    ZIO.fail(new RuntimeException("Тинькофф: интеграция ещё не реализована"))

  def confirmPayment(externalId: String): Task[Unit] =
    ZIO.fail(new RuntimeException("Тинькофф: интеграция ещё не реализована"))

  def cancelPayment(externalId: String): Task[Unit] =
    ZIO.fail(new RuntimeException("Тинькофф: интеграция ещё не реализована"))

  def refundPayment(externalId: String, amount: Money): Task[String] =
    ZIO.fail(new RuntimeException("Тинькофф: интеграция ещё не реализована"))

// ============================================================
// Sber Gateway — заглушка
// ============================================================

final case class SberPaymentGateway(
  merchantLogin: String,
  merchantPassword: String,
  apiUrl: String
) extends PaymentGateway:
  val providerName: PaymentProvider = PaymentProvider.Sber

  def initPayment(request: InitPaymentRequest): Task[InitPaymentResponse] =
    ZIO.fail(new RuntimeException("Сбер: интеграция ещё не реализована"))

  def checkStatus(externalId: String): Task[GatewayPaymentStatus] =
    ZIO.fail(new RuntimeException("Сбер: интеграция ещё не реализована"))

  def confirmPayment(externalId: String): Task[Unit] =
    ZIO.fail(new RuntimeException("Сбер: интеграция ещё не реализована"))

  def cancelPayment(externalId: String): Task[Unit] =
    ZIO.fail(new RuntimeException("Сбер: интеграция ещё не реализована"))

  def refundPayment(externalId: String, amount: Money): Task[String] =
    ZIO.fail(new RuntimeException("Сбер: интеграция ещё не реализована"))

// ============================================================
// YooKassa Gateway — заглушка
// ============================================================

final case class YooKassaPaymentGateway(
  shopId: String,
  secretKey: String,
  apiUrl: String
) extends PaymentGateway:
  val providerName: PaymentProvider = PaymentProvider.YooKassa

  def initPayment(request: InitPaymentRequest): Task[InitPaymentResponse] =
    ZIO.fail(new RuntimeException("ЮKassa: интеграция ещё не реализована"))

  def checkStatus(externalId: String): Task[GatewayPaymentStatus] =
    ZIO.fail(new RuntimeException("ЮKassa: интеграция ещё не реализована"))

  def confirmPayment(externalId: String): Task[Unit] =
    ZIO.fail(new RuntimeException("ЮKassa: интеграция ещё не реализована"))

  def cancelPayment(externalId: String): Task[Unit] =
    ZIO.fail(new RuntimeException("ЮKassa: интеграция ещё не реализована"))

  def refundPayment(externalId: String, amount: Money): Task[String] =
    ZIO.fail(new RuntimeException("ЮKassa: интеграция ещё не реализована"))

// ============================================================
// PaymentGatewayProvider — фабрика шлюзов по провайдеру
// ============================================================

trait PaymentGatewayProvider:
  def gateway(provider: PaymentProvider): Task[PaymentGateway]
  def defaultGateway: Task[PaymentGateway]

final case class PaymentGatewayProviderLive(
  mock: MockPaymentGateway,
  defaultProvider: PaymentProvider
) extends PaymentGatewayProvider:

  def gateway(provider: PaymentProvider): Task[PaymentGateway] = provider match
    case PaymentProvider.Mock     => ZIO.succeed(mock)
    case PaymentProvider.Tinkoff  => ZIO.fail(BillingError.PaymentGatewayError("Tinkoff", "Провайдер ещё не подключён"))
    case PaymentProvider.Sber     => ZIO.fail(BillingError.PaymentGatewayError("Sber", "Провайдер ещё не подключён"))
    case PaymentProvider.YooKassa => ZIO.fail(BillingError.PaymentGatewayError("YooKassa", "Провайдер ещё не подключён"))
    case PaymentProvider.Manual   => ZIO.fail(BillingError.PaymentGatewayError("Manual", "Ручные платежи не проходят через шлюз"))

  def defaultGateway: Task[PaymentGateway] = gateway(defaultProvider)

object PaymentGatewayProvider:
  val live: ZLayer[Any, Nothing, PaymentGatewayProvider] =
    ZLayer {
      for {
        mockRef <- Ref.make(Map.empty[String, GatewayPaymentStatus])
        mock = MockPaymentGateway(mockRef)
      } yield PaymentGatewayProviderLive(mock, PaymentProvider.Mock)
    }
