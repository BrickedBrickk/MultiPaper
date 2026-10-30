package puregero.multipaper.server;

import java.io.IOException;
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
 * <p>The no-argument constructor is for in-memory tests only. Runtime callers
 * must provide a {@link PlayerInventoryTransactionJournal}; the journal stores
 * complete commit after-images before the commit state is exposed and restores
 * pending reservations after restart. This class is not wired to the InvSee++
 * click path. Uncertain outcomes keep their reservations until resolved; no
 * reservation expires by time.</p>
 */
public final class PlayerInventoryTransactionCoordinator {
    private final Map<UUID, Transaction> transactions = new HashMap<>();
    private final Map<UUID, UUID> reservations = new HashMap<>();
    private final PlayerInventoryTransactionJournal journal;

    /** Creates an in-memory coordinator for isolated state-machine tests. */
    public PlayerInventoryTransactionCoordinator() {
        this.journal = null;
    }

    /** Loads durable decisions before accepting any new transaction requests. */
    public PlayerInventoryTransactionCoordinator(PlayerInventoryTransactionJournal journal) throws IOException {
        this.journal = Objects.requireNonNull(journal, "journal");
        for (PlayerInventoryTransactionJournal.CommitRecord record : journal.loadAll()) {
            Transaction transaction = new Transaction(record.request());
            if (record.aborted()) {
                transaction.status = Status.ABORTED;
            } else {
                transaction.attested.add(record.request().editor().playerId());
                transaction.attested.add(record.request().target().playerId());
                if (record.isApplied(record.request().editor().playerId())) {
                    transaction.applied.add(record.request().editor().playerId());
                }
                if (record.isApplied(record.request().target().playerId())) {
                    transaction.applied.add(record.request().target().playerId());
                }
                transaction.status = record.allOwnersApplied() ? Status.APPLIED : Status.COMMIT_DECIDED;
            }
            transactions.put(record.request().transactionId(), transaction);
            if (transaction.status == Status.COMMIT_DECIDED) {
                reserveRecovered(transaction);
            }
        }
    }

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

    /** Records the durable decision before exposing COMMIT_DECIDED when journal-backed. */
    public synchronized DecisionStatus recordDurableCommitDecision(UUID transactionId) throws IOException {
        Transaction transaction = requireTransaction(transactionId);
        if (transaction.status == Status.COMMIT_DECIDED || transaction.status == Status.APPLIED) {
            return DecisionStatus.ALREADY_COMMITTED;
        }
        if (transaction.status == Status.ABORTED) {
            return DecisionStatus.ABORTED;
        }
        if (transaction.status != Status.PREPARED) {
            return DecisionStatus.NOT_PREPARED;
        }
        if (journal != null) {
            PlayerInventoryTransactionJournal.RecordResult result = journal.recordCommit(transaction.request);
            if (result == PlayerInventoryTransactionJournal.RecordResult.ID_CONFLICT) {
                return DecisionStatus.ID_CONFLICT;
            }
            if (result == PlayerInventoryTransactionJournal.RecordResult.ABORTED) {
                transaction.status = Status.ABORTED;
                release(transaction);
                return DecisionStatus.ABORTED;
            }
        }
        transaction.status = Status.COMMIT_DECIDED;
        return DecisionStatus.COMMIT_RECORDED;
    }

    /**
     * Releases an uncommitted transaction only when the caller has confirmed
     * from durable Master state that no commit decision exists.
     */
    public synchronized AbortStatus abortBeforeDecision(UUID transactionId, boolean durableNoCommitConfirmed) throws IOException {
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
        if (journal != null) {
            PlayerInventoryTransactionJournal.AbortResult result = journal.recordAbort(transaction.request);
            if (result == PlayerInventoryTransactionJournal.AbortResult.COMMIT_ALREADY_RECORDED) {
                transaction.status = Status.COMMIT_DECIDED;
                transaction.attested.add(transaction.request.editor().playerId());
                transaction.attested.add(transaction.request.target().playerId());
                reserveRecovered(transaction);
                return AbortStatus.COMMIT_ALREADY_RECORDED;
            }
            if (result == PlayerInventoryTransactionJournal.AbortResult.ID_CONFLICT) {
                return AbortStatus.ID_CONFLICT;
            }
        }
        transaction.status = Status.ABORTED;
        release(transaction);
        return AbortStatus.ABORTED;
    }

    /** Records idempotent owner apply acknowledgements after a durable commit. */
    public synchronized ApplyStatus acknowledgeApplied(UUID transactionId, UUID playerId) throws IOException {
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

        if (journal != null) {
            PlayerInventoryTransactionJournal.AckResult result = journal.markApplied(transactionId, playerId);
            if (result == PlayerInventoryTransactionJournal.AckResult.NOT_A_PARTICIPANT) {
                return ApplyStatus.NOT_A_PARTICIPANT;
            }
            if (result == PlayerInventoryTransactionJournal.AckResult.NO_COMMIT) {
                return ApplyStatus.COMMIT_NOT_RECORDED;
            }
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

    private void reserveRecovered(Transaction transaction) throws IOException {
        UUID transactionId = transaction.request.transactionId();
        UUID editorId = transaction.request.editor().playerId();
        UUID targetId = transaction.request.target().playerId();
        UUID editorReservation = reservations.get(editorId);
        UUID targetReservation = reservations.get(targetId);
        if (editorReservation != null && !editorReservation.equals(transactionId)
                || targetReservation != null && !targetReservation.equals(transactionId)) {
            throw new IOException("Conflicting recovered inventory transaction reservations for " + transactionId);
        }
        reservations.put(editorId, transactionId);
        reservations.put(targetId, transactionId);
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
        NOT_PREPARED,
        ID_CONFLICT,
        ABORTED
    }

    public enum AbortStatus {
        ABORTED,
        ALREADY_ABORTED,
        NO_DURABLE_NO_COMMIT_CONFIRMATION,
        COMMIT_ALREADY_RECORDED,
        ID_CONFLICT
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
        private final byte[] editorAfterImage;
        private final byte[] targetAfterImage;

        public TransactionRequest(UUID transactionId, PlayerSnapshot editor, PlayerSnapshot target, byte[] requestDigest,
                                  byte[] editorAfterImage, byte[] targetAfterImage) {
            this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
            this.editor = Objects.requireNonNull(editor, "editor");
            this.target = Objects.requireNonNull(target, "target");
            if (editor.playerId.equals(target.playerId)) {
                throw new IllegalArgumentException("Editor and target must be distinct players");
            }
            this.requestDigest = digestCopy(requestDigest);
            this.editorAfterImage = afterImageCopy(editorAfterImage);
            this.targetAfterImage = afterImageCopy(targetAfterImage);
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

        public byte[] requestDigest() {
            return requestDigest.clone();
        }

        public byte[] editorAfterImage() {
            return editorAfterImage.clone();
        }

        public byte[] targetAfterImage() {
            return targetAfterImage.clone();
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

        boolean sameAs(TransactionRequest other) {
            return transactionId.equals(other.transactionId)
                    && editor.sameAs(other.editor)
                    && target.sameAs(other.target)
                    && Arrays.equals(requestDigest, other.requestDigest)
                    && Arrays.equals(editorAfterImage, other.editorAfterImage)
                    && Arrays.equals(targetAfterImage, other.targetAfterImage);
        }
    }

    private static byte[] afterImageCopy(byte[] afterImage) {
        Objects.requireNonNull(afterImage, "afterImage");
        if (afterImage.length == 0 || afterImage.length > 8 * 1024 * 1024) {
            throw new IllegalArgumentException("Inventory after-image must contain 1 to 8388608 bytes");
        }
        return afterImage.clone();
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
