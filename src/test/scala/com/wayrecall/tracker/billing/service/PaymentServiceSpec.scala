package com.wayrecall.tracker.billing.service

import com.wayrecall.tracker.billing.domain.*
import com.wayrecall.tracker.billing.payment.*
import com.wayrecall.tracker.billing.repository.*
import zio.*
import zio.test.*
import zio.test.Assertion.*
import java.util.UUID
import java.time.Instant

// ============================================================
// Тесты PaymentService — платежи, пополнения, баланс
// ============================================================

object PaymentServiceSpec extends ZIOSpecDefault:

  def spec = suite("PaymentServiceSpec")(
    initiatePaymentSpec,
    manualTopUpSpec,
    balanceHistorySpec,
    errorSpec
  ).provide(
    InMemoryAccountRepository.live,
    InMemoryTariffRepository.live,
    InMemorySubscriptionRepository.live,
    InMemoryPaymentRepository.live,
    InMemoryBalanceTransactionRepository.live,
    PaymentGatewayProvider.live,
    AccountServiceLive.live,
    PaymentServiceLive.live
  )

  // Хелпер: создать аккаунт для тестов
  private def createTestAccount(name: String = "Test Account"): ZIO[AccountService, Throwable, Account] =
    ZIO.serviceWithZIO[AccountService](_.create(
      CreateAccountRequest(OrganizationId(UUID.randomUUID()), name, None)
    ))

  val initiatePaymentSpec = suite("initiatePayment")(
    test("создание платежа через Mock — автоматическое зачисление") {
      for {
        account <- createTestAccount("Payment Test")
        payment <- ZIO.serviceWithZIO[PaymentService](_.initiatePayment(
          CreatePaymentRequest(
            accountId = account.id,
            amount = Money.kopecks(10000),  // 100 руб
            currency = Currency.RUB,
            provider = PaymentProvider.Mock,
            description = "Тестовое пополнение"
          )
        ))
      } yield assertTrue(
        payment.paymentUrl.isDefined,
        payment.payment.provider == PaymentProvider.Mock,
        payment.payment.amount.toKopecks == 10000L
      )
    },

    test("платёж с нулевой суммой — InvalidPaymentAmount") {
      for {
        account <- createTestAccount("Zero pay")
        result <- ZIO.serviceWithZIO[PaymentService](_.initiatePayment(
          CreatePaymentRequest(
            accountId = account.id,
            amount = Money.zero,
            currency = Currency.RUB,
            provider = PaymentProvider.Mock,
            description = "Нулевой платёж"
          )
        )).either
      } yield assertTrue(
        result.isLeft,
        result.left.exists(_.isInstanceOf[BillingError.InvalidPaymentAmount])
      )
    },

    test("платёж на несуществующий аккаунт — AccountNotFound") {
      for {
        result <- ZIO.serviceWithZIO[PaymentService](_.initiatePayment(
          CreatePaymentRequest(
            accountId = AccountId.generate,
            amount = Money.kopecks(5000),
            currency = Currency.RUB,
            provider = PaymentProvider.Mock,
            description = "Тест"
          )
        )).either
      } yield assertTrue(
        result.isLeft,
        result.left.exists(_.isInstanceOf[BillingError.AccountNotFound])
      )
    }
  )

  val manualTopUpSpec = suite("manualTopUp")(
    test("ручное пополнение — баланс увеличивается") {
      for {
        account <- createTestAccount("Manual TopUp")
        tx <- ZIO.serviceWithZIO[PaymentService](_.manualTopUp(
          account.id,
          ManualTopUpRequest(Money.kopecks(50000), "Admin пополнение")
        ))
      } yield assertTrue(
        tx.amount.toKopecks == 50000L,
        tx.txType == TransactionType.ManualTopUp,
        tx.balanceAfter.toKopecks == 50000L
      )
    },

    test("ручное пополнение — отрицательная сумма → InvalidPaymentAmount") {
      for {
        account <- createTestAccount("Neg TopUp")
        result <- ZIO.serviceWithZIO[PaymentService](_.manualTopUp(
          account.id,
          ManualTopUpRequest(Money.kopecks(-100), "Ошибка")
        )).either
      } yield assertTrue(result.isLeft)
    },

    test("пополнение несуществующего аккаунта → AccountNotFound") {
      for {
        result <- ZIO.serviceWithZIO[PaymentService](_.manualTopUp(
          AccountId.generate,
          ManualTopUpRequest(Money.kopecks(1000), "Тест")
        )).either
      } yield assertTrue(
        result.isLeft,
        result.left.exists(_.isInstanceOf[BillingError.AccountNotFound])
      )
    }
  )

  val balanceHistorySpec = suite("balanceHistory")(
    test("история баланса после пополнения") {
      for {
        account <- createTestAccount("History Test")
        _ <- ZIO.serviceWithZIO[PaymentService](_.manualTopUp(
          account.id, ManualTopUpRequest(Money.kopecks(10000), "Первое пополнение")
        ))
        _ <- ZIO.serviceWithZIO[PaymentService](_.manualTopUp(
          account.id, ManualTopUpRequest(Money.kopecks(20000), "Второе пополнение")
        ))
        history <- ZIO.serviceWithZIO[PaymentService](_.balanceHistory(account.id, 100, 0))
      } yield assertTrue(history.length == 2)
    }
  )

  val errorSpec = suite("errors")(
    test("getPayment — несуществующий → PaymentNotFound") {
      for {
        result <- ZIO.serviceWithZIO[PaymentService](_.getPayment(PaymentId.generate)).either
      } yield assertTrue(
        result.isLeft,
        result.left.exists(_.isInstanceOf[BillingError.PaymentNotFound])
      )
    }
  )
