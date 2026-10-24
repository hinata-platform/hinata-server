package com.ahmadre.hinata.storage;

/** The gate refused bytes of [kind] for [reason] (HIN-177), for the yearly figures. Nothing of the file. */
public record UploadRefusedEvent(UploadKind kind, String reason) {
}
