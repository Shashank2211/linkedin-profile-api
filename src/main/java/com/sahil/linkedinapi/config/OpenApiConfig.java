package com.sahil.linkedinapi.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI linkedInProfileApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("LinkedIn Profile API")
                        .version("1.0.0")
                        .description("""
                                Resolves a LinkedIn profile URL into a stable, documented JSON contract.

                                Every response carries a `meta` block describing **how** the data was \
                                obtained (`source`), **when** (`fetchedAt`, `cacheAgeSeconds`) and **how \
                                complete** it is (`completeness`, 0.0–1.0). Read `meta` before trusting \
                                a null: `null` means "we could not read this", `[]` means "there is none".

                                Built as a technical demonstration for a hiring challenge — not a \
                                commercial service. See the repository README for the legal note and \
                                the full list of known limitations.
                                """))
                .addSecurityItem(new SecurityRequirement().addList("ApiKey"))
                .components(new Components().addSecuritySchemes("ApiKey", new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-API-Key")
                        .description("Any value from the server's API_KEYS list. "
                                + "Omit entirely when the server runs with API_KEYS unset.")));
    }
}
