package com.halo.expressassistant.ui

import com.halo.expressassistant.data.DetailPoint
import com.halo.expressassistant.data.ExpressDetail
import com.halo.expressassistant.data.ExpressItem
import com.halo.expressassistant.data.ParcelState
import org.junit.Assert.*
import org.junit.Test

class DetailStateTest {
    private val item = ExpressItem("sample", "TEST", "快递", "TEST12345678", source = "taobao", state = 0)
    private fun detail(vararg points: DetailPoint) = ExpressDetail(item.mailNo, item.companyName, 0, false, points.toList())

    @Test fun newestFirstTracesDoNotFlipTheSavedSnapshotBackToTheLastRow() {
        val newest = DetailPoint("快件已揽收", "11-06 08:00", "2026-11-06 08:00")
        val oldest = DetailPoint("等待揽收", "11-05 10:00", "2026-11-05 10:00")
        val response = detail(newest, oldest)
        val saved = DetailState.reconcile(item, response)
        assertEquals(newest.context, saved.latestText)
        assertEquals(newest.formattedTime, saved.latestTime)
        assertEquals(saved, DetailState.reconcile(saved, response))
        assertEquals(saved, DetailState.reconcile(item, detail(oldest, newest)))
    }

    @Test fun pickupAndEtaArePartOfTheSameStableDisplaySnapshot() {
        val response = detail(DetailPoint("快件到达驿站，取件码：8-2-123，预计11月8日送达", "2026-11-06 08:00"))
        val saved = DetailState.reconcile(item, response)
        assertEquals("8-2-123", saved.pickupCode)
        assertEquals("", saved.eta)
        assertEquals("待取件", saved.stateLabel())
        assertEquals(saved, ParcelState.withTrace(saved, emptyList()))
        assertEquals(saved, DetailState.reconcile(saved, response))
    }

    @Test fun completedDetailNeverRestoresAPickupCodeFromOldTraceText() {
        val response = detail(
            DetailPoint("已签收，签收人本人", "2026-11-06 12:00"),
            DetailPoint("驿站取件码：8-2-123", "2026-11-06 08:00")
        )
        val saved = DetailState.reconcile(item.copy(pickupCode = "old-code"), response)
        assertTrue(saved.isCompleted())
        assertEquals("", saved.pickupCode)
        assertEquals(saved, DetailState.reconcile(saved, response))
    }
}
