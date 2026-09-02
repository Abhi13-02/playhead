package com.playhead;

/**
 * The lookup key for the playback store: one profile watching one title.
 *
 * <p>A {@code Map} needs a single key object per entry. State is tracked per (profile, title)
 * pair, so this record exists purely to combine both ids into one key — safer than squashing them
 * into one string, since a real id could contain any character.
 */
public record Key(String profileId, String titleId) {
}
