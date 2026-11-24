package com.foodrescue.storage;

/**
 * Keeps the bytes of uploaded photos. The database only remembers that a photo exists and its
 * image type; the picture itself lives wherever the implementation puts it.
 */
public interface PhotoStorage {

    /** Stores the photo, replacing any earlier photo with the same id. */
    void save(PhotoFolder folder, long id, byte[] data);

    /** Reads a stored photo back. Throws ResourceNotFoundException when it does not exist. */
    byte[] load(PhotoFolder folder, long id);

    /**
     * Gives a photo to another owner, for example a saved product's photo to today's offer.
     *
     * @return false when there was no photo to copy
     */
    boolean copy(PhotoFolder fromFolder, long fromId, PhotoFolder toFolder, long toId);

    /** Removes a photo; does nothing if there is none. */
    void delete(PhotoFolder folder, long id);
}
