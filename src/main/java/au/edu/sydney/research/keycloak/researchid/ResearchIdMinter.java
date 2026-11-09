package au.edu.sydney.research.keycloak.researchid;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Gives a user their research id, safely when several Keycloak replicas sign in the same or
 * different people at once.
 *
 * <p>No locks: a random candidate is reserved through the {@link ReservationStore}, whose
 * constraints reject a taken id or a second reservation for the same user. On rejection it looks
 * again: either the user now has an id (another replica won the race for them) or it tries a new
 * candidate.
 */
public final class ResearchIdMinter {

    /**
     * Upper bound on attempts. With 10 million ids, a clash is rare until most are used, so hitting
     * this means something is wrong (for example, the id space is nearly exhausted).
     */
    static final int MAX_ATTEMPTS = 20;

    private final ReservationStore store;
    private final Supplier<ResearchId> candidates;

    public ResearchIdMinter(ReservationStore store, Supplier<ResearchId> candidates) {
        this.store = store;
        this.candidates = candidates;
    }

    /** The user's research id: the one already reserved for them, or a newly reserved one. */
    public ResearchId ensure(String realmId, String userId) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Optional<ResearchId> existing = store.findByUser(realmId, userId);
            if (existing.isPresent()) {
                return existing.get();
            }
            ResearchId candidate = candidates.get();
            if (store.tryReserve(realmId, userId, candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "No research id could be reserved after " + MAX_ATTEMPTS + " attempts");
    }

    /**
     * Confirms an id already on the account (set by an admin, or imported) belongs to this user,
     * reserving it for them if nobody holds it.
     *
     * @return {@code false} if the id is reserved for someone else, or the user holds a different id
     */
    public boolean confirm(String realmId, String userId, ResearchId id) {
        Optional<ResearchId> existing = store.findByUser(realmId, userId);
        if (existing.isPresent()) {
            return existing.get().equals(id);
        }
        if (store.tryReserve(realmId, userId, id)) {
            return true;
        }
        // Rejected: either someone else holds the id, or another replica just reserved it for us.
        return store.findByUser(realmId, userId).map(id::equals).orElse(false);
    }
}
