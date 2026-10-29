package app.vaaniledger.extract

import app.vaaniledger.data.ShopCategory
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** A real iPhone export, read on the phone: the same three orders the server finds. */
class IosExportTest {

    private fun fixture(): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, "backend/tests/fixtures/isha_clothing_ios.txt")
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        error("fixture not found")
    }

    @Test
    fun `every order from the export comes out whole`() {
        val customer = "Isha Aggarwal NMIMS"
        val turns = ExportParser.toTurns(ExportParser.parse(fixture()), customer, setOf("Aadit"))
        val orders = ConversationResolver.resolveAll(turns, customer, ShopCategory.APPAREL)
        assertEquals(listOf(2250.0, 5000.0, 500.0), orders.map { it.amount })
        assertEquals(listOf("upi", "upi"), orders.take(2).map { it.mode })
        assertEquals("blue", orders[2].items.single().modifier)
    }
}
