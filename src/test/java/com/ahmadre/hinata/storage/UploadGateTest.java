package com.ahmadre.hinata.storage;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.config.HinataProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.ahmadre.hinata.storage.TestImages.exe;
import static com.ahmadre.hinata.storage.TestImages.m4a;
import static com.ahmadre.hinata.storage.TestImages.pdf;
import static com.ahmadre.hinata.storage.TestImages.png;
import static com.ahmadre.hinata.storage.TestImages.webm;
import static com.ahmadre.hinata.storage.TestImages.zip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The one check every byte from a person passes before it is stored (HIN-177). The type comes
 * from what the bytes are, the kind decides which types and how many bytes, an image's size is
 * read before anything decodes it, and what the gate refuses is counted once.
 */
class UploadGateTest {

	private final List<UploadRefusedEvent> refused = new ArrayList<>();
	private final UploadGate gate = TestUploadGates.counting(refused);

	private static UploadCandidate file(UploadKind kind, byte[] data, String type) {
		return UploadCandidate.of(kind, data, type, "file");
	}

	private String refusal(UploadCandidate candidate) {
		return refusal(gate, candidate);
	}

	private static String refusal(UploadGate gate, UploadCandidate candidate) {
		return gate.refusalOf(candidate).map(UploadRefusal::code).orElse(null);
	}

	@Test
	void anAttachmentIsTakenForWhatItIsNotForWhatItSays() {
		assertThat(gate.check(file(UploadKind.ATTACHMENT, pdf(), "application/pdf")).contentType())
				.isEqualTo("application/pdf");
		// Parameters and the old alias are spelling, not a different type.
		assertThat(gate.check(file(UploadKind.ATTACHMENT, "a;b\n1;2\n".getBytes(StandardCharsets.UTF_8),
				"text/csv; charset=utf-8")).contentType()).isEqualTo("text/csv");
		assertThat(gate.check(file(UploadKind.ATTACHMENT, TestImages.jpeg(8, 8), "image/jpg")).contentType())
				.isEqualTo("image/jpeg");

		assertThat(refusal(file(UploadKind.ATTACHMENT, exe(), "application/pdf"))).isEqualTo("signature");
		assertThat(refusal(file(UploadKind.ATTACHMENT, exe(), "text/plain"))).isEqualTo("signature");
		assertThat(refusal(file(UploadKind.ATTACHMENT, exe(), "application/x-msdownload"))).isEqualTo("type");
		assertThat(refusal(file(UploadKind.ATTACHMENT, "<svg/>".getBytes(StandardCharsets.UTF_8),
				"image/svg+xml"))).isEqualTo("type");
		assertThat(refusal(file(UploadKind.ATTACHMENT, pdf(), null))).isEqualTo("type");
	}

	@Test
	void eachKindKeepsToItsOwnTypes() {
		assertThat(refusal(file(UploadKind.MEDIA, png(4, 4), "image/png"))).isNull();
		assertThat(refusal(file(UploadKind.MEDIA, pdf(), "application/pdf"))).isEqualTo("type");

		assertThat(gate.check(file(UploadKind.VOICE, m4a(), "audio/mp4")).contentType()).isEqualTo("audio/mp4");
		assertThat(refusal(file(UploadKind.VOICE, webm(), "audio/webm"))).isNull();
		assertThat(refusal(file(UploadKind.VOICE, png(4, 4), "audio/mp4"))).isEqualTo("signature");
		assertThat(refusal(file(UploadKind.VOICE, pdf(), "application/pdf"))).isEqualTo("type");

		assertThat(refusal(file(UploadKind.AVATAR, png(4, 4), "image/png"))).isNull();
		assertThat(refusal(file(UploadKind.AVATAR, zip(), "application/zip"))).isEqualTo("type");
		assertThat(refusal(file(UploadKind.LOGO, png(4, 4), "image/png"))).isNull();
	}

	@Test
	void theSizeFollowsTheKindAndIsCheckedBeforeAByteIsRead() {
		assertThat(refusal(sized(UploadKind.VOICE, UploadGate.VOICE_MAX_BYTES + 1))).isEqualTo("size");
		assertThat(refusal(sized(UploadKind.AVATAR, UploadGate.AVATAR_MAX_BYTES + 1))).isEqualTo("size");
		assertThat(refusal(sized(UploadKind.LOGO, UploadGate.LOGO_MAX_BYTES + 1))).isEqualTo("size");
		assertThat(refusal(sized(UploadKind.ATTACHMENT, 25L * 1024 * 1024 + 1))).isEqualTo("size");
		assertThat(refusal(file(UploadKind.ATTACHMENT, new byte[0], "application/pdf"))).isEqualTo("empty");
	}

	/** A candidate claiming [size] bytes whose content must never be opened. */
	private static UploadCandidate sized(UploadKind kind, long size) {
		return new UploadCandidate(kind, "image/png", "file", size, () -> {
			throw new AssertionError("read although the size alone refuses it");
		});
	}

	/**
	 * A few hundred bytes may declare more pixels than the heap holds. The header is read, the
	 * image never decoded.
	 */
	@Test
	void aPixelBombIsRefusedBeforeAnythingDecodesIt() {
		byte[] bomb = TestImages.pngDeclaring(50_000, 50_000);

		assertThat(bomb.length).isLessThan(200);
		assertThat(refusal(file(UploadKind.MEDIA, bomb, "image/png"))).isEqualTo("image");
		assertThat(refusal(file(UploadKind.AVATAR, bomb, "image/png"))).isEqualTo("image");
		// The logo's own budget is smaller than the decoder's.
		assertThat(refusal(file(UploadKind.LOGO, TestImages.pngDeclaring(8_000, 8_000), "image/png")))
				.isEqualTo("image");
		assertThat(refusal(file(UploadKind.MEDIA, TestImages.pngDeclaring(8_000, 8_000), "image/png"))).isNull();
	}

	@Test
	void aFileNameCarriesNoPathAndNoControlCharacter() {
		for (String name : List.of("../etc/passwd", "a/b.pdf", "C:\\Users\\a.pdf", "a\u0000b.pdf", "a\nb.pdf",
				"a\u007Fb.pdf", "x".repeat(UploadGate.FILE_NAME_MAX + 1))) {
			assertThat(refusal(UploadCandidate.of(UploadKind.ATTACHMENT, pdf(), "application/pdf", name)))
					.as(name).isEqualTo("fileName");
		}
		assertThat(refusal(UploadCandidate.of(UploadKind.ATTACHMENT, pdf(), "application/pdf",
				"Bericht über März (final).pdf"))).isNull();
		assertThat(refusal(UploadCandidate.of(UploadKind.ATTACHMENT, pdf(), "application/pdf",
				"x".repeat(UploadGate.FILE_NAME_MAX)))).isNull();
		// No name at all is fine: the store names every object itself.
		assertThat(refusal(UploadCandidate.of(UploadKind.MEDIA, png(4, 4), "image/png", null))).isNull();
	}

	@Test
	void aGroupSwitchedOffIsRefusedForPeopleButNeverForAPicture() {
		UploadGate narrowed = TestUploadGates.gate(Set.of(UploadGroup.PDF, UploadGroup.IMAGES), refused);

		assertThat(refusal(narrowed, file(UploadKind.ATTACHMENT, pdf(), "application/pdf"))).isEqualTo("disabled");
		assertThat(refusal(narrowed, file(UploadKind.MAIL_ATTACHMENT, pdf(), "application/pdf"))).isEqualTo("disabled");
		assertThat(refusal(narrowed, file(UploadKind.MEDIA, png(4, 4), "image/png"))).isEqualTo("disabled");
		assertThat(refusal(narrowed, file(UploadKind.ATTACHMENT, zip(), "application/zip"))).isNull();
		// Avatars and the logo are re-encoded by the server and not switched with the groups.
		assertThat(refusal(narrowed, file(UploadKind.AVATAR, png(4, 4), "image/png"))).isNull();
		assertThat(refusal(narrowed, file(UploadKind.LOGO, png(4, 4), "image/png"))).isNull();
	}

	/**
	 * Text is text: no program and no web page behind it, however its first bytes look, and no name
	 * a system would run on a double click. A spreadsheet's CSV in Windows-1252 stays text.
	 */
	@Test
	void textIsNeverAProgramOrAPageInDisguise() {
		byte[] program = new byte[600];
		program[0] = 'M';
		program[1] = 'Z';
		for (int i = 2; i < 64; i++) {
			program[i] = 'A';
		}
		assertThat(refusal(file(UploadKind.ATTACHMENT, program, "text/plain"))).isEqualTo("signature");
		assertThat(refusal(file(UploadKind.MAIL_ATTACHMENT, program, "text/plain"))).isEqualTo("type");
		for (String start : List.of("#!/bin/sh\nrm -rf ~\n", "<svg onload=alert(1)>", "﻿<html><script>",
				"  <!DOCTYPE html>", "<hta:application>", "\u007FELF....", "<?xml version=\"1.0\"?><svg/>")) {
			assertThat(refusal(file(UploadKind.ATTACHMENT, start.getBytes(StandardCharsets.UTF_8), "text/plain")))
					.as(start).isEqualTo("signature");
		}
		for (String name : List.of("Rechnung.pdf.hta", "setup.EXE", "start.bat", "a.ps1", "x.lnk", "page.html",
				"img.svg", "y.js", "evil.exe.", "run.command")) {
			assertThat(refusal(UploadCandidate.of(UploadKind.ATTACHMENT, "Hallo".getBytes(StandardCharsets.UTF_8),
					"text/plain", name))).as(name).isEqualTo("type");
		}
		byte[] spreadsheet = "Name;Straße\nMüller;Bahnhofstraße 1\n".getBytes(java.nio.charset.Charset.forName("windows-1252"));
		assertThat(gate.check(UploadCandidate.of(UploadKind.ATTACHMENT, spreadsheet, "text/csv", "adressen.csv"))
				.contentType()).isEqualTo("text/csv");
	}

	/** A sender's header counts for nothing; what the bytes are decides, and only the safe list passes. */
	@Test
	void aMailAttachmentIsTypedByItsContent() {
		assertThat(gate.check(file(UploadKind.MAIL_ATTACHMENT, pdf(), "application/octet-stream")).contentType())
				.isEqualTo("application/pdf");
		assertThat(gate.check(file(UploadKind.MAIL_ATTACHMENT, zip(), "application/pdf")).contentType())
				.isEqualTo("application/zip");
		String docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
		assertThat(gate.check(file(UploadKind.MAIL_ATTACHMENT, zip(), docx)).contentType()).isEqualTo(docx);
		assertThat(gate.check(file(UploadKind.MAIL_ATTACHMENT, "Hallo".getBytes(StandardCharsets.UTF_8),
				"text/plain")).contentType()).isEqualTo("text/plain");

		assertThat(refusal(file(UploadKind.MAIL_ATTACHMENT, exe(), "application/pdf"))).isEqualTo("type");
		assertThat(refusal(file(UploadKind.MAIL_ATTACHMENT, exe(), "text/plain"))).isEqualTo("type");
	}

	/** The environment may narrow the attachment list; a type no signature covers stays refused. */
	@Test
	void theConfiguredListNeverWidensTheSafeOne() {
		HinataProperties properties = new HinataProperties();
		properties.getStorage().setAllowedContentTypes(List.of("application/pdf", "application/octet-stream",
				"text/html", "image/svg+xml"));
		@SuppressWarnings("unchecked")
		ObjectProvider<UploadPolicy> none = mock(ObjectProvider.class);
		UploadGate configured = new UploadGate(properties, none, event -> { });

		assertThat(refusal(configured, file(UploadKind.ATTACHMENT, pdf(), "application/pdf"))).isNull();
		assertThat(refusal(configured, file(UploadKind.ATTACHMENT, png(4, 4), "image/png"))).isEqualTo("type");
		assertThat(refusal(configured, file(UploadKind.ATTACHMENT, exe(), "application/octet-stream"))).isEqualTo("type");
		assertThat(refusal(configured, file(UploadKind.ATTACHMENT, "<b>x</b>".getBytes(StandardCharsets.UTF_8),
				"text/html"))).isEqualTo("type");
		assertThat(refusal(configured, file(UploadKind.MAIL_ATTACHMENT, exe(), "application/octet-stream"))).isEqualTo("type");
	}

	@Test
	void aRefusalIsA422WithItsReasonAndIsCountedOnce() {
		UploadCandidate spoofed = file(UploadKind.VOICE, pdf(), "audio/mp4");

		assertThatThrownBy(() -> gate.check(spoofed))
				.isInstanceOfSatisfying(ApiException.class, ex -> {
					assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
					assertThat(ex.getMessageKey()).isEqualTo("error.upload.refused.signature");
					assertThat(ex.getDetails()).containsEntry("code", "upload.refused")
							.containsEntry("reason", "signature");
				});
		assertThat(refused).containsExactly(new UploadRefusedEvent(UploadKind.VOICE, "signature"));

		// Looking again, as rebuilding a mail's description does, counts nothing.
		assertThat(refusal(gate, spoofed)).isEqualTo("signature");
		assertThat(refused).hasSize(1);
	}

	@Test
	void theSizeRefusalNamesTheLimit() {
		assertThatThrownBy(() -> gate.check(new UploadCandidate(UploadKind.VOICE, "audio/mp4", "v.m4a",
				UploadGate.VOICE_MAX_BYTES + 1, () -> new ByteArrayInputStream(m4a()))))
				.isInstanceOfSatisfying(ApiException.class,
						ex -> assertThat(ex.getArgs()).containsExactly(16L));
	}

	@Test
	void noFileAtAllIsAnEmptyOne() {
		assertThat(refusal(gate, UploadCandidate.of(UploadKind.AVATAR, (MultipartFile) null))).isEqualTo("empty");
	}
}
