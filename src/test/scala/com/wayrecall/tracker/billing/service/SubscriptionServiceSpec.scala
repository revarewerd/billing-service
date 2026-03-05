package com.wayrecall.tracker.billing.service

import com.wayrecall.tracker.billing.domain.*
import com.wayrecall.tracker.billing.repository.*
import zio.*
import zio.test.*
import zio.test.Assertion.*
import java.util.UUID
import java.time.Instant

// ============================================================
// Тесты SubscriptionService — управление подписками устройств
// ============================================================

object SubscriptionServiceSpec extends ZIOSpecDefault:

  def spec = suite("SubscriptionServiceSpec")(
    subscribeSpec,
    lifecycleSpec,
    querySpec,
    errorSpec
  ).provide(
    InMemoryAccountRepository.live,
    InMemoryTariffRepository.live,
    InMemorySubscriptionRepository.live,
    SubscriptionServiceLive.live
  )

  // Хелпер: создать аккаунт с тарифом (через репозитории)
  private def createAccountAndTariff(
    maxDevices: Option[Int] = Some(10)
  ): ZIO[AccountRepository & TariffRepository, Throwable, (Account, TariffPlan)] =
    for {
      now <- Clock.instant
      tariff = TariffPlan(
        id               = TariffId.generate,
        name             = "Тест Тариф",
        description      = "Тестовый",
        abonentPrices    = List(EquipmentPrice("tracker", Money.kopecks(500), 1.0)),
        additionalServices = List.empty,
        historyRetention = HistoryRetention.Days(90),
        maxDevices       = maxDevices,
        isDefault        = false,
        isPublic         = true,
        createdAt        = now,
        updatedAt        = now,
        deletedAt        = None
      )
      createdTariff <- ZIO.serviceWithZIO[TariffRepository](_.create(tariff))
      account = Account(
        id             = AccountId.generate,
        organizationId = OrganizationId(UUID.randomUUID()),
        name           = "Test Org",
        balance        = Money.kopecks(100000),
        tariffId       = Some(createdTariff.id),
        status         = AccountStatus.Active,
        autoPayment    = false,
        createdAt      = now,
        updatedAt      = now,
        blockedAt      = None,
        deletedAt      = None
      )
      createdAccount <- ZIO.serviceWithZIO[AccountRepository](_.create(account))
    } yield (createdAccount, createdTariff)

  val subscribeSpec = suite("subscribe")(
    test("подписка устройства — успешное создание") {
      for {
        (account, _) <- createAccountAndTariff()
        deviceId = UUID.randomUUID()
        sub <- ZIO.serviceWithZIO[SubscriptionService](_.subscribe(
          CreateSubscriptionRequest(account.id, deviceId, "tracker", List.empty)
        ))
      } yield assertTrue(
        sub.accountId == account.id,
        sub.deviceId == deviceId,
        sub.status == SubscriptionStatus.Active,
        sub.equipmentType == "tracker"
      )
    },

    test("дублирующая подписка устройства — DeviceAlreadySubscribed") {
      for {
        (account, _) <- createAccountAndTariff()
        deviceId = UUID.randomUUID()
        _ <- ZIO.serviceWithZIO[SubscriptionService](_.subscribe(
          CreateSubscriptionRequest(account.id, deviceId, "tracker", List.empty)
        ))
        result <- ZIO.serviceWithZIO[SubscriptionService](_.subscribe(
          CreateSubscriptionRequest(account.id, deviceId, "tracker", List.empty)
        )).either
      } yield assertTrue(
        result.isLeft,
        result.left.exists(_.isInstanceOf[BillingError.DeviceAlreadySubscribed])
      )
    },

    test("превышение лимита устройств — MaxDevicesReached") {
      for {
        (account, _) <- createAccountAndTariff(maxDevices = Some(1))
        _ <- ZIO.serviceWithZIO[SubscriptionService](_.subscribe(
          CreateSubscriptionRequest(account.id, UUID.randomUUID(), "tracker", List.empty)
        ))
        result <- ZIO.serviceWithZIO[SubscriptionService](_.subscribe(
          CreateSubscriptionRequest(account.id, UUID.randomUUID(), "tracker", List.empty)
        )).either
      } yield assertTrue(
        result.isLeft,
        result.left.exists(_.isInstanceOf[BillingError.MaxDevicesReached])
      )
    },

    test("подписка на несуществующий аккаунт — AccountNotFound") {
      for {
        result <- ZIO.serviceWithZIO[SubscriptionService](_.subscribe(
          CreateSubscriptionRequest(AccountId.generate, UUID.randomUUID(), "tracker", List.empty)
        )).either
      } yield assertTrue(
        result.isLeft,
        result.left.exists(_.isInstanceOf[BillingError.AccountNotFound])
      )
    }
  )

  val lifecycleSpec = suite("lifecycle")(
    test("пауза и возобновление подписки") {
      for {
        (account, _) <- createAccountAndTariff()
        sub <- ZIO.serviceWithZIO[SubscriptionService](_.subscribe(
          CreateSubscriptionRequest(account.id, UUID.randomUUID(), "tracker", List.empty)
        ))
        paused  <- ZIO.serviceWithZIO[SubscriptionService](_.pause(sub.id))
        _       = assertTrue(paused.status == SubscriptionStatus.Paused)
        resumed <- ZIO.serviceWithZIO[SubscriptionService](_.resume(sub.id))
      } yield assertTrue(
        paused.status == SubscriptionStatus.Paused,
        resumed.status == SubscriptionStatus.Active
      )
    },

    test("отмена подписки (unsubscribe)") {
      for {
        (account, _) <- createAccountAndTariff()
        sub <- ZIO.serviceWithZIO[SubscriptionService](_.subscribe(
          CreateSubscriptionRequest(account.id, UUID.randomUUID(), "tracker", List.empty)
        ))
        _ <- ZIO.serviceWithZIO[SubscriptionService](_.unsubscribe(sub.id))
        // После unsubscribe — findByDevice не должен находить
        found <- ZIO.serviceWithZIO[SubscriptionService](_.findByDevice(sub.deviceId))
      } yield assertTrue(found.isEmpty || found.exists(_.status == SubscriptionStatus.Cancelled))
    }
  )

  val querySpec = suite("queries")(
    test("listByAccount — все подписки аккаунта") {
      for {
        (account, _) <- createAccountAndTariff()
        _ <- ZIO.serviceWithZIO[SubscriptionService](_.subscribe(
          CreateSubscriptionRequest(account.id, UUID.randomUUID(), "tracker", List.empty)
        ))
        _ <- ZIO.serviceWithZIO[SubscriptionService](_.subscribe(
          CreateSubscriptionRequest(account.id, UUID.randomUUID(), "tracker", List.empty)
        ))
        subs <- ZIO.serviceWithZIO[SubscriptionService](_.listByAccount(account.id))
      } yield assertTrue(subs.length == 2)
    },

    test("findByDevice — поиск подписки по устройству") {
      for {
        (account, _) <- createAccountAndTariff()
        deviceId = UUID.randomUUID()
        _ <- ZIO.serviceWithZIO[SubscriptionService](_.subscribe(
          CreateSubscriptionRequest(account.id, deviceId, "tracker", List.empty)
        ))
        found <- ZIO.serviceWithZIO[SubscriptionService](_.findByDevice(deviceId))
      } yield assertTrue(
        found.isDefined,
        found.exists(_.deviceId == deviceId)
      )
    },

    test("findByDevice — несуществующее устройство → None") {
      for {
        found <- ZIO.serviceWithZIO[SubscriptionService](_.findByDevice(UUID.randomUUID()))
      } yield assertTrue(found.isEmpty)
    }
  )

  val errorSpec = suite("errors")(
    test("getById несуществующей подписки — SubscriptionNotFound") {
      for {
        result <- ZIO.serviceWithZIO[SubscriptionService](_.getById(SubscriptionId.generate)).either
      } yield assertTrue(
        result.isLeft,
        result.left.exists(_.isInstanceOf[BillingError.SubscriptionNotFound])
      )
    }
  )
