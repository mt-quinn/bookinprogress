package com.crowddressed;

import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.kit.KitType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
@PluginDescriptor(
	name = "Crowd Dressed",
	description = "Let your viewers vote on your cosmetic appearance via a shared session code",
	tags = {"cosmetic", "transmog", "crowd", "vote", "appearance", "stream", "viewers"}
)
public class CrowdDressedPlugin extends Plugin
{
	private static final int ITEM_OFFSET = PlayerComposition.ITEM_OFFSET;
	private static final int POLL_INTERVAL_SECONDS = 60;
	private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // omit ambiguous chars
	private static final int CODE_LENGTH = 6;

	// Equipment slots that have visible cosmetic impact
	static final List<KitType> VOTED_SLOTS = ImmutableList.of(
		KitType.HEAD, KitType.CAPE, KitType.AMULET, KitType.WEAPON,
		KitType.TORSO, KitType.SHIELD, KitType.LEGS, KitType.HANDS, KitType.BOOTS
	);

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ItemManager itemManager;

	@Inject
	private CrowdDressedConfig config;

	private CrowdDressedPanel panel;
	private NavigationButton navButton;
	private ScheduledFuture<?> pollFuture;

	// Equipment IDs saved before any transmog so we can restore them
	private int[] savedEquipmentIds;

	@Provides
	CrowdDressedConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CrowdDressedConfig.class);
	}

	@Override
	protected void startUp()
	{
		panel = new CrowdDressedPanel(this, config);

		// Use a simple icon; replace with a real image if desired
		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
		if (icon == null)
		{
			icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		}
		navButton = NavigationButton.builder()
			.tooltip("Crowd Dressed")
			.icon(icon)
			.priority(8)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		ensureSessionCode();

		if (config.pollEnabled())
		{
			startPolling();
		}
	}

	@Override
	protected void shutDown()
	{
		stopPolling();
		revertAppearance();
		clientToolbar.removeNavigation(navButton);
		panel = null;
		navButton = null;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			// Re-apply on world hop / login — the saved IDs are now stale
			savedEquipmentIds = null;
			if (config.pollEnabled())
			{
				// Trigger an immediate poll to re-apply the transmog
				executor.submit(this::poll);
			}
		}
	}

	// Called by the panel toggle button
	public void toggleEnabled()
	{
		boolean nowEnabled = !config.pollEnabled();
		configManager.setConfiguration(CrowdDressedConfig.GROUP, "pollEnabled", nowEnabled);
		panel.setEnabled(nowEnabled);

		if (nowEnabled)
		{
			startPolling();
		}
		else
		{
			stopPolling();
			revertAppearance();
		}
	}

	// Called by the panel reset button
	public void resetSession()
	{
		stopPolling();
		revertAppearance();
		savedEquipmentIds = null;

		String newCode = generateCode();
		configManager.setConfiguration(CrowdDressedConfig.GROUP, "sessionCode", newCode);
		panel.setCode(newCode);
		panel.updateStandings(null);

		ensureSession(newCode);

		if (config.pollEnabled())
		{
			startPolling();
		}
	}

	// -------------------------------------------------------------------------
	// Polling
	// -------------------------------------------------------------------------

	private void startPolling()
	{
		if (pollFuture != null && !pollFuture.isCancelled())
		{
			return;
		}
		// Poll immediately, then every 60 seconds
		pollFuture = executor.scheduleAtFixedRate(this::poll, 0, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
	}

	private void stopPolling()
	{
		if (pollFuture != null)
		{
			pollFuture.cancel(false);
			pollFuture = null;
		}
	}

	private void poll()
	{
		String supabaseUrl = config.supabaseUrl().trim();
		String anonKey = config.supabaseAnonKey().trim();
		String code = config.sessionCode().trim();

		if (supabaseUrl.isEmpty() || anonKey.isEmpty() || code.isEmpty())
		{
			log.debug("CrowdDressed: Supabase config incomplete, skipping poll");
			return;
		}

		HttpUrl url = HttpUrl.parse(supabaseUrl + "/rest/v1/top_votes_per_slot")
			.newBuilder()
			.addQueryParameter("code", "eq." + code)
			.addQueryParameter("select", "slot,item_id,vote_count")
			.build();

		Request request = new Request.Builder()
			.url(url)
			.addHeader("apikey", anonKey)
			.addHeader("Authorization", "Bearer " + anonKey)
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("CrowdDressed: poll failed", e);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (response)
				{
					if (!response.isSuccessful())
					{
						log.warn("CrowdDressed: poll returned HTTP {}", response.code());
						return;
					}
					String body = response.body().string();
					Gson gson = new Gson();
					Type listType = new TypeToken<List<VoteResult>>()
					{
					}.getType();
					List<VoteResult> results = gson.fromJson(body, listType);

					// Resolve item names on the client thread (ItemManager requires it)
					clientThread.invokeLater(() ->
					{
						for (VoteResult r : results)
						{
							try
							{
								ItemComposition comp = itemManager.getItemComposition(r.item_id);
								r.item_name = comp != null ? comp.getName() : "Unknown";
							}
							catch (Exception ex)
							{
								r.item_name = "Item " + r.item_id;
							}
						}
						applyVotes(results);
					});

					// Update panel standings (item_name resolved above, but panel update
					// is via SwingUtilities inside so ordering is fine)
					panel.updateStandings(results);
				}
			}
		});
	}

	// -------------------------------------------------------------------------
	// Transmog application / revert
	// -------------------------------------------------------------------------

	private void applyVotes(List<VoteResult> results)
	{
		// Must be called on the client thread
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return;
		}
		PlayerComposition comp = player.getPlayerComposition();
		if (comp == null)
		{
			return;
		}

		int[] equipmentIds = comp.getEquipmentIds();

		// Save originals only once per login session so revert always returns
		// to the real equipment, not a previously transmog'd state
		if (savedEquipmentIds == null)
		{
			savedEquipmentIds = equipmentIds.clone();
		}

		// Start from the saved originals each cycle so removed votes revert cleanly
		System.arraycopy(savedEquipmentIds, 0, equipmentIds, 0, savedEquipmentIds.length);

		for (VoteResult r : results)
		{
			KitType slot;
			try
			{
				slot = KitType.valueOf(r.slot);
			}
			catch (IllegalArgumentException e)
			{
				log.warn("CrowdDressed: unknown slot '{}', skipping", r.slot);
				continue;
			}
			if (!VOTED_SLOTS.contains(slot))
			{
				continue;
			}
			equipmentIds[slot.getIndex()] = r.item_id + ITEM_OFFSET;
		}

		comp.setHash();
	}

	private void revertAppearance()
	{
		if (savedEquipmentIds == null)
		{
			return;
		}
		int[] snapshot = savedEquipmentIds;
		clientThread.invokeLater(() ->
		{
			Player player = client.getLocalPlayer();
			if (player == null)
			{
				return;
			}
			PlayerComposition comp = player.getPlayerComposition();
			if (comp == null)
			{
				return;
			}
			int[] equipmentIds = comp.getEquipmentIds();
			System.arraycopy(snapshot, 0, equipmentIds, 0, snapshot.length);
			comp.setHash();
		});
		savedEquipmentIds = null;
	}

	// -------------------------------------------------------------------------
	// Session management
	// -------------------------------------------------------------------------

	private void ensureSessionCode()
	{
		String code = config.sessionCode().trim();
		if (code.isEmpty())
		{
			code = generateCode();
			configManager.setConfiguration(CrowdDressedConfig.GROUP, "sessionCode", code);
		}
		panel.setCode(code);
		ensureSession(code);
	}

	private void ensureSession(String code)
	{
		String supabaseUrl = config.supabaseUrl().trim();
		String anonKey = config.supabaseAnonKey().trim();
		if (supabaseUrl.isEmpty() || anonKey.isEmpty())
		{
			return;
		}

		// Upsert the session — if the code already exists in the DB, do nothing
		String json = "{\"code\":\"" + code + "\"}";
		RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), json);

		Request request = new Request.Builder()
			.url(supabaseUrl + "/rest/v1/sessions")
			.addHeader("apikey", anonKey)
			.addHeader("Authorization", "Bearer " + anonKey)
			.addHeader("Content-Type", "application/json")
			.addHeader("Prefer", "resolution=ignore-duplicates,return=minimal")
			.post(body)
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("CrowdDressed: failed to create/verify session", e);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				response.close();
				if (!response.isSuccessful())
				{
					log.warn("CrowdDressed: session upsert returned HTTP {}", response.code());
				}
			}
		});
	}

	private String generateCode()
	{
		Random rng = new Random();
		StringBuilder sb = new StringBuilder(CODE_LENGTH);
		for (int i = 0; i < CODE_LENGTH; i++)
		{
			sb.append(CODE_CHARS.charAt(rng.nextInt(CODE_CHARS.length())));
		}
		return sb.toString();
	}
}
