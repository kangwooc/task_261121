package dev.task.group.api

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import tools.jackson.module.kotlin.jacksonMapperBuilder

class GroupDtosJsonTest {
    private val mapper = jacksonMapperBuilder().build()

    @Test
    fun serializesExactlyLikeSpec() {
        val summary = GroupSummaryResponse(listOf(
            GroupSummary("arutjf9348", "Test1", 3, listOf(
                GroupMemberPermission("tu3fgxd45k", Permission.Editor),
                GroupMemberPermission("q8zk2m1x0a", Permission.Viewer))),
        ))
        assertEquals(mapper.readTree("""{"groups":[{"groupId":"arutjf9348","groupName":"Test1","totalMemberCount":3,
            "members":[{"memberId":"tu3fgxd45k","permission":"Editor"},{"memberId":"q8zk2m1x0a","permission":"Viewer"}]}]}"""),
            mapper.readTree(mapper.writeValueAsString(summary)))

        val detail = MemberGroupDetailResponse("tu3fgxd45k", "arutjf9348", "Test1",
            LocalDateTime.of(2026, 8, 15, 13, 0), Pricing.Premium, Permission.Editor)
        assertEquals(mapper.readTree("""{"memberId":"tu3fgxd45k","groupId":"arutjf9348","groupName":"Test1",
            "registeredDate":"2026-08-15 13:00","pricing":"Premium","permission":"Editor"}"""),
            mapper.readTree(mapper.writeValueAsString(detail)))
    }
}
