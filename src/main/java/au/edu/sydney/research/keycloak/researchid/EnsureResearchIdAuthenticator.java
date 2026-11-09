package au.edu.sydney.research.keycloak.researchid;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

/**
 * A sign-in step that makes sure the user has a research id, in the user attribute {@value
 * #ATTRIBUTE}.
 *
 * <ul>
 *   <li><b>No id yet:</b> mints one, or restores the one already reserved for them (if the
 *       attribute was removed), and sets the attribute.
 *   <li><b>Has an id:</b> confirms it's reserved for this user, reserving it if nobody holds it
 *       (an id set by an admin or imported). It's never replaced.
 *   <li><b>Several copies of the same id</b> (left by simultaneous first sign-ins): collapses them
 *       to one.
 *   <li><b>Malformed, different ids, or reserved for someone else:</b> fails the sign-in rather
 *       than issue a token with a wrong id.
 * </ul>
 *
 * <p>Put it in each identity provider's post-login flow (runs on every brokered sign-in, after a
 * first broker login has created the user), and in any other flow people sign in through.
 */
public final class EnsureResearchIdAuthenticator implements Authenticator {

    /** The user attribute, and the claim name the token mapper should use. */
    public static final String ATTRIBUTE = "research_id";

    private static final Logger LOG = Logger.getLogger(EnsureResearchIdAuthenticator.class);

    private final ResearchIdMinter minter;

    EnsureResearchIdAuthenticator(ResearchIdMinter minter) {
        this.minter = minter;
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        RealmModel realm = context.getRealm();
        UserModel user = context.getUser();
        List<String> values = user.getAttributeStream(ATTRIBUTE)
                .filter(value -> value != null && !value.isBlank())
                .toList();

        if (values.isEmpty()) {
            ResearchId id = minter.ensure(realm.getId(), user.getId());
            user.setSingleAttribute(ATTRIBUTE, id.value());
            context.getEvent().detail(ATTRIBUTE, id.value());
            LOG.infof("Research id %s set for user %s in realm %s",
                    id, user.getId(), realm.getName());
            context.success();
            return;
        }

        // Several values that read as the same id are tolerated (see below); different ids aren't.
        Set<Optional<ResearchId>> distinct = values.stream()
                .map(ResearchId::parse)
                .collect(Collectors.toSet());
        Optional<ResearchId> parsed = distinct.size() == 1
                ? distinct.iterator().next()
                : Optional.empty();
        if (parsed.isEmpty() || !minter.confirm(realm.getId(), user.getId(), parsed.get())) {
            // The value isn't logged: it may be anything an admin typed.
            LOG.errorf("User %s in realm %s has a research id that is malformed, or reserved for"
                    + " another account; sign-in refused", user.getId(), realm.getName());
            context.failure(AuthenticationFlowError.INTERNAL_ERROR);
            return;
        }
        if (values.size() > 1 || !values.get(0).equals(parsed.get().value())) {
            // Store exactly one value, in the standard form. Fixes an id typed by an admin in
            // another form (RC-4821-0937), and copies left by simultaneous first sign-ins of the
            // same person: Keycloak's attribute table has no unique constraint, so each request
            // that set the attribute may have added its own row.
            user.setSingleAttribute(ATTRIBUTE, parsed.get().value());
        }
        context.success();
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        // No form: everything happens in authenticate().
    }

    @Override
    public boolean requiresUser() {
        return true;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        // None.
    }

    @Override
    public void close() {
        // Nothing to release.
    }
}
