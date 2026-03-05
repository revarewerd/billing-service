package com.wayrecall.tracker.billing.infrastructure

import com.wayrecall.tracker.billing.config.PostgresConfig
import doobie.*
import doobie.hikari.HikariTransactor
import zio.*
import zio.interop.catz.*
import cats.effect.kernel.Resource

// ============================================================
// Слой Doobie Transactor для PostgreSQL
// ============================================================

object TransactorLayer:

  val live: ZLayer[PostgresConfig, Throwable, Transactor[Task]] =
    ZLayer.scoped {
      for {
        config <- ZIO.service[PostgresConfig]
        transactor <- HikariTransactor
          .newHikariTransactor[Task](
            driverClassName = "org.postgresql.Driver",
            url             = config.jdbcUrl,
            user            = config.user,
            pass            = config.password,
            connectEC       = scala.concurrent.ExecutionContext.global
          )
          .allocated
          .map(_._1)
          .tapError(err => ZIO.logError(s"Биллинг: ошибка подключения к БД — $err"))
      } yield transactor
    }
