package com.crowddressed;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(CrowdDressedConfig.GROUP)
public interface CrowdDressedConfig extends Config
{
	String GROUP = "crowddressed";

	@ConfigSection(
		name = "Backend",
		description = "Supabase connection settings",
		position = 0
	)
	String backendSection = "backend";

	@ConfigItem(
		keyName = "supabaseUrl",
		name = "Supabase Project URL",
		description = "Your Supabase project URL (e.g. https://xyz.supabase.co)",
		section = backendSection,
		position = 1
	)
	default String supabaseUrl()
	{
		return "";
	}

	@ConfigItem(
		keyName = "supabaseAnonKey",
		name = "Supabase Anon Key",
		description = "Your Supabase project's public anon key",
		section = backendSection,
		position = 2,
		secret = true
	)
	default String supabaseAnonKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "sessionCode",
		name = "Session Code",
		description = "Auto-generated code shared with viewers. Change only if you want to migrate to a specific code.",
		position = 3
	)
	default String sessionCode()
	{
		return "";
	}

	@ConfigItem(
		keyName = "pollEnabled",
		name = "Enable Crowd Voting",
		description = "When enabled, your appearance is updated every 60 seconds based on viewer votes",
		position = 4
	)
	default boolean pollEnabled()
	{
		return false;
	}
}
