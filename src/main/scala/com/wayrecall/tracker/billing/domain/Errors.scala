package com.wayrecall.tracker.billing.domain

import zio.json.*

// ============================================================
// Типизированные ошибки Billing Service
// ============================================================

sealed trait BillingError extends Throwable:
  def message: String
  override def getMessage: String = message

object BillingError:
  // Аккаунт
  final case class AccountNotFound(accountId: String)
    extends BillingError:
    val message = s"Аккаунт не найден: $accountId"

  final case class AccountAlreadyExists(organizationId: String)
    extends BillingError:
    val message = s"Аккаунт для организации уже существует: $organizationId"

  final case class AccountBlocked(accountId: String)
    extends BillingError:
    val message = s"Аккаунт заблокирован: $accountId"

  final case class InsufficientBalance(accountId: String, required: Long, available: Long)
    extends BillingError:
    val message = s"Недостаточно средств: $accountId (требуется: ${required/100.0} руб, доступно: ${available/100.0} руб)"

  // Тарифы
  final case class TariffNotFound(tariffId: String)
    extends BillingError:
    val message = s"Тарифный план не найден: $tariffId"

  final case class TariffInUse(tariffId: String, accountsCount: Int)
    extends BillingError:
    val message = s"Тариф используется ($accountsCount аккаунтов): $tariffId"

  // Подписки
  final case class SubscriptionNotFound(subscriptionId: String)
    extends BillingError:
    val message = s"Подписка не найдена: $subscriptionId"

  final case class DeviceAlreadySubscribed(deviceId: String, accountId: String)
    extends BillingError:
    val message = s"Устройство $deviceId уже подписано на аккаунт $accountId"

  final case class MaxDevicesReached(accountId: String, maxDevices: Int)
    extends BillingError:
    val message = s"Достигнут лимит устройств ($maxDevices) для аккаунта: $accountId"

  // Платежи
  final case class PaymentNotFound(paymentId: String)
    extends BillingError:
    val message = s"Платёж не найден: $paymentId"

  final case class PaymentAlreadyProcessed(paymentId: String)
    extends BillingError:
    val message = s"Платёж уже обработан: $paymentId"

  final case class PaymentGatewayError(provider: String, reason: String)
    extends BillingError:
    val message = s"Ошибка платёжного шлюза ($provider): $reason"

  final case class InvalidPaymentAmount(amount: Long)
    extends BillingError:
    val message = s"Некорректная сумма платежа: ${amount/100.0} руб"

  // Инвойсы
  final case class InvoiceNotFound(invoiceId: String)
    extends BillingError:
    val message = s"Счёт не найден: $invoiceId"

  // Общие
  final case class ValidationError(field: String, reason: String)
    extends BillingError:
    val message = s"Ошибка валидации поля '$field': $reason"

  final case class DatabaseError(cause: String)
    extends BillingError:
    val message = s"Ошибка базы данных: $cause"

  final case class KafkaError(cause: String)
    extends BillingError:
    val message = s"Ошибка Kafka: $cause"

  // JSON-ответ ошибки для REST API
  final case class ErrorResponse(
    error: String,
    message: String,
    details: Option[String] = None
  ) derives JsonEncoder

  def toResponse(err: BillingError): ErrorResponse = err match
    case e: AccountNotFound       => ErrorResponse("ACCOUNT_NOT_FOUND", e.message)
    case e: AccountAlreadyExists  => ErrorResponse("ACCOUNT_EXISTS", e.message)
    case e: AccountBlocked        => ErrorResponse("ACCOUNT_BLOCKED", e.message)
    case e: InsufficientBalance   => ErrorResponse("INSUFFICIENT_BALANCE", e.message)
    case e: TariffNotFound        => ErrorResponse("TARIFF_NOT_FOUND", e.message)
    case e: TariffInUse           => ErrorResponse("TARIFF_IN_USE", e.message)
    case e: SubscriptionNotFound  => ErrorResponse("SUBSCRIPTION_NOT_FOUND", e.message)
    case e: DeviceAlreadySubscribed => ErrorResponse("DEVICE_SUBSCRIBED", e.message)
    case e: MaxDevicesReached     => ErrorResponse("MAX_DEVICES", e.message)
    case e: PaymentNotFound       => ErrorResponse("PAYMENT_NOT_FOUND", e.message)
    case e: PaymentAlreadyProcessed => ErrorResponse("PAYMENT_PROCESSED", e.message)
    case e: PaymentGatewayError   => ErrorResponse("GATEWAY_ERROR", e.message)
    case e: InvalidPaymentAmount  => ErrorResponse("INVALID_AMOUNT", e.message)
    case e: InvoiceNotFound       => ErrorResponse("INVOICE_NOT_FOUND", e.message)
    case e: ValidationError       => ErrorResponse("VALIDATION_ERROR", e.message, Some(s"${e.field}: ${e.reason}"))
    case e: DatabaseError         => ErrorResponse("DATABASE_ERROR", e.message)
    case e: KafkaError            => ErrorResponse("KAFKA_ERROR", e.message)

  def httpStatus(err: BillingError): Int = err match
    case _: AccountNotFound       => 404
    case _: AccountAlreadyExists  => 409
    case _: AccountBlocked        => 403
    case _: InsufficientBalance   => 402
    case _: TariffNotFound        => 404
    case _: TariffInUse           => 409
    case _: SubscriptionNotFound  => 404
    case _: DeviceAlreadySubscribed => 409
    case _: MaxDevicesReached     => 422
    case _: PaymentNotFound       => 404
    case _: PaymentAlreadyProcessed => 409
    case _: PaymentGatewayError   => 502
    case _: InvalidPaymentAmount  => 422
    case _: InvoiceNotFound       => 404
    case _: ValidationError       => 422
    case _: DatabaseError         => 500
    case _: KafkaError            => 500
