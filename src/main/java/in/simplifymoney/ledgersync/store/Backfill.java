package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.time.YearMonth;
import java.util.*;

/**
 * Moves everything already in the SQL store into the document store.
 *
 * Handles:
 *  - Deduplicating legacy duplicate rows in the SQL store
 *  - Idempotency when run multiple times or after partial failure
 */
public final class Backfill {

    private final SqlLedgerStore source;
    private final DocumentStore target;

    public Backfill(SqlLedgerStore source, DocumentStore target) {
        this.source = source;
        this.target = target;
    }

    public Result run() {
        List<NormalizedTxn> sqlRows = source.all();
        long read = sqlRows.size();

        // 1. Group and deduplicate legacy rows from SQL
        record NaturalKey(String accountLast4, String occurredAt, String direction, String amount) {}

        Map<NaturalKey, List<NormalizedTxn>> deduplicated = new LinkedHashMap<>();
        for (NormalizedTxn t : sqlRows) {
            NaturalKey key = new NaturalKey(
                    t.accountLast4(),
                    t.occurredAt().toString(),
                    t.direction().name(),
                    t.amount().setScale(2).toPlainString()
            );
            deduplicated.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }

        long written = 0;
        long skipped = 0;

        for (Map.Entry<NaturalKey, List<NormalizedTxn>> entry : deduplicated.entrySet()) {
            List<NormalizedTxn> group = entry.getValue();
            NormalizedTxn first = group.get(0);

            // Merge all sourceMessageIds from duplicate rows
            List<String> mergedIds = group.stream()
                    .flatMap(t -> t.sourceMessageIds().stream())
                    .distinct()
                    .sorted()
                    .toList();

            String merchant = group.stream()
                    .map(NormalizedTxn::merchant)
                    .filter(m -> m != null && !m.isBlank())
                    .findFirst()
                    .orElse(first.merchant());

            NormalizedTxn cleanTxn = new NormalizedTxn(
                    first.accountLast4(),
                    first.occurredAt(),
                    first.direction(),
                    first.amount(),
                    first.category(),
                    merchant,
                    mergedIds
            );

            // Check if already in target (idempotency check)
            YearMonth ym = YearMonth.from(cleanTxn.occurredAt());
            List<NormalizedTxn> existing = target.forAccountMonth(cleanTxn.accountLast4(), ym);
            boolean alreadyPresent = existing.stream().anyMatch(e ->
                    e.accountLast4().equals(cleanTxn.accountLast4())
                    && e.occurredAt().equals(cleanTxn.occurredAt())
                    && e.direction() == cleanTxn.direction()
                    && e.amount().compareTo(cleanTxn.amount()) == 0);

            if (alreadyPresent) {
                skipped += group.size();
            } else {
                target.save(cleanTxn);
                written++;
                skipped += (group.size() - 1);
            }
        }

        return new Result(read, written, skipped);
    }

    public record Result(long read, long written, long skipped) {}
}
