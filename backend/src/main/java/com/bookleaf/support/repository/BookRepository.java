package com.bookleaf.support.repository;

import com.bookleaf.support.domain.Book;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookRepository extends JpaRepository<Book, UUID> {

    Optional<Book> findBySourceBookId(String sourceBookId);

    List<Book> findByAuthor_IdOrderBySourceBookIdAsc(UUID authorId);

    /** Scoped by author so another author's book id behaves like a missing book. */
    Optional<Book> findByIdAndAuthor_Id(UUID id, UUID authorId);
}
