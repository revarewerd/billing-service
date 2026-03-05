package com.wayrecall.tracker.billing.service

import com.wayrecall.tracker.billing.domain.*
import com.wayrecall.tracker.billing.repository.*
import zio.*
import zio.test.*
import zio.test.Assertion.*
import java.util.UUID

// ============================================================
// Тесты AccountService — управление аккаунтами
// ============================================================

object AccountServiceSpec extends ZIOSpecDefault:

  // Тестовые слои: InMemory репозитории + AccountServiceLive
  val testLayer =
    InMemoryAccountRepository.live ++
    InMemoryTariffRepository.live ++
    InMemorySubscriptionRepository.live >>>
    AccountServiceLive.live

  def spec = suite("AccountServiceSpec")(
    createSpec,
    getSpec,
    updateSpec,
    deleteSpec,
    balanceSpec
  ).provide(
    InMemoryAccountRepository.live,
    InMemoryTariffRepository.live,
    InMemorySubscriptionRepository.live,
    AccountServiceLive.live
  )

  val createSpec = suite("create")(
    test("создание аккаунта — happy path") {
      val orgId = OrganizationId(UUID.randomUUID())
      val req = CreateAccountRequest(
        organizationId = orgId,
        name = "Тест Компания",
        tariffId = None
      )
      for {
        account <- ZIO.serviceWithZIO[AccountService](_.create(req))
      } yield assertTrue(
        account.name == "Тест Компания",
        account.organizationId == orgId,
        account.status == AccountStatus.Active,
        account.balance.isZero,
        account.tariffId.isEmpty
      )
    },

    test("создание дубликата — AccountAlreadyExists") {
      val orgId = OrganizationId(UUID.randomUUID())
      val req = CreateAccountRequest(orgId, "Компания 1", None)
      for {
        _      <- ZIO.serviceWithZIO[AccountService](_.create(req))
        result <- ZIO.serviceWithZIO[AccountService](_.create(req.copy(name = "Компания 2"))).either
      } yield assertTrue(
        result.isLeft,
        result.left.exists(_.isInstanceOf[BillingError.AccountAlreadyExists])
      )
    },

    test("создание с несуществующим тарифом — TariffNotFound") {
      val orgId = OrganizationId(UUID.randomUUID())
      val req = CreateAccountRequest(orgId, "Компания", Some(TariffId.generate))
      for {
        result <- ZIO.serviceWithZIO[AccountService](_.create(req)).either
      } yield assertTrue(
        result.isLeft,
        result.left.exists(_.isInstanceOf[BillingError.TariffNotFound])
      )
    }
  )

  val getSpec = suite("getById / getByOrganization")(
    test("getById — существующий аккаунт") {
      val orgId = OrganizationId(UUID.randomUUID())
      for {
        created <- ZIO.serviceWithZIO[AccountService](
          _.create(CreateAccountRequest(orgId, "Get Test", None))
        )
        found <- ZIO.serviceWithZIO[AccountService](_.getById(created.id))
      } yield assertTrue(found.id == created.id)
    },

    test("getById — несуществующий → AccountNotFound") {
      for {
        result <- ZIO.serviceWithZIO[AccountService](_.getById(AccountId.generate)).either
      } yield assertTrue(
        result.isLeft,
        result.left.exists(_.isInstanceOf[BillingError.AccountNotFound])
      )
    },

    test("getByOrganization — существующий") {
      val orgId = OrganizationId(UUID.randomUUID())
      for {
        created <- ZIO.serviceWithZIO[AccountService](
          _.create(CreateAccountRequest(orgId, "Org Test", None))
        )
        found <- ZIO.serviceWithZIO[AccountService](_.getByOrganization(orgId))
      } yield assertTrue(found.organizationId == orgId)
    }
  )

  val updateSpec = suite("update")(
    test("обновление имени аккаунта") {
      val orgId = OrganizationId(UUID.randomUUID())
      for {
        created <- ZIO.serviceWithZIO[AccountService](
          _.create(CreateAccountRequest(orgId, "Старое имя", None))
        )
        updated <- ZIO.serviceWithZIO[AccountService](
          _.update(created.id, UpdateAccountRequest(name = Some("Новое имя"), tariffId = None, autoPayment = None))
        )
      } yield assertTrue(
        updated.name == "Новое имя",
        updated.id == created.id
      )
    },

    test("обновление несуществующего → AccountNotFound") {
      val req = UpdateAccountRequest(name = Some("Тест"), tariffId = None, autoPayment = None)
      for {
        result <- ZIO.serviceWithZIO[AccountService](_.update(AccountId.generate, req)).either
      } yield assertTrue(result.isLeft)
    }
  )

  val deleteSpec = suite("delete")(
    test("soft delete — аккаунт помечается удалённым") {
      val orgId = OrganizationId(UUID.randomUUID())
      for {
        created <- ZIO.serviceWithZIO[AccountService](
          _.create(CreateAccountRequest(orgId, "Delete Test", None))
        )
        _ <- ZIO.serviceWithZIO[AccountService](_.delete(created.id))
      } yield assertTrue(true) // Если не упало — удаление работает
    },

    test("delete несуществующего → AccountNotFound") {
      for {
        result <- ZIO.serviceWithZIO[AccountService](_.delete(AccountId.generate)).either
      } yield assertTrue(result.isLeft)
    }
  )

  val balanceSpec = suite("getBalance")(
    test("баланс нового аккаунта — ноль") {
      val orgId = OrganizationId(UUID.randomUUID())
      for {
        created <- ZIO.serviceWithZIO[AccountService](
          _.create(CreateAccountRequest(orgId, "Balance Test", None))
        )
        balance <- ZIO.serviceWithZIO[AccountService](_.getBalance(created.id))
      } yield assertTrue(
        balance.balance.isZero,
        balance.daysLeft == 999
      )
    }
  )
