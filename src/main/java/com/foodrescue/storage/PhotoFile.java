package com.foodrescue.storage;

/** A photo read back from storage, with the image type remembered in the database. */
public record PhotoFile(String contentType, byte[] data) {
}
