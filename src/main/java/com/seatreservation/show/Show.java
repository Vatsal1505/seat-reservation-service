package com.seatreservation.show;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "shows")
public class Show {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "price_paise", nullable = false)
    private long pricePaise;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Show() {
    }

    public Show(String name, long pricePaise, Instant createdAt) {
        this.name = name;
        this.pricePaise = pricePaise;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public long getPricePaise() {
        return pricePaise;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Show show && id != null && Objects.equals(id, show.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
