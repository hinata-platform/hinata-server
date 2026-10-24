package com.ahmadre.hinata.storage;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Bytes somebody wants to store, as the gate sees them (HIN-177): what they are for, the type
 * declared for them, the name they came with, how many there are, and a way to read them, as
 * often as the gate needs to.
 */
public record UploadCandidate(UploadKind kind, String declaredType, String fileName, long size, Source content) {

	/** Opens the bytes from the start, each time it is asked. */
	@FunctionalInterface
	public interface Source {
		InputStream open() throws IOException;
	}

	/** [file] as sent; no file at all is an empty one, which the gate refuses as such. */
	public static UploadCandidate of(UploadKind kind, MultipartFile file) {
		if (file == null) {
			return new UploadCandidate(kind, null, null, 0, InputStream::nullInputStream);
		}
		return new UploadCandidate(kind, file.getContentType(), file.getOriginalFilename(), file.getSize(),
				file::getInputStream);
	}

	public static UploadCandidate of(UploadKind kind, byte[] data, String declaredType, String fileName) {
		return new UploadCandidate(kind, declaredType, fileName, data.length, () -> new ByteArrayInputStream(data));
	}

	/** The first [length] bytes, or fewer when there are fewer. */
	public byte[] head(int length) throws IOException {
		try (InputStream in = content.open()) {
			return in.readNBytes(length);
		}
	}

	/** All of the bytes; only for what is read into memory anyway. */
	public byte[] bytes() throws IOException {
		try (InputStream in = content.open()) {
			return in.readAllBytes();
		}
	}
}
