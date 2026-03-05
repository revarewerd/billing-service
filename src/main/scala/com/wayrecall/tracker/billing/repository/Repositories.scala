package com.wayrecall.tracker.billing.repository

import com.wayrecall.tracker.billing.domain.*
import zio.*
import java.time.Instant
import java.util.UUID

// ============================================================
// Репозитории Billing Service
// ============================================================

// ---- Account Repository ----

trait AccountRepository:
  def create(account: Account): Task[Account]
  def findById(id: AccountId): Task[Option[Account]]
  def findByOrganization(orgId: OrganizationId): Task[Option[Account]]
  def update(account: Account): Task[Account]
  def updateBalance(id: AccountId, newBalance: Money, updatedAt: Instant): Task[Unit]
  def updateStatus(id: AccountId, status: AccountStatus, updatedAt: Instant): Task[Unit]
  def listAll(limit: Int, offset: Int): Task[List[Account]]
  def listActive(): Task[List[Account]]
  def softDelete(id: AccountId, deletedAt: Instant): Task[Unit]

object AccountRepository:
  def create(account: Account): ZIO[AccountRepository, Throwable, Account] =
    ZIO.serviceWithZIO[AccountRepository](_.create(account))
  def findById(id: AccountId): ZIO[AccountRepository, Throwable, Option[Account]] =
    ZIO.serviceWithZIO[AccountRepository](_.findById(id))
  def findByOrganization(orgId: OrganizationId): ZIO[AccountRepository, Throwable, Option[Account]] =
    ZIO.serviceWithZIO[AccountRepository](_.findByOrganization(orgId))

// ---- Tariff Repository ----

trait TariffRepository:
  def create(tariff: TariffPlan): Task[TariffPlan]
  def findById(id: TariffId): Task[Option[TariffPlan]]
  def update(tariff: TariffPlan): Task[TariffPlan]
  def listAll(): Task[List[TariffPlan]]
  def listPublic(): Task[List[TariffPlan]]
  def findDefault(): Task[Option[TariffPlan]]
  def countAccounts(tariffId: TariffId): Task[Int]
  def softDelete(id: TariffId, deletedAt: Instant): Task[Unit]

object TariffRepository:
  def findById(id: TariffId): ZIO[TariffRepository, Throwable, Option[TariffPlan]] =
    ZIO.serviceWithZIO[TariffRepository](_.findById(id))
  def listPublic(): ZIO[TariffRepository, Throwable, List[TariffPlan]] =
    ZIO.serviceWithZIO[TariffRepository](_.listPublic())

// ---- Subscription Repository ----

trait SubscriptionRepository:
  def create(sub: Subscription): Task[Subscription]
  def findById(id: SubscriptionId): Task[Option[Subscription]]
  def findByDevice(deviceId: UUID): Task[Option[Subscription]]
  def listByAccount(accountId: AccountId): Task[List[Subscription]]
  def countActiveByAccount(accountId: AccountId): Task[Int]
  def update(sub: Subscription): Task[Subscription]
  def deactivate(id: SubscriptionId, deactivatedAt: Instant): Task[Unit]

// ---- Payment Repository ----

trait PaymentRepository:
  def create(payment: Payment): Task[Payment]
  def findById(id: PaymentId): Task[Option[Payment]]
  def findByExternalId(externalId: String): Task[Option[Payment]]
  def update(payment: Payment): Task[Payment]
  def listByAccount(accountId: AccountId, limit: Int, offset: Int): Task[List[Payment]]

// ---- Balance Transaction Repository ----

trait BalanceTransactionRepository:
  def create(tx: BalanceTransaction): Task[BalanceTransaction]
  def listByAccount(accountId: AccountId, limit: Int, offset: Int): Task[List[BalanceTransaction]]
  def sumByType(accountId: AccountId, txType: TransactionType, from: Instant, to: Instant): Task[Money]

// ---- Invoice Repository ----

trait InvoiceRepository:
  def create(invoice: Invoice): Task[Invoice]
  def findById(id: InvoiceId): Task[Option[Invoice]]
  def listByAccount(accountId: AccountId, limit: Int, offset: Int): Task[List[Invoice]]
  def update(invoice: Invoice): Task[Invoice]
  def findOverdue(): Task[List[Invoice]]
