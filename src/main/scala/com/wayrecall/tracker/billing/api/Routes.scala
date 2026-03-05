package com.wayrecall.tracker.billing.api

import com.wayrecall.tracker.billing.domain.*
import com.wayrecall.tracker.billing.service.*
import zio.*
import zio.http.*
import zio.json.*

// ============================================================
// REST API маршруты Billing Service
// ============================================================

object BillingRoutes:

  val routes: Routes[AccountService & TariffService & PaymentService & SubscriptionService & FeeProcessor, Response] = Routes(

    // ---- Аккаунты ----

    Method.POST / "api" / "accounts" -> handler { (req: Request) =>
      (for {
        body    <- req.body.asString
        parsed  <- ZIO.fromEither(body.fromJson[CreateAccountRequest])
                    .mapError(e => new RuntimeException(s"Невалидный JSON: $e"))
        account <- ZIO.serviceWithZIO[AccountService](_.create(parsed))
      } yield Response.json(account.toJson).status(Status.Created))
        .catchAll(handleError)
    },

    Method.GET / "api" / "accounts" / string("id") -> handler { (id: String, _: Request) =>
      (for {
        accountId <- ZIO.fromEither(AccountId.fromString(id))
                      .mapError(e => new RuntimeException(e))
        account   <- ZIO.serviceWithZIO[AccountService](_.getById(accountId))
      } yield Response.json(account.toJson))
        .catchAll(handleError)
    },

    Method.GET / "api" / "accounts" / string("id") / "balance" -> handler { (id: String, _: Request) =>
      (for {
        accountId <- ZIO.fromEither(AccountId.fromString(id))
                      .mapError(e => new RuntimeException(e))
        balance   <- ZIO.serviceWithZIO[AccountService](_.getBalance(accountId))
      } yield Response.json(balance.toJson))
        .catchAll(handleError)
    },

    Method.PUT / "api" / "accounts" / string("id") -> handler { (id: String, req: Request) =>
      (for {
        accountId <- ZIO.fromEither(AccountId.fromString(id))
                      .mapError(e => new RuntimeException(e))
        body      <- req.body.asString
        parsed    <- ZIO.fromEither(body.fromJson[UpdateAccountRequest])
                      .mapError(e => new RuntimeException(s"Невалидный JSON: $e"))
        account   <- ZIO.serviceWithZIO[AccountService](_.update(accountId, parsed))
      } yield Response.json(account.toJson))
        .catchAll(handleError)
    },

    Method.DELETE / "api" / "accounts" / string("id") -> handler { (id: String, _: Request) =>
      (for {
        accountId <- ZIO.fromEither(AccountId.fromString(id))
                      .mapError(e => new RuntimeException(e))
        _         <- ZIO.serviceWithZIO[AccountService](_.delete(accountId))
      } yield Response.status(Status.NoContent))
        .catchAll(handleError)
    },

    Method.GET / "api" / "accounts" -> handler { (_: Request) =>
      ZIO.serviceWithZIO[AccountService](_.listAll(100, 0))
        .map(accounts => Response.json(accounts.toJson))
        .catchAll(handleError)
    },

    // ---- Тарифы ----

    Method.POST / "api" / "tariffs" -> handler { (req: Request) =>
      (for {
        body   <- req.body.asString
        parsed <- ZIO.fromEither(body.fromJson[CreateTariffRequest])
                    .mapError(e => new RuntimeException(s"Невалидный JSON: $e"))
        tariff <- ZIO.serviceWithZIO[TariffService](_.create(parsed))
      } yield Response.json(tariff.toJson).status(Status.Created))
        .catchAll(handleError)
    },

    Method.GET / "api" / "tariffs" -> handler { (_: Request) =>
      ZIO.serviceWithZIO[TariffService](_.listAll())
        .map(tariffs => Response.json(tariffs.toJson))
        .catchAll(handleError)
    },

    Method.GET / "api" / "tariffs" / "public" -> handler { (_: Request) =>
      ZIO.serviceWithZIO[TariffService](_.listPublic())
        .map(tariffs => Response.json(tariffs.toJson))
        .catchAll(handleError)
    },

    Method.GET / "api" / "tariffs" / string("id") -> handler { (id: String, _: Request) =>
      (for {
        tariffId <- ZIO.fromEither(TariffId.fromString(id))
                      .mapError(e => new RuntimeException(e))
        tariff   <- ZIO.serviceWithZIO[TariffService](_.getById(tariffId))
      } yield Response.json(tariff.toJson))
        .catchAll(handleError)
    },

    // ---- Подписки ----

    Method.POST / "api" / "subscriptions" -> handler { (req: Request) =>
      (for {
        body   <- req.body.asString
        parsed <- ZIO.fromEither(body.fromJson[CreateSubscriptionRequest])
                    .mapError(e => new RuntimeException(s"Невалидный JSON: $e"))
        sub    <- ZIO.serviceWithZIO[SubscriptionService](_.subscribe(parsed))
      } yield Response.json(sub.toJson).status(Status.Created))
        .catchAll(handleError)
    },

    Method.GET / "api" / "subscriptions" / "account" / string("accountId") -> handler { (accountId: String, _: Request) =>
      (for {
        aid  <- ZIO.fromEither(AccountId.fromString(accountId))
                  .mapError(e => new RuntimeException(e))
        subs <- ZIO.serviceWithZIO[SubscriptionService](_.listByAccount(aid))
      } yield Response.json(subs.toJson))
        .catchAll(handleError)
    },

    Method.DELETE / "api" / "subscriptions" / string("id") -> handler { (id: String, _: Request) =>
      (for {
        subId <- ZIO.fromEither(SubscriptionId.fromString(id))
                  .mapError(e => new RuntimeException(e))
        _     <- ZIO.serviceWithZIO[SubscriptionService](_.unsubscribe(subId))
      } yield Response.status(Status.NoContent))
        .catchAll(handleError)
    },

    // ---- Платежи ----

    Method.POST / "api" / "payments" -> handler { (req: Request) =>
      (for {
        body    <- req.body.asString
        parsed  <- ZIO.fromEither(body.fromJson[CreatePaymentRequest])
                    .mapError(e => new RuntimeException(s"Невалидный JSON: $e"))
        payment <- ZIO.serviceWithZIO[PaymentService](_.initiatePayment(parsed))
      } yield Response.json(payment.toJson).status(Status.Created))
        .catchAll(handleError)
    },

    Method.POST / "api" / "payments" / string("accountId") / "topup" -> handler { (accountId: String, req: Request) =>
      (for {
        aid    <- ZIO.fromEither(AccountId.fromString(accountId))
                    .mapError(e => new RuntimeException(e))
        body   <- req.body.asString
        parsed <- ZIO.fromEither(body.fromJson[ManualTopUpRequest])
                    .mapError(e => new RuntimeException(s"Невалидный JSON: $e"))
        tx     <- ZIO.serviceWithZIO[PaymentService](_.manualTopUp(aid, parsed))
      } yield Response.json(tx.toJson).status(Status.Created))
        .catchAll(handleError)
    },

    Method.GET / "api" / "payments" / string("accountId") / "history" -> handler { (accountId: String, _: Request) =>
      (for {
        aid     <- ZIO.fromEither(AccountId.fromString(accountId))
                    .mapError(e => new RuntimeException(e))
        history <- ZIO.serviceWithZIO[PaymentService](_.balanceHistory(aid, 100, 0))
      } yield Response.json(history.toJson))
        .catchAll(handleError)
    },

    // ---- Fee Processor ----

    Method.GET / "api" / "fees" / string("accountId") / "calculate" -> handler { (accountId: String, _: Request) =>
      (for {
        aid <- ZIO.fromEither(AccountId.fromString(accountId))
                .mapError(e => new RuntimeException(e))
        fee <- ZIO.serviceWithZIO[FeeProcessor](_.calculateDailyFee(aid))
      } yield Response.json(fee.toJson))
        .catchAll(handleError)
    },

    // Ручной запуск списания (для админов)
    Method.POST / "api" / "fees" / "charge-all" -> handler { (_: Request) =>
      ZIO.serviceWithZIO[FeeProcessor](_.chargeAllAccounts())
        .map(results =>
          val successes = results.count(_._2.isRight)
          val failures = results.count(_._2.isLeft)
          Response.json(s"""{"successes":$successes,"failures":$failures}""")
        )
        .catchAll(handleError)
    }
  )

  // Обработка ошибок — единый формат (принимает Throwable)
  private def handleError(err: Throwable): UIO[Response] = err match
    case e: BillingError =>
      val response = BillingError.toResponse(e)
      val status = Status.fromInt(BillingError.httpStatus(e)).getOrElse(Status.InternalServerError)
      ZIO.succeed(Response.json(response.toJson).status(status))
    case e =>
      ZIO.logError(s"Биллинг API: неожиданная ошибка — ${e.getMessage}") *>
      ZIO.succeed(
        Response.json("""{"code":"INTERNAL_ERROR","message":"Внутренняя ошибка сервера"}""")
          .status(Status.InternalServerError)
      )

object HealthRoutes:

  val routes: Routes[Any, Nothing] = Routes(
    Method.GET / "health" -> handler { (_: Request) =>
      ZIO.succeed(Response.json("""{"status":"UP","service":"billing-service"}"""))
    }
  )
