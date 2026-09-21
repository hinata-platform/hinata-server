package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.ApiException;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads CSV one record at a time (RFC 4180): quoted fields with doubled quotes, line breaks
 * inside quotes, CRLF or LF, a byte-order mark ignored. The delimiter is whichever of comma,
 * semicolon and tab the header line uses most — a spreadsheet saved in German writes semicolons.
 *
 * <p>Bounded on every axis a file can grow along, because the file is whatever somebody uploads:
 * the caller caps the bytes and the rows, this caps the columns and the length of one field.
 */
final class TimeCsvReader {

	static final int MAX_COLUMNS = 50;
	static final int MAX_FIELD_CHARS = 10_000;

	private final Reader in;
	private char delimiter;
	private int peeked = -2;

	TimeCsvReader(Reader in) {
		this.in = in;
	}

	/** The header, which also settles the delimiter; empty for an empty file. */
	List<String> header() throws IOException {
		StringBuilder line = new StringBuilder();
		int c = read();
		if (c == '﻿') {
			c = read();
		}
		boolean quoted = false;
		while (c != -1 && (quoted || c != '\n')) {
			if (c == '"') {
				quoted = !quoted;
			}
			line.append((char) c);
			if (line.length() > MAX_FIELD_CHARS * 4) {
				throw ApiException.badRequest("error.time.import.unreadable");
			}
			c = read();
		}
		String text = line.toString().replaceAll("\r$", "");
		if (text.isBlank()) {
			return List.of();
		}
		delimiter = delimiterOf(text);
		return parse(text);
	}

	/** The next record, or null at the end of the file. Blank lines are skipped. */
	List<String> next() throws IOException {
		while (true) {
			int c = read();
			if (c == -1) {
				return null;
			}
			if (c == '\n' || c == '\r') {
				continue;
			}
			unread(c);
			return record();
		}
	}

	private List<String> record() throws IOException {
		List<String> fields = new ArrayList<>();
		StringBuilder field = new StringBuilder();
		boolean quoted = false;
		boolean wasQuoted = false;
		while (true) {
			int c = read();
			if (c == -1) {
				add(fields, field);
				return fields;
			}
			if (quoted) {
				if (c == '"') {
					int after = read();
					if (after == '"') {
						append(field, '"');
					}
					else {
						quoted = false;
						unread(after);
					}
				}
				else {
					append(field, (char) c);
				}
				continue;
			}
			if (c == '"' && field.isEmpty() && !wasQuoted) {
				quoted = true;
				wasQuoted = true;
			}
			else if (c == delimiter) {
				add(fields, field);
				field.setLength(0);
				wasQuoted = false;
			}
			else if (c == '\n') {
				add(fields, field);
				return fields;
			}
			else if (c != '\r') {
				append(field, (char) c);
			}
		}
	}

	private List<String> parse(String line) throws IOException {
		TimeCsvReader single = new TimeCsvReader(new java.io.StringReader(line));
		single.delimiter = delimiter;
		List<String> fields = single.record();
		return fields.stream().map(String::strip).toList();
	}

	private static void add(List<String> fields, StringBuilder field) {
		if (fields.size() >= MAX_COLUMNS) {
			throw ApiException.badRequest("error.time.import.tooManyColumns", MAX_COLUMNS);
		}
		fields.add(field.toString());
	}

	private static void append(StringBuilder field, char c) {
		if (field.length() >= MAX_FIELD_CHARS) {
			throw ApiException.badRequest("error.time.import.unreadable");
		}
		field.append(c);
	}

	private static char delimiterOf(String header) {
		int commas = count(header, ',');
		int semicolons = count(header, ';');
		int tabs = count(header, '\t');
		if (tabs > commas && tabs > semicolons) {
			return '\t';
		}
		return semicolons > commas ? ';' : ',';
	}

	private static int count(String text, char c) {
		int n = 0;
		boolean quoted = false;
		for (int i = 0; i < text.length(); i++) {
			char at = text.charAt(i);
			if (at == '"') {
				quoted = !quoted;
			}
			else if (!quoted && at == c) {
				n++;
			}
		}
		return n;
	}

	private int read() throws IOException {
		if (peeked != -2) {
			int c = peeked;
			peeked = -2;
			return c;
		}
		return in.read();
	}

	private void unread(int c) {
		peeked = c;
	}
}
