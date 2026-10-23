package com.bookleaf.support.repository;

import com.bookleaf.support.domain.AiEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AiEventRepository extends JpaRepository<AiEvent, UUID> {
}
