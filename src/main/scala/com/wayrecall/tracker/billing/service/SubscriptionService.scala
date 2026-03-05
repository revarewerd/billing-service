package com.wayrecall.tracker.billing.service

import com.wayrecall.tracker.billing.domain.*
import com.wayrecall.tracker.billing.repository.*
import zio.*
import java.time.Instant
import java.util.UUID

// ============================================================
// SubscriptionService — управление подписками устройств
// ============================================================

trait SubscriptionService:
  def subscribe(req: CreateSubscriptionRequest): Task[Subscription]
  def unsubscribe(id: SubscriptionId): Task[Unit]
  def pause(id: SubscriptionId): Task[Subscription]
  def resume(id: SubscriptionId): Task[Subscription]
  def getById(id: SubscriptionId): Task[Subscription]
  def listByAccount(accountId: AccountId): Task[List[Subscription]]
  def findByDevice(deviceId: UUID): Task[Option[Subscription]]

final case class SubscriptionServiceLive(
  subRepo: SubscriptionRepository,
  accountRepo: AccountRepository,
  tariffRepo: TariffRepository
) extends SubscriptionService:

  def subscribe(req: CreateSubscriptionRequest): Task[Subscription] =
    for {
      // Проверяем аккаунт
      account <- accountRepo.findById(req.accountId)
                   .someOrFail(BillingError.AccountNotFound(req.accountId.asString))
      // Проверяем что устройство не подписано
      existing <- subRepo.findByDevice(req.deviceId)
      _        <- ZIO.when(existing.isDefined)(
                    ZIO.fail(BillingError.DeviceAlreadySubscribed(req.deviceId.toString, req.accountId.asString))
                  )
      // Проверяем лимит устройств
      _ <- account.tariffId match
        case Some(tid) =>
          for {
            tariff <- tariffRepo.findById(tid)
            count  <- subRepo.countActiveByAccount(req.accountId)
            _ <- tariff.flatMap(_.maxDevices) match
              case Some(max) if count >= max =>
                ZIO.fail(BillingError.MaxDevicesReached(req.accountId.asString, max))
              case _ => ZIO.unit
          } yield ()
        case None => ZIO.unit

      now <- Clock.instant
      sub = Subscription(
        id                 = SubscriptionId.generate,
        accountId          = req.accountId,
        deviceId           = req.deviceId,
        equipmentType      = req.equipmentType,
        status             = SubscriptionStatus.Active,
        activatedAt        = now,
        deactivatedAt      = None,
        additionalServices = req.additionalServices
      )
      created <- subRepo.create(sub)
      _       <- ZIO.logInfo(s"Биллинг: подписка создана id=${created.id.asString}, device=${req.deviceId}, account=${req.accountId.asString}")
    } yield created

  def unsubscribe(id: SubscriptionId): Task[Unit] =
    for {
      _ <- getById(id)
      now <- Clock.instant
      _ <- subRepo.deactivate(id, now)
      _ <- ZIO.logInfo(s"Биллинг: подписка отменена id=${id.asString}")
    } yield ()

  def pause(id: SubscriptionId): Task[Subscription] =
    for {
      sub <- getById(id)
      updated = sub.copy(status = SubscriptionStatus.Paused)
      result <- subRepo.update(updated)
      _ <- ZIO.logInfo(s"Биллинг: подписка приостановлена id=${id.asString}")
    } yield result

  def resume(id: SubscriptionId): Task[Subscription] =
    for {
      sub <- getById(id)
      updated = sub.copy(status = SubscriptionStatus.Active)
      result <- subRepo.update(updated)
      _ <- ZIO.logInfo(s"Биллинг: подписка возобновлена id=${id.asString}")
    } yield result

  def getById(id: SubscriptionId): Task[Subscription] =
    subRepo.findById(id).someOrFail(BillingError.SubscriptionNotFound(id.asString))

  def listByAccount(accountId: AccountId): Task[List[Subscription]] =
    subRepo.listByAccount(accountId)

  def findByDevice(deviceId: UUID): Task[Option[Subscription]] =
    subRepo.findByDevice(deviceId)

object SubscriptionServiceLive:
  val live: ZLayer[SubscriptionRepository & AccountRepository & TariffRepository, Nothing, SubscriptionService] =
    ZLayer {
      for {
        subRepo     <- ZIO.service[SubscriptionRepository]
        accountRepo <- ZIO.service[AccountRepository]
        tariffRepo  <- ZIO.service[TariffRepository]
      } yield SubscriptionServiceLive(subRepo, accountRepo, tariffRepo)
    }
