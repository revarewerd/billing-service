package com.wayrecall.tracker.billing.domain

import zio.test.*
import zio.test.Assertion.*

// ============================================================
// Тесты типизированных ошибок биллинга
// ============================================================

object ErrorsSpec extends ZIOSpecDefault:

  def spec = suite("ErrorsSpec")(
    toResponseSpec,
    httpStatusSpec,
    messageSpec
  )

  val toResponseSpec = suite("toResponse")(
    test("AccountNotFound → ACCOUNT_NOT_FOUND") {
      val err = BillingError.AccountNotFound("acc-123")
      val resp = BillingError.toResponse(err)
      assertTrue(
        resp.error == "ACCOUNT_NOT_FOUND",
        resp.message.contains("acc-123")
      )
    },

    test("AccountAlreadyExists → ACCOUNT_EXISTS") {
      val err = BillingError.AccountAlreadyExists("org-456")
      val resp = BillingError.toResponse(err)
      assertTrue(resp.error == "ACCOUNT_EXISTS")
    },

    test("InsufficientBalance → INSUFFICIENT_BALANCE") {
      val err = BillingError.InsufficientBalance("acc-1", 10000, 5000)
      val resp = BillingError.toResponse(err)
      assertTrue(
        resp.error == "INSUFFICIENT_BALANCE",
        resp.message.contains("100.0"),  // 10000 копеек = 100 руб
        resp.message.contains("50.0")    // 5000 копеек = 50 руб
      )
    },

    test("ValidationError → с details") {
      val err = BillingError.ValidationError("email", "некорректный формат")
      val resp = BillingError.toResponse(err)
      assertTrue(
        resp.error == "VALIDATION_ERROR",
        resp.details.isDefined,
        resp.details.get.contains("email")
      )
    },

    test("PaymentGatewayError → GATEWAY_ERROR") {
      val err = BillingError.PaymentGatewayError("Tinkoff", "timeout")
      val resp = BillingError.toResponse(err)
      assertTrue(
        resp.error == "GATEWAY_ERROR",
        resp.message.contains("Tinkoff"),
        resp.message.contains("timeout")
      )
    },

    test("все ошибки покрыты toResponse") {
      // Проверяем что каждый тип ошибки возвращает корректный ErrorResponse
      val errors: List[BillingError] = List(
        BillingError.AccountNotFound("x"),
        BillingError.AccountAlreadyExists("x"),
        BillingError.AccountBlocked("x"),
        BillingError.InsufficientBalance("x", 100, 50),
        BillingError.TariffNotFound("x"),
        BillingError.TariffInUse("x", 5),
        BillingError.SubscriptionNotFound("x"),
        BillingError.DeviceAlreadySubscribed("x", "y"),
        BillingError.MaxDevicesReached("x", 100),
        BillingError.PaymentNotFound("x"),
        BillingError.PaymentAlreadyProcessed("x"),
        BillingError.PaymentGatewayError("x", "y"),
        BillingError.InvalidPaymentAmount(100),
        BillingError.InvoiceNotFound("x"),
        BillingError.ValidationError("x", "y"),
        BillingError.DatabaseError("x"),
        BillingError.KafkaError("x")
      )
      val responses = errors.map(BillingError.toResponse)
      assertTrue(
        responses.forall(_.error.nonEmpty),
        responses.forall(_.message.nonEmpty),
        responses.length == 17
      )
    }
  )

  val httpStatusSpec = suite("httpStatus")(
    test("AccountNotFound → 404") {
      assertTrue(BillingError.httpStatus(BillingError.AccountNotFound("x")) == 404)
    },

    test("AccountAlreadyExists → 409") {
      assertTrue(BillingError.httpStatus(BillingError.AccountAlreadyExists("x")) == 409)
    },

    test("AccountBlocked → 403") {
      assertTrue(BillingError.httpStatus(BillingError.AccountBlocked("x")) == 403)
    },

    test("InsufficientBalance → 402") {
      assertTrue(BillingError.httpStatus(BillingError.InsufficientBalance("x", 100, 50)) == 402)
    },

    test("PaymentGatewayError → 502") {
      assertTrue(BillingError.httpStatus(BillingError.PaymentGatewayError("x", "y")) == 502)
    },

    test("ValidationError → 422") {
      assertTrue(BillingError.httpStatus(BillingError.ValidationError("x", "y")) == 422)
    }
  )

  val messageSpec = suite("getMessage")(
    test("BillingError extends Throwable — getMessage" ) {
      val err: Throwable = BillingError.AccountNotFound("test-id")
      assertTrue(err.getMessage.contains("test-id"))
    },

    test("InsufficientBalance: суммы в рублях") {
      val err = BillingError.InsufficientBalance("acc", 15050, 3020)
      assertTrue(
        err.message.contains("150.5"),   // 15050 копеек
        err.message.contains("30.2")     // 3020 копеек
      )
    }
  )
