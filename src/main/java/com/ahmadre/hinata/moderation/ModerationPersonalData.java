package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.block.BlockService;
import com.ahmadre.hinata.common.UserWords;
import com.ahmadre.hinata.me.PersonalDataExport;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What moderation holds about a person, for their data export (Art. 15 and 20 DSGVO): from
 * HIN-168 on, whom they block. Never who blocks them, and later never who reported them
 * (Art. 15 Abs. 4 DSGVO, § 29 Abs. 1 BDSG).
 */
@Component
@RequiredArgsConstructor
public class ModerationPersonalData implements PersonalDataExport {

	private final BlockService blocks;
	private final UserWords words;

	@Override
	public String key() {
		return "moderation";
	}

	@Override
	public Object data(User user) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("blocked", blocks.everyBlockOf(user).stream().map(person -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("userId", person.id());
			row.put("displayName", person.displayName());
			row.put("blockedAt", person.blockedAt());
			return row;
		}).toList());
		return out;
	}

	@Override
	public List<Table> tables(User user, Locale locale) {
		List<BlockService.BlockedPerson> blocked = blocks.everyBlockOf(user);
		if (blocked.isEmpty()) {
			return List.of();
		}
		return List.of(new Table(words.in(locale, "export.pdf.blocks.title"),
				List.of(words.in(locale, "export.pdf.blocks.person"), words.in(locale, "export.pdf.blocks.since")),
				new float[]{5, 3},
				blocked.stream().map(person -> List.of(
						person.displayName() == null ? "—" : person.displayName(),
						PersonalDataExport.instant(person.blockedAt()))).toList(),
				null));
	}
}
