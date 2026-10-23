package com.bookleaf.support.seed;

import com.bookleaf.support.domain.Book;
import com.bookleaf.support.domain.Role;
import com.bookleaf.support.domain.UserAccount;
import com.bookleaf.support.repository.BookRepository;
import com.bookleaf.support.repository.UserAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the sample dataset into the database.
 *
 * Idempotent: authors are matched by author_id and books by book_id, so running the
 * import again updates existing rows instead of duplicating them. Passwords are hashed
 * only when an account is first created, so a re-run never resets a password.
 * Business figures are copied exactly as supplied and never recalculated.
 */
@Service
public class SampleDataImporter {

    static final String ADMIN_NAME = "BookLeaf Support Admin";

    private final UserAccountRepository users;
    private final BookRepository books;
    private final PasswordEncoder passwordEncoder;
    private final String authorPassword;
    private final String adminEmail;
    private final String adminPassword;

    public SampleDataImporter(
            UserAccountRepository users,
            BookRepository books,
            PasswordEncoder passwordEncoder,
            @Value("${app.seed.author-password}") String authorPassword,
            @Value("${app.seed.admin-email}") String adminEmail,
            @Value("${app.seed.admin-password}") String adminPassword) {
        this.users = users;
        this.books = books;
        this.passwordEncoder = passwordEncoder;
        this.authorPassword = authorPassword;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Transactional
    public ImportSummary importDataset(JsonNode root) {
        int authorCount = 0;
        int bookCount = 0;
        for (JsonNode authorNode : root.path("authors")) {
            UserAccount author = upsertAuthor(authorNode);
            authorCount++;
            for (JsonNode bookNode : authorNode.path("books")) {
                upsertBook(author, bookNode);
                bookCount++;
            }
        }
        upsertAdmin();
        return new ImportSummary(authorCount, bookCount);
    }

    private UserAccount upsertAuthor(JsonNode node) {
        String sourceId = requiredText(node, "author_id");
        UserAccount author = users.findBySourceAuthorId(sourceId).orElseGet(() -> {
            UserAccount created = new UserAccount();
            created.setSourceAuthorId(sourceId);
            created.setRole(Role.AUTHOR);
            created.setPasswordHash(passwordEncoder.encode(authorPassword));
            return created;
        });
        author.setName(requiredText(node, "name"));
        author.setEmail(requiredText(node, "email").toLowerCase());
        author.setPhone(text(node, "phone"));
        author.setCity(text(node, "city"));
        author.setJoinedDate(date(node, "joined_date"));
        return users.save(author);
    }

    private void upsertBook(UserAccount author, JsonNode node) {
        String sourceId = requiredText(node, "book_id");
        Book book = books.findBySourceBookId(sourceId).orElseGet(Book::new);
        book.setSourceBookId(sourceId);
        book.setAuthor(author);
        book.setTitle(requiredText(node, "title"));
        book.setIsbn(requiredText(node, "isbn"));
        book.setGenre(requiredText(node, "genre"));
        book.setPublicationDate(date(node, "publication_date"));
        book.setStatus(requiredText(node, "status"));
        book.setMrp(decimal(node, "mrp"));
        book.setAuthorRoyaltyPerCopy(decimal(node, "author_royalty_per_copy"));
        book.setTotalCopiesSold(integer(node, "total_copies_sold"));
        book.setTotalRoyaltyEarned(decimal(node, "total_royalty_earned"));
        book.setRoyaltyPaid(decimal(node, "royalty_paid"));
        book.setRoyaltyPending(decimal(node, "royalty_pending"));
        book.setLastRoyaltyPayoutDate(date(node, "last_royalty_payout_date"));
        book.setPrintPartner(text(node, "print_partner"));
        book.setAvailableOn(stringList(node, "available_on"));
        books.save(book);
    }

    private void upsertAdmin() {
        String email = adminEmail.toLowerCase();
        UserAccount admin = users.findByEmail(email).orElseGet(() -> {
            UserAccount created = new UserAccount();
            created.setEmail(email);
            created.setRole(Role.ADMIN);
            created.setPasswordHash(passwordEncoder.encode(adminPassword));
            return created;
        });
        admin.setName(ADMIN_NAME);
        users.save(admin);
    }

    /** Returns the text value, or null when the field is missing or JSON null. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Dataset field is missing: " + field);
        }
        return value;
    }

    /** Keeps null as null: a null amount means "not applicable yet", not zero. */
    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.decimalValue();
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.intValue();
    }

    private static LocalDate date(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : LocalDate.parse(value);
    }

    private static List<String> stringList(JsonNode node, String field) {
        List<String> result = new ArrayList<>();
        for (JsonNode item : node.path(field)) {
            result.add(item.asString());
        }
        return result;
    }

    public record ImportSummary(int authors, int books) {
    }
}
