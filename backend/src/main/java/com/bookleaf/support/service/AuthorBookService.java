package com.bookleaf.support.service;

import com.bookleaf.support.dto.BookResponse;
import com.bookleaf.support.repository.BookRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AuthorBookService {

    private final BookRepository books;

    public AuthorBookService(BookRepository books) {
        this.books = books;
    }

    /** Only the caller's own books: the query is scoped by author id. */
    @Transactional(readOnly = true)
    public List<BookResponse> listForAuthor(UUID authorId) {
        return books.findByAuthor_IdOrderBySourceBookIdAsc(authorId).stream()
                .map(BookResponse::from)
                .toList();
    }
}
