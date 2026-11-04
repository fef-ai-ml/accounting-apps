package com.example.tenancy;

import javax.sql.DataSource;

import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.example.security.SecurityModule;

/**
 * Entry point of tenant isolation. Every module that owns tenant data imports it.
 *
 * <p>Each tenant table has a {@code tenant_id UUID} column mapped with {@code @TenantId} (Hibernate filters and stamps
 * it) and a row-level security policy on {@code app.tenant_id} (Postgres enforces it, also for native SQL and for
 * bugs). Both get the tenant from the request's {@link com.example.security.KeycloakAuthentication}.
 */
@Configuration(proxyBeanMethods = false)
@Import(SecurityModule.class)
public class TenancyModule {

	@Bean
	RuntimeRoleCheck runtimeRoleCheck(DataSource dataSource) {
		return new RuntimeRoleCheck(dataSource);
	}

	@Bean
	HibernatePropertiesCustomizer tenancyHibernateProperties(DataSource dataSource) {
		return properties -> {
			properties.put(AvailableSettings.MULTI_TENANT_CONNECTION_PROVIDER, new RlsConnectionProvider(dataSource));
			properties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, new JwtTenantIdentifierResolver());
		};
	}

}
