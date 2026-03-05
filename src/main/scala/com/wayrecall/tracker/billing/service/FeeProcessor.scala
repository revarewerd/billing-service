package com.wayrecall.tracker.billing.service

import com.wayrecall.tracker.billing.domain.*
import com.wayrecall.tracker.billing.repository.*
import zio.*
import java.time.Instant
import java.util.UUID

// ============================================================
// FeeProcessor — ежедневное списание абонентской платы
// Аналог legacy FeeProcessor.dailySubtractWithDetails()
// ============================================================

trait FeeProcessor:
  /** Рассчитать дневную стоимость для аккаунта */
  def calculateDailyFee(accountId: AccountId): Task[FeeCalculation]

  /** Выполнить ежедневное списание для одного аккаунта */
  def chargeDailyFee(accountId: AccountId): Task[BalanceTransaction]

  /** Выполнить ежедневное списание для всех активных аккаунтов */
  def chargeAllAccounts(): Task[List[(AccountId, Either[Throwable, BalanceTransaction])]]

final case class FeeProcessorLive(
  accountRepo: AccountRepository,
  tariffRepo: TariffRepository,
  subscriptionRepo: SubscriptionRepository,
  balanceTxRepo: BalanceTransactionRepository
) extends FeeProcessor:

  def calculateDailyFee(accountId: AccountId): Task[FeeCalculation] =
    for {
      account <- accountRepo.findById(accountId)
                   .someOrFail(BillingError.AccountNotFound(accountId.asString))
      tariff  <- account.tariffId match
        case Some(tid) => tariffRepo.findById(tid).someOrFail(BillingError.TariffNotFound(tid.asString))
        case None      => ZIO.fail(BillingError.TariffNotFound("не назначен"))
      subs    <- subscriptionRepo.listByAccount(accountId)
      activeSubs = subs.filter(_.status == SubscriptionStatus.Active)

      // Расчёт стоимости по каждому устройству
      items = activeSubs.map { sub =>
        val basePrice = tariff.abonentPrices
          .find(_.equipmentType == sub.equipmentType)
          .map(p => Money.kopecks((p.dailyRate.toKopecks * p.coefficient).toLong))
          .getOrElse(Money.zero)

        // Доп. сервисы
        val serviceCosts = sub.additionalServices.flatMap { code =>
          tariff.additionalServices.find(_.serviceCode == code).map { sp =>
            ServiceCostItem(sp.serviceCode, sp.name, sp.dailyRate)
          }
        }

        val totalServices = Money.kopecks(serviceCosts.map(_.cost.toKopecks).sum)

        FeeItem(
          deviceId      = sub.deviceId,
          equipmentType = sub.equipmentType,
          baseCost      = basePrice,
          serviceCosts  = serviceCosts
        )
      }

      dailyTotal = Money.kopecks(
        items.map(i => i.baseCost.toKopecks + i.serviceCosts.map(_.cost.toKopecks).sum).sum
      )
    } yield FeeCalculation(accountId, dailyTotal, items)

  def chargeDailyFee(accountId: AccountId): Task[BalanceTransaction] =
    for {
      fee     <- calculateDailyFee(accountId)
      account <- accountRepo.findById(accountId)
                   .someOrFail(BillingError.AccountNotFound(accountId.asString))
      now     <- Clock.instant

      chargeAmount = fee.dailyTotal.negate  // Отрицательная сумма — списание
      newBalance   = account.balance + chargeAmount

      // Создаём транзакцию списания
      tx = BalanceTransaction(
        id           = UUID.randomUUID(),
        accountId    = accountId,
        amount       = chargeAmount,
        balanceAfter = newBalance,
        txType       = TransactionType.DailyFee,
        description  = s"Ежедневное списание: ${fee.items.size} устройств, ${fee.dailyTotal.toRubles} руб",
        referenceId  = None,
        createdAt    = now
      )
      _ <- accountRepo.updateBalance(accountId, newBalance, now)
      _ <- balanceTxRepo.create(tx)

      // Проверяем, нужно ли блокировать (баланс стал отрицательным)
      _ <- ZIO.when(newBalance.isNegative && account.status == AccountStatus.Active) {
             for {
               _ <- accountRepo.updateStatus(accountId, AccountStatus.Suspended, now)
               _ <- ZIO.logWarning(s"Биллинг: аккаунт приостановлен (баланс отрицательный) id=${accountId.asString}, баланс=${newBalance.toRubles} руб")
             } yield ()
           }

      _ <- ZIO.logDebug(s"Биллинг: списание account=${accountId.asString}, сумма=${fee.dailyTotal.toRubles} руб, баланс=${newBalance.toRubles} руб")
    } yield tx

  def chargeAllAccounts(): Task[List[(AccountId, Either[Throwable, BalanceTransaction])]] =
    for {
      accounts <- accountRepo.listActive()
      _        <- ZIO.logInfo(s"Биллинг: начало ежедневного списания для ${accounts.size} аккаунтов")
      results  <- ZIO.foreach(accounts) { account =>
        chargeDailyFee(account.id).either.map(account.id -> _)
      }
      successes = results.count(_._2.isRight)
      failures  = results.count(_._2.isLeft)
      _        <- ZIO.logInfo(s"Биллинг: списание завершено — успешно: $successes, ошибок: $failures")
    } yield results

object FeeProcessorLive:
  val live: ZLayer[AccountRepository & TariffRepository & SubscriptionRepository & BalanceTransactionRepository, Nothing, FeeProcessor] =
    ZLayer {
      for {
        accountRepo <- ZIO.service[AccountRepository]
        tariffRepo  <- ZIO.service[TariffRepository]
        subRepo     <- ZIO.service[SubscriptionRepository]
        txRepo      <- ZIO.service[BalanceTransactionRepository]
      } yield FeeProcessorLive(accountRepo, tariffRepo, subRepo, txRepo)
    }
