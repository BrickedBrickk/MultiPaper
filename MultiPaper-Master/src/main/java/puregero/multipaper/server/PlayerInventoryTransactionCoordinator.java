package puregero.multipaper.server;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory state machine for a two-player inventory transaction.
 *
 * <p>This class is deliberately not a persistence mechanism and is not wired to
 * the InvSee++ click path. Callers may record a commit only after a durable
 * Master decision exists. Uncertain outcomes must keep their reservations until
 * that decision is resolved; this class never expires a reservation by time.</p>
 */
public final class PlayerInventoryTransactionCoordinator {
    private final Map<UUID, Transaction> transactions = new HashMap<>();
    private final Map<UUID, UUID> reservations = new HashMap<>();

    public synchronized BeginResult begin(TransactionRequest request) {
        Objects.requireNonNull(request, "request");
        Transaction existing = transactions.get(request.transactionId());
        if (existing != null) {
            return existing.request.sameAs(request)
                    ? new BeginResult(BeginStatus.REPLAY, existing.status, null)
                    : new BeginResult(BeginStatus.ID_CONFLICT, existing.status, null);
        }

        UUID reservedBy = reservations.get(request.editor().playerId());
        if (reservedBy == null) {
            reservedBy = reservations.get(request.target().playerId());
        }
        if (reservedBy != null) {
            return new BeginResult(BeginStatus.BUSY, transactions.get(reservedBy).status, reservedBy);
        }

        Transaction transaction = new Transaction(request);
        transactions.put(request.transactionId(), transaction);
        reservations.put(request.editor().playerId(), request.transactionId());
        reservations.put(request.target().playerId(), request.transactionId());
        return new BeginResult(BeginStatus.STARTED, transaction.status, null);
    }

    /** Records an exact live-owner before-image attestation. A mismatch stays reserved. */
    public synchronized AttestationStatus attest(
            UUID transactionId, UUID playerId, UUID sessionId, long revision, byte[] snapshotDigest) {
        Transaction transaction = requireTransaction(transactionId);
        PlayerSnapshot expected = transaction.request.snapshotFor(playerId);
        if (expected == null) {
            return AttestationStatus.NOT_A_PARTICIPANT;
        }

        boolean exact = expected.sessionId.equals(sessionId)
                && expected.revision == revision
                && Arrays.equals(expected.snapshotDigest, snapshotDigest);
        if (!exact) {
            return AttestationStatus.STALE;
        }

        if (transaction.status != Status.PREPARING) {
            if (transaction.status == Status.ABORTED) {
                return AttestationStatus.TERMINAL;
            }
            return transaction.attested.contains(playerId)
                    ? AttestationStatus.ALREADY_ACCEPTED
                    : AttestationStatus.TERMINAL;
        }

        transaction.attested.add(playerId);
        if (transaction.attested.size() == 2) {
            transaction.status = Status.PREPARED;
            return AttestationStatus.PREPARED;
        }
        return AttestationStatus.WAITING_FOR_OTHER_PARTICIPANT;
    }

    /**
     * Advances only after the caller has durably journaled the Master commit
     * decision. This method itself performs no disk I/O.
     */
    public synchronized DecisionStatus recordDurableCommitDecision(UUID transactionId) {
        Transaction transaction = requireTransaction(transactionId);
        if (transaction.status == Status.COMMIT_DECIDED || transaction.status == Status.APPLIED) {
            return DecisionStatus.ALREADY_COMMITTED;
        }
        if (transaction.status != Status.PREPARED) {
            return DecisionStatus.NOT_PREPARED;
        }
        transaction.status = Status.COMMIT_DECIDED;
        return DecisionStatus.COMMIT_RECORDED;
    }

    /**
     * Releases an uncommitted transaction only when the caller has confirmed
     * from durable Master state that no commit decision exists.
     */
    public synchronized AbortStatus abortBeforeDecision(UUID transactionId, boolean durableNoCommitConfirmed) {
        Transaction transaction = requireTransaction(transactionId);
        if (!durableNoCommitConfirmed) {
            return AbortStatus.NO_DURABLE_NO_COMMIT_CONFIRMATION;
        }
        if (transaction.status == Status.ABORTED) {
            return AbortStatus.ALREADY_ABORTED;
        }
        if (transaction.status == Status.COMMIT_DECIDED || transaction.status == Status.APPLIED) {
            return AbortStatus.COMMIT_ALREADY_RECORDED;
        }
        transaction.status = Status.ABORTED;
        release(transaction);
        return AbortStatus.ABORTED;
    }

    /** Records idempotent owner apply acknowledgements after a durable commit. */
    public synchronized ApplyStatus acknowledgeApplied(UUID transactionId, UUID playerId) {
        Transaction transaction = requireTransaction(transactionId);
        if (!transaction.request.hasParticipant(playerId)) {
            return ApplyStatus.NOT_A_PARTICIPANT;
        }
        if (transaction.status == Status.APPLIED) {
            return ApplyStatus.ALREADY_APPLIED;
        }
        if (transaction.status != Status.COMMIT_DECIDED) {
            return ApplyStatus.COMMIT_NOT_RECORDED;
        }

        transaction.applied.add(playerId);
        if (transaction.applied.size() == 2) {
            transaction.status = Status.APPLIED;
            release(transaction);
            return ApplyStatus.APPLIED;
        }
        return ApplyStatus.WAITING_FOR_OTHER_OWNER;
    }

    public synchronized Status status(UUID transactionId) {
        return requireTransaction(transactionId).status;
    }

    public synchronized UUID reservationOwner(UUID playerId) {
        return reservations.get(playerId);
    }

    private Transaction requireTransaction(UUID transactionId) {
        Transaction transaction = transactions.get(Objects.requireNonNull(transactionId, "transactionId"));
        if (transaction == null) {
            throw new IllegalArgumentException("Unknown player inventory transaction " + transactionId);
        }
        return transaction;
    }

    private void release(Transaction transaction) {
        UUID transactionId = transaction.request.transactionId();
        reservations.remove(transaction.request.editor().playerId(), transactionId);
        reservations.remove(transaction.request.target().playerId(), transactionId);
    }

    public enum Status {
        PREPARING,
        PREPARED,
        COMMIT_DECIDED,
        APPLIED,
        ABORTED
    }

    public enum BeginStatus {
        STARTED,
        REPLAY,
        BUSY,
        ID_CONFLICT
    }

    public enum AttestationStatus {
        WAITING_FOR_OTHER_PARTICIPANT,
        PREPARED,
        ALREADY_ACCEPTED,
        STALE,
        NOT_A_PARTICIPANT,
        TERMINAL
    }

    public enum DecisionStatus {
        COMMIT_RECORDED,
        ALREADY_COMMITTED,
        NOT_PREPARED
    }

    public enum AbortStatus {
        ABORTED,
        ALREADY_ABORTED,
        NO_DURABLE_NO_COMMIT_CONFIRMATION,
        COMMIT_ALREADY_RECORDED
    }

    public enum ApplyStatus {
        WAITING_FOR_OTHER_OWNER,
        APPLIED,
        ALREADY_APPLIED,
        COMMIT_NOT_RECORDED,
        NOT_A_PARTICIPANT
    }

    public record BeginResult(BeginStatus result, Status transactionStatus, UUID reservedBy) { }

    public static final class PlayerSnapshot {
        private final UUID playerId;
        private final UUID sessionId;
        private final long revision;
        private final byte[] snapshotDigest;

        public PlayerSnapshot(UUID playerId, UUID sessionId, long revision, byte[] snapshotDigest) {
            this.playerId = Objects.requireNonNull(playerId, "playerId");
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
            if (revision < 0) {
                throw new IllegalArgumentException("revision must be non-negative");
            }
            this.revision = revision;
            this.snapshotDigest = digestCopy(snapshotDigest);
        }

        public UUID playerId() {
            return playerId;
        }

        public UUID sessionId() {
            return sessionId;
        }

        public long revision() {
            return revision;
        }

        public byte[] snapshotDigest() {
            return snapshotDigest.clone();
        }

        private boolean sameAs(PlayerSnapshot other) {
            return playerId.equals(other.playerId)
                    && sessionId.equals(other.sessionId)
                    && revision == other.revision
                    && Arrays.equals(snapshotDigest, other.snapshotDigest);
        }
    }

    public static final class TransactionRequest {
        private final UUID transactionId;
        private final PlayerSnapshot editor;
        private final PlayerSnapshot target;
        private final byte[] requestDigest;

        public TransactionRequest(UUID transactionId, PlayerSnapshot editor, PlayerSnapshot target, byte[] requestDigest) {
            this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
            this.editor = Objects.requireNonNull(editor, "editor");
            this.target = Objects.requireNonNull(target, "target");
            if (editor.playerId.equals(target.playerId)) {
                throw new IllegalArgumentException("Editor and target must be distinct players");
            }
            this.requestDigest = digestCopy(requestDigest);
        }

        public UUID transactionId() {
            return transactionId;
        }

        public PlayerSnapshot editor() {
            return editor;
        }

        public PlayerSnapshot target() {
            return target;
        }

        private PlayerSnapshot snapshotFor(UUID playerId) {
            if (editor.playerId.equals(playerId)) {
                return editor;
            }
            if (target.playerId.equals(playerId)) {
                return target;
            }
            return null;
        }

        private boolean hasParticipant(UUID playerId) {
            return playerId != null && (editor.playerId.equals(playerId) || target.playerId.equals(playerId));
        }

        private boolean sameAs(TransactionRequest other) {
            return transactionId.equals(other.transactionId)
                    && editor.sameAs(other.editor)
                    && target.sameAs(other.target)
                    && Arrays.equals(requestDigest, other.requestDigest);
        }
    }

    private static byte[] digestCopy(byte[] digest) {
        Objects.requireNonNull(digest, "digest");
        if (digest.length != 32) {
            throw new IllegalArgumentException("SHA-256 digest must contain exactly 32 bytes");
        }
        return digest.clone();
    }

    private static final class Transaction {
        private final TransactionRequest request;
        private final Set<UUID> attested = new HashSet<>();
        private final Set<UUID> applied = new HashSet<>();
        private Status status = Status.PREPARING;

        private Transaction(TransactionRequest request) {
            this.request = request;
        }
    }
}
