package com.ahmadre.hinata.moderation;

/**
 * Who reported without an account (HIN-175): a name and an address to send the receipt and the
 * outcome to. The address is stored lower case; {@code countedEmail} drops a {@code +tag} so one
 * mailbox cannot pass for many under the per-address limit.
 */
public record Notifier(String name, String email, String countedEmail) {
}
