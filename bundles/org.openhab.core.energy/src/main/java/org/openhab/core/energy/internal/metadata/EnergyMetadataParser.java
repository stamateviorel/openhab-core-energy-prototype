/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.core.energy.internal.metadata;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import javax.measure.quantity.Energy;
import javax.measure.quantity.Power;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.energy.BatchProfile;
import org.openhab.core.energy.ControllableProfile;
import org.openhab.core.energy.Deadline;
import org.openhab.core.energy.Demand;
import org.openhab.core.energy.EnergyConsumer;
import org.openhab.core.energy.EnergyLevel;
import org.openhab.core.energy.EnergyParticipant;
import org.openhab.core.energy.EnergyProvider;
import org.openhab.core.energy.LevelGate;
import org.openhab.core.energy.LoadCurve;
import org.openhab.core.energy.ModeControllableProfile;
import org.openhab.core.energy.PowerProfile;
import org.openhab.core.energy.ProviderRole;
import org.openhab.core.energy.SimpleProfile;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.util.DurationUtils;

/**
 * Turns one {@code energy} item-metadata entry into an {@link EnergyParticipant}.
 * <p>
 * This is the whole of declaration mechanism (a): a pure function from {@code (item name, value, configuration)} to
 * a participant, with no OSGi, no registries and no logging, so the format a user types can be reviewed and tested
 * on its own. The declaration reads
 *
 * <pre>
 * Number Wallbox_Current "Wallbox" { energy="consumer" [ profile="controllable", min="6 A", max="32 A", priority=1 ] }
 * Number Grid_Power      "Grid"    { energy="provider" [ role="grid" ] }
 * </pre>
 *
 * <h2>Failure policy</h2>
 * A declaration is either usable or skipped. Anything that would leave the engine steering a device on a guess -
 * an unreadable value, a missing role, a Controllable consumer without bounds - raises the checked
 * {@link EnergyMetadataParseException}, and the caller skips that item and logs it. It is checked rather than
 * unchecked because a typo in a declaration is the ordinary case this parser exists to recognise, not an
 * unexpected condition. Anything that is merely redundant - a key that does not apply to the declared class, a key
 * reserved for a later wave - is reported through {@link ParsedDeclaration#warnings()} and the declaration is kept.
 * Skipping loudly is the safe failure here: the device stays with whatever automation the user already has, instead
 * of being steered from a half-understood declaration.
 *
 * <h2>Identity and duplicates</h2>
 * The participant id is <strong>the name of the Item carrying the declaration</strong>, overridable with {@code id}.
 * That is what makes a user's metadata and an add-on's contribution recognisable as two statements about one
 * participant; resolving which of them is effective is the registry's job, not this parser's, and a duplicate is
 * never an error here.
 *
 * <h2>Choices this format had to make, which the corpus does not state</h2>
 * <ul>
 * <li>an absent {@code priority} defaults to {@link #DEFAULT_PRIORITY}, which is the model's own
 * {@link EnergyParticipant#DEFAULT_PRIORITY} rather than a second number this parser keeps;</li>
 * <li>durations are written with units ({@code "45 m"}, {@code "PT45M"}), because a bare number would need a unit
 * convention that nothing in the corpus states.</li>
 * </ul>
 *
 * <h2>How a declared quantity says which dimension it is - an unresolved tension, not a choice</h2>
 * Two requirements disagree, and the disagreement is reported rather than settled here:
 * <ul>
 * <li>{@code energy-participants} <em>Declared bounds in power or current</em> keeps the scenario "min 6 A and max
 * 32 A", so a declaration must be able to say amperes;</li>
 * <li>{@code extension-surface} <em>Expressive declaration surface</em> says "a declared value is a bare number
 * whose dimension and unit come from the published {@code energy:} config description".</li>
 * </ul>
 * Nothing states how a bare number says "amps". Until a maintainer rules, this parser accepts <strong>both</strong>
 * spellings: a bare number is watts - the canonical internal unit, which is what the config description would
 * publish - and a value carrying its own unit is honoured as written, so {@code "6 A"} means six amperes. Both
 * requirements' scenarios therefore pass, and neither reading has been closed off.
 *
 * <h2>Where {@code never} went</h2>
 * Hands-off is a property of the <em>consumer</em>, not of the Simple profile's level gate, so every profile class
 * can carry it: an EV or a dishwasher can be marked hands-off without deleting the declaration that gives the
 * electrical-limit floor its visibility of the load. The key is {@code handsOff}; other requirements name the same
 * flag {@code never}. The former spelling {@code level="never"} is still read, is applied to a consumer of any
 * class, and is reported so a site can move it.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class EnergyMetadataParser {

    /**
     * The item-metadata namespace this parser reads.
     */
    public static final String NAMESPACE = "energy";

    /**
     * The metadata value declaring a provider.
     */
    public static final String VALUE_PROVIDER = "provider";

    /**
     * The metadata value declaring a consumer.
     */
    public static final String VALUE_CONSUMER = "consumer";

    /**
     * The allocation priority assumed when a participant declares none. It is the model's own default rather than a
     * second number this parser keeps, so the two cannot drift apart.
     */
    public static final int DEFAULT_PRIORITY = EnergyParticipant.DEFAULT_PRIORITY;

    private static final String KEY_ID = "id";
    private static final String KEY_PRIORITY = "priority";
    private static final String KEY_SINK = "sink";
    private static final String KEY_ACK_WINDOW = "ackWindow";
    private static final String KEY_ACK_TOLERANCE = "ackTolerance";
    private static final String KEY_MAX_AGE = "maxAge";

    private static final String KEY_ROLE = "role";
    private static final String KEY_CONTROL = "control";
    private static final String KEY_SOC = "soc";
    private static final String KEY_INVERT = "invert";
    private static final String KEY_PRICE = "price";
    private static final String KEY_SCHEDULE = "schedule";

    private static final String KEY_PROFILE = "profile";
    private static final String KEY_MEASURE = "measure";
    private static final String KEY_READY = "ready";
    private static final String KEY_HANDS_OFF = "handsOff";
    private static final String KEY_PHASES = "phases";
    private static final String KEY_LEVEL = "level";
    private static final String KEY_DEMAND_KWH = "demandKwh";
    private static final String KEY_DEADLINE_HOUR = "deadlineHour";
    private static final String KEY_CONSECUTIVE = "consecutive";

    private static final String KEY_MIN = "min";
    private static final String KEY_MAX = "max";

    private static final String KEY_ON_THRESHOLD = "onThreshold";
    private static final String KEY_RATED_POWER = "ratedPower";
    private static final String KEY_MIN_ON = "minOn";
    private static final String KEY_MAX_ON = "maxOn";
    private static final String KEY_MIN_OFF = "minOff";
    private static final String KEY_MAX_OFF = "maxOff";

    private static final String KEY_MODES = "modes";
    private static final String KEY_MODE_DRAWS = "modeDraws";

    private static final String KEY_RATED_W = "ratedW";
    private static final String KEY_RUNTIME_HOURS = "runtimeHours";
    private static final String KEY_SHAPE = "shape";

    /**
     * The per-phase reading Item keys of a provider, {@code phase1} to {@code phase3}, indexed by phase.
     */
    private static final Map<Integer, String> PHASE_ITEM_KEYS = Map.of(1, "phase1", 2, "phase2", 3, "phase3");

    private static final Set<String> PROVIDER_KEYS = union(
            Set.of(KEY_ID, KEY_PRIORITY, KEY_SINK, KEY_ACK_WINDOW, KEY_ACK_TOLERANCE, KEY_MAX_AGE, KEY_ROLE,
                    KEY_CONTROL, KEY_MIN, KEY_MAX, KEY_SOC, KEY_INVERT, KEY_PRICE, KEY_SCHEDULE),
            new LinkedHashSet<>(PHASE_ITEM_KEYS.values()));
    private static final Set<String> CONSUMER_KEYS = Set.of(KEY_ID, KEY_PRIORITY, KEY_SINK, KEY_ACK_WINDOW,
            KEY_ACK_TOLERANCE, KEY_MAX_AGE, KEY_PROFILE, KEY_MEASURE, KEY_READY, KEY_HANDS_OFF, KEY_PHASES,
            KEY_DEMAND_KWH, KEY_DEADLINE_HOUR, KEY_CONSECUTIVE);
    private static final Set<String> SIMPLE_KEYS = Set.of(KEY_LEVEL, KEY_ON_THRESHOLD, KEY_RATED_POWER, KEY_MIN_ON,
            KEY_MAX_ON, KEY_MIN_OFF, KEY_MAX_OFF);
    private static final Set<String> CONTROLLABLE_KEYS = Set.of(KEY_MIN, KEY_MAX);
    private static final Set<String> MODE_KEYS = Set.of(KEY_MODES, KEY_MODE_DRAWS);
    private static final Set<String> BATCH_KEYS = Set.of(KEY_RATED_W, KEY_RATED_POWER, KEY_RUNTIME_HOURS, KEY_SHAPE);

    /**
     * Keys the corpus names for a provider but the wave-1 model cannot carry, because the price and forecast planes
     * are later waves. They are recognized so that a site can already write them without being told they are typos.
     */
    private static final Set<String> RESERVED_KEYS = Set.of(KEY_PRICE, KEY_SCHEDULE);

    /**
     * The value {@code level} used to take to mean "leave this device alone", before hands-off moved off the Simple
     * profile onto the consumer. It is still read, on a consumer of any class, and reported.
     */
    private static final String NEVER = "never";

    private EnergyMetadataParser() {
    }

    /**
     * A successfully parsed declaration, together with everything about it that deserved the user's attention but
     * was not bad enough to drop it.
     *
     * @param participant the parsed participant
     * @param warnings human-readable notes about the declaration, empty when it was fully understood
     */
    public record ParsedDeclaration(EnergyParticipant participant, List<String> warnings) {

        /**
         * Copies the warnings so the record stays immutable.
         */
        public ParsedDeclaration {
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * Parses one metadata entry.
     *
     * @param itemName the name of the item carrying the metadata; it is both the default participant id and the
     *            Item the engine would read (provider) or steer (consumer)
     * @param value the metadata value, {@code provider} or {@code consumer}
     * @param configuration the metadata configuration keys
     * @return the parsed declaration
     * @throws EnergyMetadataParseException if the declaration cannot be turned into a participant
     */
    public static ParsedDeclaration parse(String itemName, String value, Map<String, Object> configuration)
            throws EnergyMetadataParseException {
        List<String> warnings = new ArrayList<>();
        String kind = value.trim().toLowerCase(Locale.ROOT);
        EnergyParticipant participant;
        try {
            participant = switch (kind) {
                case VALUE_PROVIDER -> parseProvider(itemName, configuration, warnings);
                case VALUE_CONSUMER -> parseConsumer(itemName, configuration, warnings);
                default -> throw new EnergyMetadataParseException("'" + value + "' is not a valid " + NAMESPACE
                        + " declaration, expected '" + VALUE_PROVIDER + "' or '" + VALUE_CONSUMER + "'");
            };
        } catch (IllegalArgumentException e) {
            // the participant model validates its own arguments, and here those arguments came from the user
            throw new EnergyMetadataParseException(e.getMessage(), e);
        }
        return new ParsedDeclaration(participant, warnings);
    }

    private static EnergyProvider parseProvider(String itemName, Map<String, Object> configuration,
            List<String> warnings) throws EnergyMetadataParseException {
        reportUnknownKeys(configuration, PROVIDER_KEYS, warnings);
        for (String key : RESERVED_KEYS) {
            if (configuration.containsKey(key)) {
                warnings.add("key '" + key + "' is reserved for a later wave of the specification and is ignored");
            }
        }

        String roleText = text(configuration, KEY_ROLE);
        if (roleText == null) {
            throw new EnergyMetadataParseException("a provider must declare a '" + KEY_ROLE + "', one of "
                    + Arrays.toString(ProviderRole.values()).toLowerCase(Locale.ROOT));
        }
        Optional<ProviderRole> parsedRole = ProviderRole.parse(roleText);
        if (parsedRole.isEmpty()) {
            throw new EnergyMetadataParseException("'" + roleText + "' is not a known " + KEY_ROLE);
        }
        ProviderRole role = parsedRole.get();

        String controlItemName = text(configuration, KEY_CONTROL);
        QuantityType<?> minPower = bound(configuration, KEY_MIN);
        QuantityType<?> maxPower = bound(configuration, KEY_MAX);
        String socItemName = text(configuration, KEY_SOC);

        if (controlItemName != null && (minPower == null || maxPower == null)) {
            // the clamp is what bounds a setpoint write to an inverter, so a controllable provider without one is
            // skipped whole rather than accepted with an unbounded write
            throw new EnergyMetadataParseException("a controllable provider must declare both '" + KEY_MIN + "' and '"
                    + KEY_MAX + "', as powers or as currents");
        }
        if (controlItemName == null && (minPower != null || maxPower != null)) {
            warnings.add("'" + KEY_MIN + "'/'" + KEY_MAX + "' clamp a setpoint, but no '" + KEY_CONTROL
                    + "' item is declared, so nothing is clamped");
        }
        return new EnergyProvider(id(itemName, configuration), itemName, role, controlItemName, minPower, maxPower,
                socItemName, priority(configuration), flag(configuration, KEY_INVERT), phaseItems(configuration),
                text(configuration, KEY_SINK), duration(configuration, KEY_ACK_WINDOW),
                bound(configuration, KEY_ACK_TOLERANCE), duration(configuration, KEY_MAX_AGE));
    }

    /**
     * Reads the per-phase reading Items of a provider, declared as {@code phase1}, {@code phase2} and {@code phase3}.
     *
     * @param configuration the declaration configuration
     * @return the reading Item per phase index, empty when the provider reads only an aggregate
     */
    private static Map<Integer, String> phaseItems(Map<String, Object> configuration) {
        Map<Integer, String> readings = new LinkedHashMap<>();
        PHASE_ITEM_KEYS.forEach((phase, key) -> {
            String declared = text(configuration, key);
            if (declared != null) {
                readings.put(phase, declared);
            }
        });
        return readings;
    }

    private static EnergyConsumer parseConsumer(String itemName, Map<String, Object> configuration,
            List<String> warnings) throws EnergyMetadataParseException {
        PowerProfile.Kind kind = profileKind(configuration, warnings);
        reportUnknownKeys(configuration, union(CONSUMER_KEYS, keysOf(kind)), warnings);

        boolean handsOff = handsOff(configuration, kind, warnings);
        PowerProfile profile = switch (kind) {
            case SIMPLE -> parseSimpleProfile(configuration);
            case CONTROLLABLE -> parseControllableProfile(configuration);
            case MODE_CONTROLLABLE -> parseModeProfile(configuration);
            case BATCH -> parseBatchProfile(configuration);
        };
        if (kind != PowerProfile.Kind.SIMPLE && configuration.containsKey(KEY_LEVEL)
                && !NEVER.equalsIgnoreCase(text(configuration, KEY_LEVEL))) {
            warnings.add("'" + KEY_LEVEL + "' is only carried by Simple consumers and is ignored for a "
                    + kind.names().getFirst() + " one");
        }

        return new EnergyConsumer(id(itemName, configuration), itemName, profile, parseDemand(configuration),
                priority(configuration), text(configuration, KEY_MEASURE), text(configuration, KEY_READY), handsOff,
                phases(configuration), text(configuration, KEY_SINK), duration(configuration, KEY_ACK_WINDOW),
                bound(configuration, KEY_ACK_TOLERANCE), duration(configuration, KEY_MAX_AGE));
    }

    /**
     * Reads the hands-off flag, which every profile class carries.
     * <p>
     * The former spelling was {@code level="never"} on a Simple consumer. It is still honoured - on any class, since
     * that is exactly the population the move exists to serve - and reported, so a site can move it to
     * {@code handsOff} without ever having been silently unmanaged in the meantime.
     *
     * @param configuration the declaration configuration
     * @param kind the declared profile class
     * @param warnings collects anything redundant but harmless
     * @return whether the consumer declared itself hands-off
     */
    private static boolean handsOff(Map<String, Object> configuration, PowerProfile.Kind kind, List<String> warnings) {
        if (flag(configuration, KEY_HANDS_OFF)) {
            return true;
        }
        if (NEVER.equalsIgnoreCase(text(configuration, KEY_LEVEL))) {
            warnings.add("'" + KEY_LEVEL + "=\"" + NEVER + "\"' now reads as '" + KEY_HANDS_OFF
                    + "=true', which every profile class carries, not as a level gate; it has been applied to this "
                    + kind.names().getFirst() + " consumer");
            return true;
        }
        return false;
    }

    /**
     * Reads the phases a consumer draws on, declared as the integer indices {@code "1,2,3"}.
     *
     * @param configuration the declaration configuration
     * @return the declared phase indices, empty when the consumer declares none
     * @throws EnergyMetadataParseException if an index is not a whole number
     */
    private static Set<Integer> phases(Map<String, Object> configuration) throws EnergyMetadataParseException {
        String declared = text(configuration, KEY_PHASES);
        if (declared == null) {
            return Set.of();
        }
        Set<Integer> indices = new TreeSet<>();
        for (String phase : declared.split(",")) {
            String trimmed = phase.trim();
            if (!trimmed.isEmpty()) {
                double value = toDouble(trimmed, KEY_PHASES);
                if (value != Math.rint(value)) {
                    throw new EnergyMetadataParseException(
                            "'" + KEY_PHASES + "' must be whole numbers but held '" + trimmed + "'");
                }
                indices.add((int) Math.rint(value));
            }
        }
        return indices;
    }

    private static int priority(Map<String, Object> configuration) throws EnergyMetadataParseException {
        Integer declared = integer(configuration, KEY_PRIORITY);
        return declared != null ? declared : DEFAULT_PRIORITY;
    }

    /**
     * Resolves the declared profile class, which a consumer must state.
     * <p>
     * <strong>Both silences are configuration errors and neither is read as Simple.</strong> An <em>unreadable</em>
     * profile is a typo that would otherwise turn a Batch programme or a Controllable wallbox into something the
     * engine believes it may switch on and off at will; an <em>absent</em> profile is the same typo one keystroke
     * earlier - {@code profil="controllable"} leaves the key absent - and it says exactly as little about the control
     * surface. The two are therefore refused alike, rather than one of them being resolved by picking the safer
     * guess. A consumer whose class the engine had to guess is a consumer it may steer wrongly, and the whole point
     * of the failure policy above is that a half-understood declaration steers nothing.
     *
     * @param configuration the declaration configuration
     * @param warnings collects anything redundant but harmless
     * @return the profile class
     * @throws EnergyMetadataParseException if no profile is declared, or one is declared that cannot be read
     */
    private static PowerProfile.Kind profileKind(Map<String, Object> configuration, List<String> warnings)
            throws EnergyMetadataParseException {
        String declared = text(configuration, KEY_PROFILE);
        if (declared == null) {
            throw new EnergyMetadataParseException("a consumer must declare a '" + KEY_PROFILE + "', one of "
                    + knownProfileKinds() + "; an absent class is a configuration error and is not read as '"
                    + PowerProfile.Kind.SIMPLE.names().getFirst() + "'");
        }
        Optional<PowerProfile.Kind> kind = PowerProfile.Kind.parse(declared);
        if (kind.isPresent()) {
            return kind.get();
        }
        throw new EnergyMetadataParseException(
                "'" + declared + "' is not a known " + KEY_PROFILE + ", which must be one of " + knownProfileKinds());
    }

    /**
     * Returns the profile classes a declaration may name, for an error message that tells a user what to write.
     *
     * @return the keys, comma separated
     */
    private static String knownProfileKinds() {
        List<String> keys = new ArrayList<>();
        for (PowerProfile.Kind kind : PowerProfile.Kind.values()) {
            keys.add(kind.names().getFirst());
        }
        return String.join(", ", keys);
    }

    private static SimpleProfile parseSimpleProfile(Map<String, Object> configuration)
            throws EnergyMetadataParseException {
        return new SimpleProfile(power(configuration, KEY_ON_THRESHOLD), power(configuration, KEY_RATED_POWER),
                duration(configuration, KEY_MIN_ON), duration(configuration, KEY_MAX_ON),
                duration(configuration, KEY_MIN_OFF), duration(configuration, KEY_MAX_OFF), levelGate(configuration));
    }

    private static ControllableProfile parseControllableProfile(Map<String, Object> configuration)
            throws EnergyMetadataParseException {
        QuantityType<?> min = bound(configuration, KEY_MIN);
        QuantityType<?> max = bound(configuration, KEY_MAX);
        if (min == null || max == null) {
            throw new EnergyMetadataParseException("a controllable consumer must declare both '" + KEY_MIN + "' and '"
                    + KEY_MAX + "', as powers or as currents");
        }
        return new ControllableProfile(min, max);
    }

    private static ModeControllableProfile parseModeProfile(Map<String, Object> configuration)
            throws EnergyMetadataParseException {
        String modes = text(configuration, KEY_MODES);
        if (modes == null) {
            throw new EnergyMetadataParseException("a mode-controllable consumer must declare its '" + KEY_MODES
                    + "' as a comma-separated list, most restricted first");
        }
        return new ModeControllableProfile(
                Arrays.stream(modes.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList(),
                modeDraws(configuration));
    }

    /**
     * Reads the optional per-mode draw, declared as {@code modeDraws="normal=800, forced=2400"}.
     * <p>
     * It is optional and partial by design: a mode change is exempt from the planner's budget precisely because what
     * a mode draws is the device's decision, so declaring a figure for one mode says nothing about the others.
     *
     * @param configuration the declaration configuration
     * @return the declared draw per mode name, empty when the site declares none
     * @throws EnergyMetadataParseException if an entry is not {@code mode=power}
     */
    private static Map<String, QuantityType<Power>> modeDraws(Map<String, Object> configuration)
            throws EnergyMetadataParseException {
        String declared = text(configuration, KEY_MODE_DRAWS);
        if (declared == null) {
            return Map.of();
        }
        Map<String, QuantityType<Power>> draws = new LinkedHashMap<>();
        for (String entry : declared.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int equals = trimmed.indexOf('=');
            if (equals <= 0 || equals == trimmed.length() - 1) {
                throw new EnergyMetadataParseException("'" + KEY_MODE_DRAWS
                        + "' entries read 'mode=power', as in 'forced=2400', but held '" + trimmed + "'");
            }
            draws.put(trimmed.substring(0, equals).trim(),
                    toPower(trimmed.substring(equals + 1).trim(), KEY_MODE_DRAWS));
        }
        return draws;
    }

    private static BatchProfile parseBatchProfile(Map<String, Object> configuration)
            throws EnergyMetadataParseException {
        QuantityType<Power> rated = power(configuration, KEY_RATED_POWER);
        if (rated == null) {
            rated = power(configuration, KEY_RATED_W);
        }
        Double runtimeHours = number(configuration, KEY_RUNTIME_HOURS);
        if (rated == null || runtimeHours == null) {
            throw new EnergyMetadataParseException(
                    "a batch consumer must declare both '" + KEY_RATED_POWER + "' and '" + KEY_RUNTIME_HOURS + "'");
        }
        return new BatchProfile(rated, Duration.ofMillis(Math.round(runtimeHours * Duration.ofHours(1).toMillis())),
                loadCurve(configuration));
    }

    private static @Nullable LoadCurve loadCurve(Map<String, Object> configuration)
            throws EnergyMetadataParseException {
        String shape = text(configuration, KEY_SHAPE);
        if (shape == null) {
            return null;
        }
        List<Double> samples = new ArrayList<>();
        for (String sample : shape.split(",")) {
            String trimmed = sample.trim();
            if (!trimmed.isEmpty()) {
                samples.add(toDouble(trimmed, KEY_SHAPE));
            }
        }
        return new LoadCurve(samples);
    }

    private static @Nullable Demand parseDemand(Map<String, Object> configuration) throws EnergyMetadataParseException {
        Double kilowattHours = number(configuration, KEY_DEMAND_KWH);
        Integer deadlineHour = integer(configuration, KEY_DEADLINE_HOUR);
        if (kilowattHours == null && deadlineHour == null) {
            return null;
        }
        if (kilowattHours == null || deadlineHour == null) {
            throw new EnergyMetadataParseException(
                    "'" + KEY_DEMAND_KWH + "' and '" + KEY_DEADLINE_HOUR + "' must be declared together");
        }
        if (deadlineHour < 0 || deadlineHour > 23) {
            throw new EnergyMetadataParseException("'" + KEY_DEADLINE_HOUR + "' must be an hour of the day (0-23)");
        }
        return new Demand(new QuantityType<Energy>(kilowattHours, Units.KILOWATT_HOUR),
                Deadline.daily(LocalTime.of(deadlineHour, 0)), flag(configuration, KEY_CONSECUTIVE));
    }

    /**
     * Reads the "run at level &ge; N" gate of a Simple consumer.
     * <p>
     * {@code never} is no longer a gate: it moved onto the consumer as {@code handsOff} and is read there, so this
     * method leaves the gate at "always" for it rather than encoding "leave this device alone" as a level.
     *
     * @param configuration the declaration configuration
     * @return the declared gate, or {@link LevelGate#always()} when none is declared
     * @throws EnergyMetadataParseException if a level is declared but is not one of the four
     */
    private static LevelGate levelGate(Map<String, Object> configuration) throws EnergyMetadataParseException {
        String declared = text(configuration, KEY_LEVEL);
        if (declared == null || NEVER.equalsIgnoreCase(declared)) {
            return LevelGate.always();
        }
        if (declared.length() == 1 && Character.isDigit(declared.charAt(0))) {
            Optional<EnergyLevel> byCode = EnergyLevel.fromCode(Character.digit(declared.charAt(0), 10));
            if (byCode.isEmpty()) {
                throw new EnergyMetadataParseException("'" + declared + "' is not a known energy level");
            }
            return LevelGate.atLeast(byCode.get());
        }
        try {
            return LevelGate.atLeast(EnergyLevel.valueOf(declared.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            throw new EnergyMetadataParseException("'" + declared + "' is not a known energy level, expected one of "
                    + Arrays.toString(EnergyLevel.values()).toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Resolves the participant id: the name of the Item carrying the declaration, overridden by an explicit
     * {@code id}.
     * <p>
     * This is what lets a user's metadata and an add-on's contribution be recognised as two statements about one
     * participant instead of two participants.
     *
     * @param itemName the Item carrying the declaration
     * @param configuration the declaration configuration
     * @return the participant id
     */
    private static String id(String itemName, Map<String, Object> configuration) {
        return participantId(itemName, configuration);
    }

    /**
     * Returns the identity a declaration claims, whether or not the rest of it can be read.
     * <p>
     * It is deliberately total: it reads one optional key and falls back to the Item name, so it answers for a
     * declaration the parser has just <em>refused</em>. That is what {@code MetadataParticipantSource} needs in order
     * to block the right participant when a declaration is malformed - a malformed declaration blocks the
     * participant it was about, and the whole point of the block is that it is the identity a lower-ranked
     * contribution would otherwise take over. Source: owner decision D26 (2026-08-03,
     * {@code openhab-ems-spec/docs/OWNER_DECISIONS.md}).
     *
     * @param itemName the Item carrying the declaration
     * @param configuration the declaration configuration, readable or not
     * @return the participant id the declaration claims
     */
    public static String participantId(String itemName, Map<String, Object> configuration) {
        String declared = text(configuration, KEY_ID);
        return declared != null ? declared : itemName;
    }

    private static Set<String> keysOf(PowerProfile.Kind kind) {
        return switch (kind) {
            case SIMPLE -> SIMPLE_KEYS;
            case CONTROLLABLE -> CONTROLLABLE_KEYS;
            case MODE_CONTROLLABLE -> MODE_KEYS;
            case BATCH -> BATCH_KEYS;
        };
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        Set<String> union = new LinkedHashSet<>(first);
        union.addAll(second);
        return union;
    }

    private static void reportUnknownKeys(Map<String, Object> configuration, Set<String> known, List<String> warnings) {
        for (String key : configuration.keySet()) {
            if (!known.contains(key)) {
                warnings.add("key '" + key + "' is unknown here and is ignored");
            }
        }
    }

    private static @Nullable String text(Map<String, Object> configuration, String key) {
        Object raw = configuration.get(key);
        if (raw == null) {
            return null;
        }
        String trimmed = raw.toString().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static @Nullable Double number(Map<String, Object> configuration, String key)
            throws EnergyMetadataParseException {
        Object raw = configuration.get(key);
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        String text = text(configuration, key);
        return text == null ? null : toDouble(text, key);
    }

    private static @Nullable Integer integer(Map<String, Object> configuration, String key)
            throws EnergyMetadataParseException {
        Double value = number(configuration, key);
        if (value == null) {
            return null;
        }
        if (value != Math.rint(value)) {
            throw new EnergyMetadataParseException("'" + key + "' must be a whole number but was " + value);
        }
        return (int) Math.rint(value);
    }

    private static boolean flag(Map<String, Object> configuration, String key) {
        Object raw = configuration.get(key);
        if (raw instanceof Boolean value) {
            return value;
        }
        String text = text(configuration, key);
        return text != null && Boolean.parseBoolean(text);
    }

    private static double toDouble(String text, String key) throws EnergyMetadataParseException {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new EnergyMetadataParseException("'" + key + "' must be a number but was '" + text + "'");
        }
    }

    private static @Nullable QuantityType<Power> power(Map<String, Object> configuration, String key)
            throws EnergyMetadataParseException {
        QuantityType<?> quantity = quantity(configuration, key);
        return quantity == null ? null : asPower(quantity, key);
    }

    /**
     * Parses one text into a power, accepting both spellings the two requirements disagree over - a bare number,
     * read as the canonical watts, and a value carrying its own unit.
     *
     * @param text the declared text
     * @param key the key it came from, used in the exception message
     * @return the power, converted to watts
     * @throws EnergyMetadataParseException if the text is neither readable nor a power
     */
    private static QuantityType<Power> toPower(String text, String key) throws EnergyMetadataParseException {
        return asPower(toQuantity(text, key), key);
    }

    private static QuantityType<Power> asPower(QuantityType<?> quantity, String key)
            throws EnergyMetadataParseException {
        QuantityType<?> watts = quantity.toUnit(Units.WATT);
        if (watts == null) {
            throw new EnergyMetadataParseException("'" + key + "' must be a power but was '" + quantity + "'");
        }
        return new QuantityType<>(watts.doubleValue(), Units.WATT);
    }

    private static @Nullable QuantityType<?> bound(Map<String, Object> configuration, String key)
            throws EnergyMetadataParseException {
        QuantityType<?> quantity = quantity(configuration, key);
        if (quantity == null) {
            return null;
        }
        QuantityType<?> amperes = quantity.toUnit(Units.AMPERE);
        if (amperes != null) {
            return new QuantityType<>(amperes.doubleValue(), Units.AMPERE);
        }
        return power(configuration, key);
    }

    private static @Nullable QuantityType<?> quantity(Map<String, Object> configuration, String key)
            throws EnergyMetadataParseException {
        Object raw = configuration.get(key);
        if (raw instanceof Number number) {
            return new QuantityType<>(number.doubleValue(), Units.WATT);
        }
        String text = text(configuration, key);
        return text == null ? null : toQuantity(text, key);
    }

    /**
     * Reads one declared quantity, in either of the two spellings the corpus leaves unreconciled.
     *
     * @param text the declared text
     * @param key the key it came from, used in the exception message
     * @return the quantity
     * @throws EnergyMetadataParseException if the text is neither a bare number nor a readable quantity
     */
    private static QuantityType<?> toQuantity(String text, String key) throws EnergyMetadataParseException {
        try {
            // a bare number is watts, the canonical internal unit the config description would publish
            return new QuantityType<>(Double.parseDouble(text), Units.WATT);
        } catch (NumberFormatException notABareNumber) {
            // fall through: the value carries its own unit, which is how "6 A" says amperes
        }
        try {
            return new QuantityType<>(text);
        } catch (IllegalArgumentException e) {
            throw new EnergyMetadataParseException(
                    "'" + key + "' must be a number of watts or a quantity such as '6 A' but was '" + text + "'");
        }
    }

    private static @Nullable Duration duration(Map<String, Object> configuration, String key)
            throws EnergyMetadataParseException {
        String text = text(configuration, key);
        if (text == null) {
            return null;
        }
        try {
            return DurationUtils.parse(text);
        } catch (IllegalArgumentException e) {
            throw new EnergyMetadataParseException(
                    "'" + key + "' must be a duration such as '45 m' or 'PT45M' but was '" + text + "'");
        }
    }
}
