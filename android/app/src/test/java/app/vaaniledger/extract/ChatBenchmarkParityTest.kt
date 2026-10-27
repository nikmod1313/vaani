package app.vaaniledger.extract

import app.vaaniledger.data.ShopCategory
import app.vaaniledger.extract.ConversationResolver.Turn
import java.io.File
import java.time.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole conversation benchmark, replayed through the phone's engine.
 *
 * `backend/eval/chats.py --dump` writes what the Python reference resolves
 * for every thread in `chats_dev.jsonl` and `chats_test.jsonl`. This feeds
 * the same turns, with the same timestamps, through [ConversationResolver]
 * and requires the identical answer -- direction, total, mode, and every
 * item's name, modifier, quantity, unit and rate.
 *
 * The other parity tests pin individual traps. This one pins the engine as
 * a whole on ninety-odd real-shaped chats, which is what catches a port that
 * agrees on every documented case and drifts on the undocumented ones. If it
 * fails after a change to either engine, re-run the dump and see which one
 * moved: a phone and a dashboard that book different figures for the same
 * chat is the failure this project cannot afford.
 */
class ChatBenchmarkParityTest {

    private val t0: LocalDateTime = LocalDateTime.of(2026, 10, 27, 9, 0)

    private fun evalDir(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "backend/eval")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("backend/eval not found above ${System.getProperty("user.dir")}")
    }

    private fun category(wire: String?) =
        ShopCategory.entries.firstOrNull { it.wire == wire } ?: ShopCategory.GENERAL

    private fun turns(case: JsonObject): List<Turn> {
        var ts = t0
        val id = case["id"]!!.jsonPrimitive.content
        return case["turns"]!!.jsonArray.mapIndexed { i, row ->
            val cells = row.jsonArray
            val shop = cells[0].jsonPrimitive.content == "s"
            val gap = cells.getOrNull(2)?.jsonPrimitive?.intOrNull ?: if (i == 0) 0 else 1
            ts = ts.plusMinutes(gap.toLong())
            Turn(
                sender = if (shop) "Sharma Kirana Store" else "Rahul Verma",
                body = cells[1].jsonPrimitive.content,
                timestamp = ts,
                isShopkeeper = shop,
                contentHash = "$id-$i",
            )
        }
    }

    private fun num(e: JsonElement?): Double? =
        if (e == null || e is JsonNull) null else e.jsonPrimitive.doubleOrNull

    private fun str(e: JsonElement?): String? =
        if (e == null || e is JsonNull) null else e.jsonPrimitive.contentOrNull

    /** One order as a comparable string, in the same shape for both engines. */
    private fun render(
        dir: String, amount: Double?, mode: String,
        items: List<List<Any?>>,
    ) = "dir=$dir amount=$amount mode=$mode items=$items"

    @Test
    fun `the kotlin engine resolves every benchmark thread exactly as python does`() {
        val dir = evalDir()
        val expected = Json.parseToJsonElement(File(dir, "chats_engine_output.json").readText()).jsonObject

        var compared = 0
        val drift = mutableListOf<String>()
        for (set in listOf("dev", "test")) {
            val python = expected[set]!!.jsonObject
            File(dir, "chats_$set.jsonl").readLines().filter { it.isNotBlank() }.forEach { line ->
                val case = Json.parseToJsonElement(line).jsonObject
                val id = case["id"]!!.jsonPrimitive.content

                val want = (python[id] as JsonArray).map { o ->
                    val obj = o.jsonObject
                    render(
                        str(obj["dir"])!!, num(obj["amount"]), str(obj["mode"])!!,
                        obj["items"]!!.jsonArray.map {
                            val item = it.jsonObject
                            listOf(
                                str(item["name"]), str(item["modifier"]), num(item["quantity"]),
                                str(item["unit"]), num(item["unit_price"]),
                            )
                        },
                    )
                }
                val got = ConversationResolver.resolveAll(
                    turns(case), "Rahul Verma", category(str(case["cat"])),
                ).map { o ->
                    render(
                        o.direction, o.amount, o.mode,
                        o.items.map { listOf(it.name, it.modifier, it.quantity, it.unit, it.unitPrice) },
                    )
                }
                if (want != got) drift.add("[$set/$id]\n  python: $want\n  kotlin: $got")
                compared++
            }
        }

        assertTrue("benchmark files are empty", compared > 80)
        assertEquals("Kotlin and Python disagree on ${drift.size} thread(s):\n" + drift.joinToString("\n"), 0, drift.size)
    }
}
