package dev.task.member.infrastructure.group

import dev.task.group.api.GroupApi
import io.micrometer.common.KeyValue
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.observation.ClientRequestObservationContext
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention
import org.springframework.web.service.registry.ImportHttpServices

/** base-url / connect-timeout / read-timeout come from `spring.http.serviceclient.group.*`. */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "group", types = [GroupApi::class])
@EnableConfigurationProperties(GroupClientProperties::class)
class GroupClientConfig {
    /**
     * Boot's RestClient observation customizer picks this up and applies it to every HTTP service group client.
     * `List` request params expand to one template var per element, so the default `uri` tag would create one
     * series per batch size; keep only the path.
     */
    @Bean
    fun pathOnlyClientUriConvention() = object : DefaultClientRequestObservationConvention() {
        override fun uri(context: ClientRequestObservationContext): KeyValue =
            super.uri(context).let { KeyValue.of(it.key, it.value.substringBefore('?')) }
    }
}
