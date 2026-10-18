package com.terraformersmc.modmenu.api;

/** Compile-only stub of Mod Menu's API (excluded from the jar). */
public interface ModMenuApi {
	default ConfigScreenFactory<?> getModConfigScreenFactory() {
		return null;
	}
}
