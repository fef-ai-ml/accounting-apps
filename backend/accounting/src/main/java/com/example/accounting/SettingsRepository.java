package com.example.accounting;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SettingsRepository extends JpaRepository<Settings, Long> {

	Optional<Settings> findFirstByOrderByIdAsc();

}
