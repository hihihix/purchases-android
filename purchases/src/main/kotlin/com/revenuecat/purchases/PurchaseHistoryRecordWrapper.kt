package com.revenuecat.purchases

import com.android.billingclient.api.PurchaseHistoryRecord

internal data class PurchaseHistoryRecordWrapper(
    val isConsumable: Boolean,
    val purchaseToken: String,
    val purchaseTime: Long,
    val sku: String,
    val purchaseHistoryRecord: PurchaseHistoryRecord,
    val type: PurchaseType
) {
    constructor(
        purchaseHistoryRecord: PurchaseHistoryRecord,
        type: PurchaseType
    ) : this(
        isConsumable = type == PurchaseType.INAPP,
        purchaseToken = purchaseHistoryRecord.purchaseToken,
        purchaseTime = purchaseHistoryRecord.purchaseTime,
        sku = purchaseHistoryRecord.sku,
        purchaseHistoryRecord = purchaseHistoryRecord,
        type = type
    )
}
