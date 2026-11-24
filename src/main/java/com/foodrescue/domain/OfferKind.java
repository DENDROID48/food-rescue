package com.foodrescue.domain;

/** What a store sells: one specific product, or a mixed bag of whatever is left over. */
public enum OfferKind {
    /** A named product, for example "Sourdough loaf". */
    PRODUCT,
    /** A mix of the day's surplus whose exact contents are not known in advance. */
    SURPRISE_BAG
}
