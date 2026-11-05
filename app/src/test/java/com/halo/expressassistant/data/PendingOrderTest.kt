package com.halo.expressassistant.data

import com.halo.expressassistant.api.TbOrders
import org.junit.Assert.*
import org.junit.Test

class PendingOrderTest {
    private val account = BoundAccount("buyer")
    private fun paid() = TbOrders.TbOrder("order-1", "WAIT_SELLER_SEND_GOODS", "买家已付款", "商品")

    @Test fun paidOrderIsVisibleBeforeWaybillExists() {
        val item = paid().pendingItem(account)!!
        assertEquals("notshipped", sectionKeyOf(item))
        assertEquals(-1, displayProgress(item))
        assertFalse(item.isCompleted())
        assertEquals("订单号：order-1", item.displayReference())
        assertEquals("商品", item.originalName)
        for (status in listOf("WAIT_BUYER_PAY", "TRADE_CLOSED", "WAIT_BUYER_CONFIRM_GOODS")) {
            assertNull(paid().copy(tradeStatus = status).pendingItem(account))
        }
    }

    @Test fun actualWaybillReplacesPendingOrderAndKeepsOtherHistory() {
        val pending = paid().pendingItem(account)!!
        val other = pending.copy(id = "other", mailNo = "other", queryChannel = "other")
        val shipped = pending.copy(id = "YT123", mailNo = "YT123", orderOnly = false, state = 0, stateNum = 104)
        val merged = OrderRecords.mergeHistory(listOf(shipped), listOf(pending, other))
        assertEquals(setOf("YT123", "other"), merged.map { it.mailNo }.toSet())
        val anotherAccount = pending.copy(mailNo = "another", accountId = "another")
        assertEquals(2, OrderRecords.mergeHistory(listOf(shipped), listOf(anotherAccount)).size)
    }

    @Test fun confirmedClosedOrderRetiresPendingButPartialPageDoesNot() {
        val pending = paid().pendingItem(account)!!
        assertEquals(listOf(pending), OrderRecords.mergeHistory(emptyList(), listOf(pending)))
        assertTrue(OrderRecords.mergeHistory(emptyList(), listOf(pending), setOf(OrderRecords.key(pending))).isEmpty())
    }

    @Test fun jsonAndJsonpListsWorkAndSessionErrorsAreNotEmptySuccess() {
        val body = """{"ret":["SUCCESS::调用成功"],"data":{"result":"{\"mainOrders\":[{\"id\":\"order-1\",\"extra\":{\"tradeStatus\":\"WAIT_SELLER_SEND_GOODS\"},\"statusInfo\":{\"text\":\"买家已付款\"}}]}"}}"""
        assertTrue(TbOrders.parseBoughtList(body).single().isPaidUnshipped())
        assertTrue(TbOrders.parseBoughtList("cb($body)").single().isPaidUnshipped())
        assertThrows(IllegalStateException::class.java) {
            TbOrders.parseBoughtList("""{"ret":["FAIL_SYS_SESSION_EXPIRED::登录过期"],"data":{}}""")
        }
    }
}
