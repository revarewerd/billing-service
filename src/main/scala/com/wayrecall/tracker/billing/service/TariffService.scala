package com.wayrecall.tracker.billing.service

import com.wayrecall.tracker.billing.domain.*
import com.wayrecall.tracker.billing.repository.*
import zio.*
import java.time.Instant

// ============================================================
// TariffService — управление тарифными планами
// ============================================================

trait TariffService:
  def create(req: CreateTariffRequest): Task[TariffPlan]
  def getById(id: TariffId): Task[TariffPlan]
  def update(id: TariffId, req: CreateTariffRequest): Task[TariffPlan]
  def delete(id: TariffId): Task[Unit]
  def listAll(): Task[List[TariffPlan]]
  def listPublic(): Task[List[TariffPlan]]
  def getDefault(): Task[Option[TariffPlan]]

final case class TariffServiceLive(
  tariffRepo: TariffRepository
) extends TariffService:

  def create(req: CreateTariffRequest): Task[TariffPlan] =
    for {
      now <- Clock.instant
      tariff = TariffPlan(
        id                 = TariffId.generate,
        name               = req.name,
        description        = req.description,
        abonentPrices      = req.abonentPrices,
        additionalServices = req.additionalServices,
        historyRetention   = req.historyRetention,
        maxDevices         = req.maxDevices,
        isDefault          = false,
        isPublic           = req.isPublic,
        createdAt          = now,
        updatedAt          = now,
        deletedAt          = None
      )
      created <- tariffRepo.create(tariff)
      _       <- ZIO.logInfo(s"Биллинг: тариф создан id=${created.id.asString}, name='${created.name}'")
    } yield created

  def getById(id: TariffId): Task[TariffPlan] =
    tariffRepo.findById(id).someOrFail(BillingError.TariffNotFound(id.asString))

  def update(id: TariffId, req: CreateTariffRequest): Task[TariffPlan] =
    for {
      existing <- getById(id)
      now      <- Clock.instant
      updated = existing.copy(
        name               = req.name,
        description        = req.description,
        abonentPrices      = req.abonentPrices,
        additionalServices = req.additionalServices,
        historyRetention   = req.historyRetention,
        maxDevices         = req.maxDevices,
        isPublic           = req.isPublic,
        updatedAt          = now
      )
      result <- tariffRepo.update(updated)
      _      <- ZIO.logInfo(s"Биллинг: тариф обновлён id=${id.asString}")
    } yield result

  def delete(id: TariffId): Task[Unit] =
    for {
      _ <- getById(id)
      // Проверяем что тариф не используется
      count <- tariffRepo.countAccounts(id)
      _     <- ZIO.when(count > 0)(ZIO.fail(BillingError.TariffInUse(id.asString, count)))
      now   <- Clock.instant
      _     <- tariffRepo.softDelete(id, now)
      _     <- ZIO.logInfo(s"Биллинг: тариф удалён id=${id.asString}")
    } yield ()

  def listAll(): Task[List[TariffPlan]] =
    tariffRepo.listAll()

  def listPublic(): Task[List[TariffPlan]] =
    tariffRepo.listPublic()

  def getDefault(): Task[Option[TariffPlan]] =
    tariffRepo.findDefault()

object TariffServiceLive:
  val live: ZLayer[TariffRepository, Nothing, TariffService] =
    ZLayer {
      ZIO.service[TariffRepository].map(TariffServiceLive(_))
    }
