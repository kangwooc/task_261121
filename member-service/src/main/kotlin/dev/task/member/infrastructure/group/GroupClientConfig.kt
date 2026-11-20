package dev.task.member.infrastructure.group

import dev.task.group.api.GroupApi
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.web.service.registry.ImportHttpServices

/** base-url / connect-timeout / read-timeout come from `spring.http.serviceclient.group.*`. */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "group", types = [GroupApi::class])
@EnableConfigurationProperties(GroupClientProperties::class)
class GroupClientConfig
