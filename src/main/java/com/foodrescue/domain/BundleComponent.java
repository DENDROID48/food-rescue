package com.foodrescue.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One line of a Smart Bundle: how many leftover units of an offer went into a surprise bag.
 * The units were taken out of the source offer when the bundle was launched.
 */
@Entity
@Table(name = "bundle_component")
@Getter
@Setter
@NoArgsConstructor
public class BundleComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bundle_item_id", nullable = false)
    private FoodItem bundle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_item_id", nullable = false)
    private FoodItem source;

    @Column(nullable = false)
    private int units;
}
