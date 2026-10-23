package com.seolwoni.assistcore.i18n;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import net.minecraft.locale.Language;

/**
 * Text in the player's language. The Assist mods are written in Korean; every piece of text on a screen or in an
 * alert goes through {@link #t}, which looks the Korean up in the language files ("assist.t." + its checksum) and
 * gives the translation for the game's language, or the Korean itself when there is none (Korean, or not translated).
 */
public final class Tr {
	private Tr() {
	}

	/** For tests: always the Korean (their checks are written against it). */
	public static boolean korean;

	public static String key(String ko) {
		CRC32 c = new CRC32();
		c.update(ko.getBytes(StandardCharsets.UTF_8));
		return "assist.t." + String.format("%08x", c.getValue());
	}

	public static String t(String ko) {
		if (korean || ko == null || ko.isEmpty()) return ko;
		try {
			return Language.getInstance().getOrDefault(key(ko), ko);
		} catch (RuntimeException e) {
			return ko;
		}
	}
}
