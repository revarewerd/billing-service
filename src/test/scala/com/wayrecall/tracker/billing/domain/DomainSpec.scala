package com.wayrecall.tracker.billing.domain

import zio.test.*
import zio.test.Assertion.*
import zio.*
import java.util.UUID

// ============================================================
// Тесты доменной модели биллинга
// ============================================================

object DomainSpec extends ZIOSpecDefault:

  def spec = suite("DomainSpec")(
    moneySpec,
    opaqueTypeSpec,
    accountStatusSpec,
    paymentStatusSpec,
    historyRetentionSpec
  ) @@ TestAspect.timeout(60.seconds)

  val moneySpec = suite("Money")(
    test("kopecks: создание и извлечение") {
      val m = Money.kopecks(15000)
      assertTrue(
        m.toKopecks == 15000L,
        m.toRubles == 150.0
      )
    },

    test("rubles: конвертация рублей в копейки") {
      val m = Money.rubles(99.99)
      assertTrue(m.toKopecks == 9999L)
    },

    test("zero: нулевая сумма") {
      val m = Money.zero
      assertTrue(
        m.toKopecks == 0L,
        m.isZero,
        !m.isPositive,
        !m.isNegative
      )
    },

    test("арифметика: сложение") {
      val a = Money.kopecks(500)
      val b = Money.kopecks(300)
      val sum = a + b
      assertTrue(sum.toKopecks == 800L)
    },

    test("арифметика: вычитание") {
      val a = Money.kopecks(500)
      val b = Money.kopecks(300)
      val diff = a - b
      assertTrue(diff.toKopecks == 200L)
    },

    test("negate: отрицательная сумма") {
      val m = Money.kopecks(1000)
      val neg = m.negate
      assertTrue(
        neg.toKopecks == -1000L,
        neg.isNegative,
        !neg.isPositive
      )
    },

    test("isPositive/isNegative/isZero — корректность") {
      val pos = Money.kopecks(1)
      val neg = Money.kopecks(-1)
      val zero = Money.zero
      assertTrue(
        pos.isPositive && !pos.isNegative && !pos.isZero,
        neg.isNegative && !neg.isPositive && !neg.isZero,
        zero.isZero && !zero.isPositive && !zero.isNegative
      )
    }
  )

  val opaqueTypeSpec = suite("Opaque Types")(
    test("AccountId: generate и fromString") {
      val id = AccountId.generate
      val str = id.asString
      val parsed = AccountId.fromString(str)
      assertTrue(parsed == Right(id))
    },

    test("AccountId: некорректная строка") {
      val result = AccountId.fromString("not-a-uuid")
      assertTrue(result.isLeft)
    },

    test("TariffId: generate и fromString") {
      val id = TariffId.generate
      val str = id.asString
      val parsed = TariffId.fromString(str)
      assertTrue(parsed == Right(id))
    },

    test("SubscriptionId: generate и fromString") {
      val id = SubscriptionId.generate
      val str = id.asString
      val parsed = SubscriptionId.fromString(str)
      assertTrue(parsed == Right(id))
    },

    test("PaymentId: generate и fromString") {
      val id = PaymentId.generate
      val str = id.asString
      val parsed = PaymentId.fromString(str)
      assertTrue(parsed == Right(id))
    },

    test("InvoiceId: generate и fromString") {
      val id = InvoiceId.generate
      val str = id.asString
      val parsed = InvoiceId.fromString(str)
      assertTrue(parsed == Right(id))
    },

    test("OrganizationId: generate и fromString") {
      val id = OrganizationId(UUID.randomUUID())
      val str = id.asString
      val parsed = OrganizationId.fromString(str)
      assertTrue(parsed == Right(id))
    },

    test("Money: сериализуется как Long (копейки)") {
      // Opaque type Money = Long — сериализация через Long
      val m = Money.kopecks(5000)
      assertTrue(
        m.toKopecks == 5000L,
        m.toRubles == 50.0
      )
    }
  )

  val accountStatusSpec = suite("AccountStatus")(
    test("все статусы перечислены") {
      val statuses = AccountStatus.values
      assertTrue(statuses.length == 4)
    },

    test("JSON roundtrip") {
      import zio.json.*
      val status = AccountStatus.Active
      val json = status.toJson
      val decoded = json.fromJson[AccountStatus]
      assertTrue(decoded == Right(AccountStatus.Active))
    }
  )

  val paymentStatusSpec = suite("PaymentStatus")(
    test("все статусы перечислены") {
      val statuses = PaymentStatus.values
      assertTrue(statuses.length == 7)
    },

    test("PaymentProvider: все провайдеры перечислены") {
      val providers = PaymentProvider.values
      assertTrue(providers.length == 5)
    }
  )

  val historyRetentionSpec = suite("HistoryRetention")(
    test("Days retention") {
      import zio.json.*
      val r = HistoryRetention.Days(30)
      val json = r.toJson
      assertTrue(json.nonEmpty)
    },

    test("Unlimited retention") {
      import zio.json.*
      val r: HistoryRetention = HistoryRetention.Unlimited
      val json = r.toJson
      assertTrue(json.nonEmpty)
    }
  )
