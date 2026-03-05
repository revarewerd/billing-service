package com.wayrecall.tracker.billing.service

import com.wayrecall.tracker.billing.domain.*
import com.wayrecall.tracker.billing.repository.*
import zio.*
import java.time.Instant
import java.util.UUID

// ============================================================
// AccountService — управление биллинговыми аккаунтами
// ============================================================

trait AccountService:
  def create(req: CreateAccountRequest): Task[Account]
  def getById(id: AccountId): Task[Account]
  def getByOrganization(orgId: OrganizationId): Task[Account]
  def update(id: AccountId, req: UpdateAccountRequest): Task[Account]
  def getBalance(id: AccountId): Task[BalanceResponse]
  def delete(id: AccountId): Task[Unit]
  def listAll(limit: Int, offset: Int): Task[List[Account]]

final case class AccountServiceLive(
  accountRepo: AccountRepository,
  tariffRepo: TariffRepository,
  subscriptionRepo: SubscriptionRepository
) extends AccountService:

  def create(req: CreateAccountRequest): Task[Account] =
    for {
      // Проверяем, что аккаунт для организации ещё не существует
      existing <- accountRepo.findByOrganization(req.organizationId)
      _        <- ZIO.when(existing.isDefined)(
                    ZIO.fail(BillingError.AccountAlreadyExists(req.organizationId.asString))
                  )
      // Если указан тариф — проверяем его существование
      _        <- ZIO.foreachDiscard(req.tariffId) { tid =>
                    tariffRepo.findById(tid).flatMap {
                      case Some(_) => ZIO.unit
                      case None    => ZIO.fail(BillingError.TariffNotFound(tid.asString))
                    }
                  }
      now      <- Clock.instant
      account = Account(
        id             = AccountId.generate,
        organizationId = req.organizationId,
        name           = req.name,
        balance        = Money.zero,
        tariffId       = req.tariffId,
        status         = AccountStatus.Active,
        autoPayment    = false,
        createdAt      = now,
        updatedAt      = now,
        blockedAt      = None,
        deletedAt      = None
      )
      created <- accountRepo.create(account)
      _       <- ZIO.logInfo(s"Биллинг: аккаунт создан id=${created.id.asString}, org=${req.organizationId.asString}")
    } yield created

  def getById(id: AccountId): Task[Account] =
    accountRepo.findById(id).someOrFail(BillingError.AccountNotFound(id.asString))

  def getByOrganization(orgId: OrganizationId): Task[Account] =
    accountRepo.findByOrganization(orgId).someOrFail(BillingError.AccountNotFound(s"org:${orgId.asString}"))

  def update(id: AccountId, req: UpdateAccountRequest): Task[Account] =
    for {
      account <- getById(id)
      // Если меняем тариф — проверяем существование
      _       <- ZIO.foreachDiscard(req.tariffId) { tid =>
                   tariffRepo.findById(tid).flatMap {
                     case Some(_) => ZIO.unit
                     case None    => ZIO.fail(BillingError.TariffNotFound(tid.asString))
                   }
                 }
      now     <- Clock.instant
      updated = account.copy(
        name        = req.name.getOrElse(account.name),
        tariffId    = req.tariffId.orElse(account.tariffId),
        autoPayment = req.autoPayment.getOrElse(account.autoPayment),
        updatedAt   = now
      )
      result <- accountRepo.update(updated)
      _      <- ZIO.logInfo(s"Биллинг: аккаунт обновлён id=${id.asString}")
    } yield result

  def getBalance(id: AccountId): Task[BalanceResponse] =
    for {
      account <- getById(id)
      subs    <- subscriptionRepo.countActiveByAccount(id)
      // Расчёт дневной стоимости
      dailyCost <- account.tariffId match
        case Some(tid) =>
          tariffRepo.findById(tid).map {
            case Some(tariff) =>
              val base = tariff.abonentPrices.headOption.map(_.dailyRate).getOrElse(Money.zero)
              Money.kopecks(base.toKopecks * subs)
            case None => Money.zero
          }
        case None => ZIO.succeed(Money.zero)
      // Сколько дней хватит баланса
      daysLeft = if dailyCost.isZero || dailyCost.isNegative then 999
                 else (account.balance.toKopecks / dailyCost.toKopecks).toInt.max(0)
    } yield BalanceResponse(
      accountId = account.id,
      balance   = account.balance,
      dailyCost = dailyCost,
      daysLeft  = daysLeft,
      status    = account.status
    )

  def delete(id: AccountId): Task[Unit] =
    for {
      _ <- getById(id) // Проверяем что существует
      now <- Clock.instant
      _ <- accountRepo.softDelete(id, now)
      _ <- ZIO.logInfo(s"Биллинг: аккаунт удалён (soft) id=${id.asString}")
    } yield ()

  def listAll(limit: Int, offset: Int): Task[List[Account]] =
    accountRepo.listAll(limit, offset)

object AccountServiceLive:
  val live: ZLayer[AccountRepository & TariffRepository & SubscriptionRepository, Nothing, AccountService] =
    ZLayer {
      for {
        accountRepo <- ZIO.service[AccountRepository]
        tariffRepo  <- ZIO.service[TariffRepository]
        subRepo     <- ZIO.service[SubscriptionRepository]
      } yield AccountServiceLive(accountRepo, tariffRepo, subRepo)
    }
