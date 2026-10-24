package com.ahmadre.hinata.moderation;

/**
 * Who reported without an account (HIN-175): a name and an address, as typed, to send the receipt
 * and the outcome to. The per-address limit counts in {@code public_report_quota}, not here.
 */
public record Notifier(String name, String email) {
}
