package com.wayrecall.tracker.billing.kafka

import com.wayrecall.tracker.billing.domain.*
import zio.*
import zio.json.*
import zio.kafka.producer.{Producer, ProducerSettings}
import zio.kafka.serde.Serde
import org.apache.kafka.clients.producer.ProducerRecord

// ============================================================
// BillingEventProducer — публикация событий биллинга в Kafka
// Топик: billing-events
// ============================================================

trait BillingEventProducer:
  def publish(event: BillingEvent): Task[Unit]

final case class BillingEventProducerLive(producer: Producer, topic: String) extends BillingEventProducer:
  def publish(event: BillingEvent): Task[Unit] =
    val key = event match
      case BillingEvent.AccountCreated(id, _, _, _)         => id.asString
      case BillingEvent.AccountBlocked(id, _, _)            => id.asString
      case BillingEvent.AccountUnblocked(id, _)             => id.asString
      case BillingEvent.PaymentReceived(id, _, _, _, _)     => id.asString
      case BillingEvent.DailyFeeCharged(id, _, _, _)        => id.asString
      case BillingEvent.BalanceLow(id, _, _, _)             => id.asString
      case BillingEvent.SubscriptionActivated(id, _, _, _)  => id.asString
      case BillingEvent.SubscriptionCancelled(id, _, _, _)  => id.asString

    val json = event.toJson
    val record = new ProducerRecord[String, String](topic, key, json)

    producer.produce(record, Serde.string, Serde.string)
      .unit
      .tapError(err => ZIO.logError(s"Биллинг Kafka: ошибка публикации события — $err"))

object BillingEventProducerLive:
  def live(topic: String): ZLayer[Producer, Nothing, BillingEventProducer] =
    ZLayer {
      ZIO.service[Producer].map(p => BillingEventProducerLive(p, topic))
    }
