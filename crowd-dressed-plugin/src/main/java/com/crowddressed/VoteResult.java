package com.crowddressed;

import lombok.Data;

/**
 * Represents one row from the top_votes_per_slot Supabase view.
 * Gson deserialises the REST response directly into these fields.
 */
@Data
public class VoteResult
{
	public String slot;
	public int item_id;
	public int vote_count;
	// item_name is populated locally by the plugin via ItemManager, not from the DB
	public transient String item_name;
}
