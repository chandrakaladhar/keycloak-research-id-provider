package au.edu.sydney.research.keycloak.researchid.jpa;

import org.keycloak.Config;
import org.keycloak.connections.jpa.entityprovider.JpaEntityProvider;
import org.keycloak.connections.jpa.entityprovider.JpaEntityProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

public final class ResearchIdEntityProviderFactory implements JpaEntityProviderFactory {

    static final String PROVIDER_ID = "research-id";

    private static final ResearchIdEntityProvider PROVIDER = new ResearchIdEntityProvider();

    @Override
    public JpaEntityProvider create(KeycloakSession session) {
        return PROVIDER;
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public void init(Config.Scope config) {
        // No configuration.
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Nothing to do.
    }

    @Override
    public void close() {
        // Nothing to release.
    }
}
