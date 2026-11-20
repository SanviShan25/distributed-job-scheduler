package com.sanvi.jobscheduler.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.sanvi.jobscheduler.security.ApiKeyAuthenticationFilter;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/** Swagger UI at {@code /swagger-ui.html}; use "Authorize" to supply an API key. */
@Configuration(proxyBeanMethods = false)
@Profile("api")
public class OpenApiConfig {
    private static final String SCHEME = "apiKey";

    @Bean
    OpenAPI jobSchedulerOpenApi() {
        return new OpenAPI()
            .info(new Info().title("Distributed Job Scheduler API").version("v1")
                .description("Submit, schedule, inspect and cancel background jobs. "
                    + "Delivery is at-least-once: handlers must be idempotent."))
            .components(new Components().addSecuritySchemes(SCHEME, new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER)
                .name(ApiKeyAuthenticationFilter.HEADER)))
            .addSecurityItem(new SecurityRequirement().addList(SCHEME));
    }
}
