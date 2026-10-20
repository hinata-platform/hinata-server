package com.ahmadre.hinata.shifts;

import org.springframework.data.convert.PropertyValueConverter;
import org.springframework.data.convert.ValueConversionContext;
import org.springframework.data.mongodb.core.mapping.MongoPersistentProperty;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

/**
 * Stores the module's wall-clock times and days as ISO text: {@code 18:00},
 * {@code 2026-10-26T18:00}, {@code 2026-10-26}.
 *
 * <p>The default mapping writes a {@link LocalTime}, {@link LocalDateTime} or {@link LocalDate} as
 * a BSON date read in the server's zone, so a server moved to another zone reads a series that
 * starts at another hour, and an occurrence that falls on another day. Text has no zone; ISO dates
 * and date-times also sort as they read, so range queries on them keep working. Single values,
 * lists ({@code exdates}) and the values of a query ({@code $in}, {@code $gte}) are all converted.
 * A value written before this converter (a BSON date) is still read, in the server's zone as it
 * was written.
 */
public class ShiftWallClock implements PropertyValueConverter<Object, Object, ValueConversionContext<MongoPersistentProperty>> {

	@Override
	public Object read(Object value, ValueConversionContext<MongoPersistentProperty> context) {
		Class<?> type = context.getProperty().getActualType();
		if (value instanceof Collection<?> values) {
			List<Object> out = new ArrayList<>(values.size());
			values.forEach(one -> out.add(readOne(one, type)));
			return out;
		}
		return readOne(value, type);
	}

	@Override
	public Object write(Object value, ValueConversionContext<MongoPersistentProperty> context) {
		if (value instanceof Collection<?> values) {
			List<Object> out = new ArrayList<>(values.size());
			values.forEach(one -> out.add(writeOne(one)));
			return out;
		}
		return writeOne(value);
	}

	static Object writeOne(Object value) {
		return value instanceof Temporal ? value.toString() : value;
	}

	static Object readOne(Object value, Class<?> type) {
		if (value instanceof String text) {
			if (type == LocalTime.class) {
				return LocalTime.parse(text);
			}
			if (type == LocalDateTime.class) {
				return LocalDateTime.parse(text);
			}
			if (type == LocalDate.class) {
				return LocalDate.parse(text);
			}
		}
		if (value instanceof Date legacy) {
			LocalDateTime local = LocalDateTime.ofInstant(legacy.toInstant(), ZoneId.systemDefault());
			if (type == LocalTime.class) {
				return local.toLocalTime();
			}
			if (type == LocalDate.class) {
				return local.toLocalDate();
			}
			return local;
		}
		return value;
	}
}
