package com.vexsoftware.votifier.model;

import net.minecraftforge.fml.common.eventhandler.Event;

/**
 * {@code VotifierEvent} is a custom Bukkit event class that is sent
 * synchronously to CraftBukkit's main thread allowing other plugins to listener
 * for votes.
 * 
 * @author frelling
 * 
 */
public class VotifierEvent extends Event {
	/**
	 * Encapsulated vote record.
	 */
	private Vote vote;

	/**
	 * Constructs a vote event that encapsulated the given vote record.
	 * 
	 * @param vote
	 *            vote record
	 */
	public VotifierEvent(final Vote vote) {
		this.vote = vote;
	}

	/**
	 * Return the encapsulated vote record.
	 * 
	 * @return vote record
	 */
	public Vote getVote() {
		return vote;
	}

}
