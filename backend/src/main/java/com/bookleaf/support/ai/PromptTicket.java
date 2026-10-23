package com.bookleaf.support.ai;

import com.bookleaf.support.domain.Book;
import com.bookleaf.support.domain.Priority;
import com.bookleaf.support.domain.TicketCategory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the model is allowed to see about a ticket. Deliberately small: no email, phone,
 * other tickets or other authors. This is the cost and privacy boundary for prompts.
 */
public record PromptTicket(
        String subject,
        String description,
        String authorFirstName,
        BookFacts book,
        TicketCategory category,
        Priority priority,
        List<String> recentPublicReplies) {

    /** Verified book record values. Null fields are passed on as "not available", never guessed. */
    public record BookFacts(
            String title,
            String genre,
            String status,
            LocalDate publicationDate,
            BigDecimal mrp,
            Integer copiesSold,
            BigDecimal royaltyEarned,
            BigDecimal royaltyPaid,
            BigDecimal royaltyPending,
            LocalDate lastRoyaltyPayoutDate,
            String printPartner,
            List<String> availableOn) {

        public static BookFacts from(Book book) {
            return new BookFacts(
                    book.getTitle(),
                    book.getGenre(),
                    book.getStatus(),
                    book.getPublicationDate(),
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
}
