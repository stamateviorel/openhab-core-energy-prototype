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
package org.openhab.core.energy.forecast.store.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * The bound on what this bundle writes, asserted rather than promised - and the direction of the dependency between it
 * and the framework.
 * <p>
 * "May write" is a licence that grows if nobody writes the limits down. Its neighbour {@code org.openhab.core.energy}
 * proves it writes nothing with five structural tests; the honest counterpart here is to prove that what this one
 * writes is history and one named Item's time series, and never a command or a state update that would move a device.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class WriteBoundTest {

    /**
     * Returns a source file with its comments removed, so the scan judges the code rather than the prose about it.
     *
     * @param file the source file
     * @return the file's code, comments blanked out
     * @throws IOException if the file cannot be read
     */
    private static String codeOf(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$",
                " ");
    }

    private static List<Path> mainSourcesOf(Path bundle) throws IOException {
        Path main = bundle.resolve(Path.of("src", "main", "java"));
        assertThat("the source tree must be where this test expects it", Files.isDirectory(main), is(true));
        try (Stream<Path> sources = Files.walk(main)) {
            return sources.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }

    /**
     * Nothing in this bundle can command an Item or update its state. A time series is neither: it carries values for
     * other moments in time, and what an Item currently reads is untouched by it.
     *
     * @throws IOException if the sources cannot be read
     */
    @Test
    public void nothingInThisBundleCommandsAnItemOrChangesItsCurrentState() throws IOException {
        List<String> offences = new ArrayList<>();
        for (Path file : mainSourcesOf(Path.of(""))) {
            String code = codeOf(file);
            for (String forbidden : List.of("sendCommand", "postUpdate", "ItemCommandEvent", "ItemStateEvent",
                    "ItemStateUpdatedEvent", "setState(")) {
                if (code.contains(forbidden)) {
                    offences.add(file.getFileName() + " calls " + forbidden);
                }
            }
        }

        assertThat(offences, is(empty()));
    }

    /**
     * Exactly one class may publish anything, and exactly one may store anything - so acquiring a second writer is a
     * failing test rather than a quiet commit.
     *
     * @throws IOException if the sources cannot be read
     */
    @Test
    public void theWritingIsConfinedToTwoClasses() throws IOException {
        List<String> publishing = new ArrayList<>();
        List<String> storing = new ArrayList<>();
        for (Path file : mainSourcesOf(Path.of(""))) {
            String code = codeOf(file);
            if (code.contains(".post(")) {
                publishing.add(file.getFileName().toString());
            }
            if (code.contains(".store(")) {
                storing.add(file.getFileName().toString());
            }
        }

        assertThat(publishing, contains("DerivedHeatingDemandSource.java"));
        assertThat(storing, contains("PersistenceLayeredStore.java"));
    }

    /**
     * <strong>The dependency runs one way.</strong> The framework bundle does not know this one exists: nothing in its
     * sources names this bundle's package, so a checkout with this directory deleted still builds and still passes its
     * own tests. That is what makes this a genuinely optional feature rather than a split of one bundle into two.
     *
     * @throws IOException if the sources cannot be read
     */
    @Test
    public void theFrameworkBundleDoesNotKnowThisOneExists() throws IOException {
        Path framework = Path.of("..", "org.openhab.core.energy");
        assertThat("the framework bundle must be beside this one", Files.isDirectory(framework), is(true));

        List<String> referring = new ArrayList<>();
        for (Path file : mainSourcesOf(framework)) {
            if (codeOf(file).contains("org.openhab.core.energy.forecast.store")) {
                referring.add(file.getFileName().toString());
            }
        }

        assertThat(referring, is(empty()));
    }

    /**
     * The framework's own no-write invariant is not weakened by this bundle existing: the tokens this bundle is built
     * on are still absent from every source next door.
     *
     * @throws IOException if the sources cannot be read
     */
    @Test
    public void theFrameworkBundleStillNamesNoPersistenceCall() throws IOException {
        List<String> offences = new ArrayList<>();
        for (Path file : mainSourcesOf(Path.of("..", "org.openhab.core.energy"))) {
            String code = codeOf(file).replace("PersistenceServiceConfigurationRegistry", "")
                    .replace("PersistenceServiceConfiguration", "");
            for (String forbidden : List.of(".store(", ".query(", "FilterCriteria", "HistoricItem",
                    "ModifiablePersistenceService")) {
                if (code.contains(forbidden)) {
                    offences.add(file.getFileName() + " uses " + forbidden);
                }
            }
        }

        assertThat(offences, is(empty()));
    }
}
