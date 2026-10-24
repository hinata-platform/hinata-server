package com.ahmadre.hinata.moderation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV as the transparency report must be published (HIN-178): RFC 4180, UTF-8, comma separated,
 * CRLF between records, a field quoted where it holds a comma, a quote or a line break.
 */
final class TransparencyCsv {

	private TransparencyCsv() {
	}

	/** The records of [in], each a list of its fields. */
	static List<List<String>> read(InputStream in) {
		String text;
		try {
			text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		if (text.startsWith("﻿")) {
			text = text.substring(1);
		}
		List<List<String>> records = new ArrayList<>();
		List<String> fields = new ArrayList<>();
		StringBuilder field = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (quoted) {
				if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
					field.append('"');
					i++;
				}
				else if (c == '"') {
					quoted = false;
				}
				else {
					field.append(c);
				}
			}
			else if (c == '"') {
				quoted = true;
			}
			else if (c == ',') {
				fields.add(field.toString());
				field.setLength(0);
			}
			else if (c == '\n' || c == '\r') {
				if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
					i++;
				}
				fields.add(field.toString());
				field.setLength(0);
				records.add(fields);
				fields = new ArrayList<>();
			}
			else {
				field.append(c);
			}
		}
		if (field.length() > 0 || !fields.isEmpty()) {
			fields.add(field.toString());
			records.add(fields);
		}
		return records;
	}

	/** [records] as the bytes of one file. */
	static byte[] write(List<List<String>> records) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		StringBuilder line = new StringBuilder();
		for (List<String> record : records) {
			line.setLength(0);
			for (int i = 0; i < record.size(); i++) {
				if (i > 0) {
					line.append(',');
				}
				line.append(escape(record.get(i)));
			}
			line.append("\r\n");
			out.writeBytes(line.toString().getBytes(StandardCharsets.UTF_8));
		}
		return out.toByteArray();
	}

	private static String escape(String field) {
		if (field == null) {
			return "";
		}
		if (field.indexOf(',') < 0 && field.indexOf('"') < 0 && field.indexOf('\n') < 0
				&& field.indexOf('\r') < 0) {
			return field;
		}
		return '"' + field.replace("\"", "\"\"") + '"';
	}
}
