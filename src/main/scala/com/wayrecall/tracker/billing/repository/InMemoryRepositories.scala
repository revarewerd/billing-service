package com.wayrecall.tracker.billing.repository

import com.wayrecall.tracker.billing.domain.*
import zio.*
import java.time.Instant
import java.util.UUID

// ============================================================
// In-Memory реализации репозиториев (для MVP и тестов)
// Позже заменяются на Doobie (PostgreSQL)
// ============================================================

final case class InMemoryAccountRepository(ref: Ref[Map[AccountId, Account]]) extends AccountRepository:
  def create(account: Account): Task[Account] =
    ref.update(_ + (account.id -> account)).as(account)

  def findById(id: AccountId): Task[Option[Account]] =
    ref.get.map(_.get(id).filter(_.deletedAt.isEmpty))

  def findByOrganization(orgId: OrganizationId): Task[Option[Account]] =
    ref.get.map(_.values.find(a => a.organizationId == orgId && a.deletedAt.isEmpty))

  def update(account: Account): Task[Account] =
    ref.update(_ + (account.id -> account)).as(account)

  def updateBalance(id: AccountId, newBalance: Money, updatedAt: Instant): Task[Unit] =
    ref.update(_.updatedWith(id)(_.map(_.copy(balance = newBalance, updatedAt = updatedAt))))

  def updateStatus(id: AccountId, status: AccountStatus, updatedAt: Instant): Task[Unit] =
    ref.update(_.updatedWith(id)(_.map(_.copy(status = status, updatedAt = updatedAt))))

  def listAll(limit: Int, offset: Int): Task[List[Account]] =
    ref.get.map(_.values.filter(_.deletedAt.isEmpty).toList.sortBy(_.createdAt).drop(offset).take(limit))

  def listActive(): Task[List[Account]] =
    ref.get.map(_.values.filter(a => a.status == AccountStatus.Active && a.deletedAt.isEmpty).toList)

  def softDelete(id: AccountId, deletedAt: Instant): Task[Unit] =
    ref.update(_.updatedWith(id)(_.map(_.copy(deletedAt = Some(deletedAt), status = AccountStatus.Closed))))

object InMemoryAccountRepository:
  val live: ZLayer[Any, Nothing, AccountRepository] =
    ZLayer {
      Ref.make(Map.empty[AccountId, Account]).map(InMemoryAccountRepository(_))
    }

final case class InMemoryTariffRepository(ref: Ref[Map[TariffId, TariffPlan]]) extends TariffRepository:
  def create(tariff: TariffPlan): Task[TariffPlan] =
    ref.update(_ + (tariff.id -> tariff)).as(tariff)

  def findById(id: TariffId): Task[Option[TariffPlan]] =
    ref.get.map(_.get(id).filter(_.deletedAt.isEmpty))

  def update(tariff: TariffPlan): Task[TariffPlan] =
    ref.update(_ + (tariff.id -> tariff)).as(tariff)

  def listAll(): Task[List[TariffPlan]] =
    ref.get.map(_.values.filter(_.deletedAt.isEmpty).toList)

  def listPublic(): Task[List[TariffPlan]] =
    ref.get.map(_.values.filter(t => t.isPublic && t.deletedAt.isEmpty).toList)

  def findDefault(): Task[Option[TariffPlan]] =
    ref.get.map(_.values.find(t => t.isDefault && t.deletedAt.isEmpty))

  def countAccounts(tariffId: TariffId): Task[Int] =
    ZIO.succeed(0) // В InMemory нет связи — возвращаем 0

  def softDelete(id: TariffId, deletedAt: Instant): Task[Unit] =
    ref.update(_.updatedWith(id)(_.map(_.copy(deletedAt = Some(deletedAt)))))

object InMemoryTariffRepository:
  val live: ZLayer[Any, Nothing, TariffRepository] =
    ZLayer {
      Ref.make(Map.empty[TariffId, TariffPlan]).map(InMemoryTariffRepository(_))
    }

final case class InMemorySubscriptionRepository(ref: Ref[Map[SubscriptionId, Subscription]]) extends SubscriptionRepository:
  def create(sub: Subscription): Task[Subscription] =
    ref.update(_ + (sub.id -> sub)).as(sub)

  def findById(id: SubscriptionId): Task[Option[Subscription]] =
    ref.get.map(_.get(id))

  def findByDevice(deviceId: UUID): Task[Option[Subscription]] =
    ref.get.map(_.values.find(s => s.deviceId == deviceId && s.status == SubscriptionStatus.Active))

  def listByAccount(accountId: AccountId): Task[List[Subscription]] =
    ref.get.map(_.values.filter(_.accountId == accountId).toList)

  def countActiveByAccount(accountId: AccountId): Task[Int] =
    ref.get.map(_.values.count(s => s.accountId == accountId && s.status == SubscriptionStatus.Active))

  def update(sub: Subscription): Task[Subscription] =
    ref.update(_ + (sub.id -> sub)).as(sub)

  def deactivate(id: SubscriptionId, deactivatedAt: Instant): Task[Unit] =
    ref.update(_.updatedWith(id)(_.map(_.copy(status = SubscriptionStatus.Cancelled, deactivatedAt = Some(deactivatedAt)))))

object InMemorySubscriptionRepository:
  val live: ZLayer[Any, Nothing, SubscriptionRepository] =
    ZLayer {
      Ref.make(Map.empty[SubscriptionId, Subscription]).map(InMemorySubscriptionRepository(_))
    }

final case class InMemoryPaymentRepository(ref: Ref[Map[PaymentId, Payment]]) extends PaymentRepository:
  def create(payment: Payment): Task[Payment] =
    ref.update(_ + (payment.id -> payment)).as(payment)

  def findById(id: PaymentId): Task[Option[Payment]] =
    ref.get.map(_.get(id))

  def findByExternalId(externalId: String): Task[Option[Payment]] =
    ref.get.map(_.values.find(_.externalId.contains(externalId)))

  def update(payment: Payment): Task[Payment] =
    ref.update(_ + (payment.id -> payment)).as(payment)

  def listByAccount(accountId: AccountId, limit: Int, offset: Int): Task[List[Payment]] =
    ref.get.map(_.values.filter(_.accountId == accountId).toList.sortBy(_.createdAt).reverse.drop(offset).take(limit))

object InMemoryPaymentRepository:
  val live: ZLayer[Any, Nothing, PaymentRepository] =
    ZLayer {
      Ref.make(Map.empty[PaymentId, Payment]).map(InMemoryPaymentRepository(_))
    }

final case class InMemoryBalanceTransactionRepository(ref: Ref[List[BalanceTransaction]]) extends BalanceTransactionRepository:
  def create(tx: BalanceTransaction): Task[BalanceTransaction] =
    ref.update(tx :: _).as(tx)

  def listByAccount(accountId: AccountId, limit: Int, offset: Int): Task[List[BalanceTransaction]] =
    ref.get.map(_.filter(_.accountId == accountId).sortBy(_.createdAt).reverse.drop(offset).take(limit))

  def sumByType(accountId: AccountId, txType: TransactionType, from: Instant, to: Instant): Task[Money] =
    ref.get.map { txs =>
      val sum = txs
        .filter(t => t.accountId == accountId && t.txType == txType && !t.createdAt.isBefore(from) && t.createdAt.isBefore(to))
        .map(_.amount.toKopecks)
        .sum
      Money.kopecks(sum)
    }

object InMemoryBalanceTransactionRepository:
  val live: ZLayer[Any, Nothing, BalanceTransactionRepository] =
    ZLayer {
      Ref.make(List.empty[BalanceTransaction]).map(InMemoryBalanceTransactionRepository(_))
    }

final case class InMemoryInvoiceRepository(ref: Ref[Map[InvoiceId, Invoice]]) extends InvoiceRepository:
  def create(invoice: Invoice): Task[Invoice] =
    ref.update(_ + (invoice.id -> invoice)).as(invoice)

  def findById(id: InvoiceId): Task[Option[Invoice]] =
    ref.get.map(_.get(id))

  def listByAccount(accountId: AccountId, limit: Int, offset: Int): Task[List[Invoice]] =
    ref.get.map(_.values.filter(_.accountId == accountId).toList.sortBy(_.createdAt).reverse.drop(offset).take(limit))

  def update(invoice: Invoice): Task[Invoice] =
    ref.update(_ + (invoice.id -> invoice)).as(invoice)

  def findOverdue(): Task[List[Invoice]] =
    ref.get.map(_.values.filter(_.status == InvoiceStatus.Overdue).toList)

object InMemoryInvoiceRepository:
  val live: ZLayer[Any, Nothing, InvoiceRepository] =
    ZLayer {
      Ref.make(Map.empty[InvoiceId, Invoice]).map(InMemoryInvoiceRepository(_))
    }
