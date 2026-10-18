package com.seolwoni.mineassist.config;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.seolwoni.mineassist.MineAssistClient;

import net.fabricmc.loader.api.FabricLoader;

/** Progress of the current tunnel job, saved so digging can resume later. */
public class JobState {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("mineassist-job.json");

	public boolean active = false;
	public String world = "";
	public int ox, oy, oz;
	/** Direction index: 0 south, 1 west, 2 north, 3 east (vanilla horizontal order). */
	public int dir;
	public int targetY;
	public int mainLength, branchLength, branchSpacing;
	/** Next main tunnel step to dig (1-based), and branch progress at the current branch point. */
	public int mainK = 1;
	/** 0 = left branch pending, 1 = right branch pending, 2 = both done. */
	public int branchSide = 0;
	public int branchD = 0;
	public int stairsDone = 0;

	// stats
	public long blocksMined;
	public long oresMined;
	public long ticks;

	public static JobState load() {
		try {
			if (Files.exists(PATH)) {
				try (Reader r = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
					JobState s = GSON.fromJson(r, JobState.class);
					if (s != null) return s;
				}
			}
		} catch (Exception e) {
			MineAssistClient.LOGGER.warn("Failed to load job", e);
		}
		return new JobState();
	}

	public void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer w = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(this, w);
			}
		} catch (Exception e) {
			MineAssistClient.LOGGER.warn("Failed to save job", e);
		}
	}
}
