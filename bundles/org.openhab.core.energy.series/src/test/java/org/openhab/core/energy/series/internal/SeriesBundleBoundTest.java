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
package org.openhab.core.energy.series.internal;

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
 * This bundle's own write bound, stated as tightly as {@code org.openhab.core.energy.publish} states its two Items,
 * and asserted the same way the engine bundle's invariant is - by scanning comment-stripped sources.
 * <p>
 * <strong>The bound.</strong> This bundle <em>reads</em> a persisted future series and nothing else. It does not
 * command an Item, does not update an Item's state, does not mutate the Item registry, and does not store anything
 * through a persistence service. The price plane needed the reading half of the Item edge and no more, so the write
 * half is not here to be misused later; when the forecast plane's layered predictions arrive they will add a
 * {@code ModifiablePersistenceService} writer, and <em>that</em> is the moment to widen this test deliberately, with
 * the argument written next to it, rather than to find it already widened.
 * <p>
 * <strong>Why this bundle exists at all</strong> is the mirror of the same rule: the engine bundle is structurally
 * incapable of a persistence query - {@code .query(}, {@code FilterCriteria}, {@code HistoricItem} and
 * {@code PersistenceService} are forbidden tokens there - so the one Item-touching half of the core-shipped
 * grid-price provider has to live somewhere else. The dependency runs one way only, which the last test asserts.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class SeriesBundleBoundTest {

    /**
     * Returns a source file with its comments removed, so the scan judges the code rather than the prose about it -
     * this very class names several of the tokens it forbids.
     *
     * @param file the source file
     * @return the file's code, comments blanked out
     * @throws IOException if the file cannot be read
     */
    private static String codeOf(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$",
                " ");
    }

    private static List<Path> mainSources() throws IOException {
        Path main = Path.of("src", "main", "java");
        assertThat("the source tree must be where this test expects it", Files.isDirectory(main), is(true));
        try (Stream<Path> sources = Files.walk(main)) {
            return sources.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }

    /**
     * Nothing in this bundle writes to an Item, by any of the routes the engine bundle's own witness closes.
     *
     * @throws IOException if the sources cannot be read
     */
    @Test
    public void thisBundleWritesToNoItemEither() throws IOException {
        List<String> offences = new ArrayList<>();
        for (Path file : mainSources()) {
            String code = codeOf(file);
            for (String forbidden : List.of("sendCommand", "postUpdate", "ItemEventFactory", "ItemCommandEvent",
                    "ItemStateEvent", "ItemStateUpdatedEvent", "ItemTimeSeriesEvent", "setState(", "EventPublisher",
                    "ItemRegistry")) {
                if (code.contains(forbidden)) {
                    offences.add(file.getFileName() + " uses " + forbidden);
                }
            }
        }

        assertThat(offences, is(empty()));
    }

    /**
     * Persistence is read and never written. The price plane needs {@code query} and nothing else; the writer that
     * the forecast plane's layered predictions need is a deliberate later widening of this list, not an accident.
     *
     * @throws IOException if the sources cannot be read
     */
    @Test
    public void persistenceIsQueriedAndNeverStoredTo() throws IOException {
        List<String> querying = new ArrayList<>();
        List<String> offences = new ArrayList<>();
        for (Path file : mainSources()) {
            String code = codeOf(file);
            if (code.contains(".query(")) {
                querying.add(file.getFileName().toString());
            }
            for (String forbidden : List.of(".store(", "ModifiablePersistenceService", ".remove(")) {
                if (code.contains(forbidden)) {
                    offences.add(file.getFileName() + " uses " + forbidden);
                }
            }
        }

        assertThat("the reader is the only thing that queries", querying, contains("ItemPriceSeriesReader.java"));
        assertThat(offences, is(empty()));
    }

    /**
     * The dependency runs one way. The engine bundle must not name this one, or "install energy management" would
     * silently install something that reads a site's persistence.
     *
     * @throws IOException if the poms cannot be read
     */
    @Test
    public void theEngineBundleDoesNotDependOnThisOne() throws IOException {
        Path enginePom = Path.of("..", "org.openhab.core.energy", "pom.xml");
        assertThat("the engine bundle must be where this test expects it", Files.isRegularFile(enginePom), is(true));

        String pom = Files.readString(enginePom, StandardCharsets.UTF_8);

        assertThat(pom, not(containsString("org.openhab.core.energy.series")));
        assertThat("and this bundle does depend on the engine",
                Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8),
                containsString("<artifactId>org.openhab.core.energy</artifactId>"));
    }
}
