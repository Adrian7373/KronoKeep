package com.kronokeep.app.navigation

import com.kronokeep.app.domain.model.MaintenanceStatus
import com.kronokeep.app.domain.reminders.Reminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinkTest {
    private fun roundTrip(link: DeepLink): DeepLink? = DeepLink.fromExtras(link.toExtras()::get)

    @Test fun `every link survives intent extras`() {
        for (link in listOf(DeepLink.Dashboard, DeepLink.Asset("a-1"), DeepLink.LogService("a-1", "s-1"), DeepLink.LogService("a-1", null))) {
            assertEquals(link, roundTrip(link))
        }
    }

    @Test fun `plain launches and malformed extras are not links`() {
        assertNull(DeepLink.fromExtras { null })
        assertNull(DeepLink.fromExtras(mapOf(DeepLink.EXTRA_TARGET to "asset")::get))
        assertNull(DeepLink.fromExtras(mapOf(DeepLink.EXTRA_TARGET to "asset", DeepLink.EXTRA_ASSET_ID to " ")::get))
        assertNull(DeepLink.fromExtras(mapOf(DeepLink.EXTRA_TARGET to "somewhere")::get))
    }

    @Test fun `targets put the stack under the right tab, root first`() {
        assertEquals(DeepLinkTarget(DashboardRoute, listOf(DashboardRoute)), DeepLink.Dashboard.target())
        assertEquals(DeepLinkTarget(AssetsRoute, listOf(AssetsRoute, AssetDetailRoute("a-1"))), DeepLink.Asset("a-1").target())
        assertEquals(
            DeepLinkTarget(AssetsRoute, listOf(AssetsRoute, AssetDetailRoute("a-1"), LogServiceRoute("a-1", "s-1"))),
            DeepLink.LogService("a-1", "s-1").target(),
        )
    }

    @Test fun `a digest about one asset opens it, a wider one opens the dashboard`() {
        val oil = Reminder.Due("s-1", "a-1", "Click", "Oil", MaintenanceStatus.DUE_SOON)
        val gear = Reminder.Due("s-2", "a-1", "Click", "Gear oil", MaintenanceStatus.OVERDUE)
        val odo = Reminder.UpdateOdometer("a-1", "Click", 15)
        val aircon = Reminder.Due("s-3", "a-2", "Aircon", "Cleaning", MaintenanceStatus.DUE_SOON)

        assertEquals(DeepLink.Asset("a-1"), DeepLink.forReminders(listOf(oil)))
        assertEquals(DeepLink.Asset("a-1"), DeepLink.forReminders(listOf(oil, gear, odo)))
        assertEquals(DeepLink.Dashboard, DeepLink.forReminders(listOf(oil, aircon)))
        assertEquals(DeepLink.Dashboard, DeepLink.forReminders(emptyList()))
    }
}
