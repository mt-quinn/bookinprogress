package com.crowddressed;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

public class CrowdDressedPanel extends PluginPanel
{
	private final CrowdDressedPlugin plugin;

	private final JLabel codeLabel;
	private final JButton toggleButton;
	private final JPanel standingsPanel;

	// ordered map so slots always render in the same order
	private static final Map<String, String> SLOT_LABELS = new LinkedHashMap<>();

	static
	{
		SLOT_LABELS.put("HEAD", "Head");
		SLOT_LABELS.put("CAPE", "Cape");
		SLOT_LABELS.put("AMULET", "Amulet");
		SLOT_LABELS.put("WEAPON", "Weapon");
		SLOT_LABELS.put("TORSO", "Body");
		SLOT_LABELS.put("SHIELD", "Shield");
		SLOT_LABELS.put("LEGS", "Legs");
		SLOT_LABELS.put("HANDS", "Gloves");
		SLOT_LABELS.put("BOOTS", "Boots");
	}

	CrowdDressedPanel(CrowdDressedPlugin plugin, CrowdDressedConfig config)
	{
		super(false);
		this.plugin = plugin;

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBorder(new EmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		// --- Title ---
		JLabel title = new JLabel("Crowd Dressed", SwingConstants.CENTER);
		title.setFont(FontManager.getRunescapeBoldFont().deriveFont(16f));
		title.setForeground(Color.WHITE);
		title.setAlignmentX(CENTER_ALIGNMENT);
		add(title);
		add(Box.createVerticalStrut(12));

		// --- Session code display ---
		JLabel codeHint = new JLabel("Your session code", SwingConstants.CENTER);
		codeHint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		codeHint.setFont(FontManager.getRunescapeSmallFont());
		codeHint.setAlignmentX(CENTER_ALIGNMENT);
		add(codeHint);
		add(Box.createVerticalStrut(4));

		codeLabel = new JLabel("------", SwingConstants.CENTER);
		codeLabel.setFont(FontManager.getRunescapeBoldFont().deriveFont(28f));
		codeLabel.setForeground(new Color(0xFF, 0xA5, 0x00)); // OSRS gold
		codeLabel.setAlignmentX(CENTER_ALIGNMENT);
		codeLabel.setToolTipText("Click to copy");
		codeLabel.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
		codeLabel.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				copyCodeToClipboard();
			}
		});
		add(codeLabel);
		add(Box.createVerticalStrut(4));

		JLabel copyHint = new JLabel("(click to copy)", SwingConstants.CENTER);
		copyHint.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		copyHint.setFont(FontManager.getRunescapeSmallFont());
		copyHint.setAlignmentX(CENTER_ALIGNMENT);
		add(copyHint);
		add(Box.createVerticalStrut(14));

		// --- Toggle button ---
		toggleButton = new JButton(config.pollEnabled() ? "Disable Voting" : "Enable Voting");
		toggleButton.setAlignmentX(CENTER_ALIGNMENT);
		toggleButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		toggleButton.setBackground(config.pollEnabled() ? new Color(0x8B, 0x00, 0x00) : new Color(0x1A, 0x5C, 0x1A));
		toggleButton.setForeground(Color.WHITE);
		toggleButton.setFocusPainted(false);
		toggleButton.addActionListener(e -> plugin.toggleEnabled());
		add(toggleButton);
		add(Box.createVerticalStrut(6));

		// --- Reset code button ---
		JButton resetButton = new JButton("Reset Code (new session)");
		resetButton.setAlignmentX(CENTER_ALIGNMENT);
		resetButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		resetButton.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		resetButton.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		resetButton.setFocusPainted(false);
		resetButton.addActionListener(e ->
		{
			int confirm = JOptionPane.showConfirmDialog(
				this,
				"Generate a new session code? Viewers will need to use the new code and all current votes will be abandoned.",
				"Reset Session",
				JOptionPane.YES_NO_OPTION
			);
			if (confirm == JOptionPane.YES_OPTION)
			{
				plugin.resetSession();
			}
		});
		add(resetButton);
		add(Box.createVerticalStrut(16));

		// --- Standings header ---
		JLabel standingsTitle = new JLabel("Current Vote Leaders", SwingConstants.CENTER);
		standingsTitle.setFont(FontManager.getRunescapeBoldFont());
		standingsTitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		standingsTitle.setAlignmentX(CENTER_ALIGNMENT);
		add(standingsTitle);
		add(Box.createVerticalStrut(6));

		// --- Standings table ---
		standingsPanel = new JPanel(new GridLayout(0, 1, 0, 2));
		standingsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(standingsPanel);

		refreshStandingsPlaceholder();
	}

	void setCode(String code)
	{
		SwingUtilities.invokeLater(() -> codeLabel.setText(code));
	}

	void setEnabled(boolean enabled)
	{
		SwingUtilities.invokeLater(() ->
		{
			toggleButton.setText(enabled ? "Disable Voting" : "Enable Voting");
			toggleButton.setBackground(enabled ? new Color(0x8B, 0x00, 0x00) : new Color(0x1A, 0x5C, 0x1A));
		});
	}

	void updateStandings(List<VoteResult> results)
	{
		SwingUtilities.invokeLater(() ->
		{
			standingsPanel.removeAll();

			if (results == null || results.isEmpty())
			{
				refreshStandingsPlaceholder();
				standingsPanel.revalidate();
				standingsPanel.repaint();
				return;
			}

			// Build a map so we can render in a consistent slot order
			Map<String, VoteResult> bySlot = new LinkedHashMap<>();
			for (VoteResult r : results)
			{
				bySlot.put(r.slot, r);
			}

			for (Map.Entry<String, String> entry : SLOT_LABELS.entrySet())
			{
				VoteResult r = bySlot.get(entry.getKey());
				JPanel row = buildStandingRow(
					entry.getValue(),
					r != null ? r.item_name : "—",
					r != null ? r.vote_count : 0
				);
				standingsPanel.add(row);
			}

			standingsPanel.revalidate();
			standingsPanel.repaint();
		});
	}

	private JPanel buildStandingRow(String slotLabel, String itemName, int votes)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.DARK_GRAY_COLOR, 1),
			new EmptyBorder(4, 6, 4, 6)
		));

		JLabel slot = new JLabel(slotLabel);
		slot.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		slot.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		slot.setPreferredSize(new Dimension(48, 16));

		JLabel item = new JLabel(itemName != null ? itemName : "—");
		item.setFont(FontManager.getRunescapeSmallFont());
		item.setForeground(Color.WHITE);

		JLabel count = new JLabel(votes > 0 ? votes + " vote" + (votes == 1 ? "" : "s") : "");
		count.setFont(FontManager.getRunescapeSmallFont());
		count.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		count.setHorizontalAlignment(SwingConstants.RIGHT);

		row.add(slot, BorderLayout.WEST);
		row.add(item, BorderLayout.CENTER);
		row.add(count, BorderLayout.EAST);
		return row;
	}

	private void refreshStandingsPlaceholder()
	{
		for (String label : SLOT_LABELS.values())
		{
			standingsPanel.add(buildStandingRow(label, "—", 0));
		}
	}

	private void copyCodeToClipboard()
	{
		String code = codeLabel.getText();
		if (code == null || code.equals("------"))
		{
			return;
		}
		StringSelection sel = new StringSelection(code);
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, sel);
	}
}
