package com.ahmadre.hinata.storage;

/** What bytes somebody wants to store are for (HIN-177); each kind has its own types and limits. */
public enum UploadKind {
	ATTACHMENT,
	MEDIA,
	VOICE,
	AVATAR,
	LOGO,
	MAIL_ATTACHMENT
}
