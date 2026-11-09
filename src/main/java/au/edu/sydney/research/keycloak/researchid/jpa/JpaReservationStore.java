package au.edu.sydney.research.keycloak.researchid.jpa;

import au.edu.sydney.research.keycloak.researchid.ReservationStore;
import au.edu.sydney.research.keycloak.researchid.ResearchId;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityManager;
import java.sql.SQLException;
import java.util.Optional;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.utils.KeycloakModelUtils;

/**
 * Reservations in Keycloak's own database.
 *
 * <p>Every call runs in its own short transaction, separate from the sign-in's transaction
 * (Keycloak suspends the outer one). That matters for two reasons:
 *
 * <ul>
 *   <li>The reservation is committed straight away, so other replicas see it, rather than at the
 *       end of the sign-in request.
 *   <li>A rejected insert rolls back only its own transaction, not the sign-in.
 * </ul>
 *
 * <p>Postgres checks the primary key and unique constraint at insert time, and makes a competing
 * insert of the same key wait for the first to commit, so two replicas can never both succeed.
 */
public final class JpaReservationStore implements ReservationStore {

    /** SQLSTATE class 23: integrity constraint violation (unique, primary key). */
    private static final String INTEGRITY_VIOLATION_CLASS = "23";

    private final KeycloakSessionFactory sessionFactory;

    public JpaReservationStore(KeycloakSessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    @Override
    public Optional<ResearchId> findByUser(String realmId, String userId) {
        return KeycloakModelUtils.runJobInTransactionWithResult(
                sessionFactory,
                session -> entityManager(session)
                        .createNamedQuery(
                                ResearchIdReservationEntity.FIND_BY_USER,
                                ResearchIdReservationEntity.class)
                        .setParameter("realmId", realmId)
                        .setParameter("userId", userId)
                        .getResultStream()
                        .findFirst()
                        .map(entity -> ResearchId.parse(entity.getResearchId())
                                .orElseThrow(() -> new IllegalStateException(
                                        "Malformed research id in RESEARCH_ID_RESERVATION for user "
                                                + userId))));
    }

    @Override
    public boolean tryReserve(String realmId, String userId, ResearchId id) {
        try {
            KeycloakModelUtils.runJobInTransaction(sessionFactory, session -> {
                EntityManager em = entityManager(session);
                em.persist(new ResearchIdReservationEntity(
                        id.value(), realmId, userId, System.currentTimeMillis()));
                em.flush();
            });
            return true;
        } catch (RuntimeException e) {
            if (isConstraintViolation(e)) {
                return false;
            }
            throw e;
        }
    }

    private static EntityManager entityManager(KeycloakSession session) {
        return session.getProvider(JpaConnectionProvider.class).getEntityManager();
    }

    /** True if the failure, or anything that caused it, is a unique or primary key violation. */
    static boolean isConstraintViolation(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof EntityExistsException) {
                return true;
            }
            if (t instanceof SQLException sql
                    && sql.getSQLState() != null
                    && sql.getSQLState().startsWith(INTEGRITY_VIOLATION_CLASS)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
