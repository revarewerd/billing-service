package com.wayrecall.tracker.billing

import com.wayrecall.tracker.billing.api.*
import com.wayrecall.tracker.billing.config.AppConfig
import com.wayrecall.tracker.billing.infrastructure.TransactorLayer
import com.wayrecall.tracker.billing.kafka.*
import com.wayrecall.tracker.billing.payment.*
import com.wayrecall.tracker.billing.repository.*
import com.wayrecall.tracker.billing.service.*
import zio.*
import zio.http.*
import zio.kafka.consumer.{Consumer, ConsumerSettings}
import zio.kafka.producer.{Producer, ProducerSettings}
import zio.logging.backend.SLF4J

// ============================================================
// Main — точка входа Billing Service (порт 8099)
// Биллинг, оплата, тарифы, подписки
// ============================================================

object Main extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  /**
   * Слой конфигурации с производными sub-конфигами
   */
  private val configLayers =
    AppConfig.live.flatMap { env =>
      val config = env.get
      ZLayer.succeed(config) ++
      ZLayer.succeed(config.postgres) ++
      ZLayer.succeed(config.kafka) ++
      ZLayer.succeed(config.feeProcessor) ++
      ZLayer.succeed(config.paymentGateways)
    }

  /**
   * Kafka Consumer layer
   */
  private val kafkaConsumerLayer: ZLayer[AppConfig, Throwable, Consumer] =
    ZLayer.scoped {
      for {
        config <- ZIO.service[AppConfig]
        consumer <- Consumer.make(
          ConsumerSettings(List(config.kafka.bootstrapServers))
            .withGroupId(config.kafka.consumerGroup)
        )
      } yield consumer
    }

  /**
   * Kafka Producer layer
   */
  private val kafkaProducerLayer: ZLayer[AppConfig, Throwable, Producer] =
    ZLayer.scoped {
      for {
        config <- ZIO.service[AppConfig]
        producer <- Producer.make(
          ProducerSettings(List(config.kafka.bootstrapServers))
        )
      } yield producer
    }

  override def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    val program = for {
      config <- ZIO.service[AppConfig]
      _      <- ZIO.logInfo(s"=== Billing Service запускается на порту ${config.server.port} ===")

      // Собираем маршруты биллинга
      allRoutes = HealthRoutes.routes ++ BillingRoutes.routes

      // Запускаем HTTP-сервер
      _      <- Server.serve(allRoutes.toHttpApp)
    } yield ()

    program.provide(
      // Конфигурация
      configLayers,

      // БД
      TransactorLayer.live,

      // Kafka
      kafkaConsumerLayer,
      kafkaProducerLayer,

      // Kafka продюсер событий биллинга
      BillingEventProducerLive.live(billingEventsTopic),

      // Платёжные шлюзы
      PaymentGatewayProvider.live,

      // Репозитории (InMemory для MVP, потом Doobie)
      InMemoryAccountRepository.live,
      InMemoryTariffRepository.live,
      InMemorySubscriptionRepository.live,
      InMemoryPaymentRepository.live,
      InMemoryBalanceTransactionRepository.live,
      InMemoryInvoiceRepository.live,

      // Сервисы
      AccountServiceLive.live,
      TariffServiceLive.live,
      SubscriptionServiceLive.live,
      PaymentServiceLive.live,
      FeeProcessorLive.live,

      // HTTP-сервер
      Server.defaultWithPort(8099)
    )

  // Топик для событий биллинга (из конфигурации при необходимости)
  private val billingEventsTopic = "billing-events"
