package com.foodrescue.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Stores a set of food types as "BAKERY,VEGAN"; unknown names (from a newer version) are skipped. */
@Converter
public class FoodTagsConverter implements AttributeConverter<Set<FoodTag>, String> {

    @Override
    public String convertToDatabaseColumn(Set<FoodTag> tags) {
        return tags == null ? "" : tags.stream().sorted().map(Enum::name).collect(Collectors.joining(","));
    }

    @Override
    public Set<FoodTag> convertToEntityAttribute(String column) {
        EnumSet<FoodTag> tags = EnumSet.noneOf(FoodTag.class);
        if (column == null || column.isBlank()) {
            return tags;
        }
        Arrays.stream(column.split(",")).map(String::trim).forEach(name -> {
            try {
                tags.add(FoodTag.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                // A tag removed from the enum; dropping it is harmless.
            }
        });
        return tags;
    }
}
