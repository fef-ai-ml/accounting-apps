package com.example.ui.common;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CurrentUserTests {

	private static final TenantDto DEMO = new TenantDto("7f3a0c4e-1b2d-4e5f-8a9b-0c1d2e3f4a5b", "demo", "Demo Company",
			"ACTIVE");

	private static final MeDto EDITOR =
			new MeDto("editor", "Eddie Editor", List.of("EDITOR"), List.of("task:read", "task:write"), DEMO);

	private final IamApiClient iamApiClient = mock(IamApiClient.class);

	private final CurrentUser currentUser = new CurrentUser(iamApiClient);

	@Test
	void knowsTheTenant() {
		when(iamApiClient.me()).thenReturn(EDITOR);

		assertThat(currentUser.hasActiveTenant()).isTrue();
		assertThat(currentUser.getTenantName()).isEqualTo("Demo Company");
	}

	@Test
	void noOrSuspendedTenantIsNotActive() {
		when(iamApiClient.me())
				.thenReturn(new MeDto("loner", "Loner", List.of(), List.of(), null))
				.thenReturn(new MeDto("x", "X", List.of(), List.of(),
						new TenantDto("c0c0", "closed", "Closed Ltd", "SUSPENDED")));

		assertThat(currentUser.hasActiveTenant()).isFalse();
		assertThat(currentUser.getTenantName()).isEmpty();

		CurrentUser other = new CurrentUser(iamApiClient);
		assertThat(other.hasActiveTenant()).isFalse();
		assertThat(other.getTenantName()).isEqualTo("Closed Ltd");
	}

	@Test
	void checksPermissions() {
		when(iamApiClient.me()).thenReturn(EDITOR);

		assertThat(currentUser.can("task:write")).isTrue();
		assertThat(currentUser.can("task:delete")).isFalse();
		assertThat(currentUser.getName()).isEqualTo("Eddie Editor");
		assertThat(currentUser.getUsername()).isEqualTo("editor");
	}

	@Test
	void fetchesOncePerSession() {
		when(iamApiClient.me()).thenReturn(EDITOR);

		currentUser.can("task:read");
		currentUser.can("task:write");
		currentUser.getName();

		verify(iamApiClient, times(1)).me();
	}

	@Test
	void noRequiredPermissionIsAlwaysAllowed() {
		assertThat(currentUser.can(null)).isTrue();
		verifyNoInteractions(iamApiClient);
	}

	@Test
	void failedFetchIsNotCached() {
		when(iamApiClient.me()).thenThrow(new ResourceAccessException("Connection refused")).thenReturn(EDITOR);

		assertThatThrownBy(() -> currentUser.can("task:read")).isInstanceOf(ResourceAccessException.class);
		assertThat(currentUser.can("task:read")).isTrue();
	}

}
