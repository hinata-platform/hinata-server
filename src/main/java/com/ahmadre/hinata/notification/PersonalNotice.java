package com.ahmadre.hinata.notification;

/**
 * One notice worded for one person: what the bell and the mail say, and what the push says.
 *
 * <p>The push is read on a lock screen by whoever holds the phone, so a module that names a place,
 * a time or another person in the bell passes a push text that names none of them. [ctaLabel] is
 * the mail button's caption; null keeps the default.
 */
public record PersonalNotice(String title, String body, String pushTitle, String pushBody, String ctaLabel) {
}
