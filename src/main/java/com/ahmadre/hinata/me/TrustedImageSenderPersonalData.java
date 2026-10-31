package com.ahmadre.hinata.me;

import com.ahmadre.hinata.common.UserWords;
import com.ahmadre.hinata.user.TrustedImageSender;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The sender domains a person trusts with images (HIN-181), in their own data export. At most
 * {@value TrustedImageSenderService#MAX_DOMAINS}, so never cut.
 */
@Component
@RequiredArgsConstructor
class TrustedImageSenderPersonalData implements PersonalDataExport {

	private final UserWords words;

	@Override
	public String key() {
		return "trustedImageSenders";
	}

	@Override
	public Object data(User user) {
		return user.getTrustedImageSenders().stream().map(sender -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("domain", sender.domain());
			row.put("addedAt", sender.addedAt());
			return row;
		}).toList();
	}

	@Override
	public List<Table> tables(User user, Locale locale) {
		List<TrustedImageSender> senders = user.getTrustedImageSenders();
		if (senders.isEmpty()) return List.of();
		return List.of(new Table(words.in(locale, "export.pdf.trustedImageSenders"),
				List.of(words.in(locale, "export.pdf.trustedImageSenders.domain"),
						words.in(locale, "export.pdf.calendar.createdAt")),
				new float[] { 6, 3 },
				senders.stream().map(sender -> List.of(sender.domain(), PersonalDataExport.instant(sender.addedAt())))
						.toList(),
				null));
	}
}
