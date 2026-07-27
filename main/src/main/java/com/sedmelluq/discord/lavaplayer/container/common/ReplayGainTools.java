package com.sedmelluq.discord.lavaplayer.container.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Shared helpers for turning ReplayGain metadata into a playback volume multiplier.
 *
 * <p>Every container resolves gain the same way, so the tag names, the reference-level correction and the
 * clipping limit live here rather than being repeated per format.
 */
public class ReplayGainTools {
    private static final Logger log = LoggerFactory.getLogger(ReplayGainTools.class);

    /**
     * Decibels to add to an R128 gain to express it on the ReplayGain 2.0 scale. {@code R128_TRACK_GAIN} is
     * referenced to -23 LUFS and ReplayGain 2.0 to -18 LUFS, so without this correction a tagged Opus file and a
     * tagged MP3 play about 5 dB apart.
     */
    public static final float R128_REFERENCE_OFFSET_DB = 5.0f;

    public static final String TRACK_GAIN_TAG = "REPLAYGAIN_TRACK_GAIN";
    public static final String TRACK_PEAK_TAG = "REPLAYGAIN_TRACK_PEAK";
    public static final String R128_TRACK_GAIN_TAG = "R128_TRACK_GAIN";

    private ReplayGainTools() {
    }

    /**
     * Resolves the volume multiplier described by a map of tags, limited so it cannot push the track's peak past
     * full scale.
     *
     * @param tags             Tag map, keyed by uppercased tag name
     * @param r128HeaderGainDb Gain already carried in the container header, in decibels and on the R128 (-23 LUFS)
     *                         scale — the Opus header output gain. Pass 0 for formats that have no such field.
     * @param format           Format name, used only for logging
     * @return The multiplier to apply, or 1.0 if the track carries no usable gain
     */
    public static float resolveMultiplier(Map<String, String> tags, float r128HeaderGainDb, String format) {
        float totalGainDb = resolveGainDb(tags, r128HeaderGainDb);

        if (totalGainDb == 0.0f) {
            return 1.0f;
        }

        return multiplierFromGain(totalGainDb, tags.get(TRACK_PEAK_TAG), format);
    }

    /**
     * Resolves the total gain to apply, expressed on the ReplayGain 2.0 (-18 LUFS) scale.
     *
     * <p>The header gain is always folded into the result — the Opus spec makes it a decode-time gain rather than
     * optional metadata — and a tag is then applied on top of it. (Callers still only consult this at all when
     * ReplayGain is enabled, so an unconfigured player keeps playing streams raw.)
     *
     * <ul>
     *   <li>{@code R128_TRACK_GAIN} is the residual half of the same -23 LUFS figure the header carries: encoders
     *       write the bulk into the header and leave the tag as the remainder. The two are summed and the reference
     *       shift is applied <b>once</b> to that sum, so a file normalised purely via its header lands at the same
     *       level as the same file carrying an extra {@code R128_TRACK_GAIN=0}.</li>
     *   <li>{@code REPLAYGAIN_TRACK_GAIN} is measured on decoded output, which already includes the header gain, and
     *       targets -18 LUFS directly. It is added as-is with no shift, and supersedes the reference question
     *       entirely. It is only consulted when there is no R128 tag, since the two describe the same correction on
     *       different scales and adding both would double-count it.</li>
     *   <li>With no tag at all, a non-zero header gain is -23 LUFS referenced normalisation and takes the shift.</li>
     * </ul>
     *
     * @param tags             Tag map, keyed by uppercased tag name
     * @param r128HeaderGainDb Gain carried in the container header, on the R128 scale (0 if none)
     * @return The gain in decibels, or 0 if the track carries none
     */
    public static float resolveGainDb(Map<String, String> tags, float r128HeaderGainDb) {
        Float r128TagGainDb = parseR128GainDb(tags.get(R128_TRACK_GAIN_TAG));

        if (r128TagGainDb != null) {
            return r128HeaderGainDb + r128TagGainDb + R128_REFERENCE_OFFSET_DB;
        }

        Float replayGainDb = parseGainDb(tags.get(TRACK_GAIN_TAG));

        if (replayGainDb != null) {
            return r128HeaderGainDb + replayGainDb;
        }

        return r128HeaderGainDb != 0.0f ? r128HeaderGainDb + R128_REFERENCE_OFFSET_DB : 0.0f;
    }

    /**
     * Parses an {@code R128_TRACK_GAIN} tag, which is a Q7.8 fixed-point integer rather than a decibel string.
     *
     * @param tag The raw tag value, may be null
     * @return The gain in decibels on the R128 scale, or null if absent or unparseable
     */
    public static Float parseR128GainDb(String tag) {
        if (tag == null) {
            return null;
        }

        try {
            return Integer.parseInt(tag.trim()) / 256.0f;
        } catch (NumberFormatException e) {
            log.warn("Invalid {} tag value: {}", R128_TRACK_GAIN_TAG, tag);
            return null;
        }
    }

    /**
     * Converts a gain to a multiplier and limits it against the track peak.
     *
     * @param totalGainDb The gain to apply, in decibels
     * @param peakTag     The raw peak tag value, or null if the track has none
     * @param format      Format name, used only for logging
     * @return The multiplier to apply
     */
    public static float multiplierFromGain(float totalGainDb, String peakTag, String format) {
        float multiplier = multiplierFromDb(totalGainDb);
        float limited = limitToPeak(multiplier, peakTag);

        if (limited != multiplier) {
            log.debug("Applying ReplayGain ({}): {} dB -> {}x multiplier, capped to {}x to avoid clipping",
                format, totalGainDb, multiplier, limited);
        } else {
            log.debug("Applying ReplayGain ({}): {} dB -> {}x multiplier", format, totalGainDb, multiplier);
        }

        return limited;
    }

    /**
     * @param gainDb Gain in decibels
     * @return The equivalent linear multiplier
     */
    public static float multiplierFromDb(float gainDb) {
        return (float) Math.pow(10, gainDb / 20.0f);
    }

    /**
     * Parses a ReplayGain gain tag, for example {@code "-5.0 dB"} or {@code "+2.5 dB"}.
     *
     * @param tag The raw tag value, may be null
     * @return The gain in decibels, or null if absent or unparseable
     */
    public static Float parseGainDb(String tag) {
        if (tag == null) {
            return null;
        }

        try {
            return Float.parseFloat(tag.replace("dB", "").replace("db", "").trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid ReplayGain tag value: {}", tag);
            return null;
        }
    }

    /**
     * Caps a multiplier so that it cannot drive the track's loudest sample past full scale. A tagged gain and the
     * track's peak can easily multiply out above 1.0, and the sample-level clamp that would otherwise catch it is
     * audible as distortion rather than as a level problem.
     *
     * @param multiplier The multiplier derived from the gain tags
     * @param peakTag    The raw peak tag value, where 1.0 means full scale, or null if the track has none
     * @return The multiplier, reduced if needed to keep the peak within full scale
     */
    public static float limitToPeak(float multiplier, String peakTag) {
        if (peakTag == null || multiplier <= 1.0f) {
            return multiplier;
        }

        try {
            float peak = Float.parseFloat(peakTag.trim());

            if (peak > 0.0f && multiplier * peak > 1.0f) {
                return 1.0f / peak;
            }
        } catch (NumberFormatException e) {
            log.warn("Invalid {} tag value: {}", TRACK_PEAK_TAG, peakTag);
        }

        return multiplier;
    }
}
