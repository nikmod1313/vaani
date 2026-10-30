package app.vaaniledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardAccessTest {
    @Test
    fun `the phone and the server hash a code the same way`() {
        // python3 -c "from app.main import code_hash; print(code_hash('VK7Q-M2PD'))"
        val server = "b8d1eb4d6d0a2a4d52b1777e98573648df4f7e977e2e9a5428ecd1e5e0afe876"
        assertEquals(server, DashboardAccess.hash("VK7Q-M2PD"))
        assertEquals(server, DashboardAccess.hash("vk7q m2pd"))
    }

    @Test
    fun `new codes are readable and unguessable`() {
        val codes = (1..500).map { DashboardAccess.newCode() }.toSet()
        assertEquals(500, codes.size)
        for (c in codes) {
            assertTrue(c, Regex("""^[A-HJ-KM-NP-Z2-9]{4}-[A-HJ-KM-NP-Z2-9]{4}$""").matches(c))
            assertTrue(c, c.none { it in "01OIL" })
        }
        assertNotEquals(DashboardAccess.newCode(), DashboardAccess.newCode())
    }
}
