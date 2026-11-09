package au.edu.sydney.research.keycloak.researchid;

import au.edu.sydney.research.keycloak.researchid.jpa.JpaReservationStore;
import java.security.SecureRandom;
import java.util.List;
import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

public final class EnsureResearchIdAuthenticatorFactory implements AuthenticatorFactory {

    public static final String PROVIDER_ID = "research-id-ensure";

    /** Ids must not be guessable from one another, so a cryptographic generator. Thread-safe. */
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Requirement[] REQUIREMENTS = {Requirement.REQUIRED, Requirement.DISABLED};

    @Override
    public Authenticator create(KeycloakSession session) {
        ReservationStore store = new JpaReservationStore(session.getKeycloakSessionFactory());
        return new EnsureResearchIdAuthenticator(
                new ResearchIdMinter(store, () -> ResearchId.generate(RANDOM)));
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "Research id";
    }

    @Override
    public String getHelpText() {
        return "Gives the user a research id (user attribute research_id) if they don't have one."
                + " Ids are random, never reused, and unique across Keycloak replicas.";
    }

    @Override
    public String getReferenceCategory() {
        return "research-id";
    }

    @Override
    public boolean isConfigurable() {
        return false;
    }

    @Override
    public Requirement[] getRequirementChoices() {
        return REQUIREMENTS;
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of();
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
