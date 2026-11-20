package dev.task.member.api

import dev.task.group.api.Permission
import dev.task.group.api.Pricing
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import tools.jackson.module.kotlin.jacksonMapperBuilder

class MemberDtosJsonTest {
    private val mapper = jacksonMapperBuilder().build()
    private fun json(o: Any) = mapper.readTree(mapper.writeValueAsString(o))

    @Test
    fun listOmitsUnusedPagingBlock() {
        val n = json(MemberListResponse(listOf(MemberSummary("tu3fgxd45k", "user1", "Test1", Permission.Editor)), page = PageInfo(0, 20, 42, 3)))
        assertFalse(n.has("cursor"))
        assertEquals(mapper.readTree("""{"number":0,"size":20,"totalElements":42,"totalPages":3}"""), n["page"])
    }

    @Test
    fun nullGroupIsSerializedAsNull() {
        val n = json(MemberListResponse(listOf(MemberSummary("q8zk2m1x0a", "user3", null, null))))
        val m = n["members"][0]
        assertTrue(m.has("group") && m["group"].isNull)
        assertTrue(m.has("permission") && m["permission"].isNull)
    }

    @Test
    fun cursorNextNullIsSerialized() {
        val n = json(MemberListResponse(emptyList(), cursor = CursorInfo(null, false)))
        assertTrue(n["cursor"].has("next") && n["cursor"]["next"].isNull)
        assertFalse(n.has("page"))
    }

    @Test
    fun detailMatchesSpec() {
        val t = LocalDateTime.of(2026, 8, 15, 13, 0)
        val d = MemberDetailResponse("tu3fgxd45k", "user1", "user1@company.com", "seoul, gangnamgu", t,
            MemberGroupInfo("arutjf9348", "Test1", t, Pricing.Premium, Permission.Editor))
        assertEquals(mapper.readTree("""{"id":"tu3fgxd45k","name":"user1","email":"user1@company.com","address":"seoul, gangnamgu",
            "registeredDate":"2026-08-15 13:00","group":{"id":"arutjf9348","name":"Test1","registeredDate":"2026-08-15 13:00",
            "pricing":"Premium","permission":"Editor"}}"""), json(d))
        assertTrue(json(d.copy(group = null)).let { it.has("group") && it["group"].isNull })
    }
}
