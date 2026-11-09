package au.edu.sydney.research.keycloak.researchid;

import java.util.Optional;

/**
 * Where research ids are reserved. Each id is reserved at most once, ever, and each user holds at
 * most one reservation per realm. Both rules are enforced by the store itself (a database
 * constraint), so they hold across any number of Keycloak replicas.
 */
public interface ReservationStore {

    /** The research id reserved for this user, if any. */
    Optional<ResearchId> findByUser(String realmId, String userId);

    /**
     * Reserves {@code id} for the user, committed before this returns.
     *
     * @return {@code false} if the id is already reserved, or the user already holds a reservation
     */
    boolean tryReserve(String realmId, String userId, ResearchId id);
}
