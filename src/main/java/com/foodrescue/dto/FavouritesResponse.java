package com.foodrescue.dto;

import java.util.List;

public record FavouritesResponse(List<Store> stores, List<Product> products) {

    public record Store(Long id, String name, String address) {
    }

    public record Product(Long templateId, String name, Long storeId, String storeName) {
    }
}
