package com.seolwoni.mineassist.compat;

import com.seolwoni.mineassist.gui.MineConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return MineConfigScreen::new;
	}
}
