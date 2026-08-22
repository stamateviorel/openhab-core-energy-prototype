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
package org.openhab.core.energy;

import java.time.Duration;
import java.util.Collections;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.QuantityType;

/**
 * A load the engine may steer, expressed through one of the four {@link PowerProfile} classes.
 * <p>
 * {@link #itemName()} is the Item the engine would write to. {@code measureItemName} is the Item that reports what
 * the device actually draws: commands are envelopes, so planning must use measured power rather than commanded
 * values wherever a measurement exists. {@code readyItemName} is the readiness interlock - while it reads "not
 * ready", an engine-initiated start is skipped, and that is a normal outcome, not an error.
 * <p>
 * {@code priority} orders surplus allocation and load selection deterministically. <strong>A numerically lower value
 * is the better priority</strong>, in both senses: served first when power cannot serve everyone, and winning when
 * two decisions conflict. Use {@link #PRIORITY_ORDER} rather than sorting by hand: it breaks ties on {@link #id()},
 * so the outcome never depends on registration or iteration order and carries nothing between cycles. A declaration
 * that names no priority is placed at {@link #DEFAULT_PRIORITY}.
 * <p>
 * {@code handsOff} is the "never" flag: a consumer of <strong>any</strong> of the four classes may declare itself
 * hands-off, after which the engine neither starts, stops, trims nor re-modes it - while still reading everything it
 * declares, so that marking a device hands-off never costs the electrical-limit floor its visibility of the load.
 * It is an engine-owned prohibition: no contributed algorithm can set it aside.
 * <p>
 * {@code phases} are the integer indices 1, 2 and 3 this consumer draws on. A consumer that declares none is exempt
 * from per-phase enforcement, still constrained by the site total, and reported as a declaration gap wherever the
 * site declares per-phase budgets - it is never attributed to all three phases nor to a guessed one.
 * <p>
 * {@code ackWindow} and {@code ackTolerance} override the engine's own acknowledgement defaults for this participant
 * alone: how long a command may go unacknowledged before it lapses and control resumes, and how far the reported
 * value may sit from the commanded one and still count. <strong>The band is an absolute quantity in the control
 * Item's own dimension, never a fraction of the commanded value</strong> - a wallbox that settles within 1 mA
 * declares 0.01 A, and that band means the same thing at 6 A as it does at 32 A, which a percentage would not.
 * Declaring neither is normal; the engine's defaults then apply and an exact comparison is required.
 * <p>
 * {@code maxReadingAge} is how old a reading may be before the engine treats it as stale. It is optional because the
 * framework invents no ageing mechanism of its own: an unreadable state and the {@code UNDEF} and {@code NULL} states
 * count as stale whether or not an age is declared, and a site that wants a frozen-but-defined Item to age out can
 * put core's {@code expire} namespace on it and let the same rule catch it.
 *
 * @param id the stable participant id, defaulting to the name of the Item carrying the declaration
 * @param itemName the Item the engine steers
 * @param profile the control surface of this consumer
 * @param demand the declared future demand, or {@code null} if the consumer declares none
 * @param priority the allocation priority, lower is better
 * @param measureItemName the Item reporting measured power, or {@code null} if the consumer is unmetered
 * @param readyItemName the Item holding the readiness interlock, or {@code null} if the consumer is always ready
 * @param handsOff whether the engine must leave this consumer alone
 * @param phases the phase indices this consumer draws on, empty when it declares none
 * @param sinkId the actuation sink this consumer's decisions are written through, or {@code null} for the
 *            site-wide one
 * @param ackWindow how long a command to this consumer may go unacknowledged before it lapses, or {@code null} to
 *            use the engine's default
 * @param ackTolerance how far the reported value may sit from the commanded one and still acknowledge it - an
 *            absolute quantity in the control Item's own dimension - or {@code null} to require an exact match
 * @param maxReadingAge how old a reading from this consumer may be before it counts as stale, or {@code null} when
 *            it declares no age
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record EnergyConsumer(String id, String itemName, PowerProfile profile, @Nullable Demand demand, int priority,
        @Nullable String measureItemName, @Nullable String readyItemName, boolean handsOff, Set<Integer> phases,
        @Nullable String sinkId, @Nullable Duration ackWindow, @Nullable QuantityType<?> ackTolerance,
        @Nullable Duration maxReadingAge) implements EnergyParticipant {

    /**
     * The priority a consumer declaring none is placed at.
     */
    public static final int DEFAULT_PRIORITY = EnergyParticipant.DEFAULT_PRIORITY;

    /**
     * The canonical allocation order: better priority first, ties broken on the participant id so that the order is
     * total, stateless and independent of registration or iteration order.
     */
    public static final Comparator<EnergyConsumer> PRIORITY_ORDER = Comparator.comparingInt(EnergyConsumer::priority)
            .thenComparing(EnergyConsumer::id);

    /**
     * Validates the consumer and takes an immutable, numerically ordered copy of the phase set.
     *
     * @throws IllegalArgumentException if an identifier or Item name is blank, a phase is not 1, 2 or 3, a declared
     *             duration is not positive, or the tolerance band is negative
     */
    public EnergyConsumer {
        id = ModelChecks.requireText(id, "id");
        itemName = ModelChecks.requireText(itemName, "itemName");
        String measure = measureItemName;
        if (measure != null) {
            measureItemName = ModelChecks.requireText(measure, "measureItemName");
        }
        String ready = readyItemName;
        if (ready != null) {
            readyItemName = ModelChecks.requireText(ready, "readyItemName");
        }
        String sink = sinkId;
        if (sink != null) {
            sinkId = ModelChecks.requireText(sink, "sinkId");
        }
        phases = Collections.unmodifiableSet(new TreeSet<>(ModelChecks.requirePhases(phases)));
        ackWindow = ModelChecks.requirePositiveOrNull(ackWindow, "ackWindow");
        ackTolerance = ModelChecks.requireNonNegativeOrNull(ackTolerance, "ackTolerance");
        maxReadingAge = ModelChecks.requirePositiveOrNull(maxReadingAge, "maxReadingAge");
    }

    /**
     * Creates a consumer identified by the Item carrying its declaration, with the default priority, no demand, no
     * measurement, no readiness interlock, no phase declaration and hands-off unset.
     *
     * @param itemName the Item the engine steers, which is also the participant id
     * @param profile the control surface of this consumer
     * @return the consumer
     */
    public static EnergyConsumer of(String itemName, PowerProfile profile) {
        return of(itemName, itemName, profile, DEFAULT_PRIORITY);
    }

    /**
     * Creates a consumer with no demand, no measurement, no readiness interlock and no phase declaration.
     *
     * @param id the stable participant id
     * @param itemName the Item the engine steers
     * @param profile the control surface of this consumer
     * @param priority the allocation priority, lower is better
     * @return the consumer
     */
    public static EnergyConsumer of(String id, String itemName, PowerProfile profile, int priority) {
        return new EnergyConsumer(id, itemName, profile, null, priority, null, null, false, Set.of(), null, null, null,
                null);
    }

    /**
     * Returns a copy declaring the future demand of this consumer.
     *
     * @param declaredDemand the demand
     * @return the copy
     */
    public EnergyConsumer withDemand(Demand declaredDemand) {
        return new EnergyConsumer(id, itemName, profile, declaredDemand, priority, measureItemName, readyItemName,
                handsOff, phases, sinkId, ackWindow, ackTolerance, maxReadingAge);
    }

    /**
     * Returns a copy declaring the Item that reports what this consumer actually draws.
     *
     * @param itemNameOfMeasurement the measurement Item
     * @return the copy
     */
    public EnergyConsumer withMeasurement(String itemNameOfMeasurement) {
        return new EnergyConsumer(id, itemName, profile, demand, priority, itemNameOfMeasurement, readyItemName,
                handsOff, phases, sinkId, ackWindow, ackTolerance, maxReadingAge);
    }

    /**
     * Returns a copy declaring the readiness interlock of this consumer.
     *
     * @param itemNameOfInterlock the readiness Item
     * @return the copy
     */
    public EnergyConsumer withReadiness(String itemNameOfInterlock) {
        return new EnergyConsumer(id, itemName, profile, demand, priority, measureItemName, itemNameOfInterlock,
                handsOff, phases, sinkId, ackWindow, ackTolerance, maxReadingAge);
    }

    /**
     * Returns a copy marked hands-off: the engine reads it and never steers it.
     *
     * @return the copy
     */
    public EnergyConsumer withHandsOff() {
        return new EnergyConsumer(id, itemName, profile, demand, priority, measureItemName, readyItemName, true, phases,
                sinkId, ackWindow, ackTolerance, maxReadingAge);
    }

    /**
     * Returns a copy declaring the phases this consumer draws on.
     *
     * @param declaredPhases the phase indices, each 1, 2 or 3
     * @return the copy
     * @throws IllegalArgumentException if a phase is not 1, 2 or 3
     */
    public EnergyConsumer withPhases(Set<Integer> declaredPhases) {
        return new EnergyConsumer(id, itemName, profile, demand, priority, measureItemName, readyItemName, handsOff,
                declaredPhases, sinkId, ackWindow, ackTolerance, maxReadingAge);
    }

    /**
     * Returns a copy whose decisions are written through the named actuation sink rather than the site-wide one.
     *
     * @param declaredSinkId the sink id
     * @return the copy
     */
    public EnergyConsumer withSink(String declaredSinkId) {
        return new EnergyConsumer(id, itemName, profile, demand, priority, measureItemName, readyItemName, handsOff,
                phases, declaredSinkId, ackWindow, ackTolerance, maxReadingAge);
    }

    /**
     * Returns a copy whose commands lapse after the declared window rather than after the engine's default.
     *
     * @param declaredWindow how long a command may go unacknowledged
     * @return the copy
     * @throws IllegalArgumentException if the window is not positive
     */
    public EnergyConsumer withAckWindow(Duration declaredWindow) {
        return new EnergyConsumer(id, itemName, profile, demand, priority, measureItemName, readyItemName, handsOff,
                phases, sinkId, declaredWindow, ackTolerance, maxReadingAge);
    }

    /**
     * Returns a copy that accepts a reported value within the declared band as an acknowledgement.
     *
     * @param declaredTolerance the band, an absolute quantity in the control Item's own dimension
     * @return the copy
     * @throws IllegalArgumentException if the band is negative
     */
    public EnergyConsumer withAckTolerance(QuantityType<?> declaredTolerance) {
        return new EnergyConsumer(id, itemName, profile, demand, priority, measureItemName, readyItemName, handsOff,
                phases, sinkId, ackWindow, declaredTolerance, maxReadingAge);
    }

    /**
     * Returns a copy whose readings count as stale once they are older than the declared age.
     *
     * @param declaredAge the maximum age of a reading
     * @return the copy
     * @throws IllegalArgumentException if the age is not positive
     */
    public EnergyConsumer withMaxReadingAge(Duration declaredAge) {
        return new EnergyConsumer(id, itemName, profile, demand, priority, measureItemName, readyItemName, handsOff,
                phases, sinkId, ackWindow, ackTolerance, declaredAge);
    }

    /**
     * Returns the level gate of this consumer.
     * <p>
     * The gate lives on {@link SimpleProfile} because the requirement scopes "run at level &ge; N" to Simple
     * consumers, and it stayed there deliberately: the threshold has no crisp meaning for a Batch programme with a
     * deadline or for a ModeControllable device that has a mode-per-level mapping of its own. What every class does
     * carry is {@link #handsOff()}.
     *
     * @return the gate, or {@link Optional#empty()} if this consumer's class does not carry one
     */
    public Optional<LevelGate> levelGate() {
        return profile instanceof SimpleProfile simple ? Optional.of(simple.levelGate()) : Optional.empty();
    }

    /**
     * Returns the power figure budget-constrained scheduling and the electrical-limit floor book against this
     * consumer, fixed by its profile class rather than inferred by each engine:
     * <ul>
     * <li><strong>Controllable</strong> - its declared maximum;</li>
     * <li><strong>Batch</strong> - its rated power scaled by its curve's mean, the energy the programme costs;</li>
     * <li><strong>Simple</strong> - its declared {@code ratedPower}, falling back to the surplus on-threshold when
     * absent, which is a declaration gap to report and never a reason to reject - see {@link #ratingIsInferred()};</li>
     * <li><strong>ModeControllable</strong> - none: a mode change is exempt from the planner's budget, because what
     * a mode draws is the device's decision. Where the site does declare it, the figure belongs to the mode being
     * proposed and is read through {@link ModeControllableProfile#drawOf(String)}.</li>
     * </ul>
     * A Controllable consumer may be bounded in amperes, so the figure is not necessarily a power. Converting a
     * current to watts needs the site's nominal voltage and this consumer's {@link #phases()}, neither of which the
     * model carries, so that conversion happens where the voltage is known.
     *
     * @return the figure to book, or {@link Optional#empty()} where the class carries none
     */
    public Optional<QuantityType<?>> powerFigure() {
        return switch (profile) {
            case SimpleProfile simple -> simple.powerFigure().map(QuantityType.class::cast);
            case ControllableProfile controllable -> Optional.of(controllable.max());
            case BatchProfile batch -> Optional.of(batch.meanPower());
            case ModeControllableProfile ignored -> Optional.empty();
        };
    }

    /**
     * Returns the power figure to book while <em>admitting</em> this consumer - deciding whether there is room to
     * start it.
     * <p>
     * It differs from {@link #powerFigure()} for a Batch programme only, which is admitted at its curve's peak
     * rather than at its mean: the mean is what the programme costs, the peak is what the site has to carry.
     *
     * @return the admission figure, or {@link Optional#empty()} where the class carries none
     */
    public Optional<QuantityType<?>> admissionFigure() {
        return profile instanceof BatchProfile batch ? Optional.of(batch.admissionPower()) : powerFigure();
    }

    /**
     * Tests whether the figure this consumer is booked at was inferred rather than declared.
     * <p>
     * It is {@code true} for a Simple consumer that declares a surplus on-threshold and no {@code ratedPower}: the
     * on-threshold is a switching figure carrying margin, so booking it is a working answer that the engine reports
     * as a declaration gap. It is never a reason to reject the declaration.
     *
     * @return {@code true} if this consumer carries the rating declaration gap
     */
    public boolean ratingIsInferred() {
        return profile instanceof SimpleProfile simple && simple.ratingIsInferred() && simple.onThreshold() != null;
    }

    /**
     * Tests whether this consumer declares a readiness interlock.
     *
     * @return {@code true} if a readiness Item is declared
     */
    public boolean hasReadinessInterlock() {
        return readyItemName != null;
    }

    /**
     * Tests whether this consumer reports measured power.
     *
     * @return {@code true} if a measurement Item is declared
     */
    public boolean isMetered() {
        return measureItemName != null;
    }

    /**
     * Tests whether this consumer declares the phases it draws on.
     *
     * @return {@code true} if at least one phase is declared
     */
    public boolean declaresPhases() {
        return !phases.isEmpty();
    }

    /**
     * Tests whether this consumer declares a device protection whose elapsed time is measured from the steered
     * Item's own state history.
     * <p>
     * The engine needs this to report a protected participant whose state is not persisted: without a persisted last
     * state change, such a protection is a degraded guarantee, and a degraded guarantee has to be visible rather
     * than assumed. A consumer that declares no protection cannot have one degrade.
     *
     * @return {@code true} if a minimum or maximum ON or OFF time is declared
     */
    public boolean declaresProtections() {
        return profile instanceof SimpleProfile simple && simple.declaresProtections();
    }
}
