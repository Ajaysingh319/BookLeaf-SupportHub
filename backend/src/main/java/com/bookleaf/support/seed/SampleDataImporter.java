package com.bookleaf.support.seed;

import com.bookleaf.support.config.AppProperties;
import com.bookleaf.support.domain.Book;
import com.bookleaf.support.domain.Role;
import com.bookleaf.support.domain.UserAccount;
import com.bookleaf.support.repository.BookRepository;
import com.bookleaf.support.repository.UserAccountRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Loads the sample dataset into the database.
 *
 * Idempotent: authors are matched by author_id and books by book_id, so running the
 * import again updates existing rows instead of duplicating them. Passwords are hashed
 * only when an account is first created, so a re-run never resets a password.
 * Business figures are copied exactly as supplied and never recalculated.
 * Account details and demo passwords come from configuration (SEED_* environment variables).
 */
@Service
public class SampleDataImporter {

    private final UserAccountRepository users;
    private final BookRepository books;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties.Seed seed;

    public SampleDataImporter(UserAccountRepository users, BookRepository books,
                              PasswordEncoder passwordEncoder, AppProperties properties) {
        this.users = users;
        this.books = books;
        this.passwordEncoder = passwordEncoder;
        this.seed = properties.seed();
    }

    @Transactional
    public ImportSummary importDataset(JsonNode root) {
        requireSeedSettings();
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

    /** The demo account settings are only needed for a seed run, so they are checked here. */
    private void requireSeedSettings() {
        List<String> missing = new ArrayList<>();
        if (isBlank(seed.authorPassword())) missing.add("SEED_AUTHOR_PASSWORD");
        if (isBlank(seed.adminEmail())) missing.add("SEED_ADMIN_EMAIL");
        if (isBlank(seed.adminName())) missing.add("SEED_ADMIN_NAME");
        if (isBlank(seed.adminPassword())) missing.add("SEED_ADMIN_PASSWORD");
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Cannot seed: set these environment variables first: " + String.join(", ", missing));
        }
    }

    private UserAccount upsertAuthor(JsonNode node) {
        String sourceId = requiredText(node, "author_id");
        UserAccount author = users.findBySourceAuthorId(sourceId).orElseGet(() -> {
            UserAccount created = new UserAccount();
            created.setSourceAuthorId(sourceId);
            created.setRole(Role.AUTHOR);
            created.setPasswordHash(passwordEncoder.encode(seed.authorPassword()));
            return created;
        });
        author.setName(requiredText(node, "name"));
        author.setEmail(requiredText(node, "email").toLowerCase(Locale.ROOT));
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
        String email = seed.adminEmail().toLowerCase(Locale.ROOT);
        UserAccount admin = users.findByEmail(email).orElseGet(() -> {
            UserAccount created = new UserAccount();
            created.setEmail(email);
            created.setRole(Role.ADMIN);
            created.setPasswordHash(passwordEncoder.encode(seed.adminPassword()));
            return created;
        });
        admin.setName(seed.adminName());
        users.save(admin);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
