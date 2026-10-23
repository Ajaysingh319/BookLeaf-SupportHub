package com.bookleaf.support.dto;

import com.bookleaf.support.domain.Book;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What an author sees for a book. Nullable fields stay null for books still in production
 * (the client shows "Not yet available"); zero amounts are real zeros.
 */
public record BookResponse(
        UUID id,
        String sourceBookId,
        String title,
        String isbn,
        String genre,
        LocalDate publicationDate,
        String status,
        BigDecimal mrp,
        Integer totalCopiesSold,
        BigDecimal totalRoyaltyEarned,
        BigDecimal royaltyPaid,
        BigDecimal royaltyPending,
        LocalDate lastRoyaltyPayoutDate,
        String printPartner,
        List<String> availableOn) {

    public static BookResponse from(Book book) {
        return new BookResponse(
                book.getId(),
                book.getSourceBookId(),
                book.getTitle(),
                book.getIsbn(),
                book.getGenre(),
                book.getPublicationDate(),
                book.getStatus(),
                book.getMrp(),
                book.getTotalCopiesSold(),
                book.getTotalRoyaltyEarned(),
                book.getRoyaltyPaid(),
                book.getRoyaltyPending(),
                book.getLastRoyaltyPayoutDate(),
                book.getPrintPartner(),
                List.copyOf(book.getAvailableOn()));
    }
}
