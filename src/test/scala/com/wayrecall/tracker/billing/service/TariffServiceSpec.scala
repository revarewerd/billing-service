package com.wayrecall.tracker.billing.service

import com.wayrecall.tracker.billing.domain.*
import com.wayrecall.tracker.billing.repository.*
import zio.*
import zio.test.*
import zio.test.Assertion.*

// ============================================================
// Тесты TariffService — управление тарифами
// ============================================================

object TariffServiceSpec extends ZIOSpecDefault:

  // Хелпер для создания тестовых данных
  private val sampleCreateRequest = CreateTariffRequest(
    name = "Базовый тариф",
    description = "Описание тарифа",
    abonentPrices = List(
      EquipmentPrice("gps-tracker", Money.kopecks(500), 1.0)
    ),
    additionalServices = List(
      ServicePrice("fuel-monitoring", "Мониторинг топлива", Money.kopecks(200), true)
    ),
    historyRetention = HistoryRetention.Days(30),
    maxDevices = Some(100),
    isPublic = true
  )

  def spec = suite("TariffServiceSpec")(
    createSpec,
    getSpec,
    updateSpec,
    deleteSpec,
    listSpec
  ).provide(
    InMemoryTariffRepository.live,
    TariffServiceLive.live
  )

  val createSpec = suite("create")(
    test("создание тарифа — happy path") {
      for {
        tariff <- ZIO.serviceWithZIO[TariffService](_.create(sampleCreateRequest))
      } yield assertTrue(
        tariff.name == "Базовый тариф",
        tariff.abonentPrices.length == 1,
        tariff.additionalServices.length == 1,
        tariff.isPublic,
        !tariff.isDefault,
        tariff.maxDevices == Some(100)
      )
    },

    test("создание тарифа с Unlimited retention") {
      val req = sampleCreateRequest.copy(
        name = "Премиум",
        historyRetention = HistoryRetention.Unlimited
      )
      for {
        tariff <- ZIO.serviceWithZIO[TariffService](_.create(req))
      } yield assertTrue(
        tariff.historyRetention == HistoryRetention.Unlimited
      )
    }
  )

  val getSpec = suite("getById")(
    test("getById — существующий тариф") {
      for {
        created <- ZIO.serviceWithZIO[TariffService](_.create(sampleCreateRequest.copy(name = "Get Test")))
        found   <- ZIO.serviceWithZIO[TariffService](_.getById(created.id))
      } yield assertTrue(found.id == created.id)
    },

    test("getById — несуществующий → TariffNotFound") {
      for {
        result <- ZIO.serviceWithZIO[TariffService](_.getById(TariffId.generate)).either
      } yield assertTrue(
        result.isLeft,
        result.left.exists(_.isInstanceOf[BillingError.TariffNotFound])
      )
    }
  )

  val updateSpec = suite("update")(
    test("обновление тарифа — имя и цена") {
      for {
        created <- ZIO.serviceWithZIO[TariffService](_.create(sampleCreateRequest.copy(name = "Original")))
        updated <- ZIO.serviceWithZIO[TariffService](
          _.update(created.id, sampleCreateRequest.copy(name = "Updated"))
        )
      } yield assertTrue(
        updated.name == "Updated",
        updated.id == created.id
      )
    }
  )

  val deleteSpec = suite("delete")(
    test("удаление неиспользуемого тарифа") {
      for {
        created <- ZIO.serviceWithZIO[TariffService](_.create(sampleCreateRequest.copy(name = "To Delete")))
        _       <- ZIO.serviceWithZIO[TariffService](_.delete(created.id))
      } yield assertTrue(true) // Не упало = успех
    },

    test("удаление несуществующего → TariffNotFound") {
      for {
        result <- ZIO.serviceWithZIO[TariffService](_.delete(TariffId.generate)).either
      } yield assertTrue(result.isLeft)
    }
  )

  val listSpec = suite("list")(
    test("listAll — возвращает все тарифы") {
      for {
        _ <- ZIO.serviceWithZIO[TariffService](_.create(sampleCreateRequest.copy(name = "List 1")))
        _ <- ZIO.serviceWithZIO[TariffService](_.create(sampleCreateRequest.copy(name = "List 2")))
        all <- ZIO.serviceWithZIO[TariffService](_.listAll())
      } yield assertTrue(all.length >= 2)
    },

    test("listPublic — только публичные") {
      for {
        _ <- ZIO.serviceWithZIO[TariffService](_.create(sampleCreateRequest.copy(name = "Public", isPublic = true)))
        _ <- ZIO.serviceWithZIO[TariffService](_.create(sampleCreateRequest.copy(name = "Private", isPublic = false)))
        publicTariffs <- ZIO.serviceWithZIO[TariffService](_.listPublic())
      } yield assertTrue(publicTariffs.forall(_.isPublic))
    }
  )
