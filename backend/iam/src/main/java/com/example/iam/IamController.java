package com.example.iam;

import java.util.List;
import java.util.Objects;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.security.KeycloakRoles;

@RestController
@RequestMapping("/api/iam")
class IamController {

	private final RolePermissionRepository repository;

	IamController(RolePermissionRepository repository) {
		this.repository = repository;
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
		return new MeResponse(username, name, roles, permissions);
	}

	@GetMapping("/roles")
	@PreAuthorize("hasAuthority('iam:read')")
	List<RoleResponse> roles() {
		return repository.permissionsByRole().entrySet().stream()
				.map(entry -> new RoleResponse(entry.getKey(), entry.getValue()))
				.toList();
	}

}
