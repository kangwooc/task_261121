package dev.task.member.infrastructure.group

import dev.task.member.api.Permission
import dev.task.member.api.Pricing
import dev.task.group.api.Permission as GroupPermission
import dev.task.group.api.Pricing as GroupPricing

// Exhaustive `when` (no else): a new group-api constant is a compile error here, not a runtime valueOf failure.

internal fun GroupPricing.toMemberApi(): Pricing = when (this) {
    GroupPricing.Free -> Pricing.Free
    GroupPricing.Advanced -> Pricing.Advanced
    GroupPricing.Premium -> Pricing.Premium
}

internal fun GroupPermission.toMemberApi(): Permission = when (this) {
    GroupPermission.Viewer -> Permission.Viewer
    GroupPermission.Editor -> Permission.Editor
    GroupPermission.Admin -> Permission.Admin
}
