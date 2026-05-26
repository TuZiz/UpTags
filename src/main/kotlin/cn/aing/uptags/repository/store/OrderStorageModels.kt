package cn.aing.uptags.repository.store

import cn.aing.uptags.model.runtime.CustomTitleOrderStatus
import cn.aing.uptags.model.runtime.CustomTitlePurchaseOrderData
import cn.aing.uptags.model.runtime.PurchaseOrderData
import cn.aing.uptags.model.runtime.PurchaseOrderStatus

data class PlayerOrdersSnapshot(
    val purchaseOrders: Map<String, PurchaseOrderData> = emptyMap(),
    val customTitleOrders: Map<String, CustomTitlePurchaseOrderData> = emptyMap(),
)

internal object OrderStatusPolicies {
    val recoverablePurchaseStatuses: Set<PurchaseOrderStatus> = setOf(
        PurchaseOrderStatus.PENDING,
        PurchaseOrderStatus.ITEMS_TAKEN,
        PurchaseOrderStatus.PAID,
        PurchaseOrderStatus.GRANTING,
        PurchaseOrderStatus.REFUND_PENDING,
    )

    val recoverableCustomTitleStatuses: Set<CustomTitleOrderStatus> = setOf(
        CustomTitleOrderStatus.PENDING,
        CustomTitleOrderStatus.REFUND_PENDING,
    )
}
