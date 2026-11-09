package com.writelab.persistence;

import org.springframework.data.repository.CrudRepository;

import java.util.UUID;

public interface PracticeAttemptRepository extends CrudRepository<PracticeAttemptEntity, UUID> {
}
