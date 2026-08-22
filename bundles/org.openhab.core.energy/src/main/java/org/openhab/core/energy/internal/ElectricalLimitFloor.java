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
package org.openhab.core.energy.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.energy.ControlAction;
import org.openhab.core.energy.Decision;
import org.openhab.core.energy.DecisionKind;
import org.openhab.core.energy.ElectricalLimits;
import org.openhab.core.energy.EnergyContext;
import org.openhab.core.energy.EnergyProvider;
import org.openhab.core.energy.ParticipantState;
import org.openhab.core.energy.ProviderRole;
import org.openhab.core.library.types.QuantityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The runtime floor beneath every plan: no cycle may dispatch the site past its declared total budget or past the
 * headroom of a phase, whatever the algorithm proposed and whichever algorithm proposed it.
 * <p>
 * <strong>Priority decides who is acted on, shape decides how.</strong> Decisions arrive in
 * {@link ConstraintLadder#STRENGTH_ORDER}, so headroom is claimed from the top of the ladder down and, inside a
 * rung, better priority first - which resolves an overload from the bottom, worst-priority-first. Whether a load is
 * trimmable never enters that order. What happens to a load once it is reached is decided by its own shape, over
 * exactly four dispositions:
 * <ul>
 * <li><strong>trim</strong> - a continuous control surface ({@code SetPower}, {@code SetCurrent}) is reduced to the
 * largest value that fits, provided that value is still at or above the participant's declared minimum;</li>
 * <li><strong>defer</strong> - a switch has no intermediate value and can only be deferred, and so is a trim that
 * would fall below the declared minimum. Deferring a Simple load that is <em>already running</em> means switching it
 * off, subject to device protections; the deferral is still published, so a planner may reschedule it;</li>
 * <li><strong>leave untouched</strong> - a Batch programme already running and a consumer marked hands-off are never
 * trimmed, deferred or switched off. Their draw is booked up front as load everything else is trimmed against, which
 * is why {@link Ledger} books them before the first decision is considered;</li>
 * <li><strong>refuse with a reason</strong> - when the remainder still does not fit, the deferral says so, naming the
 * load the floor was not allowed to shed rather than leaving an operator to work out why a budget was not met.</li>
 * </ul>
 * A ModeControllable load is exempt to the extent of the figure it declares, which is currently none: the model maps
 * no mode onto a draw, so a mode change is admitted and booked at zero and the gap is reported rather than turned
 * into a refusal. That replaces the prototype's {@code unknownDemandPolicy} parameter, which offered the same choice
 * as configuration.
 * <p>
 * <strong>What the floor books against a participant is the larger of its declared figure and its live
 * measurement</strong> while it is running, and its declared figure while it is not - so an appliance that ignores
 * its envelope is constrained rather than hidden behind the command, and a wallbox still ramping up books the
 * declaration it is ramping towards rather than the small number it happens to be drawing. Every cycle books from
 * scratch; no reserve is carried.
 * <p>
 * Source: owner decisions <strong>D15</strong> and <strong>D29</strong> (2026-08-03,
 * {@code openhab-ems-spec/docs/OWNER_DECISIONS.md}). D15 says "prefer a fresh measurement over an estimate", which
 * the wave-1 slice implemented as {@code max(declared, measured)} and reported as an interpretation rather than the
 * decision's words. D29 confirms it as the rule: conservative in <em>both</em> directions is the property that
 * matters, and it is now an owner decision rather than the code's reading. <em>Alternatives preserved:</em> booking
 * the measurement literally, which hides a ramping device behind a small instantaneous number; and always booking
 * the declaration, which hides a device exceeding its envelope behind its own paperwork.
 * <p>
 * <strong>A load nobody decided about this cycle still occupies headroom.</strong> {@link Ledger} pre-books every
 * consumer that reports itself running, and releases that booking only when a decision for that participant is
 * committed - which is the moment the engine knows what it will draw instead. Without it the ordinary case would be
 * invisible: a metered device already in the state it would be commanded to gets no decision at all, and its draw is
 * subtracted from the uncontrolled figure precisely because the floor is supposed to be accounting for it directly.
 * <p>
 * <strong>A protection is let through the stale-input freeze.</strong> While a safety input is stale the floor
 * refuses every increase, and the one exception is a decision at the device-protection rung: a duty-cycle guarantee
 * that is refused because a current clamp went stale spoils food.
 * <p>
 * Source: owner decision <strong>D24</strong> (2026-08-03, {@code openhab-ems-spec/docs/OWNER_DECISIONS.md}). Two
 * decisions collided here and the slice had to pick one: D2 puts electrical limits above device protections, while
 * D14 says freeze-and-floor is "all subject to
 * device protections" and, in the same breath, "refuse increases". A duty-cycle guarantee coming due during a freeze
 * wants to switch a fridge <em>on</em>, which is an increase. The slice let it through and reported the choice as an
 * inference beyond D14's flat wording; D24 confirms it as the rule, on the ground that <strong>a stale reading is an
 * unknown, not a known overload</strong>. The freeze's authority rests on a <em>measured</em> overload, which is
 * exactly what a stale input means the engine does not have. <em>Alternatives preserved:</em> freeze-means-freeze,
 * which is simpler to state and spoils food; and a bounded blind allowance, which needs a number nothing in the
 * corpus supplies.
 * <p>
 * <strong>The exception is not claimable by a label.</strong> A decision reaches this class at the
 * device-protection rung only if {@link EvaluationPass} let it: either its author carries
 * {@link EngineOwnedAlgorithm}, or the claim was corroborated against the participant's own declared, currently-due
 * protection (owner decision D25, {@link DeclaredProtections#corroborate}). A contributed algorithm can therefore
 * reach this exception - which is the point of D25 - but only by proposing what the engine can independently see the
 * declaration requires.
 * <p>
 * Phase accounting only ever constrains participants that declare phases; one that declares none counts against the
 * site total alone.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class ElectricalLimitFloor {

    /**
     * The algorithm id the floor's own decisions carry - a switch-off that stands in for a deferral, for instance.
     */
    public static final String ID = "electrical-limits";

    private final Logger logger = LoggerFactory.getLogger(ElectricalLimitFloor.class);

    private final PowerEstimator estimator;

    /**
     * Creates the floor.
     *
     * @param estimator the estimator translating actions into site load
     */
    public ElectricalLimitFloor(PowerEstimator estimator) {
        this.estimator = estimator;
    }

    /**
     * Applies the floor to one cycle's conflict-resolved decisions.
     *
     * @param context the snapshot of the cycle
     * @param ordered the winning decisions, strongest first
     * @return the admissions, in the same order, plus an outcome for every decision the floor removed
     */
    public Result apply(EnergyContext context, List<Decision> ordered) {
        Ledger ledger = new Ledger(context);
        List<Admission> admitted = new ArrayList<>();
        List<DecisionOutcome> rejected = new ArrayList<>();

        for (Decision decision : ordered) {
            ParticipantState state = context.participants().get(decision.participantId());
            if (state == null) {
                rejected.add(
                        DecisionOutcome.of(decision, DecisionStatus.REJECTED, "participant is not part of this cycle"));
                continue;
            }
            if (EngineProhibitions.isExemptFromShedding(state)) {
                // already booked by the ledger; the ladder forbids the floor from touching it
                admitted.add(new Admission(decision, null, exemptionReason(state) + ", booked but never trimmed"));
                continue;
            }
            OptionalDouble estimate = bookedWatts(state, decision.action());
            if (context.measurementsStale() && increasesLoad(state, estimate)
                    && decision.kind() != DecisionKind.DEVICE_PROTECTION) {
                rejected.add(DecisionOutcome.of(decision, DecisionStatus.DEFERRED,
                        "safety measurements are stale, load increases are refused until they return"));
                continue;
            }
            if (estimate.isEmpty()) {
                logger.debug("Admitting {} without checking it against the budget: its power demand cannot be "
                        + "derived from the declaration", decision.describe());
                admitted.add(new Admission(decision, null, "demand unknown, not counted against the budget"));
                continue;
            }

            double watts = estimate.getAsDouble();
            if (watts <= 0) {
                ledger.commit(watts, state);
                admitted.add(new Admission(decision, null, ""));
                continue;
            }
            double allowed = ledger.headroomFor(state);
            if (watts <= allowed) {
                ledger.commit(watts, state);
                admitted.add(new Admission(decision, null, ""));
                continue;
            }
            Optional<ControlAction> trimmedAction = trim(state, decision.action(), allowed);
            if (trimmedAction.isEmpty()) {
                defer(context, ledger, decision, state, watts, allowed, admitted, rejected);
                continue;
            }
            Decision trimmed = decision.withAction(trimmedAction.get());
            double trimmedWatts = bookedWatts(state, trimmedAction.get()).orElse(0);
            ledger.commit(trimmedWatts, state);
            admitted.add(
                    new Admission(trimmed, decision, "trimmed to fit " + format(allowed) + " W of available headroom"));
        }
        return new Result(admitted, rejected);
    }

    /**
     * Defers a decision the floor has no headroom for, switching the participant off when deferring a running load
     * means exactly that, and refusing with a reason that names what the floor was not allowed to shed.
     *
     * @param context the snapshot of the cycle
     * @param ledger the running budget
     * @param decision the decision being deferred
     * @param state the participant it addresses
     * @param watts what it would have booked
     * @param allowed what was available
     * @param admitted collects a switch-off when the deferred load is already running
     * @param rejected collects the deferral itself
     */
    private void defer(EnergyContext context, Ledger ledger, Decision decision, ParticipantState state, double watts,
            double allowed, List<Admission> admitted, List<DecisionOutcome> rejected) {
        String reason = "no headroom: needs " + format(watts) + " W, " + format(allowed) + " W available"
                + ledger.exemptNote();
        rejected.add(DecisionOutcome.of(decision, DecisionStatus.DEFERRED, reason));
        boolean alreadyOff = decision.action() instanceof ControlAction.Switch onOff && !onOff.on();
        if (!state.isReportedOn() || alreadyOff) {
            return;
        }
        Optional<String> held = EngineProhibitions.protectionHolding(state, context);
        if (held.isPresent()) {
            // a protection outranks the deferral: the load keeps running and its draw stays booked
            ledger.commit(Math.max(0, estimator.currentLoadWatts(state)), state);
            logger.debug("Not switching {} off to resolve the overload: {}", decision.participantId(), held.get());
            return;
        }
        // the load is being switched off, so the headroom it was pre-booked for is available to what follows
        ledger.release(state);
        Decision off = new Decision(decision.participantId(), ControlAction.off(), ID, decision.priority(),
                DecisionKind.ELECTRICAL_LIMIT, "deferred by the electrical limit: " + reason);
        admitted.add(new Admission(off, decision, "deferring a running load switches it off"));
    }

    private String exemptionReason(ParticipantState state) {
        return EngineProhibitions.isRunningBatch(state) ? "a running batch programme is not interrupted"
                : "the participant is marked hands-off";
    }

    /**
     * Returns what the floor books for an action: the larger of the participant's declared figure and its live
     * measurement while it is running, and the declared figure while it is not.
     *
     * @param state the participant the action addresses
     * @param action the action
     * @return the booked load in watts, or empty when nothing can be derived from the declaration
     */
    private OptionalDouble bookedWatts(ParticipantState state, ControlAction action) {
        OptionalDouble declared = estimator.loadWatts(state, action);
        if (declared.isEmpty() || !state.isReportedOn() || !state.hasMeasurement()) {
            return declared;
        }
        double measured = estimator.currentLoadWatts(state);
        return measured > declared.getAsDouble() ? OptionalDouble.of(measured) : declared;
    }

    private boolean increasesLoad(ParticipantState state, OptionalDouble estimate) {
        return estimate.isEmpty() || estimate.getAsDouble() > estimator.currentLoadWatts(state);
    }

    private Optional<ControlAction> trim(ParticipantState state, ControlAction action, double allowedWatts) {
        if (allowedWatts <= 0) {
            return Optional.empty();
        }
        return switch (action) {
            case ControlAction.SetPower power -> trimPower(state, allowedWatts);
            case ControlAction.SetCurrent current -> trimCurrent(state, allowedWatts);
            case ControlAction.Switch onOff -> Optional.empty();
            case ControlAction.SetMode mode -> Optional.empty();
            case ControlAction.Hold hold -> Optional.empty();
        };
    }

    private Optional<ControlAction> trimPower(ParticipantState state, double allowedWatts) {
        if (state.participant() instanceof EnergyProvider provider) {
            // a provider setpoint takes the sign of its own reading (SignConvention), and a battery charging at 3 kW
            // *is* 3 kW of load, so the setpoint that fits the remaining headroom is the headroom itself; for a role
            // whose positive direction is supply, fitting the headroom means supplying that much
            double setpoint = provider.role() == ProviderRole.BATTERY ? allowedWatts : -allowedWatts;
            QuantityType<?> minPower = provider.minPower();
            OptionalDouble minimum = minPower == null ? OptionalDouble.empty() : EngineUnits.watts(minPower);
            if (minimum.isPresent() && setpoint < minimum.getAsDouble()) {
                return Optional.empty();
            }
            return Optional.of(ControlAction.watts(setpoint));
        }
        OptionalDouble minimum = estimator.minimumLoadWatts(state);
        if (minimum.isPresent() && allowedWatts < minimum.getAsDouble()) {
            return Optional.empty();
        }
        return Optional.of(ControlAction.watts(allowedWatts));
    }

    private Optional<ControlAction> trimCurrent(ParticipantState state, double allowedWatts) {
        OptionalDouble minimum = estimator.minimumLoadWatts(state);
        if (minimum.isPresent() && allowedWatts < minimum.getAsDouble()) {
            return Optional.empty();
        }
        double amperes = allowedWatts / (estimator.nominalVoltage() * estimator.phaseCount(state));
        return Optional.of(ControlAction.amperes(amperes));
    }

    private static String format(double watts) {
        return String.format(Locale.ROOT, "%.0f", watts);
    }

    /**
     * The running budget of one cycle: how much of the total and of each phase has been claimed so far.
     * <p>
     * <strong>Every running consumer is booked here, before the first decision is looked at.</strong> Two populations
     * with two reasons:
     * <ul>
     * <li>the loads the ladder forbids the floor to shed - a running Batch programme and a hands-off consumer - are
     * booked so that everything else is trimmed against them rather than competing with them, and their total is
     * named in the refusal note;</li>
     * <li>every other running consumer is <em>pre</em>-booked, and its booking is released the moment a decision for
     * it is committed. A managed device the cycle reaches no decision about - the ordinary case, since a device
     * already in the state it would be commanded to gets no decision - would otherwise be counted nowhere at all: its
     * measured draw is subtracted from the uncontrolled figure exactly because the floor is meant to account for it
     * here. The figure is the measurement alone, never a rating, so a consumer that declares no measurement is not
     * double-counted against the uncontrolled load it is still part of.</li>
     * </ul>
     * A participant's own pre-booking is available to its own decision - {@link #headroomFor} adds it back - because
     * a new command replaces the draw rather than adding to it.
     *
     * @author Stamate Viorel - Initial contribution
     */
    private final class Ledger {

        private final ElectricalLimits limits;
        private final Map<Integer, Double> phaseUsed = new HashMap<>();
        private final List<String> exempt = new ArrayList<>();
        private final Map<String, Double> prebooked = new HashMap<>();
        private double totalUsed;
        private double exemptWatts;

        private Ledger(EnergyContext context) {
            limits = context.limits();
            totalUsed = context.uncontrolledLoadWatts();
            for (Integer phase : limits.phaseBudgets().keySet()) {
                phaseUsed.put(phase, context.uncontrolledPhaseWatts(phase));
            }
            for (ParticipantState state : context.consumers()) {
                if (EngineProhibitions.isExemptFromShedding(state)) {
                    double booked = Math.max(0, Math.max(estimator.currentLoadWatts(state),
                            state.isReportedOn() ? estimator.ratedLoadWatts(state).orElse(0) : 0));
                    exempt.add(state.id());
                    exemptWatts += booked;
                    book(booked, state);
                    continue;
                }
                if (!state.isReportedOn()) {
                    continue;
                }
                double running = Math.max(0, estimator.currentLoadWatts(state));
                if (running > 0) {
                    prebooked.put(state.id(), running);
                    book(running, state);
                }
            }
        }

        /**
         * Drops a participant's pre-booking, which is what switching it off does to the site load.
         *
         * @param state the participant state
         */
        private void release(ParticipantState state) {
            Double booked = prebooked.remove(state.id());
            if (booked != null) {
                book(-booked, state);
            }
        }

        /**
         * Returns the note the floor appends to a refusal, naming the load it was not allowed to shed.
         *
         * @return the note, empty when nothing was exempt this cycle
         */
        private String exemptNote() {
            return exempt.isEmpty() ? ""
                    : " (" + format(exemptWatts) + " W of it booked for " + String.join(", ", exempt)
                            + ", which the ladder does not allow the floor to shed)";
        }

        /**
         * Returns the largest total load this participant may still be given, its own pre-booking included: a command
         * to a running device replaces what it draws rather than adding to it.
         *
         * @param state the participant state
         * @return the headroom in watts, {@link Double#POSITIVE_INFINITY} when nothing constrains it
         */
        private double headroomFor(ParticipantState state) {
            double allowed = Double.POSITIVE_INFINITY;
            double own = prebooked.getOrDefault(state.id(), 0.0);
            OptionalDouble total = limits.totalBudgetWatts();
            if (total.isPresent()) {
                allowed = total.getAsDouble() - totalUsed + own;
            }
            int phases = estimator.phaseCount(state);
            double ownPerPhase = estimator.perPhaseWatts(own, state);
            for (Integer phase : state.phases()) {
                OptionalDouble budget = limits.phaseBudgetWatts(phase);
                if (budget.isPresent()) {
                    double used = phaseUsed.getOrDefault(phase, 0.0) - ownPerPhase;
                    allowed = Math.min(allowed, phases * (budget.getAsDouble() - used));
                }
            }
            return allowed;
        }

        /**
         * Books what a decision will draw, releasing whatever the participant was pre-booked for first.
         *
         * @param watts the load to book
         * @param state the participant state
         */
        private void commit(double watts, ParticipantState state) {
            release(state);
            book(watts, state);
        }

        /**
         * Adds a load to the total and to every phase the participant declares.
         *
         * @param watts the load to add, negative to remove one
         * @param state the participant state
         */
        private void book(double watts, ParticipantState state) {
            totalUsed += watts;
            double perPhase = estimator.perPhaseWatts(watts, state);
            for (Integer phase : state.phases()) {
                phaseUsed.merge(phase, perPhase, Double::sum);
            }
        }
    }

    /**
     * A decision the floor lets through, with the untrimmed original when it had to be reduced.
     *
     * @param decision the decision as it should be dispatched
     * @param original the decision as proposed, or {@code null} when it was not modified
     * @param detail a short explanation for the log line, may be empty
     *
     * @author Stamate Viorel - Initial contribution
     */
    public record Admission(Decision decision, @Nullable Decision original, String detail) {
    }

    /**
     * What the floor made of one cycle.
     *
     * @param admitted the decisions that may proceed, strongest first
     * @param rejected an outcome for every decision the floor removed
     *
     * @author Stamate Viorel - Initial contribution
     */
    public record Result(List<Admission> admitted, List<DecisionOutcome> rejected) {

        /**
         * Takes immutable copies of both lists.
         */
        public Result {
            admitted = List.copyOf(admitted);
            rejected = List.copyOf(rejected);
        }

        /**
         * Returns the participants whose decisions the floor removed.
         *
         * @return the participant ids
         */
        public Set<String> deferredParticipants() {
            return rejected.stream().map(outcome -> outcome.decision().participantId())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }
}
