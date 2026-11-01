package com.example.iam;

import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.security.KeycloakRoles;
import com.example.security.KeycloakTenant;

@RestController
@RequestMapping("/api/iam")
class IamController {

	private final RolePermissionRepository repository;

	private final DbTenantDirectory tenants;

	IamController(RolePermissionRepository repository, DbTenantDirectory tenants) {
		this.repository = repository;
		this.tenants = tenants;
	}

	/** Who the caller is and what they may do; the UI uses it to show or hide actions. */
	@GetMapping("/me")
	MeResponse me(JwtAuthenticationToken authentication) {
		Jwt jwt = authentication.getToken();
		String username = Objects.requireNonNullElse(jwt.getClaimAsString("preferred_username"), authentication.getName());
		String name = Objects.requireNonNullElse(jwt.getClaimAsString("name"), username);
		List<String> roles = KeycloakRoles.of(jwt).stream().sorted().toList();
		List<String> permissions = authentication.getAuthorities().stream()
				.map(GrantedAuthority::getAuthority)
				.sorted()
				.toList();
		TenantResponse tenant = KeycloakTenant.id(jwt).flatMap(tenants::find).map(TenantResponse::of).orElse(null);
		return new MeResponse(username, name, roles, permissions, tenant);
	}

	@GetMapping("/roles")
	@PreAuthorize("hasAuthority('iam:read')")
	List<RoleResponse> roles() {
		return repository.findAllOrderedByRoleAndPermission().stream()
				.collect(Collectors.groupingBy(rp -> rp.getId().role(), TreeMap::new,
						Collectors.mapping(rp -> rp.getId().permissionCode(), Collectors.toList())))
				.entrySet().stream()
				.map(entry -> new RoleResponse(entry.getKey(), entry.getValue()))
				.toList();
	}

}
