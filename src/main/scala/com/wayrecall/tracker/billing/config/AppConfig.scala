package com.wayrecall.tracker.billing.config

import zio.*
import zio.config.*
import zio.config.magnolia.deriveConfig
import zio.config.typesafe.TypesafeConfigProvider

// ============================================================
// Конфигурация Billing Service
// ============================================================

final case class ServerConfig(
  host: String,
  port: Int
)

final case class PostgresConfig(
  host: String,
  port: Int,
  database: String,
  user: String,
  password: String,
  maxPoolSize: Int
):
  def jdbcUrl: String = s"jdbc:postgresql://$host:$port/$database"

final case class KafkaConfig(
  bootstrapServers: String,
  consumerGroup: String,
  topics: KafkaTopicsConfig
)

final case class KafkaTopicsConfig(
  deviceEvents: String,
  billingEvents: String,
  billingCommands: String
)

final case class FeeProcessorConfig(
  dailyRunHour: Int,
  maxRetries: Int,
  retryDelaySeconds: Int
)

final case class TinkoffConfig(
  enabled: Boolean,
  terminalKey: String,
  secretKey: String,
  apiUrl: String
)

final case class SberConfig(
  enabled: Boolean,
  merchantLogin: String,
  merchantPassword: String,
  apiUrl: String
)

final case class YooKassaConfig(
  enabled: Boolean,
  shopId: String,
  secretKey: String,
  apiUrl: String
)

final case class PaymentGatewaysConfig(
  defaultProvider: String,
  tinkoff: TinkoffConfig,
  sber: SberConfig,
  yookassa: YooKassaConfig
)

final case class AppConfig(
  server: ServerConfig,
  postgres: PostgresConfig,
  kafka: KafkaConfig,
  feeProcessor: FeeProcessorConfig,
  paymentGateways: PaymentGatewaysConfig
)

object AppConfig:

  val live: ZLayer[Any, Config.Error, AppConfig] =
    ZLayer.fromZIO(
      ZIO.config[AppConfig](
        deriveConfig[AppConfig].mapKey(toKebabCase)
      )
    )

  // Тестовый слой (для юнит-тестов)
  val test: ULayer[AppConfig] = ZLayer.succeed(
    AppConfig(
      server = ServerConfig("localhost", 8099),
      postgres = PostgresConfig("localhost", 5432, "billing_test", "test", "test", 5),
      kafka = KafkaConfig("localhost:9092", "billing-test", KafkaTopicsConfig("device-events", "billing-events", "billing-commands")),
      feeProcessor = FeeProcessorConfig(3, 3, 60),
      paymentGateways = PaymentGatewaysConfig(
        defaultProvider = "mock",
        tinkoff = TinkoffConfig(false, "", "", ""),
        sber = SberConfig(false, "", "", ""),
        yookassa = YooKassaConfig(false, "", "", "")
      )
    )
  )
