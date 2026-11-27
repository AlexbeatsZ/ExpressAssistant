package com.halo.expressassistant.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.widget.EditText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.halo.expressassistant.PackageAutoImport
import com.halo.expressassistant.R
import com.halo.expressassistant.api.ManualPackages
import com.halo.expressassistant.data.ExpressItem
import com.halo.expressassistant.data.Store
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PackageAutoImportTest {
    @Test fun smsPickupMatchesParcelsInBothArrivalOrdersAndSurvivesReplay() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefsName = "sms-pickup-test-${java.util.UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefsName, mode)
        }
        val now = System.currentTimeMillis()
        val parcel = ExpressItem("test-parcel", "", "快递", "SF1234567890123", source = "pdd")
        try {
            Store.saveItems(context, listOf(parcel.copy(pickupCode = "a1-2345")))
            assertTrue(Store.importSmsPickupCodes(context, listOf("Ａ１－２３４５"), now))
            assertEquals(1, Store.items(context).size)
            assertFalse(Store.items(context).single().isSmsPickup)

            assertTrue(Store.importSmsPickupCodes(context, listOf("2-3-4567"), now))
            assertTrue(Store.importSmsPickupCodes(context, listOf("2-3-4567"), now + 1))
            val sms = Store.items(context).single { it.isSmsPickup }
            assertTrue(sms.isAwaitingPickup())
            assertEquals("取件码 2-3-4567", sms.pickupLabel())
            assertEquals("", sms.displayReference())

            val merged = Store.updateItems(context) { items ->
                items.map { if (it.mailNo == parcel.mailNo) it.copy(pickupCode = "2-3-4567") else it }
            }
            assertEquals(listOf(parcel.mailNo), merged.map { it.mailNo })
            Store.updateItems(context) { items -> items.map { it.copy(receiptConfirmed = true) } }
            assertTrue(Store.importSmsPickupCodes(context, listOf("2-3-4567"), now + 1))
            assertFalse(Store.items(context).any { it.isSmsPickup })

            assertTrue(Store.importSmsPickupCodes(context, listOf("2-3-4567"), now + 2))
            assertEquals(1, Store.items(context).count { it.isSmsPickup })
        } finally {
            base.deleteSharedPreferences(prefsName)
        }
    }

    @Test fun importDeduplicatesTrackingNumbersAndRespectsHiddenPackages() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefsName = "auto-import-test-${java.util.UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefsName, mode)
        }
        try {
            val linked = ExpressItem("PDD-ORDER", "", "快递", "PDD-ORDER", source = "pdd", trackingNumber = "SF1234567890123")
            Store.saveItems(context, listOf(linked))
            assertEquals(0, PackageAutoImport.importWaybills(context, listOf("sf1234567890123", "1234", "123456-1234567890")))
            val hidden = ManualPackages.add(context, "SF1234567890124", null)
            Store.addHidden(context, hidden)
            Store.updateItems(context) { it.filterNot { item -> item.mailNo == hidden.mailNo } }
            assertEquals(0, PackageAutoImport.importWaybills(context, listOf(hidden.mailNo)))
            assertEquals(listOf(linked), Store.items(context))
            assertTrue(Store.xiaomiHidden(context).any { it.mailNo == hidden.mailNo })
        } finally {
            base.deleteSharedPreferences(prefsName)
        }
    }

    @Test fun foregroundClipboardImportUpdatesListAndInvalidSearchStaysLocal() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val mail = "EA${System.currentTimeMillis()}"
        val prefs = context.getSharedPreferences("express_store", Context.MODE_PRIVATE)
        val imported = CountDownLatch(1)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "items" && Store.items(context).any { it.mailNo == mail }) imported.countDown()
        }
        var originalClip: ClipData? = null
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    originalClip = clipboard.primaryClip
                    clipboard.setPrimaryClip(ClipData.newPlainText("运单", "物流单号：$mail"))
                    activity.findViewById<EditText>(R.id.search_input).setText("123456-1234567890")
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.pending_package).visibility)
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                assertTrue("Foreground clipboard import did not finish", imported.await(15, TimeUnit.SECONDS))
                assertEquals(1, Store.items(context).count { it.mailNo == mail })
                scenario.onActivity { activity ->
                    val clip = originalClip ?: ClipData.newPlainText("", "")
                    clipboard.setPrimaryClip(clip)
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.pending_package).visibility)
                }
            }
        } finally {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
            Store.updateItems(context) { it.filterNot { item -> item.mailNo == mail } }
        }
    }
}
