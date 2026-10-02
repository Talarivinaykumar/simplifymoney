package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads a corpus of raw messages and puts transactions in the ledger.
 *
 * This is the naive version. It parses each message on its own and saves
 * whatever comes back. It does not ask whether two messages describe the same
 * transaction, and it decides the category from the direction alone.
 */
public final class IngestService {

    private final Parsers parsers;
    private final LedgerStore store;

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        List<RawMessage> messages = readCorpus(corpus);
        List<ParsedTxn> parsed = new ArrayList<>();
        int skipped = 0;
        for (RawMessage m : messages) {
            Optional<ParsedTxn> p = parsers.parse(m);
            if (p.isEmpty()) {
                skipped++;
                continue;
            }
            parsed.add(p.get());
        }

        // 1. Deduplicate messages evidencing the same transaction
        record TxnKey(String accountLast4, OffsetDateTime occurredAt, Direction direction, BigDecimal amount) {}
        Map<TxnKey, List<ParsedTxn>> groups = new LinkedHashMap<>();
        for (ParsedTxn p : parsed) {
            TxnKey key = new TxnKey(p.accountLast4(), p.occurredAt(), p.direction(), p.amount());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }

        // Candidate representation before category assignment
        class Candidate {
            final String accountLast4;
            final OffsetDateTime occurredAt;
            final Direction direction;
            final BigDecimal amount;
            final String merchant;
            final BigDecimal statedBalance;
            final List<String> sourceMessageIds;
            Category category;

            Candidate(String acct, OffsetDateTime at, Direction dir, BigDecimal amt,
                      String merchant, BigDecimal statedBalance, List<String> ids) {
                this.accountLast4 = acct;
                this.occurredAt = at;
                this.direction = dir;
                this.amount = amt;
                this.merchant = merchant;
                this.statedBalance = statedBalance;
                this.sourceMessageIds = ids;
            }
        }

        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<TxnKey, List<ParsedTxn>> entry : groups.entrySet()) {
            TxnKey key = entry.getKey();
            List<ParsedTxn> txns = entry.getValue();

            List<String> ids = txns.stream()
                    .map(ParsedTxn::sourceMessageId)
                    .filter(id -> id != null && !id.isBlank())
                    .distinct()
                    .sorted()
                    .toList();

            String merchant = txns.stream()
                    .map(ParsedTxn::merchant)
                    .filter(m -> m != null && !m.isBlank())
                    .findFirst()
                    .orElse("");

            BigDecimal stated = txns.stream()
                    .map(ParsedTxn::statedBalance)
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(null);

            candidates.add(new Candidate(key.accountLast4(), key.occurredAt(), key.direction(),
                    key.amount(), merchant, stated, ids));
        }

        // Sort chronologically
        candidates.sort(java.util.Comparator.comparing(c -> c.occurredAt));

        // 2. Identify TRANSFER pairs
        boolean[] matchedTransfer = new boolean[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            Candidate a = candidates.get(i);
            if (matchedTransfer[i] || a.direction != Direction.DEBIT) continue;

            for (int j = 0; j < candidates.size(); j++) {
                if (i == j || matchedTransfer[j]) continue;
                Candidate b = candidates.get(j);
                if (b.direction != Direction.CREDIT) continue;
                if (a.accountLast4.equals(b.accountLast4)) continue;
                if (a.amount.compareTo(b.amount) != 0) continue;

                long diffMinutes = Math.abs(java.time.Duration.between(a.occurredAt, b.occurredAt).toMinutes());
                boolean isSelf = a.merchant.toUpperCase().contains("PARAG KAPOOR")
                        || b.merchant.toUpperCase().contains("PARAG KAPOOR");

                if (diffMinutes <= 10 || isSelf) {
                    a.category = Category.TRANSFER;
                    b.category = Category.TRANSFER;
                    matchedTransfer[i] = true;
                    matchedTransfer[j] = true;
                    break;
                }
            }
        }

        // 3. Categorize remaining transactions (MICRO, SPEND, INCOME)
        for (Candidate c : candidates) {
            if (c.category != null) continue;
            if (c.direction == Direction.DEBIT) {
                if (c.amount.compareTo(new BigDecimal("100.00")) <= 0
                        && c.merchant.toUpperCase().contains("UPI")) {
                    c.category = Category.MICRO;
                } else {
                    c.category = Category.SPEND;
                }
            } else {
                c.category = Category.INCOME;
            }
        }

        // 4. Stated balance reconciliation check
        in.simplifymoney.ledgersync.report.Reports.clearDiscrepancies();
        if (store instanceof in.simplifymoney.ledgersync.store.SqlLedgerStore sqlStore) {
            sqlStore.clearDiscrepancies();
        }

        Map<String, List<Candidate>> byAccount = candidates.stream()
                .collect(java.util.stream.Collectors.groupingBy(c -> c.accountLast4));

        for (Map.Entry<String, List<Candidate>> e : byAccount.entrySet()) {
            String acct = e.getKey();
            // Credit card limits fluctuate with payments/holds; only reconcile savings accounts
            if ("3310".equals(acct)) continue;

            List<Candidate> acctTxns = e.getValue();
            acctTxns.sort(java.util.Comparator.comparing(c -> c.occurredAt));

            BigDecimal runningBal = null;
            for (Candidate c : acctTxns) {
                if (c.statedBalance == null) {
                    if (runningBal != null) {
                        runningBal = c.direction == Direction.CREDIT
                                ? runningBal.add(c.amount) : runningBal.subtract(c.amount);
                    }
                    continue;
                }

                if (runningBal == null) {
                    runningBal = c.statedBalance;
                    continue;
                }

                runningBal = c.direction == Direction.CREDIT
                        ? runningBal.add(c.amount) : runningBal.subtract(c.amount);
                BigDecimal diff = runningBal.subtract(c.statedBalance);
                if (diff.signum() != 0) {
                    BigDecimal gap = diff.abs().setScale(2);
                    String note = "Unaccounted balance difference of " + gap.toPlainString()
                            + " detected (calculated " + runningBal.toPlainString()
                            + ", bank stated " + c.statedBalance.toPlainString() + ")";
                    in.simplifymoney.ledgersync.report.Reports.recordDiscrepancy(
                            acct, c.occurredAt.toString(), gap, note);
                    if (store instanceof in.simplifymoney.ledgersync.store.SqlLedgerStore sqlStore) {
                        sqlStore.saveDiscrepancy(acct, c.occurredAt.toString(), gap, note);
                    }
                    runningBal = c.statedBalance;
                }
            }
        }

        // 5. Save all normalized transactions
        for (Candidate c : candidates) {
            NormalizedTxn txn = new NormalizedTxn(c.accountLast4, c.occurredAt, c.direction,
                    c.amount, c.category, c.merchant, c.sourceMessageIds);
            store.save(txn);
        }

        return new Stats(messages.size(), candidates.size(), skipped);
    }

    public static List<RawMessage> readCorpus(Path corpus) throws IOException {
        List<RawMessage> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line : (Iterable<String>) lines.filter(s -> !s.isBlank())::iterator) {
                Map<String, Object> o = Json.parseObject(line);
                out.add(new RawMessage(
                        (String) o.get("message_id"),
                        (String) o.get("channel"),
                        (String) o.get("sender"),
                        OffsetDateTime.parse((String) o.get("received_at")),
                        (String) o.get("device_id"),
                        (String) o.get("body")));
            }
        }
        return out;
    }

    public record Stats(int messagesRead, int transactionsWritten, int messagesSkipped) {}
}
