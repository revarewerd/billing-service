package com.wayrecall.tracker.billing.payment

import com.wayrecall.tracker.billing.domain.*
import com.wayrecall.tracker.billing.payment.*
import zio.*
import zio.test.*
import java.util.UUID

// ============================================================
// Тесты MockPaymentGateway и PaymentGatewayProvider
// Покрытие: инициализация платежа, чек статуса, подтверждение,
//           отмена, возврат, фабрика провайдеров
// ============================================================

object PaymentGatewaySpec extends ZIOSpecDefault:

  private val testRequest = InitPaymentRequest(
    orderId = UUID.randomUUID().toString,
    amount = Money.kopecks(100_00L), // 100 рублей
    currency = Currency.RUB,
    description = "Тестовый платёж",
    customerEmail = Some("test@example.com"),
    returnUrl = Some("https://wayrecall.com/payment/success"),
    metadata = Map("source" -> "test")
  )

  def spec = suite("PaymentGateway")(
    mockGatewaySuite,
    providerSuite,
    gatewayStatusSuite,
    stubGatewaysSuite
  ) @@ TestAspect.timeout(60.seconds)

  val mockGatewaySuite = suite("MockPaymentGateway")(
    test("initPayment — создаёт платёж со статусом Confirmed") {
      for {
        gw       <- ZIO.service[PaymentGateway]
        response <- gw.initPayment(testRequest)
      } yield assertTrue(
        response.externalId.startsWith("mock-"),
        response.paymentUrl.contains("mock-payment.wayrecall.com"),
        response.status == GatewayPaymentStatus.Confirmed
      )
    }.provide(MockPaymentGateway.live),

    test("checkStatus — новый externalId → New") {
      for {
        gw     <- ZIO.service[PaymentGateway]
        status <- gw.checkStatus("nonexistent-id")
      } yield assertTrue(status == GatewayPaymentStatus.New)
    }.provide(MockPaymentGateway.live),

    test("checkStatus — после initPayment → Confirmed") {
      for {
        gw       <- ZIO.service[PaymentGateway]
        response <- gw.initPayment(testRequest)
        status   <- gw.checkStatus(response.externalId)
      } yield assertTrue(status == GatewayPaymentStatus.Confirmed)
    }.provide(MockPaymentGateway.live),

    test("cancelPayment — статус Cancelled") {
      for {
        gw       <- ZIO.service[PaymentGateway]
        response <- gw.initPayment(testRequest)
        _        <- gw.cancelPayment(response.externalId)
        status   <- gw.checkStatus(response.externalId)
      } yield assertTrue(status == GatewayPaymentStatus.Cancelled)
    }.provide(MockPaymentGateway.live),

    test("refundPayment — статус Refunded + ID возврата") {
      for {
        gw       <- ZIO.service[PaymentGateway]
        response <- gw.initPayment(testRequest)
        refundId <- gw.refundPayment(response.externalId, Money.kopecks(50_00L))
        status   <- gw.checkStatus(response.externalId)
      } yield assertTrue(
        refundId.startsWith("refund-"),
        status == GatewayPaymentStatus.Refunded
      )
    }.provide(MockPaymentGateway.live),

    test("confirmPayment — подтверждение") {
      for {
        gw <- ZIO.service[PaymentGateway]
        _  <- gw.confirmPayment("test-id")
        st <- gw.checkStatus("test-id")
      } yield assertTrue(st == GatewayPaymentStatus.Confirmed)
    }.provide(MockPaymentGateway.live),

    test("providerName — Mock") {
      for {
        gw <- ZIO.service[PaymentGateway]
      } yield assertTrue(gw.providerName == PaymentProvider.Mock)
    }.provide(MockPaymentGateway.live)
  )

  val providerSuite = suite("PaymentGatewayProvider")(
    test("gateway(Mock) — возвращает MockPaymentGateway") {
      for {
        provider <- ZIO.service[PaymentGatewayProvider]
        gw       <- provider.gateway(PaymentProvider.Mock)
      } yield assertTrue(gw.providerName == PaymentProvider.Mock)
    }.provide(PaymentGatewayProvider.live),

    test("gateway(Tinkoff) — ошибка 'не подключён'") {
      for {
        provider <- ZIO.service[PaymentGatewayProvider]
        result   <- provider.gateway(PaymentProvider.Tinkoff).either
      } yield assertTrue(result.isLeft)
    }.provide(PaymentGatewayProvider.live),

    test("gateway(Sber) — ошибка 'не подключён'") {
      for {
        provider <- ZIO.service[PaymentGatewayProvider]
        result   <- provider.gateway(PaymentProvider.Sber).either
      } yield assertTrue(result.isLeft)
    }.provide(PaymentGatewayProvider.live),

    test("gateway(YooKassa) — ошибка 'не подключён'") {
      for {
        provider <- ZIO.service[PaymentGatewayProvider]
        result   <- provider.gateway(PaymentProvider.YooKassa).either
      } yield assertTrue(result.isLeft)
    }.provide(PaymentGatewayProvider.live),

    test("gateway(Manual) — ошибка 'не через шлюз'") {
      for {
        provider <- ZIO.service[PaymentGatewayProvider]
        result   <- provider.gateway(PaymentProvider.Manual).either
      } yield assertTrue(result.isLeft)
    }.provide(PaymentGatewayProvider.live),

    test("defaultGateway — Mock (по умолчанию)") {
      for {
        provider <- ZIO.service[PaymentGatewayProvider]
        gw       <- provider.defaultGateway
      } yield assertTrue(gw.providerName == PaymentProvider.Mock)
    }.provide(PaymentGatewayProvider.live)
  )

  val gatewayStatusSuite = suite("GatewayPaymentStatus enum")(
    test("все статусы") {
      val statuses = List(
        GatewayPaymentStatus.New,
        GatewayPaymentStatus.Pending,
        GatewayPaymentStatus.Authorized,
        GatewayPaymentStatus.Confirmed,
        GatewayPaymentStatus.Cancelled,
        GatewayPaymentStatus.Refunded,
        GatewayPaymentStatus.Failed("test error")
      )
      assertTrue(statuses.length == 7)
    },

    test("Failed — содержит причину") {
      val failed = GatewayPaymentStatus.Failed("insufficient funds")
      assertTrue(failed match
        case GatewayPaymentStatus.Failed(reason) => reason == "insufficient funds"
        case _ => false
      )
    }
  )

  val stubGatewaysSuite = suite("Stub Gateways — ещё не реализованы")(
    test("TinkoffPaymentGateway — initPayment → ошибка") {
      val gw = TinkoffPaymentGateway("terminal", "secret", "https://api.tinkoff.ru")
      for {
        result <- gw.initPayment(testRequest).either
      } yield assertTrue(result.isLeft)
    },

    test("SberPaymentGateway — initPayment → ошибка") {
      val gw = SberPaymentGateway("login", "pass", "https://api.sber.ru")
      for {
        result <- gw.initPayment(testRequest).either
      } yield assertTrue(result.isLeft)
    },

    test("YooKassaPaymentGateway — initPayment → ошибка") {
      val gw = YooKassaPaymentGateway("shop", "secret", "https://api.yookassa.ru")
      for {
        result <- gw.initPayment(testRequest).either
      } yield assertTrue(result.isLeft)
    }
  )
