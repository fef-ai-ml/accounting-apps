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

	private static final MeDto EDITOR =
			new MeDto("editor", "Eddie Editor", List.of("EDITOR"), List.of("task:read", "task:write"));

	private final IamApiClient iamApiClient = mock(IamApiClient.class);

	private final CurrentUser currentUser = new CurrentUser(iamApiClient);

	@Test
	void checksPermissions() {
		when(iamApiClient.me()).thenReturn(EDITOR);

		assertThat(currentUser.can("task:write")).isTrue();
		assertThat(currentUser.can("task:delete")).isFalse();
		assertThat(currentUser.getName()).isEqualTo("Eddie Editor");
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
