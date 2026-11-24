package com.foodrescue.push;

/**
 * What a push notification shows. Kept short: push services limit the payload to about 4 KB.
 *
 * @param url opened when the notification is tapped
 * @param tag notifications with the same tag replace each other instead of piling up
 */
public record PushMessage(Long userId, String title, String body, String url, String tag) {
}
