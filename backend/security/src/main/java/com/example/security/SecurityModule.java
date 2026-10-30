package com.example.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Entry point of the shared resource-server wiring. Backend modules {@code @Import} it and protect their endpoints with
 * {@code @PreAuthorize("hasAuthority('<module>:<action>')")}.
 *
 * <p>Tokens are validated against {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}. Permissions come from
 * the {@link PermissionResolver} bean of the deployment (the {@code iam} module in {@code backend/app}); without one,
 * every request is authenticated but has no permissions. The same holds for the {@link TenantDirectory}: without one,
 * no request has a tenant.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@ComponentScan(basePackageClasses = SecurityModule.class)
public class SecurityModule {

	private static final Logger log = LoggerFactory.getLogger(SecurityModule.class);

	@Bean
	KeycloakJwtAuthenticationConverter keycloakJwtAuthenticationConverter(ObjectProvider<PermissionResolver> resolvers,
			ObjectProvider<TenantDirectory> directories) {
		PermissionResolver resolver = resolvers.getIfAvailable(() -> {
			log.warn("No PermissionResolver bean: requests are authenticated but have no permissions");
			return PermissionResolver.denyAll();
		});
		TenantDirectory directory = directories.getIfAvailable(() -> {
			log.warn("No TenantDirectory bean: requests are authenticated but have no tenant");
			return TenantDirectory.none();
		});
		return new KeycloakJwtAuthenticationConverter(resolver, directory);
	}

	/** Only Keycloak access tokens are accepted; ID and refresh tokens carry another "typ". */
	@Bean
	OAuth2TokenValidator<Jwt> keycloakAccessTokenValidator() {
		return new JwtClaimValidator<String>("typ", "Bearer"::equals);
	}

	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http, KeycloakJwtAuthenticationConverter converter) throws Exception {
		http.authorizeHttpRequests(auth -> auth
						.requestMatchers("/actuator/health/**", "/actuator/info", "/v3/api-docs/**", "/swagger-ui/**",
								"/swagger-ui.html", "/error").permitAll()
						.anyRequest().authenticated())
				.oauth2ResourceServer(resourceServer -> resourceServer
						.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				// Bearer tokens only: no cookies, so no CSRF exposure.
				.csrf(csrf -> csrf.disable());
		return http.build();
	}

}
