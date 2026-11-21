package dev.task.member.infrastructure.group

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import dev.task.group.api.Permission as GroupPermission
import dev.task.group.api.Pricing as GroupPricing

class GroupEnumMappingTest {
    @Test
    fun everyGroupConstantMapsToSameNamedMemberConstant() {
        GroupPricing.entries.forEach { assertEquals(it.name, it.toMemberApi().name) }
        GroupPermission.entries.forEach { assertEquals(it.name, it.toMemberApi().name) }
    }
}
