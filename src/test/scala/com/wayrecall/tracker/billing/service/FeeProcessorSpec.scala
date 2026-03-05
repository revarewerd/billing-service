package com.wayrecall.tracker.billing.service

import com.wayrecall.tracker.billing.domain.*
import com.wayrecall.tracker.billing.repository.*
import zio.*
import zio.test.*
import zio.test.Assertion.*
import java.util.UUID
import java.time.Instant

// ============================================================
// Тесты FeeProcessor — ежедневное списание абонентской платы
// ============================================================

object FeeProcessorSpec extends ZIOSpecDefault:

  def spec = suite("FeeProcessorSpec")(
    calculateFeeSpec,
    chargeFeeSpec,
    chargeAllSpec
  ).provide(
    InMemoryAccountRepository.live,
    InMemoryTariffRepository.live,
    InMemorySubscriptionRepository.live,
    InMemoryBalanceTransactionRepository.live,
    FeeProcessorLive.live
  )

  // Хелпер: создать аккаунт через репозиторий напрямую
  private def createAccountWithTariff(
    tariffId: TariffId,
    balance: Long = 100000L // 1000 руб по умолчанию
  ): ZIO[AccountRepository, Throwable, Account] =
    for {
      now <- Clock.instant
      account = Account(
        id             = AccountId.generate,
        organizationId = OrganizationId(UUID.randomUUID()),
        name           = "Test Account",
        balance        = Money.kopecks(balance),
        tariffId       = Some(tariffId),
        status         = AccountStatus.Active,
        autoPayment    = false,
        createdAt      = now,
        updatedAt      = now,
        blockedAt      = None,
        deletedAt      = None
      )
      created <- ZIO.serviceWithZIO[AccountRepository](_.create(account))
    } yield created

  // Хелпер: создать тариф
  private def createTestTariff(
    dailyRate: Long = 500L, // 5 руб в день за устройство
    coefficient: Double = 1.0
  ): ZIO[TariffRepository, Throwable, TariffPlan] =
    for {
      now <- Clock.instant
      tariff = TariffPlan(
        id              = TariffId.generate,
        name            = "Тест Тариф",
        description     = "Тестовый",
        abonentPrices   = List(
          EquipmentPrice("tracker", Money.kopecks(dailyRate), coefficient)
        ),
        additionalServices = List(
          ServicePrice("fuel_monitoring", "Мониторинг топлива", Money.kopecks(200), true)
        ),
        historyRetention = HistoryRetention.Days(90),
        maxDevices       = Some(10),
        isDefault        = false,
        isPublic         = true,
        createdAt        = now,
        updatedAt        = now,
        deletedAt        = None
      )
      created <- ZIO.serviceWithZIO[TariffRepository](_.create(tariff))
    } yield created

  // Хелпер: создать подписку
  private def createSubscription(
    accountId: AccountId,
    services: List[String] = List.empty
  ): ZIO[SubscriptionRepository, Throwable, Subscription] =
    for {
      now <- Clock.instant
      sub = Subscription(
        id                 = SubscriptionId.generate,
        accountId          = accountId,
        deviceId           = UUID.randomUUID(),
        equipmentType      = "tracker",
        status             = SubscriptionStatus.Active,
        activatedAt        = now,
        deactivatedAt      = None,
        additionalServices = services
      )
      created <- ZIO.serviceWithZIO[SubscriptionRepository](_.create(sub))
    } yield created

  val calculateFeeSpec = suite("calculateDailyFee")(
    test("расчёт для аккаунта с одним устройством — базовая стоимость") {
      for {
        tariff  <- createTestTariff(dailyRate = 500)
        account <- createAccountWithTariff(tariff.id)
        _       <- createSubscription(account.id)
        fee     <- ZIO.serviceWithZIO[FeeProcessor](_.calculateDailyFee(account.id))
      } yield assertTrue(
        fee.dailyTotal.toKopecks == 500L,
        fee.items.size == 1,
        fee.items.head.baseCost.toKopecks == 500L
      )
    },

    test("расчёт с доп. сервисом — учитываются допуслуги") {
      for {
        tariff  <- createTestTariff(dailyRate = 500)
        account <- createAccountWithTariff(tariff.id)
        _       <- createSubscription(account.id, List("fuel_monitoring"))
        fee     <- ZIO.serviceWithZIO[FeeProcessor](_.calculateDailyFee(account.id))
      } yield assertTrue(
        fee.dailyTotal.toKopecks == 700L, // 500 + 200
        fee.items.head.serviceCosts.size == 1,
        fee.items.head.serviceCosts.head.cost.toKopecks == 200L
      )
    },

    test("расчёт для аккаунта без тарифа — TariffNotFound") {
      for {
        now <- Clock.instant
        account = Account(
          id             = AccountId.generate,
          organizationId = OrganizationId(UUID.randomUUID()),
          name           = "No Tariff",
          balance        = Money.kopecks(10000),
          tariffId       = None,
          status         = AccountStatus.Active,
          autoPayment    = false,
          createdAt      = now,
          updatedAt      = now,
          blockedAt      = None,
          deletedAt      = None
        )
        _ <- ZIO.serviceWithZIO[AccountRepository](_.create(account))
        result <- ZIO.serviceWithZIO[FeeProcessor](_.calculateDailyFee(account.id)).either
      } yield assertTrue(
        result.isLeft,
        result.left.exists(_.isInstanceOf[BillingError.TariffNotFound])
      )
    },

    test("расчёт без подписок — нулевая стоимость") {
      for {
        tariff  <- createTestTariff(dailyRate = 500)
        account <- createAccountWithTariff(tariff.id)
        fee     <- ZIO.serviceWithZIO[FeeProcessor](_.calculateDailyFee(account.id))
      } yield assertTrue(
        fee.dailyTotal.isZero,
        fee.items.isEmpty
      )
    }
  )

  val chargeFeeSpec = suite("chargeDailyFee")(
    test("списание — баланс уменьшается, создаётся транзакция") {
      for {
        tariff  <- createTestTariff(dailyRate = 1000) // 10 руб/день
        account <- createAccountWithTariff(tariff.id, balance = 100000) // 1000 руб
        _       <- createSubscription(account.id)
        tx      <- ZIO.serviceWithZIO[FeeProcessor](_.chargeDailyFee(account.id))
      } yield assertTrue(
        tx.amount.isNegative,
        tx.txType == TransactionType.DailyFee,
        tx.balanceAfter.toKopecks == 99000L // 1000руб - 10руб = 990руб
      )
    },

    test("списание при отрицательном балансе — аккаунт приостанавливается") {
      for {
        tariff  <- createTestTariff(dailyRate = 60000) // 600 руб/день
        account <- createAccountWithTariff(tariff.id, balance = 50000) // 500 руб (меньше чем списание)
        _       <- createSubscription(account.id)
        tx      <- ZIO.serviceWithZIO[FeeProcessor](_.chargeDailyFee(account.id))
        updated <- ZIO.serviceWithZIO[AccountRepository](_.findById(account.id))
      } yield assertTrue(
        tx.balanceAfter.isNegative,
        updated.exists(_.status == AccountStatus.Suspended)
      )
    }
  )

  val chargeAllSpec = suite("chargeAllAccounts")(
    test("массовое списание — обрабатывает все активные аккаунты") {
      for {
        tariff   <- createTestTariff(dailyRate = 200)
        account1 <- createAccountWithTariff(tariff.id, balance = 100000)
        account2 <- createAccountWithTariff(tariff.id, balance = 100000)
        _        <- createSubscription(account1.id)
        _        <- createSubscription(account2.id)
        results  <- ZIO.serviceWithZIO[FeeProcessor](_.chargeAllAccounts())
        successes = results.count(_._2.isRight)
      } yield assertTrue(
        results.length >= 2,
        successes >= 2
      )
    }
  )
