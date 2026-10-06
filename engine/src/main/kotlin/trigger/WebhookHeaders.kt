package dev.lawlan.runline.engine.trigger

/** The header a webhook call shows the trigger's secret in. */
const val WEBHOOK_SECRET_HEADER = "X-Runline-Webhook-Secret"

/** The header a webhook call names its delivery in; a delivery is handled once per trigger. */
const val WEBHOOK_DELIVERY_HEADER = "X-Runline-Delivery-Id"
