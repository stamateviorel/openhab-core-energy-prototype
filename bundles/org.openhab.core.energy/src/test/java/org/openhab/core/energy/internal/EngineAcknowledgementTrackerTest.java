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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.openhab.core.energy.internal.EngineTestFixtures.*;

import java.time.Duration;
import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.ControlAction;
import org.openhab.core.energy.Decision;
import org.openhab.core.energy.EnergyConsumer;
import org.openhab.core.energy.EnergyContext;
import org.openhab.core.energy.EnergyLevel;
import org.openhab.core.energy.ParticipantState;
import org.openhab.core.library.types.QuantityType;

/**
 * The acknowledgement window: the <em>Acknowledgement-aware actuation</em> requirement and its "Charger with
 * lagging state" scenario, what expiry means, and what counts as an acknowledgement.
 * <p>
 * The last of those is the discriminating one. A value inside a declared tolerance band acknowledges the command;
 * with no band declared the comparison is exact. The prototype instead compared with a band proportional to the
 * commanded value, which happened to accept 15.999 A for 16 A - so the scenario passed for the wrong reason and
 * would have meant something different at 1.6 A.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class EngineAcknowledgementTrackerTest {

    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final EnergyConsumer wallbox = wallbox("wallbox", 1, 6, 32);
    private final Decision sixteenAmps = Decision.of("wallbox", ControlAction.amperes(16), "test", 1);

    private EnergyContext contextAt(Instant moment, String reportedState) {
        return contextAt(wallbox, moment, reportedState);
    }

    private EnergyContext contextAt(EnergyConsumer participant, Instant moment, String reportedState) {
        return EnergyContext.builder(moment, EnergyLevel.NORMAL)
                .participant(ParticipantState.of(participant).withReportedState(reportedState)).build();
    }

    /**
     * Scenario "A wallbox that never lands exactly": a charger settling within a milliamp of any commanded value
     * declares a band of 0.01 A, and a reported 15.999 A then acknowledges a commanded 16 A - through the tracker,
     * on the participant's own declaration, rather than through a constant nobody chose.
     * <p>
     * The second half is what makes it a declaration: the identical reading on an identical charger that declares no
     * band does <em>not</em> acknowledge, and the command stays outstanding.
     */
    @Test
    public void aWallboxThatNeverLandsExactlyIsAcknowledgedWithinItsDeclaredBand() {
        EnergyConsumer tolerant = wallbox.withAckTolerance(new QuantityType<>("0.01 A"));
        EngineAcknowledgementTracker tracker = new EngineAcknowledgementTracker(WINDOW, false);
        tracker.recordDispatch(sixteenAmps, T0);

        EnergyContext almost = contextAt(tolerant, T0.plusSeconds(30), "15.999 A");
        tracker.observe(almost);

        assertThat("the band is declared, so a milliamp low still lands", tracker.pendingParticipants(), is(empty()));

        EngineAcknowledgementTracker strict = new EngineAcknowledgementTracker(WINDOW, false);
        strict.recordDispatch(sixteenAmps, T0);
        strict.observe(contextAt(T0.plusSeconds(30), "15.999 A"));

        assertThat("with no band declared the comparison is exact", strict.pendingParticipants(), contains("wallbox"));
    }

    /**
     * Scenario "A slow device gets a longer window": a device declaring three minutes keeps its command outstanding
     * past the engine's 60-second default, and the default still governs every participant that declares none.
     */
    @Test
    public void aSlowDeviceGetsTheLongerWindowItDeclaresAndOthersKeepTheDefault() {
        EnergyConsumer slow = wallbox.withAckWindow(Duration.ofMinutes(3));
        EngineAcknowledgementTracker tracker = new EngineAcknowledgementTracker(WINDOW, false);
        tracker.recordDispatch(sixteenAmps, T0);

        // past the engine default of 60 s, but inside this participant's declared three minutes
        EnergyContext pastTheDefault = contextAt(slow, T0.plusSeconds(90), "10 A");
        tracker.observe(pastTheDefault);
        assertThat(tracker.isSuppressed(sixteenAmps, pastTheDefault), is(true));

        EnergyContext pastItsOwn = contextAt(slow, T0.plusSeconds(200), "10 A");
        tracker.observe(pastItsOwn);
        assertThat("its own window still expires", tracker.isSuppressed(sixteenAmps, pastItsOwn), is(false));

        // the same moment, on a participant that declares nothing, lapsed long ago
        EngineAcknowledgementTracker byDefault = new EngineAcknowledgementTracker(WINDOW, false);
        byDefault.recordDispatch(sixteenAmps, T0);
        EnergyContext other = contextAt(T0.plusSeconds(90), "10 A");
        byDefault.observe(other);
        assertThat(byDefault.isSuppressed(sixteenAmps, other), is(false));
    }

    /**
     * Scenario "Neither is required": a participant declaring no window and no band is judged by the engine's default
     * window and by an exact comparison, and nothing about it is a special case.
     */
    @Test
    public void neitherDeclarationIsRequired() {
        assertThat(wallbox.ackWindow(), is(nullValue()));
        assertThat(wallbox.ackTolerance(), is(nullValue()));

        AcknowledgementTerms terms = AcknowledgementTerms.declaredBy(wallbox, WINDOW);

        assertThat(terms.window(), is(WINDOW));
        assertThat(terms.tolerance(), is(nullValue()));
    }

    /**
     * A band declared in a dimension the command does not use cannot judge it, and the comparison falls back to
     * exact rather than to a number that means something else. A band of "0.01 A" says nothing about how close a
     * watt setpoint has to land.
     */
    @Test
    public void aBandInTheWrongDimensionDoesNotWidenAnything() {
        EnergyConsumer amps = wallbox.withAckTolerance(new QuantityType<>("0.01 A"));
        Decision watts = Decision.of("wallbox", ControlAction.watts(3000), "test", 1);
        EngineAcknowledgementTracker tracker = new EngineAcknowledgementTracker(WINDOW, false);
        tracker.recordDispatch(watts, T0);

        tracker.observe(contextAt(amps, T0.plusSeconds(30), "2999.999 W"));

        assertThat(tracker.pendingParticipants(), contains("wallbox"));
    }

    @Test
    public void aChargerWhoseStateLagsIsNotCommandedAgain() {
        EngineAcknowledgementTracker tracker = new EngineAcknowledgementTracker(WINDOW, false);
        tracker.recordDispatch(sixteenAmps, T0);

        EnergyContext nextCycle = contextAt(T0.plusSeconds(30), "10 A");
        tracker.observe(nextCycle);

        assertThat(tracker.pendingParticipants(), contains("wallbox"));
        assertThat(tracker.isSuppressed(sixteenAmps, nextCycle), is(true));
    }

    @Test
    public void theCommandIsSentAgainOnceTheChargerHasCaughtUp() {
        EngineAcknowledgementTracker tracker = new EngineAcknowledgementTracker(WINDOW, false);
        tracker.recordDispatch(sixteenAmps, T0);

        EnergyContext acknowledged = contextAt(T0.plusSeconds(30), "16 A");
        tracker.observe(acknowledged);

        assertThat(tracker.pendingParticipants(), is(empty()));
        assertThat(tracker.isSuppressed(sixteenAmps, acknowledged), is(false));
    }

    @Test
    public void aDeviceThatNeverEchoesDoesNotStayBlockedForever() {
        EngineAcknowledgementTracker tracker = new EngineAcknowledgementTracker(WINDOW, false);
        tracker.recordDispatch(sixteenAmps, T0);

        EnergyContext insideWindow = contextAt(T0.plusSeconds(59), "10 A");
        tracker.observe(insideWindow);
        assertThat(tracker.isSuppressed(sixteenAmps, insideWindow), is(true));

        EnergyContext afterWindow = contextAt(T0.plusSeconds(61), "10 A");
        tracker.observe(afterWindow);
        assertThat(tracker.isSuppressed(sixteenAmps, afterWindow), is(false));
    }

    /**
     * A reading inside a declared tolerance band acknowledges the command; the same reading with no band declared
     * does not. Both halves matter: the first is the requirement, the second is what makes the band a declaration
     * rather than a hidden constant.
     */
    @Test
    public void aReadingInsideTheDeclaredToleranceBandAcknowledgesTheCommand() {
        assertThat(EngineUnits.acknowledges(15.999, 16, 0.01), is(true));
        assertThat(EngineUnits.acknowledges(15.999, 16, null), is(false));
    }

    /**
     * The band is absolute and in the control Item's own dimension, never a fraction of the commanded value. A band
     * of 0.01 A means 0.01 A whether the command is 16 A or 1.6 A - which a proportional rule cannot express.
     */
    @Test
    public void theToleranceBandIsAbsoluteRatherThanProportional() {
        assertThat(EngineUnits.acknowledges(1.599, 1.6, 0.01), is(true));
        assertThat(EngineUnits.acknowledges(1.5, 1.6, 0.01), is(false));
        // proportional would have accepted this, since 16 x 0.001 is 0.016
        assertThat(EngineUnits.acknowledges(15.99, 16, null), is(false));
    }

    /**
     * With no band declared, an exact command still acknowledges itself across a unit conversion - the comparison is
     * exact up to representation error, not up to a device tolerance.
     */
    @Test
    public void anExactEchoAcknowledgesEvenAcrossAUnitConversion() {
        EngineAcknowledgementTracker tracker = new EngineAcknowledgementTracker(WINDOW, false);
        tracker.recordDispatch(Decision.of("wallbox", ControlAction.amperes(16), "test", 1), T0);

        tracker.observe(contextAt(T0.plusSeconds(5), "16000 mA"));

        assertThat(tracker.pendingParticipants(), is(empty()));
    }

    /**
     * On expiry the command lapses and control resumes - the engine is free to decide afresh. It does not withhold
     * the participant from further commands, which is the preserved alternative and would silently drop a flaky
     * charger out of management.
     */
    @Test
    public void onExpiryTheCommandLapsesAndControlResumes() {
        EngineAcknowledgementTracker tracker = new EngineAcknowledgementTracker(WINDOW, false);
        tracker.recordDispatch(sixteenAmps, T0);

        EnergyContext afterWindow = contextAt(T0.plusSeconds(61), "10 A");
        tracker.observe(afterWindow);

        assertThat(tracker.pendingParticipants(), is(empty()));
        assertThat(tracker.outstanding("wallbox"), is(nullValue()));
        assertThat(tracker.isSuppressed(sixteenAmps, afterWindow), is(false));
    }

    /**
     * Neither a window nor a band is required: a participant that declares nothing is judged by the engine's default
     * window and by an exact comparison.
     */
    @Test
    public void neitherAWindowNorABandIsRequired() {
        AcknowledgementTerms terms = AcknowledgementTerms.declaredBy(wallbox, WINDOW);

        assertThat(terms.window(), is(WINDOW));
        assertThat(terms.tolerance(), is(nullValue()));
    }

    @Test
    public void aDifferentTargetIsNotARepeatUnlessConfiguredToBe() {
        Decision tenAmps = Decision.of("wallbox", ControlAction.amperes(10), "test", 1);

        EngineAcknowledgementTracker permissive = new EngineAcknowledgementTracker(WINDOW, false);
        permissive.recordDispatch(sixteenAmps, T0);
        EnergyContext context = contextAt(T0.plusSeconds(5), "10 A");
        permissive.observe(context);
        assertThat(permissive.isSuppressed(tenAmps, context), is(false));

        EngineAcknowledgementTracker strict = new EngineAcknowledgementTracker(WINDOW, true);
        strict.recordDispatch(sixteenAmps, T0);
        strict.observe(context);
        assertThat(strict.isSuppressed(tenAmps, context), is(true));
    }

    @Test
    public void onOffCommandsAreAcknowledgedByTheReportedSwitchState() {
        EngineAcknowledgementTracker tracker = new EngineAcknowledgementTracker(WINDOW, false);
        Decision switchOn = Decision.of("wallbox", ControlAction.on(), "test", 1);
        tracker.recordDispatch(switchOn, T0);

        tracker.observe(contextAt(T0.plusSeconds(5), "OFF"));
        assertThat(tracker.pendingParticipants(), contains("wallbox"));

        tracker.observe(contextAt(T0.plusSeconds(10), "ON"));
        assertThat(tracker.pendingParticipants(), is(empty()));
    }

    @Test
    public void theAdapterVariantLeavesTheWindowToTheDeviceAdapter() {
        AdapterAcknowledgementTracker tracker = new AdapterAcknowledgementTracker();
        tracker.recordDispatch(sixteenAmps, T0);
        EnergyContext context = contextAt(T0.plusSeconds(5), "10 A");
        tracker.observe(context);

        assertThat(tracker.pendingParticipants(), is(empty()));
        assertThat(tracker.isSuppressed(sixteenAmps, context), is(false));
    }
}
