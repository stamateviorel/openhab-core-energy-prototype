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

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.common.registry.AbstractRegistry;
import org.openhab.core.common.registry.Provider;
import org.openhab.core.config.core.ConfigurableService;
import org.openhab.core.energy.EnergyConsumer;
import org.openhab.core.energy.EnergyParticipant;
import org.openhab.core.energy.EnergyProvider;
import org.openhab.core.energy.spi.EnergyParticipantRegistry;
import org.openhab.core.energy.spi.EnergyParticipantSource;
import org.openhab.core.energy.spi.ParticipantDeclaration;
import org.osgi.framework.Constants;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aggregates every {@link EnergyParticipantSource} into one view of the site's participants.
 * <p>
 * Sources are tracked as OSGi services by {@link AbstractRegistry}, which is what makes runtime contribution work:
 * installing an add-on adds its declarations, uninstalling it removes them, and neither requires a core change or a
 * restart. Declarations are keyed by {@code sourceId + participantId} rather than by participant id alone, so two
 * sources declaring the same participant coexist in the registry instead of one of them being dropped on a
 * first-come basis - the resolution then happens deliberately, in {@link #resolve()}.
 *
 * <h2>One participant, several statements</h2>
 * A second declaration of an identity already present is <strong>a further statement about that participant, never
 * a second participant and never an error</strong>. That is why the registry key is {@code sourceId + participantId}:
 * every statement is kept, and {@link #resolve()} decides which one is effective on one fixed precedence chain,
 * <strong>explicit {@code energy:} metadata over contributed over discovered, ties between contributed statements
 * broken by {@code service.ranking}</strong>, with the source id as a final total tie-break so that the outcome
 * never depends on the order in which sources registered. A contributor that leaves and comes back therefore
 * restores the outcome it had before, and nothing is rejected or reported as a conflict on the way.
 * <p>
 * The chain is <strong>not</strong> configurable. It used to be - the prototype carried a {@code precedence}
 * parameter so a maintainer could try each answer - and collapsing it is a deliberate reduction in flexibility: a
 * site can no longer decide that a contributed declaration outranks its own metadata.
 *
 * <h2>The chain does not promote past a broken link</h2>
 * A source that has a declaration for an identity and <strong>cannot read it</strong> reports that identity through
 * {@link EnergyParticipantSource#getBlockedParticipants()} rather than simply going quiet, and
 * {@link #applyBlocks(Map)} then leaves the participant out of the resolved view entirely. Source: owner decision
 * <strong>D26</strong> (2026-08-03, {@code openhab-ems-spec/docs/OWNER_DECISIONS.md}). Without it, a typo in a
 * user's own metadata withdraws the explicit statement and promotes the add-on's contribution for the same identity:
 * the device stays managed, on terms its owner never chose. A block reaches only statements that are not strictly
 * more authoritative than it, so an add-on that cannot read its own declaration can never disable a site's.
 *
 * <h2>Configuration ({@value #CONFIGURATION_PID})</h2>
 * <dl>
 * <dt>{@value #CONFIG_SOURCES}</dt>
 * <dd>Comma-separated source ids to use, or empty for all of them. This is the per-role selection every
 * contribution kind gets - which sources take part at all - not a way to reorder the precedence chain.</dd>
 * </dl>
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@Component(immediate = true, service = EnergyParticipantRegistry.class, configurationPid = EnergyParticipantRegistryImpl.CONFIGURATION_PID, configurationPolicy = ConfigurationPolicy.OPTIONAL, property = Constants.SERVICE_PID
        + "=org.openhab.core.energy.declaration")
@ConfigurableService(category = "system", label = "Energy Management Declarations", description_uri = EnergyParticipantRegistryImpl.CONFIG_URI)
public class EnergyParticipantRegistryImpl extends
        AbstractRegistry<ParticipantDeclaration, String, EnergyParticipantSource> implements EnergyParticipantRegistry {

    /**
     * The configuration pid carrying the declaration-plane settings.
     */
    public static final String CONFIGURATION_PID = "org.openhab.core.energy.declaration";

    /**
     * The URI of the configuration description that renders the declaration parameters in the UI.
     */
    public static final String CONFIG_URI = "system:energy-declaration";

    /**
     * The configuration key listing the source ids to use.
     */
    public static final String CONFIG_SOURCES = "sources";

    private final Logger logger = LoggerFactory.getLogger(EnergyParticipantRegistryImpl.class);

    /**
     * The fixed precedence chain: the more authoritative origin first, then the higher {@code service.ranking},
     * then the source id so that the order is total and stateless.
     */
    private static final Comparator<ParticipantDeclaration> BY_AUTHORITY = Comparator
            .comparingInt((ParticipantDeclaration declaration) -> declaration.origin().rank()).reversed()
            .thenComparing(Comparator.comparingInt(ParticipantDeclaration::serviceRanking).reversed())
            .thenComparing(ParticipantDeclaration::sourceId);

    /**
     * The sources themselves, kept because a block is a statement a source makes <em>without</em> a declaration to
     * carry it, and {@link AbstractRegistry} hands a registry only the elements. Read on every {@link #resolve()},
     * which is recomputed on every read anyway, so a block that appears or lifts needs no event of its own.
     */
    private final Set<EnergyParticipantSource> sources = ConcurrentHashMap.newKeySet();

    private volatile List<String> enabledSources = List.of();

    /**
     * Creates the registry.
     *
     * @param configuration the component configuration
     */
    @Activate
    public EnergyParticipantRegistryImpl(Map<String, @Nullable Object> configuration) {
        super(EnergyParticipantSource.class);
        applyConfiguration(configuration);
    }

    /**
     * Starts tracking {@link EnergyParticipantSource} providers once OSGi has activated the component.
     *
     * @param componentContext the component context supplying the bundle context
     */
    @Activate
    protected void activate(ComponentContext componentContext) {
        super.activate(componentContext.getBundleContext());
    }

    @Override
    @Deactivate
    protected void deactivate() {
        super.deactivate();
    }

    /**
     * Applies a changed configuration; the next read of the registry uses it.
     *
     * @param configuration the component configuration
     */
    @Modified
    protected void modified(Map<String, @Nullable Object> configuration) {
        applyConfiguration(configuration);
    }

    @Override
    public Collection<EnergyParticipant> getParticipants() {
        return resolve().values().stream().map(ParticipantDeclaration::participant).toList();
    }

    @Override
    public @Nullable EnergyParticipant getParticipant(String participantId) {
        ParticipantDeclaration declaration = getEffectiveDeclaration(participantId);
        return declaration == null ? null : declaration.participant();
    }

    @Override
    public Collection<EnergyProvider> getProviders() {
        return getParticipants().stream().filter(EnergyProvider.class::isInstance).map(EnergyProvider.class::cast)
                .toList();
    }

    @Override
    public List<EnergyConsumer> getConsumers() {
        return getParticipants().stream().filter(EnergyConsumer.class::isInstance).map(EnergyConsumer.class::cast)
                .sorted(EnergyConsumer.PRIORITY_ORDER).toList();
    }

    @Override
    public @Nullable ParticipantDeclaration getEffectiveDeclaration(String participantId) {
        return resolve().get(participantId);
    }

    @Override
    public List<ParticipantDeclaration> getDeclarations(String participantId) {
        return getAll().stream().filter(declaration -> declaration.participantId().equals(participantId))
                .sorted(BY_AUTHORITY).toList();
    }

    @Override
    protected void addProvider(Provider<ParticipantDeclaration> provider) {
        // overridden to make the method available for testing, and to keep sight of the source behind the provider
        if (provider instanceof EnergyParticipantSource source) {
            sources.add(source);
        }
        super.addProvider(provider);
    }

    @Override
    protected void removeProvider(Provider<ParticipantDeclaration> provider) {
        // overridden to make the method available for testing
        if (provider instanceof EnergyParticipantSource source) {
            sources.remove(source);
        }
        super.removeProvider(provider);
    }

    /**
     * Resolves the declarations into at most one per participant id.
     * <p>
     * Recomputed on every read rather than cached: a cache would have to be invalidated on every declaration event
     * and on every configuration change, and the engine reads this once per evaluation cycle over a participant
     * count measured in tens.
     *
     * @return the effective declaration per participant id
     */
    private Map<String, ParticipantDeclaration> resolve() {
        Map<String, ParticipantDeclaration> effective = new LinkedHashMap<>();
        for (ParticipantDeclaration declaration : getAll()) {
            if (!isEnabled(declaration.sourceId())) {
                continue;
            }
            ParticipantDeclaration incumbent = effective.get(declaration.participantId());
            if (incumbent == null) {
                effective.put(declaration.participantId(), declaration);
            } else if (BY_AUTHORITY.compare(declaration, incumbent) < 0) {
                effective.put(declaration.participantId(), declaration);
                logger.debug("Participant '{}' is taken from source '{}', shadowing source '{}'",
                        declaration.participantId(), declaration.sourceId(), incumbent.sourceId());
            } else {
                logger.debug("Participant '{}' stays with source '{}', shadowing source '{}'",
                        declaration.participantId(), incumbent.sourceId(), declaration.sourceId());
            }
        }
        applyBlocks(effective);
        return effective;
    }

    /**
     * Removes every identity a source has a declaration for and cannot read, unless the statement that survived
     * precedence is strictly more authoritative than the block.
     * <p>
     * Source: owner decision <strong>D26</strong> (2026-08-03, {@code openhab-ems-spec/docs/OWNER_DECISIONS.md}).
     * The rule this replaces is the one the wave-1 slice shipped: a malformed declaration was simply withdrawn, and
     * the next statement down the chain became effective by itself. That is a silent transfer of control - the
     * device carries on being steered, on terms its owner never chose, because of one keystroke - and nothing about
     * its behaviour says which declaration is in force. Blocking makes a typo degrade to "nothing happens", which is
     * diagnosable, instead of to "something else happens", which is not.
     * <p>
     * The comparison is on <em>authority</em> alone - origin first, then {@code service.ranking} - and deliberately
     * not on the source id that makes {@link #BY_AUTHORITY} total: an alphabetical tie-break decides which of two
     * equally authoritative statements is effective, and has no business deciding whether a device is steered at
     * all. Two equally authoritative statements, one of them unreadable, resolve to "nothing happens".
     *
     * @param effective the resolved declarations, edited in place
     */
    private void applyBlocks(Map<String, ParticipantDeclaration> effective) {
        for (EnergyParticipantSource source : sources) {
            if (!isEnabled(source.getSourceId())) {
                continue;
            }
            for (String participantId : source.getBlockedParticipants()) {
                ParticipantDeclaration incumbent = effective.get(participantId);
                if (incumbent == null || outranks(incumbent, source)) {
                    continue;
                }
                effective.remove(participantId);
                logger.debug(
                        "Participant '{}' is blocked: source '{}' has a declaration for it that it cannot read, and "
                                + "no lower-ranked statement takes its place",
                        participantId, source.getSourceId());
            }
        }
    }

    /**
     * Tells whether a declaration is strictly more authoritative than a source, and therefore survives that source's
     * block.
     *
     * @param declaration the statement that survived precedence
     * @param source the source holding the block
     * @return {@code true} if the declaration outranks the block
     */
    private static boolean outranks(ParticipantDeclaration declaration, EnergyParticipantSource source) {
        int byOrigin = Integer.compare(declaration.origin().rank(), source.getOrigin().rank());
        return byOrigin != 0 ? byOrigin > 0 : declaration.serviceRanking() > source.getServiceRanking();
    }

    private boolean isEnabled(String sourceId) {
        return enabledSources.isEmpty() || enabledSources.contains(sourceId);
    }

    private void applyConfiguration(Map<String, @Nullable Object> configuration) {
        enabledSources = ConfigLists.toList(configuration.get(CONFIG_SOURCES));
        logger.debug("Energy declaration sources: {}", enabledSources.isEmpty() ? "all" : enabledSources);
    }
}
