package dev.finflow.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";
    private static final String PUBLIC_PREFIX = "/api/v1/auth/";

    @Bean
    OpenAPI finflowOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("FinFlow API")
                        .version("v1")
                        .description("Personal finance service with transfers and anti-fraud checks"))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }

    @Bean
    OpenApiCustomizer publicEndpointsWithoutSecurity() {
        return openApi -> openApi.getPaths().forEach((path, item) -> {
            if (path.startsWith(PUBLIC_PREFIX)) {
                item.readOperations().forEach(operation -> operation.setSecurity(List.of()));
            }
        });
    }
}
