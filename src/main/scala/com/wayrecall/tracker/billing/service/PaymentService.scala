package com.wayrecall.tracker.billing.service

import com.wayrecall.tracker.billing.domain.*
import com.wayrecall.tracker.billing.payment.*
import com.wayrecall.tracker.billing.repository.*
import zio.*
import java.time.Instant
import java.util.UUID

// ============================================================
// PaymentService — управление платежами (провайдер-агностичный)
// Работает с любым эквайрингом через PaymentGateway trait
// ============================================================

trait PaymentService:
  /** Инициировать платёж через эквайринг */
  def initiatePayment(req: CreatePaymentRequest): Task[PaymentResponse]

  /** Обработать callback от платёжной системы */
  def processCallback(externalId: String, status: GatewayPaymentStatus): Task[Payment]

  /** Ручное пополнение администратором */
  def manualTopUp(accountId: AccountId, req: ManualTopUpRequest): Task[BalanceTransaction]

  /** Получить платёж по ID */
  def getPayment(id: PaymentId): Task[Payment]

  /** История платежей аккаунта */
  def listPayments(accountId: AccountId, limit: Int, offset: Int): Task[List[Payment]]

  /** История баланса */
  def balanceHistory(accountId: AccountId, limit: Int, offset: Int): Task[List[BalanceTransaction]]

final case class PaymentServiceLive(
  paymentRepo: PaymentRepository,
  accountRepo: AccountRepository,
  balanceTxRepo: BalanceTransactionRepository,
  gatewayProvider: PaymentGatewayProvider
) extends PaymentService:

  def initiatePayment(req: CreatePaymentRequest): Task[PaymentResponse] =
    for {
      // Валидация
      _       <- ZIO.when(req.amount.toKopecks <= 0)(
                   ZIO.fail(BillingError.InvalidPaymentAmount(req.amount.toKopecks))
                 )
      account <- accountRepo.findById(req.accountId)
                   .someOrFail(BillingError.AccountNotFound(req.accountId.asString))

      // Создаём запись платежа
      now <- Clock.instant
      paymentId = PaymentId.generate
      payment = Payment(
        id           = paymentId,
        accountId    = req.accountId,
        amount       = req.amount,
        currency     = req.currency,
        status       = PaymentStatus.Created,
        provider     = req.provider,
        externalId   = None,
        paymentUrl   = None,
        description  = req.description,
        createdAt    = now,
        paidAt       = None,
        failedAt     = None,
        failureReason = None,
        metadata     = Map.empty
      )
      _ <- paymentRepo.create(payment)

      // Инициируем через шлюз
      gateway  <- gatewayProvider.gateway(req.provider)
      response <- gateway.initPayment(InitPaymentRequest(
        orderId       = paymentId.asString,
        amount        = req.amount,
        currency      = req.currency,
        description   = req.description,
        customerEmail = None,
        returnUrl     = None,
        metadata      = Map("accountId" -> req.accountId.asString)
      ))

      // Обновляем платёж с данными от шлюза
      updatedPayment = payment.copy(
        externalId = Some(response.externalId),
        paymentUrl = Some(response.paymentUrl),
        status     = mapGatewayStatus(response.status)
      )
      _ <- paymentRepo.update(updatedPayment)

      // Если Mock — сразу зачисляем (для разработки)
      _ <- ZIO.when(response.status == GatewayPaymentStatus.Confirmed)(
             creditAccount(account, updatedPayment.copy(status = PaymentStatus.Confirmed, paidAt = Some(now)))
           )

      _ <- ZIO.logInfo(s"Биллинг: платёж инициирован id=${paymentId.asString}, сумма=${req.amount.toRubles} руб, провайдер=${req.provider}")
    } yield PaymentResponse(
      payment    = updatedPayment,
      paymentUrl = Some(response.paymentUrl)
    )

  def processCallback(externalId: String, status: GatewayPaymentStatus): Task[Payment] =
    for {
      payment <- paymentRepo.findByExternalId(externalId)
                   .someOrFail(BillingError.PaymentNotFound(s"ext:$externalId"))
      _       <- ZIO.when(payment.status == PaymentStatus.Confirmed || payment.status == PaymentStatus.Refunded)(
                   ZIO.fail(BillingError.PaymentAlreadyProcessed(payment.id.asString))
                 )
      now     <- Clock.instant
      newStatus = mapGatewayStatus(status)
      updated = payment.copy(
        status = newStatus,
        paidAt = if newStatus == PaymentStatus.Confirmed then Some(now) else payment.paidAt,
        failedAt = status match
          case GatewayPaymentStatus.Failed(_) => Some(now)
          case _ => payment.failedAt,
        failureReason = status match
          case GatewayPaymentStatus.Failed(r) => Some(r)
          case _ => payment.failureReason
      )
      _ <- paymentRepo.update(updated)
      // Зачисляем на баланс при успехе
      _ <- ZIO.when(newStatus == PaymentStatus.Confirmed) {
             for {
               account <- accountRepo.findById(payment.accountId)
                            .someOrFail(BillingError.AccountNotFound(payment.accountId.asString))
               _ <- creditAccount(account, updated)
             } yield ()
           }
      _ <- ZIO.logInfo(s"Биллинг: callback платежа ext=$externalId, статус=$newStatus")
    } yield updated

  def manualTopUp(accountId: AccountId, req: ManualTopUpRequest): Task[BalanceTransaction] =
    for {
      _       <- ZIO.when(req.amount.toKopecks <= 0)(
                   ZIO.fail(BillingError.InvalidPaymentAmount(req.amount.toKopecks))
                 )
      account <- accountRepo.findById(accountId)
                   .someOrFail(BillingError.AccountNotFound(accountId.asString))
      now     <- Clock.instant
      newBalance = account.balance + req.amount
      tx = BalanceTransaction(
        id          = UUID.randomUUID(),
        accountId   = accountId,
        amount      = req.amount,
        balanceAfter = newBalance,
        txType      = TransactionType.ManualTopUp,
        description = req.description,
        referenceId = None,
        createdAt   = now
      )
      _ <- accountRepo.updateBalance(accountId, newBalance, now)
      _ <- balanceTxRepo.create(tx)
      // Разблокируем если был заблокирован
      _ <- ZIO.when(account.status == AccountStatus.Blocked && newBalance.isPositive)(
             accountRepo.updateStatus(accountId, AccountStatus.Active, now)
           )
      _ <- ZIO.logInfo(s"Биллинг: ручное пополнение account=${accountId.asString}, сумма=${req.amount.toRubles} руб")
    } yield tx

  def getPayment(id: PaymentId): Task[Payment] =
    paymentRepo.findById(id).someOrFail(BillingError.PaymentNotFound(id.asString))

  def listPayments(accountId: AccountId, limit: Int, offset: Int): Task[List[Payment]] =
    paymentRepo.listByAccount(accountId, limit, offset)

  def balanceHistory(accountId: AccountId, limit: Int, offset: Int): Task[List[BalanceTransaction]] =
    balanceTxRepo.listByAccount(accountId, limit, offset)

  // Зачисление средств на баланс аккаунта
  private def creditAccount(account: Account, payment: Payment): Task[Unit] =
    for {
      now <- Clock.instant
      newBalance = account.balance + payment.amount
      tx = BalanceTransaction(
        id           = UUID.randomUUID(),
        accountId    = account.id,
        amount       = payment.amount,
        balanceAfter = newBalance,
        txType       = TransactionType.TopUp,
        description  = s"Пополнение через ${payment.provider}",
        referenceId  = Some(payment.id.asString),
        createdAt    = now
      )
      _ <- accountRepo.updateBalance(account.id, newBalance, now)
      _ <- balanceTxRepo.create(tx)
      // Разблокируем если был заблокирован и баланс положительный
      _ <- ZIO.when(account.status == AccountStatus.Blocked && newBalance.isPositive)(
             accountRepo.updateStatus(account.id, AccountStatus.Active, now)
           )
    } yield ()

  private def mapGatewayStatus(status: GatewayPaymentStatus): PaymentStatus = status match
    case GatewayPaymentStatus.New        => PaymentStatus.Created
    case GatewayPaymentStatus.Pending    => PaymentStatus.Pending
    case GatewayPaymentStatus.Authorized => PaymentStatus.Authorized
    case GatewayPaymentStatus.Confirmed  => PaymentStatus.Confirmed
    case GatewayPaymentStatus.Cancelled  => PaymentStatus.Cancelled
    case GatewayPaymentStatus.Refunded   => PaymentStatus.Refunded
    case GatewayPaymentStatus.Failed(_)  => PaymentStatus.Failed

object PaymentServiceLive:
  val live: ZLayer[PaymentRepository & AccountRepository & BalanceTransactionRepository & PaymentGatewayProvider, Nothing, PaymentService] =
    ZLayer {
      for {
        paymentRepo <- ZIO.service[PaymentRepository]
        accountRepo <- ZIO.service[AccountRepository]
        balanceTxRepo <- ZIO.service[BalanceTransactionRepository]
        gatewayProvider <- ZIO.service[PaymentGatewayProvider]
      } yield PaymentServiceLive(paymentRepo, accountRepo, balanceTxRepo, gatewayProvider)
    }
