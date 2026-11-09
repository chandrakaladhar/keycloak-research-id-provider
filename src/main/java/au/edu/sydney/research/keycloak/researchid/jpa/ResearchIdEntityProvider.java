package au.edu.sydney.research.keycloak.researchid.jpa;

import java.util.List;
import org.keycloak.connections.jpa.entityprovider.JpaEntityProvider;

/** Registers the reservation table with Keycloak, which creates and upgrades it with Liquibase. */
public final class ResearchIdEntityProvider implements JpaEntityProvider {

    @Override
    public List<Class<?>> getEntities() {
        return List.of(ResearchIdReservationEntity.class);
    }

    @Override
    public String getChangelogLocation() {
        return "META-INF/research-id-changelog.xml";
    }

    @Override
    public String getFactoryId() {
        return ResearchIdEntityProviderFactory.PROVIDER_ID;
    }

    @Override
    public void close() {
        // Nothing to release.
    }
}
