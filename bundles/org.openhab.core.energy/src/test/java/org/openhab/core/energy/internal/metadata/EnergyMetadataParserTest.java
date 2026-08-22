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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.BatchProfile;
import org.openhab.core.energy.ControllableProfile;
import org.openhab.core.energy.Deadline;
import org.openhab.core.energy.Demand;
import org.openhab.core.energy.EnergyConsumer;
import org.openhab.core.energy.EnergyLevel;
import org.openhab.core.energy.EnergyParticipant;
import org.openhab.core.energy.EnergyProvider;
import org.openhab.core.energy.LevelGate;
import org.openhab.core.energy.ModeControllableProfile;
import org.openhab.core.energy.ProviderRole;
import org.openhab.core.energy.SimpleProfile;
import org.openhab.core.energy.internal.metadata.EnergyMetadataParser.ParsedDeclaration;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;

/**
 * Scenario tests for the {@code energy} item-metadata declaration mechanism.
 * <p>
 * Every test named after a {@code #### Scenario:} of the {@code energy-participants} requirements covers the
 * declaration half of that scenario - that the user can say the thing at all. What the engine then does with it is
 * tested with the engine.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class EnergyMetadataParserTest {

    private static EnergyParticipant parse(String itemName, String value, Map<String, Object> configuration)
            throws EnergyMetadataParseException {
        return EnergyMetadataParser.parse(itemName, value, configuration).participant();
    }

    private static EnergyConsumer consumer(String itemName, Map<String, Object> configuration)
            throws EnergyMetadataParseException {
        EnergyParticipant participant = parse(itemName, "consumer", configuration);
        assertThat(participant, instanceOf(EnergyConsumer.class));
        return (EnergyConsumer) participant;
    }

    private static EnergyProvider provider(String itemName, Map<String, Object> configuration)
            throws EnergyMetadataParseException {
        EnergyParticipant participant = parse(itemName, "provider", configuration);
        assertThat(participant, instanceOf(EnergyProvider.class));
        return (EnergyProvider) participant;
    }

    /**
     * Scenario: Marking a wallbox consumer, and Scenario: Wallbox as Controllable.
     */
    @Test
    public void wallboxIsDeclaredOnAnExistingNumberItemAsControllable() throws EnergyMetadataParseException {
        EnergyConsumer wallbox = consumer("Wallbox_Current",
                Map.of("profile", "controllable", "min", "6 A", "max", "32 A", "priority", 1));

        assertThat(wallbox.id(), is("Wallbox_Current"));
        assertThat(wallbox.itemName(), is("Wallbox_Current"));
        assertThat(wallbox.priority(), is(1));
        assertThat(wallbox.profile(), instanceOf(ControllableProfile.class));
        ControllableProfile profile = (ControllableProfile) wallbox.profile();
        assertThat(profile.isCurrentBased(), is(true));
        assertThat(profile.min().doubleValue(), is(6.0));
        assertThat(profile.max().doubleValue(), is(32.0));
    }

    /**
     * Scenario: Boiler as Simple.
     */
    @Test
    public void boilerIsDeclaredAsSimple() throws EnergyMetadataParseException {
        EnergyConsumer boiler = consumer("Boiler_Switch", Map.of("profile", "simple", "priority", 2));

        assertThat(boiler.profile(), instanceOf(SimpleProfile.class));
        assertThat(boiler.levelGate().isPresent(), is(true));
    }

    /**
     * Scenario: SG-ready heat pump as ModeControllable.
     */
    @Test
    public void heatPumpIsDeclaredWithOrderedModes() throws EnergyMetadataParseException {
        EnergyConsumer heatPump = consumer("HeatPump_Mode",
                Map.of("profile", "mode", "modes", "blocked, normal, encouraged, forced", "priority", 1));

        assertThat(heatPump.profile(), instanceOf(ModeControllableProfile.class));
        ModeControllableProfile profile = (ModeControllableProfile) heatPump.profile();
        assertThat(profile.modes(), contains("blocked", "normal", "encouraged", "forced"));
        assertThat(profile.mostRestricted(), is("blocked"));
        assertThat(profile.leastRestricted(), is("forced"));
    }

    /**
     * Scenario: Dishwasher as Batch, and Scenario: Dishwasher ready by 7am.
     */
    @Test
    public void dishwasherIsDeclaredAsBatchWithItsLoadCurveAndDeadline() throws EnergyMetadataParseException {
        EnergyConsumer dishwasher = consumer("Dishwasher_Start", Map.of("profile", "batch", "ratedW", 2000,
                "runtimeHours", 2.5, "shape", "0.2, 1.0, 0.4, 0.1", "demandKwh", 1.4, "deadlineHour", 7));

        assertThat(dishwasher.profile(), instanceOf(BatchProfile.class));
        BatchProfile profile = (BatchProfile) dishwasher.profile();
        assertThat(profile.ratedPower().doubleValue(), is(2000.0));
        assertThat(profile.runtime(), is(Duration.ofMinutes(150)));
        assertThat(profile.loadCurve(), is(notNullValue()));
        assertThat(profile.meanFraction(), is(closeTo(0.425, 1e-9)));
        assertThat(Objects.requireNonNull(profile.loadCurve()).size(), is(4));

        Demand demand = Objects.requireNonNull(dishwasher.demand());
        assertThat(demand.kilowattHours(), is(closeTo(1.4, 1e-9)));
        assertThat(demand.deadline(), is(Deadline.daily(LocalTime.of(7, 0))));
        assertThat(demand.consecutive(), is(false));
    }

    /**
     * Scenario: Consecutive-hours demand.
     */
    @Test
    public void demandCanRequireConsecutiveHours() throws EnergyMetadataParseException {
        EnergyConsumer boiler = consumer("Boiler_Switch",
                Map.of("profile", "simple", "demandKwh", 4, "deadlineHour", 7, "consecutive", true));

        Demand demand = Objects.requireNonNull(boiler.demand());
        assertThat(demand.consecutive(), is(true));
    }

    /**
     * Scenario: PV and grid declared.
     */
    @Test
    public void pvAndGridAreDeclaredAsProviders() throws EnergyMetadataParseException {
        EnergyProvider grid = provider("Grid_Power", Map.of("role", "grid"));
        EnergyProvider pv = provider("Solar_Power", Map.of("role", "solar"));

        assertThat(grid.role(), is(ProviderRole.GRID));
        assertThat(grid.isControllable(), is(false));
        assertThat(pv.role(), is(ProviderRole.PV));
    }

    /**
     * Scenario: Battery as negative load - the declaration half: setpoint item, clamp and state of charge.
     */
    @Test
    public void batteryIsDeclaredAsAControllableProvider() throws EnergyMetadataParseException {
        EnergyProvider battery = provider("Battery_Power", Map.of("role", "battery", "control", "Battery_Setpoint",
                "min", "-3000", "max", "3 kW", "soc", "Battery_Soc"));

        assertThat(battery.role(), is(ProviderRole.BATTERY));
        assertThat(battery.isControllable(), is(true));
        assertThat(battery.controlItemName(), is("Battery_Setpoint"));
        assertThat(battery.hasStateOfCharge(), is(true));
        assertThat(Objects.requireNonNull(battery.minPower()).doubleValue(), is(-3000.0));
        assertThat(Objects.requireNonNull(battery.maxPower()).doubleValue(), is(3000.0));
        assertThat(Objects.requireNonNull(battery.maxPower()).getUnit(), is(Units.WATT));
    }

    /**
     * Scenario: Fridge duty-cycle guarantee, and Scenario: Cooldown respected - the declaration half.
     */
    @Test
    public void simpleConsumerCarriesTheFullProtectionSet() throws EnergyMetadataParseException {
        EnergyConsumer fridge = consumer("Fridge_Switch", Map.of("profile", "simple", "onThreshold", "150 W", "minOn",
                "10 m", "maxOn", "2 h", "minOff", "15 m", "maxOff", "45 m"));

        SimpleProfile profile = (SimpleProfile) fridge.profile();
        assertThat(Objects.requireNonNull(profile.onThreshold()).doubleValue(), is(150.0));
        assertThat(profile.minOn(), is(Duration.ofMinutes(10)));
        assertThat(profile.maxOn(), is(Duration.ofHours(2)));
        assertThat(profile.minOff(), is(Duration.ofMinutes(15)));
        assertThat(profile.maxOff(), is(Duration.ofMinutes(45)));
    }

    /**
     * Scenario: Pool pump waits for encouraged.
     */
    @Test
    public void poolPumpDeclaresTheLevelItRunsFrom() throws EnergyMetadataParseException {
        EnergyConsumer poolPump = consumer("Pool_Pump", Map.of("profile", "simple", "level", "encouraged"));

        LevelGate gate = poolPump.levelGate().orElseThrow();
        assertThat(gate.permits(EnergyLevel.ENCOURAGED), is(true));
        assertThat(gate.permits(EnergyLevel.NORMAL), is(false));
    }

    /**
     * Scenario: Manual-only device.
     */
    @Test
    public void manualOnlyDeviceDeclaresItselfHandsOff() throws EnergyMetadataParseException {
        EnergyConsumer manual = consumer("Sauna_Switch", Map.of("profile", "simple", "handsOff", true));

        assertThat(manual.handsOff(), is(true));
        assertThat("hands-off is not a level, so the gate is untouched",
                manual.levelGate().orElseThrow().minimumLevel(), is(EnergyLevel.BLOCKED));
    }

    /**
     * Scenario: Hands-off on a class other than Simple - an EV charger and a dishwasher can say it too, which is
     * the whole reason the flag moved off the Simple profile.
     */
    @Test
    public void handsOffIsDeclarableOnEveryProfileClass() throws EnergyMetadataParseException {
        assertThat(
                consumer("Wallbox_Current",
                        Map.of("profile", "controllable", "min", "6 A", "max", "32 A", "handsOff", true)).handsOff(),
                is(true));
        assertThat(
                consumer("HeatPump_Mode", Map.of("profile", "mode", "modes", "off,on", "handsOff", "true")).handsOff(),
                is(true));
        assertThat(
                consumer("Dishwasher_Start",
                        Map.of("profile", "batch", "ratedW", 2000, "runtimeHours", 2, "handsOff", true)).handsOff(),
                is(true));
        assertThat("an undeclared flag leaves the device steerable",
                consumer("Boiler_Switch", Map.of("profile", "simple")).handsOff(), is(false));
    }

    /**
     * The former spelling is still read - on any class, since that is the population the move exists to serve - and
     * reported, so a site is told to move it rather than silently losing the device from management.
     */
    @Test
    public void theOldLevelNeverSpellingStillMarksAConsumerHandsOffAndIsReported() throws EnergyMetadataParseException {
        ParsedDeclaration simple = EnergyMetadataParser.parse("Sauna_Switch", "consumer",
                Map.of("profile", "simple", "level", "never"));

        assertThat(((EnergyConsumer) simple.participant()).handsOff(), is(true));
        assertThat(simple.warnings(), hasItem(containsString("handsOff")));

        ParsedDeclaration batch = EnergyMetadataParser.parse("Dishwasher_Start", "consumer",
                Map.of("profile", "batch", "ratedW", 2000, "runtimeHours", 2, "level", "never"));

        assertThat("the class that could not say it before can now", ((EnergyConsumer) batch.participant()).handsOff(),
                is(true));
        assertThat(batch.warnings(), hasItem(containsString("handsOff")));
    }

    /**
     * Scenario: A hands-off device is still measured - the declaration keeps everything it declared, so marking a
     * device hands-off never costs the electrical-limit floor its visibility of the load.
     */
    @Test
    public void aHandsOffDeclarationKeepsItsMeasurement() throws EnergyMetadataParseException {
        EnergyConsumer sauna = consumer("Sauna_Switch",
                Map.of("profile", "simple", "handsOff", true, "measure", "Sauna_Power", "onThreshold", "3000 W"));

        assertThat(sauna.handsOff(), is(true));
        assertThat(sauna.isMetered(), is(true));
        assertThat(sauna.measureItemName(), is("Sauna_Power"));
    }

    /**
     * Scenario: Not ready, not started - the declaration half.
     */
    @Test
    public void readinessInterlockAndMeasurementAreDeclared() throws EnergyMetadataParseException {
        EnergyConsumer poolPump = consumer("Pool_Pump",
                Map.of("profile", "simple", "ready", "Pool_Pump_Ready", "measure", "Pool_Pump_Power"));

        assertThat(poolPump.hasReadinessInterlock(), is(true));
        assertThat(poolPump.readyItemName(), is("Pool_Pump_Ready"));
        assertThat(poolPump.isMetered(), is(true));
        assertThat(poolPump.measureItemName(), is("Pool_Pump_Power"));
    }

    /**
     * A gate always names a level now, so an unknown one is rejected and the word that used to mean "leave it alone"
     * no longer reaches the gate at all.
     */
    @Test
    public void aLevelGateAlwaysNamesOneOfTheFourLevels() throws EnergyMetadataParseException {
        assertThrows(EnergyMetadataParseException.class,
                () -> parse("Pool_Pump", "consumer", Map.of("profile", "simple", "level", "sometimes")));
        assertThat(consumer("Sauna_Switch", Map.of("profile", "simple", "level", "never")).levelGate().orElseThrow()
                .minimumLevel(), is(EnergyLevel.BLOCKED));
    }

    @Test
    public void theLevelIsAlsoAcceptedAsAFixtureCode() throws EnergyMetadataParseException {
        EnergyConsumer poolPump = consumer("Pool_Pump", Map.of("profile", "simple", "level", "2"));

        assertThat(poolPump.levelGate().orElseThrow().minimumLevel(), is(EnergyLevel.ENCOURAGED));
    }

    @Test
    public void anUndeclaredPriorityFallsBackToTheDocumentedDefault() throws EnergyMetadataParseException {
        assertThat(consumer("Pool_Pump", Map.of("profile", "simple")).priority(),
                is(EnergyMetadataParser.DEFAULT_PRIORITY));
        assertThat(EnergyMetadataParser.DEFAULT_PRIORITY, is(EnergyParticipant.DEFAULT_PRIORITY));
    }

    /**
     * Scenario: Whose charge is reclaimable - the declaration half. A provider carries a priority on the same scale
     * consumers use, so the comparison is against a number the battery declares rather than against an assumption.
     */
    @Test
    public void aProviderCarriesAPriorityOnTheConsumerScale() throws EnergyMetadataParseException {
        assertThat(provider("Battery_Power", Map.of("role", "battery")).priority(),
                is(EnergyMetadataParser.DEFAULT_PRIORITY));
        assertThat(provider("Battery_Power", Map.of("role", "battery", "priority", 40)).priority(), is(40));
    }

    /**
     * Scenario: An unrecognised profile class is not defaulted - the absent half of it.
     * <p>
     * This test used to assert the opposite, under the name {@code anUndeclaredProfileFallsBackToTheDocumentedDefault}
     * and with an explicit assertion that nothing was even reported. An absent class is now a configuration error like
     * an unreadable one, so the assertion is inverted rather than deleted: the case it covers has not gone away, the
     * answer to it has changed.
     */
    @Test
    public void anUndeclaredProfileIsRefusedRatherThanDefaultedToSimple() throws EnergyMetadataParseException {
        EnergyMetadataParseException failure = assertThrows(EnergyMetadataParseException.class,
                () -> EnergyMetadataParser.parse("Pool_Pump", "consumer", Map.of()));

        assertThat(failure.getMessage(), containsString("profile"));
        assertThat("the refusal says what may be written instead", failure.getMessage(),
                containsString("controllable"));
    }

    /**
     * The other half of the same rule, and the reason the two halves have to agree: {@code profil="controllable"} is
     * one keystroke from {@code profile="controllable"} and leaves the key absent, so a parser that refused only what
     * it could read would let exactly the typo it exists to catch through.
     */
    @Test
    public void anUnreadableProfileIsRejectedRatherThanDefaulted() throws EnergyMetadataParseException {
        EnergyMetadataParseException failure = assertThrows(EnergyMetadataParseException.class,
                () -> EnergyMetadataParser.parse("Pool_Pump", "consumer", Map.of("profile", "wobbly")));

        assertThat(failure.getMessage(), containsString("wobbly"));
    }

    @Test
    public void theParticipantIdDefaultsToTheItemNameAndCanBeOverridden() throws EnergyMetadataParseException {
        assertThat(consumer("Pool_Pump", Map.of("profile", "simple")).id(), is("Pool_Pump"));
        assertThat(consumer("Pool_Pump", Map.of("profile", "simple", "id", "pool")).id(), is("pool"));
        assertThat(consumer("Pool_Pump", Map.of("profile", "simple", "id", "pool")).itemName(), is("Pool_Pump"));
    }

    @Test
    public void keysThatDoNotApplyAreReportedButDoNotDropTheDeclaration() throws EnergyMetadataParseException {
        ParsedDeclaration parsed = EnergyMetadataParser.parse("Pool_Pump", "consumer",
                Map.of("profile", "simple", "modes", "a,b", "typo", "1"));

        assertThat(parsed.participant(), instanceOf(EnergyConsumer.class));
        assertThat(parsed.warnings(), hasSize(2));
        assertThat(parsed.warnings(), hasItem(containsString("modes")));
        assertThat(parsed.warnings(), hasItem(containsString("typo")));
    }

    @Test
    public void aLevelOnANonSimpleConsumerIsReported() throws EnergyMetadataParseException {
        ParsedDeclaration parsed = EnergyMetadataParser.parse("Wallbox_Current", "consumer",
                Map.of("profile", "controllable", "min", "6 A", "max", "32 A", "level", "encouraged"));

        assertThat(parsed.warnings(), hasItem(containsString("level")));
        assertThat(((EnergyConsumer) parsed.participant()).levelGate().isEmpty(), is(true));
    }

    @Test
    public void providerKeysReservedForLaterWavesAreReported() throws EnergyMetadataParseException {
        ParsedDeclaration parsed = EnergyMetadataParser.parse("Grid_Power", "provider",
                Map.of("role", "grid", "price", "Grid_Price", "schedule", "Grid_Schedule"));

        assertThat(parsed.participant(), instanceOf(EnergyProvider.class));
        assertThat(parsed.warnings(), hasSize(2));
        assertThat(parsed.warnings(), everyItem(containsString("reserved")));
    }

    @Test
    public void aClampWithoutASetpointItemIsReported() throws EnergyMetadataParseException {
        ParsedDeclaration parsed = EnergyMetadataParser.parse("Battery_Power", "provider",
                Map.of("role", "battery", "min", "-3000", "max", "3000"));

        assertThat(parsed.warnings(), hasItem(containsString("control")));
    }

    /**
     * The declaration surface for the acknowledgement window and tolerance band, on a consumer and on a provider
     * alike: both override the engine's defaults for that participant alone, and both are optional.
     * <p>
     * The band is read as a quantity in the control Item's own dimension, so "0.01 A" stays amps rather than being
     * flattened into the bare-number watts default.
     */
    @Test
    public void acknowledgementTermsAreDeclarable() throws EnergyMetadataParseException {
        EnergyConsumer wallbox = consumer("Wallbox_Current", Map.of("profile", "controllable", "min", "6 A", "max",
                "32 A", "ackWindow", "3 min", "ackTolerance", "0.01 A"));

        assertThat(wallbox.ackWindow(), is(Duration.ofMinutes(3)));
        assertThat(wallbox.ackTolerance(), is(new QuantityType<>("0.01 A")));

        EnergyProvider battery = provider("Battery_Power", Map.of("role", "battery", "control", "Battery_Setpoint",
                "min", "-3000", "max", "3000", "ackWindow", "PT10S"));

        assertThat(battery.ackWindow(), is(Duration.ofSeconds(10)));
        assertThat("declaring one does not imply the other", battery.ackTolerance(), is(nullValue()));

        EnergyConsumer plain = consumer("Boiler_Switch", Map.of("profile", "simple", "onThreshold", "2000"));
        assertThat(plain.ackWindow(), is(nullValue()));
        assertThat(plain.ackTolerance(), is(nullValue()));
    }

    /**
     * The declaration surface for the maximum reading age. It is optional, and its absence does not mean a reading
     * can never be stale - unreadable, {@code UNDEF} and {@code NULL} trip staleness on their own.
     */
    @Test
    public void aMaximumReadingAgeIsDeclarable() throws EnergyMetadataParseException {
        assertThat(provider("Grid_Power", Map.of("role", "grid", "maxAge", "30 s")).maxReadingAge(),
                is(Duration.ofSeconds(30)));
        assertThat(consumer("Boiler_Switch", Map.of("profile", "simple", "onThreshold", "2000", "maxAge", "PT2M"))
                .maxReadingAge(), is(Duration.ofMinutes(2)));
        assertThat(provider("Grid_Power", Map.of("role", "grid")).maxReadingAge(), is(nullValue()));
    }

    /**
     * A declared window or age of zero is refused rather than read as "no declaration": a site that means the
     * default leaves the key out, and one that typed zero has said something it cannot have meant.
     * <p>
     * It surfaces as a parse failure rather than as the model's own {@link IllegalArgumentException}, because the
     * parser turns every model complaint into one - which is what makes a malformed declaration skip the whole
     * participant instead of being half-accepted.
     */
    @Test
    public void aZeroWindowOrAgeIsRefusedRatherThanReadAsAbsent() {
        assertThrows(EnergyMetadataParseException.class, () -> consumer("Boiler_Switch",
                Map.of("profile", "simple", "onThreshold", "2000", "ackWindow", "0 s")));
        assertThrows(EnergyMetadataParseException.class,
                () -> provider("Grid_Power", Map.of("role", "grid", "maxAge", "PT0S")));
    }

    /**
     * Scenario: An inverter that disagrees - the declaration half.
     */
    @Test
    public void aProviderThatCountsTheOtherWayRoundDeclaresTheInvertFlag() throws EnergyMetadataParseException {
        assertThat(provider("Battery_Power", Map.of("role", "battery", "invert", true)).invert(), is(true));
        assertThat(provider("Battery_Power", Map.of("role", "battery", "invert", "true")).invert(), is(true));
        assertThat(provider("Battery_Power", Map.of("role", "battery")).invert(), is(false));
    }

    /**
     * Scenario: Three-phase load, and Scenario: A consumer that declares no phase.
     */
    @Test
    public void aConsumerDeclaresThePhasesItDrawsOn() throws EnergyMetadataParseException {
        assertThat(
                consumer("Wallbox_Current",
                        Map.of("profile", "controllable", "min", "6 A", "max", "32 A", "phases", "1,2,3")).phases(),
                is(Set.of(1, 2, 3)));
        assertThat(consumer("Boiler_Switch", Map.of("profile", "simple", "phases", " 1 ")).phases(), is(Set.of(1)));
        assertThat("declaring none is not declaring all three",
                consumer("Boiler_Switch", Map.of("profile", "simple")).declaresPhases(), is(false));
        assertThrows(EnergyMetadataParseException.class,
                () -> parse("Boiler_Switch", "consumer", Map.of("profile", "simple", "phases", "1,4")));
        assertThrows(EnergyMetadataParseException.class,
                () -> parse("Boiler_Switch", "consumer", Map.of("profile", "simple", "phases", "left")));
    }

    /**
     * A malformed phase declaration is reported and takes only its own participant down; a well-formed declaration
     * on another Item is unaffected.
     * <p>
     * This is the successor of an engine-configuration test. Phases used to be assigned centrally, as
     * {@code phases="wallbox=1,2,3;boiler=left"}, where one bad entry had to be skipped without losing the good ones.
     * They are now declared on the device, so "the others still apply" is no longer a parsing rule the engine needs -
     * it falls out of each Item being read on its own - and what is left to prove is that a bad phase declaration is
     * refused whole rather than half-accepted.
     */
    @Test
    public void aMalformedPhaseDeclarationIsRefusedWholeAndLeavesTheOthersAlone() throws EnergyMetadataParseException {
        EnergyMetadataParseException failure = assertThrows(EnergyMetadataParseException.class,
                () -> parse("Boiler_Switch", "consumer", Map.of("profile", "simple", "phases", "left")));
        assertThat(failure.getMessage(), containsString("left"));

        assertThat("a different Item's declaration is read on its own terms",
                consumer("Wallbox_Current",
                        Map.of("profile", "controllable", "min", "6 A", "max", "32 A", "phases", "1,2,3")).phases(),
                is(Set.of(1, 2, 3)));
    }

    /**
     * Scenario: Per-phase provider reading.
     */
    @Test
    public void aProviderDeclaresAReadingPerPhase() throws EnergyMetadataParseException {
        EnergyProvider grid = provider("Grid_Power",
                Map.of("role", "grid", "phase1", "Grid_L1", "phase2", "Grid_L2", "phase3", "Grid_L3"));

        assertThat(grid.hasPerPhaseReadings(), is(true));
        assertThat(grid.phaseItemName(1).orElseThrow(), is("Grid_L1"));
        assertThat(grid.phaseItemName(3).orElseThrow(), is("Grid_L3"));
        assertThat(provider("Solar_Power", Map.of("role", "pv")).hasPerPhaseReadings(), is(false));
    }

    /**
     * Scenario: Simple consumer without a declared rating, and its opposite. The two power figures are distinct:
     * one decides when to switch on, the other is what the floor books.
     */
    @Test
    public void aSimpleConsumerMayDeclareItsRatedPowerSeparatelyFromItsThreshold() throws EnergyMetadataParseException {
        EnergyConsumer declared = consumer("Boiler_Switch",
                Map.of("profile", "simple", "onThreshold", "1500 W", "ratedPower", "2200 W"));
        SimpleProfile profile = (SimpleProfile) declared.profile();

        assertThat(Objects.requireNonNull(profile.onThreshold()).doubleValue(), is(1500.0));
        assertThat(Objects.requireNonNull(profile.ratedPower()).doubleValue(), is(2200.0));
        assertThat(declared.ratingIsInferred(), is(false));
        assertThat(declared.powerFigure().orElseThrow().doubleValue(), is(2200.0));

        EnergyConsumer inferred = consumer("Boiler_Switch", Map.of("profile", "simple", "onThreshold", 1500));
        assertThat("an existing declaration is not invalidated by the new key", inferred.ratingIsInferred(), is(true));
        assertThat(inferred.powerFigure().orElseThrow().doubleValue(), is(1500.0));
    }

    /**
     * Scenario: Mode change carries no figure - unless the site says otherwise, per mode.
     */
    @Test
    public void aModeConsumerMayDeclareWhatItsModesDraw() throws EnergyMetadataParseException {
        ModeControllableProfile plain = (ModeControllableProfile) consumer("HeatPump_Mode",
                Map.of("profile", "mode", "modes", "blocked,normal,encouraged,forced")).profile();
        assertThat(plain.declaresModeDraws(), is(false));

        ModeControllableProfile declared = (ModeControllableProfile) consumer("HeatPump_Mode", Map.of("profile", "mode",
                "modes", "blocked,normal,encouraged,forced", "modeDraws", "normal=800, forced=2.4 kW")).profile();
        assertThat(declared.drawOf("normal").orElseThrow().doubleValue(), is(800.0));
        assertThat(declared.drawOf("forced").orElseThrow().doubleValue(), is(2400.0));
        assertThat(declared.drawOf("blocked").isEmpty(), is(true));

        assertThrows(EnergyMetadataParseException.class, () -> parse("HeatPump_Mode", "consumer",
                Map.of("profile", "mode", "modes", "off,on", "modeDraws", "turbo=2400")));
        assertThrows(EnergyMetadataParseException.class, () -> parse("HeatPump_Mode", "consumer",
                Map.of("profile", "mode", "modes", "off,on", "modeDraws", "on")));
    }

    /**
     * Scenario: A participant overrides the site sink.
     */
    @Test
    public void aParticipantMayNameTheAdapterItIsWrittenThrough() throws EnergyMetadataParseException {
        assertThat(
                consumer("Wallbox_Current",
                        Map.of("profile", "controllable", "min", "6 A", "max", "32 A", "sink", "ocpp")).sinkId(),
                is("ocpp"));
        assertThat(provider("Battery_Power",
                Map.of("role", "battery", "control", "Battery_Setpoint", "min", -3000, "max", 3000, "sink", "modbus"))
                .sinkId(), is("modbus"));
        assertThat("nothing named means the site-wide sink",
                consumer("Boiler_Switch", Map.of("profile", "simple")).sinkId(), is(nullValue()));
    }

    /**
     * Scenario: A controllable provider without a clamp - the participant is skipped whole rather than accepted with
     * a setpoint the engine could write without bound.
     */
    @Test
    public void aControllableProviderWithoutAClampIsRejected() throws EnergyMetadataParseException {
        assertThrows(EnergyMetadataParseException.class,
                () -> parse("Battery_Power", "provider", Map.of("role", "battery", "control", "Battery_Setpoint")));
        assertThrows(EnergyMetadataParseException.class, () -> parse("Battery_Power", "provider",
                Map.of("role", "battery", "control", "Battery_Setpoint", "min", -3000)));
        assertThat("an uncontrollable provider needs none", provider("Grid_Power", Map.of("role", "grid")).minPower(),
                is(nullValue()));
    }

    /**
     * A provider clamp may be stated in either dimension, exactly like a Controllable consumer's bounds.
     */
    @Test
    public void aProviderClampMayBeDeclaredInAmps() throws EnergyMetadataParseException {
        EnergyProvider battery = provider("Battery_Power",
                Map.of("role", "battery", "control", "Battery_Setpoint", "min", "-16 A", "max", "16 A"));

        assertThat(battery.isCurrentClamped(), is(true));
        assertThat(Objects.requireNonNull(battery.maxPower()).getUnit(), is(Units.AMPERE));
        assertThrows(EnergyMetadataParseException.class, () -> parse("Battery_Power", "provider",
                Map.of("role", "battery", "control", "Battery_Setpoint", "min", "-16 A", "max", "3 kW")));
    }

    @Test
    public void anUnknownDeclarationValueIsRejected() throws EnergyMetadataParseException {
        assertThrows(EnergyMetadataParseException.class, () -> parse("Some_Item", "manager", Map.of()));
        assertThrows(EnergyMetadataParseException.class, () -> parse("Some_Item", "", Map.of()));
    }

    @Test
    public void aProviderWithoutAReadableRoleIsRejected() throws EnergyMetadataParseException {
        assertThrows(EnergyMetadataParseException.class, () -> parse("Grid_Power", "provider", Map.of()));
        assertThrows(EnergyMetadataParseException.class,
                () -> parse("Grid_Power", "provider", Map.of("role", "windmill")));
    }

    @Test
    public void aControllableConsumerWithoutBoundsIsRejected() throws EnergyMetadataParseException {
        assertThrows(EnergyMetadataParseException.class,
                () -> parse("Wallbox_Current", "consumer", Map.of("profile", "controllable", "min", "6 A")));
    }

    @Test
    public void boundsOfDifferentDimensionsAreRejected() throws EnergyMetadataParseException {
        assertThrows(EnergyMetadataParseException.class, () -> parse("Wallbox_Current", "consumer",
                Map.of("profile", "controllable", "min", "6 A", "max", "7000 W")));
    }

    @Test
    public void aModeConsumerWithoutModesIsRejected() throws EnergyMetadataParseException {
        assertThrows(EnergyMetadataParseException.class,
                () -> parse("HeatPump_Mode", "consumer", Map.of("profile", "mode")));
        assertThrows(EnergyMetadataParseException.class,
                () -> parse("HeatPump_Mode", "consumer", Map.of("profile", "mode", "modes", "onlyone")));
    }

    @Test
    public void aBatchConsumerWithoutItsProgramIsRejected() throws EnergyMetadataParseException {
        assertThrows(EnergyMetadataParseException.class,
                () -> parse("Dishwasher_Start", "consumer", Map.of("profile", "batch", "ratedW", 2000)));
    }

    @Test
    public void aHalfDeclaredDemandIsRejected() throws EnergyMetadataParseException {
        assertThrows(EnergyMetadataParseException.class,
                () -> parse("Boiler_Switch", "consumer", Map.of("profile", "simple", "demandKwh", 4)));
        assertThrows(EnergyMetadataParseException.class,
                () -> parse("Boiler_Switch", "consumer", Map.of("profile", "simple", "deadlineHour", 7)));
        assertThrows(EnergyMetadataParseException.class, () -> parse("Boiler_Switch", "consumer",
                Map.of("profile", "simple", "demandKwh", 4, "deadlineHour", 24)));
    }

    @Test
    public void unreadableNumbersDurationsAndQuantitiesAreRejected() throws EnergyMetadataParseException {
        assertThrows(EnergyMetadataParseException.class,
                () -> parse("Pool_Pump", "consumer", Map.of("profile", "simple", "priority", "soon")));
        assertThrows(EnergyMetadataParseException.class,
                () -> parse("Pool_Pump", "consumer", Map.of("profile", "simple", "minOn", "45")));
        assertThrows(EnergyMetadataParseException.class,
                () -> parse("Pool_Pump", "consumer", Map.of("profile", "simple", "onThreshold", "quite a lot")));
        assertThrows(EnergyMetadataParseException.class,
                () -> parse("Pool_Pump", "consumer", Map.of("profile", "simple", "onThreshold", "20 kWh")));
    }

    @Test
    public void inconsistentProtectionTimesAreRejected() throws EnergyMetadataParseException {
        assertThrows(EnergyMetadataParseException.class,
                () -> parse("Fridge_Switch", "consumer", Map.of("profile", "simple", "minOn", "2 h", "maxOn", "10 m")));
    }
}
