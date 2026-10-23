package com.bookleaf.support.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A book with the figures exactly as supplied in the dataset.
 * Null amounts mean "not applicable yet" (for example, a book still in production).
 * Zero is a real value and is stored as zero. Figures are never recomputed.
 */
@Entity
@Table(name = "books")
public class Book {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Source identifier from the dataset (BK001..BK018). */
    @Column(nullable = false, unique = true)
    private String sourceBookId;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", nullable = false)
    private UserAccount author;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String isbn;

    @Column(nullable = false)
    private String genre;

    private LocalDate publicationDate;

    @Column(nullable = false)
    private String status;

    @Column(precision = 12, scale = 2)
    private BigDecimal mrp;

    @Column(precision = 12, scale = 2)
    private BigDecimal authorRoyaltyPerCopy;

    private Integer totalCopiesSold;

    @Column(precision = 14, scale = 2)
    private BigDecimal totalRoyaltyEarned;

    @Column(precision = 14, scale = 2)
    private BigDecimal royaltyPaid;

    @Column(precision = 14, scale = 2)
    private BigDecimal royaltyPending;

    private LocalDate lastRoyaltyPayoutDate;

    private String printPartner;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "book_available_on", joinColumns = @JoinColumn(name = "book_id"))
    @Column(name = "platform", nullable = false)
    private List<String> availableOn = new ArrayList<>();

    public UUID getId() { return id; }

    public String getSourceBookId() { return sourceBookId; }
    public void setSourceBookId(String sourceBookId) { this.sourceBookId = sourceBookId; }

    public UserAccount getAuthor() { return author; }
    public void setAuthor(UserAccount author) { this.author = author; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getIsbn() { return isbn; }
    public void setIsbn(String isbn) { this.isbn = isbn; }

    public String getGenre() { return genre; }
    public void setGenre(String genre) { this.genre = genre; }

    public LocalDate getPublicationDate() { return publicationDate; }
    public void setPublicationDate(LocalDate publicationDate) { this.publicationDate = publicationDate; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public BigDecimal getMrp() { return mrp; }
    public void setMrp(BigDecimal mrp) { this.mrp = mrp; }

    public BigDecimal getAuthorRoyaltyPerCopy() { return authorRoyaltyPerCopy; }
    public void setAuthorRoyaltyPerCopy(BigDecimal authorRoyaltyPerCopy) { this.authorRoyaltyPerCopy = authorRoyaltyPerCopy; }

    public Integer getTotalCopiesSold() { return totalCopiesSold; }
    public void setTotalCopiesSold(Integer totalCopiesSold) { this.totalCopiesSold = totalCopiesSold; }

    public BigDecimal getTotalRoyaltyEarned() { return totalRoyaltyEarned; }
    public void setTotalRoyaltyEarned(BigDecimal totalRoyaltyEarned) { this.totalRoyaltyEarned = totalRoyaltyEarned; }

    public BigDecimal getRoyaltyPaid() { return royaltyPaid; }
    public void setRoyaltyPaid(BigDecimal royaltyPaid) { this.royaltyPaid = royaltyPaid; }

    public BigDecimal getRoyaltyPending() { return royaltyPending; }
    public void setRoyaltyPending(BigDecimal royaltyPending) { this.royaltyPending = royaltyPending; }

    public LocalDate getLastRoyaltyPayoutDate() { return lastRoyaltyPayoutDate; }
    public void setLastRoyaltyPayoutDate(LocalDate lastRoyaltyPayoutDate) { this.lastRoyaltyPayoutDate = lastRoyaltyPayoutDate; }

    public String getPrintPartner() { return printPartner; }
    public void setPrintPartner(String printPartner) { this.printPartner = printPartner; }

    public List<String> getAvailableOn() { return availableOn; }
    public void setAvailableOn(List<String> availableOn) { this.availableOn = availableOn; }
}
