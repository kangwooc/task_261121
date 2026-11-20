package dev.task.group.infrastructure.persistence

import dev.task.group.TestcontainersConfig
import dev.task.group.api.Permission
import dev.task.group.api.Pricing
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertNull

@DataJpaTest
@Import(TestcontainersConfig::class, GroupReadRepository::class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class GroupReadRepositoryTest @Autowired constructor(private val repo: GroupReadRepository) {
    private val admin = "27ysni0g1i"      // Alpha Team, Admin
    private val editor = "itn3xvk9tf"     // Alpha Team, Editor
    private val ungrouped = "pq12td4qds"  // seed id #46

    @Test
    fun findMembershipsReturnsOnlyGroupedMembers() {
        val r = repo.findMemberships(listOf(admin, editor, ungrouped)).associateBy { it.memberId }
        assertEquals(setOf(admin, editor), r.keys)
        assertEquals(Permission.Admin, r.getValue(admin).permission)
        assertEquals(Permission.Editor, r.getValue(editor).permission)
        assertEquals("Alpha Team", r.getValue(admin).groupName)
    }

    @Test
    fun countMembersReturnsWholeGroupSize() {
        assertEquals(listOf(GroupCountView("g1a2b3c4d5", 12)), repo.countMembers(listOf("g1a2b3c4d5")))
    }

    @Test
    fun findMemberGroupReturnsDetail() {
        val d = repo.findMemberGroup(admin)!!
        assertEquals(Pricing.Free, d.pricing)
        assertEquals("g1a2b3c4d5", d.groupId)
    }

    @Test
    fun findMemberGroupReturnsNullForUngrouped() {
        assertNull(repo.findMemberGroup(ungrouped))
    }
}
