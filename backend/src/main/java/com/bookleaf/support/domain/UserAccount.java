package com.bookleaf.support.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A login account. Authors come from the sample dataset; admins are seeded separately.
 */
@Entity
@Table(name = "app_users")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Source identifier from the dataset (AUTH001..AUTH010). Null for admins. */
    @Column(unique = true)
    private String sourceAuthorId;

    @Column(nullable = false)
    private String name;

    /** Stored lowercase. */
    @Column(nullable = false, unique = true)
    private String email;

    /** BCrypt hash. The plain password is never stored. */
    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    private String phone;

    private String city;

    private LocalDate joinedDate;

    @OneToMany(mappedBy = "author")
    private List<Book> books = new ArrayList<>();

    public UUID getId() { return id; }

    public String getSourceAuthorId() { return sourceAuthorId; }
    public void setSourceAuthorId(String sourceAuthorId) { this.sourceAuthorId = sourceAuthorId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public LocalDate getJoinedDate() { return joinedDate; }
    public void setJoinedDate(LocalDate joinedDate) { this.joinedDate = joinedDate; }

    public List<Book> getBooks() { return books; }
}
