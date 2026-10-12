package com.ahmadre.hinata.notification;

/**
 * The {@code content-state} of a Live Activity push, sealed (HIN-103):
 * {@code {"v":1,"n":<nonce>,"c":<ciphertext+tag>}}, both standard Base64.
 * Nobody between the server and the widget extension can read it.
 */
public record SealedLiveActivityState(int v, String n, String c) {
}
